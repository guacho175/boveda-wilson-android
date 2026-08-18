plugins {
    id("bovedawilson.android.application")
    // El plugin del compilador de Compose es obligatorio desde Kotlin 2.0: sin él los
    // @Composable se compilan sin transformar y fallan en ejecución al llamar a
    // `remember`/`currentComposer`. Solo :app usa Compose.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services) apply false
}

// B-01 (PROJECT_STATE.md §10): no hay proyecto real de Firebase en este entorno, solo
// `google-services.json.example`. Aplicar el plugin sin el archivo real rompe
// assembleDebug; se aplica condicionalmente para no bloquear la compilación de quien no
// lo tenga (ADR-037).
val hasGoogleServicesJson = file("google-services.json").exists()

if (hasGoogleServicesJson) {
    apply(plugin = "com.google.gms.google-services")
}

val verifyReleaseFirebaseConfiguration by tasks.registering {
    group = "verification"
    description = "Comprueba que Firebase y Google OAuth estén incorporados en el release."

    if (hasGoogleServicesJson) {
        dependsOn("processReleaseGoogleServices")
    }

    doLast {
        check(hasGoogleServicesJson) {
            "Release requiere app/google-services.json local. Consulta FIREBASE_SETUP.md."
        }
        val generatedValues = layout.buildDirectory
            .file("generated/res/processReleaseGoogleServices/values/values.xml")
            .get()
            .asFile
        check(generatedValues.isFile) {
            "Google Services no generó los recursos Firebase del release."
        }
        val generatedText = generatedValues.readText()
        check(
            Regex("""name=[\"']default_web_client_id[\"'][^>]*>[^<]+<""")
                .containsMatchIn(generatedText)
        ) {
            "La configuración Firebase no contiene el cliente OAuth Web requerido por Google Sign-In. " +
                "Habilita Google, registra la firma y vuelve a descargar google-services.json."
        }
    }
}

tasks.matching { task ->
    task.name == "packageRelease" || task.name == "bundleRelease"
}.configureEach {
    dependsOn(verifyReleaseFirebaseConfiguration)
}

android {
    defaultConfig {
        buildConfigField("boolean", "HAS_GOOGLE_SERVICES", hasGoogleServicesJson.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":data:sync"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.runtime)
    debugImplementation(libs.compose.ui.tooling)

    // Navegación e inyección de Hilt en Compose
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)

    // Hilt: el procesador es obligatorio. Sin él, @HiltAndroidApp/@AndroidEntryPoint/
    // @HiltViewModel son anotaciones sin componente generado y nada se inyecta.
    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    implementation(libs.work.runtime.ktx)
    ksp(libs.hilt.compiler)

    // Ciclo de vida de ViewModel
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // BiometricPrompt (H-01, docs/DEPENDENCY_POLICY.md): la ventana del sistema del
    // diálogo biométrico solo la puede lanzar la capa de UI, con FragmentActivity como
    // huésped (ADR-019 punto 5). El Cipher/CryptoObject en sí y la clave del Keystore
    // viven en :data:sync (cl.bovedawilson.data.sync.biometric.BiometricUnlock).
    implementation(libs.biometric)

    // Google Sign-In explícito mediante Credential Manager. El ID token se entrega de
    // inmediato a Firebase Auth y nunca se guarda en estado, disco o logs.
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.google.id)

    // App Check usa proveedor debug en debug y Play Integrity en release (ADR-037/ADR-048).
    // El enforcement de Play Integrity sigue bloqueado hasta validar el APK sideload. Se declara aquí y no en
    // :data:remote porque no cruza el límite de Ciphertext ni de dominio: es plomería
    // de transporte, no acceso a datos (docs/architecture.md §1).
    implementation(platform(libs.firebase.bom))
    debugImplementation(libs.firebase.appcheck.debug)
    releaseImplementation(libs.firebase.appcheck.playintegrity)

    // Gate instrumentado de seguridad de ventana/proceso. Estas dependencias ya estan
    // aprobadas y fijadas en docs/DEPENDENCY_POLICY.md; no contienen datos de prueba reales.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    testImplementation(libs.junit)
}
