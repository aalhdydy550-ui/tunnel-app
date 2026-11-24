// Hilt 2.58's hilt-compiler brings kotlin-metadata-jvm 2.2.20, which reads
// Kotlin metadata only up to 2.3.0 — but a dependency compiled with
// Kotlin 2.4 carries metadata 2.4.0, so :app:hiltJavaCompileDebug failed
// with "Provided Metadata instance has version 2.4.0, while maximum
// supported version is 2.3.0" (CI run 64). Hilt 2.59 would fix it but
// needs AGP 9.x (run 41), so force the metadata reader to 2.4.21 in
// every configuration instead (version pinned in libs.versions.toml).
val kotlinMetadataJvmVersion = libs.versions.kotlinMetadataJvm.get()

allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin"
                && requested.name == "kotlin-metadata-jvm"
            ) {
                useVersion(kotlinMetadataJvmVersion)
                because("Hilt 2.58 must read Kotlin 2.4 metadata (CI run 64)")
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
