plugins {
    id("bovedawilson.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:crypto"))
    implementation(project(":data:local"))
    implementation(project(":data:remote"))

    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.ext.compiler)
    implementation(libs.biometric)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.datastore.preferences)
    testImplementation(kotlin("reflect"))
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockito.core)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.room.runtime)
}
