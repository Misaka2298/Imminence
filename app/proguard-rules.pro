# 无反射、无序列化，默认规则即可。
# 保留入口组件，防止 R8 在 release 下裁掉清单里引用的类。
-keep class net.misakanetwork.imminence.core.TrackerService { *; }
-keep class net.misakanetwork.imminence.core.BootReceiver { *; }
-keep class net.misakanetwork.imminence.ui.MainActivity { *; }
