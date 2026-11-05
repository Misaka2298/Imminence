package net.misakanetwork.imminence.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** 只在结算出「一段有意义的时长」时打日志，用来排查某个应用的时长是怎么来的。 */
private const val TAG = "Imminence"
private const val LOG_THRESHOLD_MS = 60_000L
private val TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/**
 * 内存累加 + 批量落盘。
 *
 * 之所以不每写一次就落盘：这颗应用的写入量很小，但写盘是唯一会产生
 * I/O 唤醒的部分，把它压到一天几十次，待机功耗才有意义。
 */
class UsageStore(context: Context) : UsageSink {

    private val helper = Db(context.applicationContext)
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "imminence-io").apply { isDaemon = true }
    }

    /** (day, pkg) -> 当天累计毫秒。只保存本次进程内被改过的条目。 */
    private val mem = HashMap<Pair<String, String>, Long>()
    private val lock = Any()

    override fun totalToday(pkg: String, now: Long): Long =
        synchronized(lock) { mem[Days.keyOf(now) to pkg] ?: 0L }

    override fun add(pkg: String, from: Long, to: Long) {
        if (to <= from) return
        for (segment in DaySplit.segments(from, to)) {
            if (segment.millis >= LOG_THRESHOLD_MS) {
                Log.d(
                    TAG,
                    "settle $pkg +${segment.millis / 60_000}min " +
                        "from ${TIME.format(Instant.ofEpochMilli(segment.start))} " +
                        "to ${TIME.format(Instant.ofEpochMilli(segment.end))}",
                )
            }
            synchronized(lock) {
                val key = segment.day to pkg
                mem[key] = (mem[key] ?: 0L) + segment.millis
            }
        }
    }

    /**
     * 从库里读回某一天的累计，覆盖内存中该天的账。
     *
     * 这是服务重启后恢复历史成绩的**唯一**途径。
     * 刻意不从事件流重放：事件流并不保证完整（部分 ROM 会漏掉息屏事件），
     * 一旦拿它重新推导历史，就会把"应用在后台被拉起来"的瞬间当成用户在用，
     * 于是一整晚的时长被算到某个应用头上。
     */
    fun loadDay(day: String) {
        val rows = HashMap<String, Long>()
        runCatching {
            helper.readableDatabase.rawQuery(
                "SELECT pkg, millis FROM usage WHERE day = ?",
                arrayOf(day),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows[cursor.getString(0)] = cursor.getLong(1)
                }
            }
        }

        synchronized(lock) {
            mem.keys.removeAll { it.first == day }
            rows.forEach { (pkg, millis) -> mem[day to pkg] = millis }
        }
    }

    /**
     * 用系统统计值补齐当天的账，取两者较大值。
     *
     * - 系统值覆盖了「服务启动之前」和「服务被杀期间」的使用；
     * - 我们的累加覆盖了「系统还没刷新出来」的那一小段。
     * 两者描述的是同一个量，取 max 既不会漏，也不会重复。
     */
    fun rebase(day: String, systemTotals: Map<String, Long>) {
        if (systemTotals.isEmpty()) return
        synchronized(lock) {
            for ((pkg, millis) in systemTotals) {
                val key = day to pkg
                val mine = mem[key] ?: 0L
                if (millis > mine) mem[key] = millis
            }
        }
    }

    /** 把内存快照整体覆盖进库里。调用点：切应用、息屏、每 5 分钟。 */
    fun flush() {
        val snapshot = synchronized(lock) {
            mem.entries.map { Triple(it.key.first, it.key.second, it.value) }
        }
        if (snapshot.isEmpty()) return

        io.execute {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                for ((day, pkg, millis) in snapshot) {
                    db.execSQL(
                        "INSERT OR REPLACE INTO usage (day, pkg, millis) VALUES (?, ?, ?)",
                        arrayOf<Any>(day, pkg, millis),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun close() {
        // 先排到写线程末尾再关闭，保证最后一次 flush 落盘完成。
        io.execute { runCatching { helper.close() } }
        io.shutdown()
    }

    private class Db(context: Context, version: Int = DATABASE_VERSION) :
        SQLiteOpenHelper(context, "usage.db", null, version) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE usage (" +
                    "day TEXT NOT NULL, " +
                    "pkg TEXT NOT NULL, " +
                    "millis INTEGER NOT NULL, " +
                    "PRIMARY KEY (day, pkg))",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 0.2.0 之前的记账口径是「从点击开始统计起」，和现在的「今日累计」不是一回事，
            // 旧数据全部不可信，直接丢弃重建。
            db.execSQL("DROP TABLE IF EXISTS usage")
            onCreate(db)
        }
    }

    private companion object {
        const val DATABASE_VERSION = 2
    }
}
