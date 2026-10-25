plugins {
    alias(libs.plugins.android.application)
    // 只有 Compose 编译器插件。Kotlin 本体由 AGP 9 的 built-in Kotlin 提供，
    // 再写 org.jetbrains.kotlin.android 会报错。
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yinxia.music"

    // Compose 1.12 起要求 compileSdk 37，和 targetSdk 是两回事：
    // compileSdk 决定“能用哪些 API 编译”，targetSdk 决定“系统按哪个版本的行为对待你”。
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yinxia.music"
        minSdk = 26
        // 36 = Android 16，正好对应 HyperOS 3 那台机器
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                // AGP 9 的 getDefaultProguardFile 只接受这一个名字
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 没有自己的签名证书，release 先用 debug 签名，方便直接装机对比体积
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // 内置 Kotlin 下 Kotlin 的 jvmTarget 默认跟随这里的 targetCompatibility，
    // 所以只需要设一次；不需要（也不再需要）android.kotlinOptions {}。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel)

    // Compose 全家桶版本由 BOM 统一决定，下面几行不写版本号
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
