# Media3 / ExoPlayer 自带 consumer ProGuard 规则，这里只做兜底：
# 保留注解信息，避免个别 ROM 上反射相关的类被误删。
-keepattributes *Annotation*, InnerClasses

# Guava 在 Android 上会引用一些不存在的 JDK 类，属于已知无害警告
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
