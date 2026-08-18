plugins {
    id("bovedawilson.android.library")
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    implementation(project(":core:common"))
    implementation(libs.tink.android)
    implementation(libs.bouncycastle)
    implementation(libs.kotlin.bip39)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
