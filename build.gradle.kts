// 顶层构建脚本：只声明插件，不在这里配置模块
//
// 注意：AGP 9 内置 Kotlin 支持，这里**不能**再声明 org.jetbrains.kotlin.android，
// 否则构建会直接报 "no longer required for Kotlin support since AGP 9.0"。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
