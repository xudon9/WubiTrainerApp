// Top-level build file for WubiTrainer.
// AGP 9.x has built-in Kotlin support, so no separate kotlin-android plugin is needed.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
