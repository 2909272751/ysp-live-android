# App 模块混淆规则
# 引擎门面通过反射创建，必须保留
-keep class com.gary.ysplive.core.engine.YspGateway { *; }
-keep class com.gary.ysplive.core.engine.YspGateway$* { *; }
-keep class com.gary.ysplive.core.** { *; }
-keepclassmembers class com.gary.ysplive.core.** { *; }

# AndroidX Media3 / ExoPlayer
-dontwarn androidx.media3.**

# 我们的 UI 组件由系统按类名实例化
-keep class com.gary.ysplive.ui.** { *; }
-keep class com.gary.ysplive.play.** { *; }
