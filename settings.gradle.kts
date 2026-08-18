pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

includeBuild("build-logic")

rootProject.name = "boveda-wilson"

// R-01: el repositorio vive dentro de OneDrive, que abre los archivos recién escritos para
// sincronizarlos y provoca `FileSystemException: el proceso no tiene acceso al archivo`
// justo cuando Gradle intenta reemplazar un .jar intermedio. Sacar los directorios de
// salida del árbol sincronizado elimina la carrera de raíz, en vez de reintentar la
// compilación hasta que OneDrive suelte el archivo.
//
// Los artefactos siguen siendo reproducibles y desechables; nada versionado se mueve. La
// ruta se puede fijar con -PbuildDirRoot=... o la variable BOVEDA_BUILD_DIR.
val relocatedBuildRoot: String? = System.getenv("BOVEDA_BUILD_DIR")
    ?: providers.gradleProperty("buildDirRoot").orNull
    ?: System.getenv("LOCALAPPDATA")?.let { "$it\\BovedaWilson\\build" }

if (relocatedBuildRoot != null) {
    gradle.beforeProject {
        val slug = path.removePrefix(":").replace(':', '-').ifEmpty { "root" }
        layout.buildDirectory.set(File(relocatedBuildRoot, slug))
    }
}

include(":app")
include(":core:model")
include(":core:common")
include(":core:crypto")
include(":data:local")
include(":data:remote")
include(":data:sync")
