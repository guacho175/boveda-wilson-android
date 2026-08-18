import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.gitlab.arturbosch.detekt")
}

val releaseSigningPath = providers.gradleProperty("boveda.signingProperties")
    .orElse(providers.environmentVariable("BOVEDA_SIGNING_PROPERTIES"))
    .orNull
val releaseSigningFile = releaseSigningPath?.let(::file)
val releaseSigningProperties = Properties()

if (releaseSigningFile?.isFile == true) {
    releaseSigningFile.inputStream().use(releaseSigningProperties::load)
}

fun requiredReleaseSigningProperty(name: String): String =
    releaseSigningProperties.getProperty(name)?.takeIf(String::isNotBlank)
        ?: error("signing.properties incompleto: falta $name")

android {
    namespace = "cl.bovedawilson.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "cl.bovedawilson.app"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (releaseSigningFile?.isFile == true) {
            create("release") {
                storeFile = rootProject.file(requiredReleaseSigningProperty("storeFile"))
                storePassword = requiredReleaseSigningProperty("storePassword")
                keyAlias = requiredReleaseSigningProperty("keyAlias")
                keyPassword = requiredReleaseSigningProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        // Solo :app genera BuildConfig, y solo para derivar el modo de SecureLogger del
        // tipo de build (hallazgo M-1). Los módulos de biblioteca siguen sin generarlo.
        buildConfig = true
    }
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Falla cerrado si no existe una configuración de firma release completa."

    doLast {
        check(releaseSigningFile != null) {
            "Release requiere -Pboveda.signingProperties=<ruta absoluta> o BOVEDA_SIGNING_PROPERTIES."
        }
        val canonicalSigningFile = releaseSigningFile.canonicalFile
        val canonicalProjectRoot = rootProject.projectDir.canonicalFile
        check(canonicalSigningFile.isAbsolute && !canonicalSigningFile.toPath().startsWith(canonicalProjectRoot.toPath())) {
            "La configuración de firma debe estar fuera del repositorio y de su carpeta OneDrive."
        }
        val oneDriveRoots = listOf("OneDrive", "OneDriveConsumer", "OneDriveCommercial")
            .mapNotNull(System::getenv)
            .map { path -> file(path).canonicalFile.toPath() }
        check(oneDriveRoots.none(canonicalSigningFile.toPath()::startsWith)) {
            "La configuración de firma no puede estar dentro de una carpeta sincronizada por OneDrive."
        }
        check(canonicalSigningFile.isFile) { "No se encontró la configuración externa de firma release." }
        val configuredStore = rootProject.file(requiredReleaseSigningProperty("storeFile")).canonicalFile
        check(!configuredStore.toPath().startsWith(canonicalProjectRoot.toPath())) {
            "El almacén de firma release debe estar fuera del repositorio y de su carpeta OneDrive."
        }
        check(oneDriveRoots.none(configuredStore.toPath()::startsWith)) {
            "El almacén de firma release no puede estar dentro de una carpeta sincronizada por OneDrive."
        }
        check(configuredStore.isFile) { "No se encontró el almacén de firma release configurado." }
    }
}

tasks.matching { task ->
    task.name == "packageRelease" || task.name == "bundleRelease"
}.configureEach {
    dependsOn(verifyReleaseSigning)
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
