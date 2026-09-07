pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Some Android Studio / Windows daemons only resolve convention plugins
    // declared here, not solely in the settings plugins {} block below.
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Machine-specific JVM (gitignored). Only apply paths that exist on *this*
// OS — a WSL gradle-local.properties must not poison Windows Gradle.
fun isWindowsOs(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

fun looksLikeWindowsDrivePath(path: String): Boolean =
    path.length >= 3 && path[0].isLetter() && path[1] == ':' && (path[2] == '\\' || path[2] == '/')

fun isUsableJdkHome(path: String): Boolean {
    val windowsOs = isWindowsOs()
    // Do not call Gradle file() on C:\... from WSL — the colon is parsed as a URI.
    if (looksLikeWindowsDrivePath(path) != windowsOs) return false
    if (windowsOs && path.startsWith("/")) return false
    val home = java.io.File(path)
    return if (windowsOs) {
        java.io.File(home, "bin/java.exe").isFile
    } else {
        java.io.File(home, "bin/java").isFile
    }
}

fun publishJdkProperty(key: String, usable: String) {
    // Toolchain lookup reads Gradle project properties (and daemon system
    // properties), not only System.setProperty in this settings script.
    // startParameter maps are immutable — replace them with a copy.
    System.setProperty(key, usable)
    startParameter.projectProperties = HashMap(startParameter.projectProperties).apply { put(key, usable) }
    startParameter.systemPropertiesArgs = HashMap(startParameter.systemPropertiesArgs).apply { put(key, usable) }
}

val gradleLocalFile = file("gradle-local.properties")
if (gradleLocalFile.exists()) {
    val props = java.util.Properties()
    gradleLocalFile.inputStream().use { props.load(it) }
    props.stringPropertyNames()
        .filter { it == "org.gradle.java.home" || it.startsWith("org.gradle.java.installations.") }
        .forEach { key ->
            val raw = props.getProperty(key)?.trim().orEmpty()
            if (raw.isEmpty()) return@forEach
            val usable =
                if (key == "org.gradle.java.installations.paths") {
                    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() && isUsableJdkHome(it) }
                        .joinToString(",")
                } else if (key == "org.gradle.java.home") {
                    raw.takeIf { isUsableJdkHome(it) }.orEmpty()
                } else {
                    raw
                }
            if (usable.isNotEmpty()) {
                publishJdkProperty(key, usable)
            }
        }
}

rootProject.name = "FlightBuddy"
include(":app")
