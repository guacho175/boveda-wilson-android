plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.gitlab.arturbosch.detekt")
}

kotlin {
    jvmToolchain(17)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

val libs = project.extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")
dependencies {
    detektPlugins(libs.findLibrary("detekt-formatting").get())
}
