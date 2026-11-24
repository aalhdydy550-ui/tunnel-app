plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.aalhdydy550.tunnel.core.database"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // api (not implementation): TunnelDatabase extends RoomDatabase, so any
    // module that uses TunnelDatabase in its own code (e.g. :core:data's
    // DataModule/BackupWorker/SubscriptionWorker) needs RoomDatabase on its
    // compile classpath — with `implementation` here, :core:data failed with
    // "Cannot access 'androidx.room.RoomDatabase' which is a supertype of
    // 'TunnelDatabase'" (run 57, :core:data:compileDebugKotlin).
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
