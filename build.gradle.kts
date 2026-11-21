/**
 * Top-level build file. AGP 9 provides built-in Kotlin support, so the
 * org.jetbrains.kotlin.android plugin is no longer applied for the app module.
 */

plugins {
    id("com.android.application") version "9.5.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.protobuf") version "0.10.1" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
