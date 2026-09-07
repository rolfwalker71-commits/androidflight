import com.android.build.api.variant.ApplicationAndroidComponentsExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// Belt: disable androidTest on every Android app module before AGP creates
// compile*AndroidTestJavaWithJavac (Studio sync still asks for that task).
gradle.beforeProject {
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<ApplicationAndroidComponentsExtension>("androidComponents") {
            beforeVariants(selector().all()) { variant ->
                variant.enableAndroidTest = false
                variant.deviceTests.values.forEach { it.enable = false }
            }
        }
    }
}
