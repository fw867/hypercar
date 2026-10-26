# HyperCar R8 规则
# 开 isMinifyEnabled=true 后必须保住「Rust 侧按 JNI 名字调用」的 Kotlin 符号，
# 否则 R8 会改名/裁掉，运行期报 NoSuchMethodError 或 JNI 回调静默失效。

# ---- JNI 桥：Rust 通过符号名找方法 ----
-keep class io.github.hypercar.core.RustCoreBridge { *; }
-keep interface io.github.hypercar.core.RustCoreBridge$EventListener { *; }
-keep interface io.github.hypercar.core.RustCoreBridge$SocketProvider { *; }
-keep interface io.github.hypercar.core.RustCoreBridge$TouchListener { *; }
# 回调实现类（匿名 object）：方法名与接口一致，接口 keep 后即可，但显式保一下更稳
-keep class * implements io.github.hypercar.core.RustCoreBridge$EventListener { *; }
-keep class * implements io.github.hypercar.core.RustCoreBridge$SocketProvider { *; }
-keep class * implements io.github.hypercar.core.RustCoreBridge$TouchListener { *; }

# ---- SPIKE-P0：调试控制台/探针（P0 结束后连同 spike 包一起删） ----
-keep class io.github.hypercar.spike.** { *; }

# ---- P1：自绘车机首页 / 音乐通知监听（manifest 组件 + Compose） ----
-keep class io.github.hypercar.carui.** { *; }

# ---- Shizuku（binder 反射 + provider）----
-keep class dev.rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# ---- Kotlin / 注解 ----
-dontwarn org.jetbrains.annotations.**
-keep class kotlin.Metadata { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod
