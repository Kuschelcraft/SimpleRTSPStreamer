// Plugin versions live in settings.gradle.kts. The Kotlin plugin is declared once here (apply false) so
// that :rtsp (kotlin.jvm) and :app (kotlin.android) share a single plugin classloader.
plugins {
    id("org.jetbrains.kotlin.jvm") apply false
}
