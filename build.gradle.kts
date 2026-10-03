// Plugin versions live in settings.gradle.kts. All plugins are declared once here (apply false) so that
// the Kotlin and Android Gradle plugins share one classloader across :rtsp (kotlin.jvm) and :app.
plugins {
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlin.jvm") apply false
}
