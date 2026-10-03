import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Dev helper: serves an Annex-B H.264 file as a live RTSP stream, for interop tests with
// real clients (GStreamer, ffmpeg):  ./gradlew -PrtspOnly :rtsp:devServer -Pfile=clip.h264
tasks.register<JavaExec>("devServer") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.kuschelcraft.rtsp.DevServerKt")
    args(
        (project.findProperty("file") ?: "clip.h264").toString(),
        (project.findProperty("port") ?: "1945").toString(),
        (project.findProperty("fps") ?: "30").toString(),
        (project.findProperty("transport") ?: "AUTO").toString(),
    )
}
