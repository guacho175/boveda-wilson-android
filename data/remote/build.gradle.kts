plugins {
    id("bovedawilson.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val useFirebaseEmulator = providers.gradleProperty("boveda.useFirebaseEmulator")
    .orElse("true")

android {
    defaultConfig {
        // Debug permanece cerrado sobre Emulator Suite por defecto. El propietario puede
        // autorizar una compilación de desarrollo contra su proyecto real pasando
        // -Pboveda.useFirebaseEmulator=false; release nunca usa el emulador.
        buildConfigField("boolean", "USE_FIREBASE_EMULATOR", useFirebaseEmulator.get())
    }

    buildFeatures {
        // Necesario para BuildConfig.DEBUG: EmulatorConfig lo usa para decidir si
        // conecta a los emuladores de Auth/Firestore (nunca en release, ADR-037).
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:crypto"))

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.coroutines.test)
}
