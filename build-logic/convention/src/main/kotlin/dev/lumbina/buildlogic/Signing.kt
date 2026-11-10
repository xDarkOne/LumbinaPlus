package dev.lumbina.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties
import org.gradle.api.Project

/**
 * Signs every app of the project alike: Lumbina carries its companion inside itself, and the two
 * must have one key. Debug builds take the committed debug key, so builds from a PC and from CI
 * install over each other. Release builds take the key named in keystore.properties or, on CI,
 * in LUMBINA_* environment variables. Without one a release build stops: signed with the public
 * debug key it would let anyone's debug build replace it and take over its adb key and the
 * permissions granted to it. A check-only build (CI on a pull request) may allow the debug key
 * with LUMBINA_ALLOW_DEBUG_SIGNED_RELEASE=true.
 */
internal fun Project.configureSigning(android: ApplicationExtension) {
    val file = rootProject.file("keystore.properties")
    val props = Properties().apply { if (file.exists()) file.inputStream().use(::load) }
    fun value(key: String, env: String): String? = props.getProperty(key) ?: System.getenv(env)
    val store = value("storeFile", "LUMBINA_KEYSTORE_FILE")
    val storeKey = value("storePassword", "LUMBINA_KEYSTORE_PASSWORD")
    val alias = value("keyAlias", "LUMBINA_KEY_ALIAS")
    val aliasKey = value("keyPassword", "LUMBINA_KEY_PASSWORD")

    val debug = android.signingConfigs.getByName("debug").apply {
        storeFile = rootProject.file("config/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
    val release = if (store == null || storeKey == null || alias == null || aliasKey == null) {
        if (System.getenv("LUMBINA_ALLOW_DEBUG_SIGNED_RELEASE") != "true") {
            gradle.taskGraph.whenReady {
                val releaseTasks = allTasks.filter { it.project == project && it.name.contains("Release") && it.name.startsWith("package") }
                check(releaseTasks.isEmpty()) {
                    "No release key: set keystore.properties or LUMBINA_KEYSTORE_FILE, LUMBINA_KEYSTORE_PASSWORD, " +
                        "LUMBINA_KEY_ALIAS and LUMBINA_KEY_PASSWORD (or LUMBINA_ALLOW_DEBUG_SIGNED_RELEASE=true for a check-only build)"
                }
            }
        }
        debug
    } else {
        android.signingConfigs.create("release") {
            storeFile = rootProject.file(store)
            storePassword = storeKey
            keyAlias = alias
            keyPassword = aliasKey
        }
    }
    android.buildTypes.getByName("release").signingConfig = release
}
