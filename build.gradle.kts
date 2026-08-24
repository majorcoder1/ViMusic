plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        if (project.findProperty("enableComposeCompilerReports") == "true") {
            compilerOptions.freeCompilerArgs.addAll(
                listOf("reports", "metrics").flatMap {
                    listOf(
                        "-P",
                        "plugin:androidx.compose.compiler.plugins.kotlin:${it}Destination=" +
                            "${layout.buildDirectory.get().asFile.absolutePath}/compose_metrics"
                    )
                }
            )
        }
    }
}
