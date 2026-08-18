# docs/DEPENDENCY_POLICY.md — Política y registro de dependencias

## 1. Política

- Antes de añadir una dependencia se comprueba en su **fuente oficial**: última versión, actividad
  de mantenimiento, compatibilidad con las versiones de Android objetivo y vulnerabilidades
  conocidas. El resultado se registra en la tabla de §3.
- Versiones **fijas** en `gradle/libs.versions.toml`. Prohibidos los rangos dinámicos (`+`,
  `latest.release`) y los literales de versión dispersos en los `build.gradle.kts`.
- Ninguna dependencia abandonada en el camino crítico de seguridad.
- Cuantas menos dependencias, menor superficie de cadena de suministro (T-16 de `THREAT_MODEL.md`).
  Una dependencia que solo ahorra unas líneas no entra.
- **Prohibido inventar** resultados de auditorías, CVE, benchmarks o compatibilidad. Lo que no se
  verificó se declara «no verificado» con su motivo.
- Se prefieren artefactos publicados por el propietario del proyecto en Maven Central o en el
  repositorio de Google.
- Sin SDK de telemetría, publicidad, tracking ni analítica (ADR-018).
- Cuando sea viable se activan `dependency verification` y `dependency locking` de Gradle
  (Fase 9).

## 2. Método de verificación usado

Las versiones de §3 se obtuvieron el **2026-07-29** consultando directamente los metadatos Maven
oficiales de cada artefacto:

- Maven Central: `https://repo1.maven.org/maven2/<ruta>/maven-metadata.xml`
- Repositorio de Google: `https://dl.google.com/dl/android/maven2/<ruta>/maven-metadata.xml`
- Registro de npm: `https://registry.npmjs.org/<paquete>`

**Lo que esto verifica:** que la versión indicada existe y es la última publicada (o la última
**estable**, cuando la última publicada es alpha/beta) en el repositorio oficial en esa fecha.

La consulta de versiones de 2026-07-29 no verificaba vulnerabilidades. El 2026-08-10 se añadió una
revisión separada con OSV-Scanner 2.5.0 sobre lockfiles y `npm audit --audit-level=high`; sus
resultados y límites están en §5. No se afirma que ninguna dependencia esté libre de fallos.

## 3. Registro de dependencias

Fecha de verificación de todas las filas: **2026-07-29**.

### Criptografía (camino crítico)

| Artefacto | Versión | Fuente | Última estable observada | Uso | Alternativa considerada | CVE |
|---|---|---|---|---|---|---|
| `com.google.crypto.tink:tink-android` | 1.23.0 | Maven Central | 1.23.0 | AEAD AES256-GCM, keysets cifrados, HKDF | `javax.crypto` directo (obligaría a gestionar nonces a mano) | no verificado |
| `org.bouncycastle:bcprov-jdk18on` | 1.85 | Maven Central | 1.85 | Argon2id (`Argon2BytesGenerator`), HKDF de respaldo | `com.lambdapioneer.argon2kt:argon2kt` 1.6.0 (nativo, más rápido, pero JNI impide probar en la JVM) | no verificado |
| `cash.z.ecc.android:kotlin-bip39` | 1.0.9 | Maven Central | 1.0.9 | codificación BIP-39 de la entropía de recuperación | `io.github.novacrypto:BIP39` (menos actividad), `bitcoinj` (arrastra un cliente Bitcoin completo) | no verificado |

Justificaciones detalladas: ADR-004, ADR-006 y ADR-008 en `DECISIONS.md`.

**Nota de compatibilidad de API (2026-07-31, Fase 2).** Con Tink 1.23.0, las firmas usadas por
`:core:crypto` (`AesGcmParameters`/`AesGcmKey`/`SecretBytes`/`KeysetHandle.importKey(...)`,
`TinkProtoKeysetFormat.serializeEncryptedKeyset`/`parseEncryptedKeyset`/`serializeKeyset`,
`KeysetHandle.getPrimitive(Class)`) existen y compilan tal como las especifica
el contrato de `CRYPTOGRAPHY.md`, y funcionan correctamente en pruebas JVM puras (D-05).
Quedan seis avisos de deprecación no bloqueantes, aceptados por ahora: `getPrimitive(Class)` en
`keys/KekImporter.kt` y `session/UnlockedVault.kt`, y las variantes de
`serializeKeyset`/`serializeEncryptedKeyset`/`parseEncryptedKeyset` sin `Configuration` explícita
en `wrap/VdekWrapper.kt` y `vault/VaultWrapping.kt`. Todas tienen una variante más nueva con un
parámetro `Configuration` adicional; adoptarla es una migración mecánica que no cambia el
comportamiento y queda pendiente para cuando se revise el módulo de nuevo, sin bloquear la Fase 2.

### Plataforma y build

| Artefacto | Versión | Fuente | Nota |
|---|---|---|---|
| Gradle (wrapper) | 8.14.3 | `services.gradle.org` (última observada: 9.6.1) | se fija 8.14.x por compatibilidad con AGP 8.13.x y JDK 17 |
| `com.android.tools.build:gradle` (AGP) | 8.13.1 | repositorio de Google (última estable observada: 9.3.1) | AGP 9.x no se adopta: solo hay JDK 17 en el entorno (ADR-002) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin` | 2.2.20 | Maven Central (última estable observada: 2.4.10) | pareja conocida con AGP 8.13.x y con KSP disponible |
| `com.google.devtools.ksp` | 2.2.20-2.0.4 | Maven Central | debe coincidir con la versión de Kotlin |
| `io.gitlab.arturbosch.detekt` | 1.23.8 | Maven Central | 1.23.8 es la última estable observada |
| `io.gitlab.arturbosch.detekt:detekt-formatting` | 1.23.8 | Maven Central | registrado 2026-07-30. Reglas de formato de Detekt (envuelve `ktlint` en el classpath de build). Solo herramienta de análisis: **no entra en el APK**. Se declara en `gradle/libs.versions.toml` con `version.ref = "detekt"` para que no pueda divergir del plugin |
| Android SDK | compileSdk 36, targetSdk 36, minSdk 33 | SDK local | platforms 34/35/36 instaladas; ADR-027 |
| JDK | Temurin 17.0.19 | instalación local | no hay JDK 21 disponible |

### AndroidX, Firebase y utilidades

| Artefacto | Versión | Fuente | Uso | Nota |
|---|---|---|---|---|
| `androidx.compose:compose-bom` | 2024.06.00 | repositorio de Google | UI | versión fija del catálogo; se cambia solo con compilación y revisión de compatibilidad |
| `androidx.room:room-runtime` / `-ktx` / `-compiler` | 2.8.4 | repositorio de Google | persistencia de ciphertext | KSP |
| `com.google.dagger:hilt-android` / `hilt-compiler` | 2.57.2 | Maven Central | inyección de dependencias | KSP; la línea 2.60.x requiere una actualización previa de AGP |
| `com.google.firebase:firebase-bom` | 34.16.0 | repositorio de Google | Auth + Firestore | solo Auth y Firestore; **sin** Analytics ni Crashlytics |
| `androidx.biometric:biometric` | 1.1.0 | repositorio de Google | `BiometricPrompt` + `CryptoObject` | 1.4.0-alpha07 es la última publicada; **no se usa una alpha** en el camino de seguridad |
| `androidx.work:work-runtime-ktx` | 2.11.2 | repositorio de Google | sincronización diferida | última estable observada |
| `androidx.datastore:datastore-preferences` | 1.2.1 | repositorio de Google | **solo** preferencias no sensibles (tiempo de bloqueo, biometría activada) | última estable observada; jamás secretos |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.9.0 | Maven Central | JSON estricto del contenido del ítem y del respaldo | versión indicada por la documentación de DataStore junto al plugin Kotlin 2.2.20; esquema estricto, sin polimorfismo abierto |
| `androidx.lifecycle:lifecycle-process` | 2.8.7 | repositorio de Google | detección de segundo plano para el bloqueo automático | versión fija del catálogo |

### Plugins, integración y pruebas (ADR-024)

| Artefacto / plugin | Versión | Fuente | Uso |
|---|---|---|---|
| `com.android.application` / `com.android.library` | 8.13.1 | repositorio de Google | plugins Android en `build-logic` |
| `org.jetbrains.kotlin.android` / `org.jetbrains.kotlin.jvm` | 2.2.20 | Maven Central / Plugin Portal | Kotlin Android y JVM |
| `org.jetbrains.kotlin.plugin.compose` | 2.2.20 | Maven Central / Plugin Portal | compilador de Compose obligatorio con Kotlin 2.x |
| `com.google.dagger.hilt.android` | 2.57.2 | Maven Central / Plugin Portal | plugin de Hilt; la línea 2.60.x requiere actualizar AGP antes |
| `com.google.devtools.ksp` | 2.2.20-2.0.4 | Maven Central / Plugin Portal | generación de Room y Hilt |
| `androidx.activity:activity-compose` | 1.13.0 | repositorio de Google | host de Compose |
| `androidx.navigation:navigation-compose` | 2.9.8 | repositorio de Google | navegación del MVP |
| `androidx.hilt:hilt-work` / `hilt-compiler` | 1.3.0 | repositorio de Google | `HiltWorkerFactory` e inyección en WorkManager |
| `androidx.room:room-testing` | 2.8.4 | repositorio de Google | `MigrationTestHelper` y pruebas de esquema |
| `androidx.work:work-testing` | 2.11.2 | repositorio de Google | pruebas deterministas del worker |
| `com.google.dagger:hilt-android-testing` | 2.57.2 | Maven Central | runner y componentes de prueba de Hilt; misma versión que Hilt |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.10.2 | Maven Central | pruebas de coroutines y Flow |
| `androidx.test:runner` / `androidx.test:core` | 1.7.0 | repositorio de Google | runner y utilidades de pruebas instrumentadas |
| `androidx.test.ext:junit` | 1.3.0 | repositorio de Google | integración JUnit 4 instrumentada |
| `androidx.test.espresso:espresso-core` | 3.7.0 | repositorio de Google | aserciones e interacción de UI |
| `androidx.credentials:credentials` / `credentials-play-services-auth` | 1.5.0 | repositorio de Google | Google Sign-In mediante Credential Manager | versión estable; el token solo vive durante el intercambio con Firebase Auth |
| `com.google.android.libraries.identity.googleid:googleid` | 1.1.1 | repositorio de Google | parseo tipado de Google ID token | no persiste tokens; requiere cliente web OAuth externo para ejecución real |
| `org.mockito.kotlin:mockito-kotlin` | 5.1.0 | Maven Central | dobles de prueba de `:data:sync` | solo pruebas; registrado en el catálogo de versiones |
| `org.mockito:mockito-core` | 5.2.0 | Maven Central | motor de los dobles Mockito | solo pruebas; registrado en el catálogo de versiones |

### Añadidos para la Fase 1 (verificados el 2026-07-30)

Fecha de verificación de estas cuatro filas: **2026-07-30**, por el método de §2.

| Artefacto | Versión | Fuente | Última estable observada | Uso |
|---|---|---|---|---|
| `junit:junit` | 4.13.2 | Maven Central | 4.13.2 | motor de las pruebas unitarias JVM y de las de higiene |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.10.2 | Maven Central | 1.11.0 | `AppDispatchers` y asincronía del núcleo; se fija 1.10.2 para emparejar con `kotlinx-coroutines-test` 1.10.2 ya registrado |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.2 | Maven Central | 1.11.0 | `Dispatchers.Main` en Android; misma versión que el core, obligatoriamente |

**No se declaran explícitamente** `androidx.core:core-ktx` (última observada 1.19.0) ni
`androidx.lifecycle:lifecycle-runtime-ktx` (2.11.0): llegan de forma transitiva por
`activity-compose` y por la BOM de Compose. Declararlas añadiría superficie de versión sin aportar
nada en la Fase 1. Si alguna se necesita de forma directa, entra por este mismo procedimiento (§6).

El plugin de convención de Room configura `room.schemaLocation`; el de aplicación retira el
inicializador por defecto de WorkManager cuando se instale `HiltWorkerFactory`. Estas piezas forman
parte de la puerta, no son dependencias opcionales.

### Añadidos para la Fase 4, parte Android (verificados el 2026-07-31)

| Artefacto | Versión | Fuente | Última estable observada | Uso | Nota |
|---|---|---|---|---|---|
| `com.google.gms:google-services` (plugin) | 4.5.0 | `dl.google.com` (repositorio de Google) | 4.5.0 | procesa `app/google-services.json` para generar los recursos que Firebase lee en tiempo de ejecución | se aplica **condicionalmente** en `:app` (`if (file("google-services.json").exists())`), para que `assembleDebug` compile sin el archivo real, ausente en este entorno (B-01) |
| `com.google.firebase:firebase-appcheck-debug` | gestionada por `firebase-bom` 34.16.0 | repositorio de Google | — | proveedor de depuración de App Check, solo `debugImplementation` en `:app` | Nunca se incluye en release; sus tokens no se guardan en el repositorio ni en documentación |
| `com.google.firebase:firebase-appcheck-playintegrity` | gestionada por `firebase-bom` 34.16.0 | repositorio de Google | — | proveedor de App Check para el build `release` | El enforcement permanece bloqueado hasta registrar SHA-256 y validar tokens del APK release firmado y cargado por sideload (B-02) |

`com.google.firebase:firebase-auth` y `com.google.firebase:firebase-firestore` (ya registradas en la
tabla de AndroidX/Firebase de arriba, declaradas desde la Fase 1 pero no consumidas hasta ahora) se
usan por primera vez en `:data:remote` en esta fase (`FirebaseAuthSourceImpl`,
`FirestoreVaultSourceImpl`). No se añadió `kotlinx-coroutines-play-services`: el puente
`Task<T> → suspend fun` se escribió a mano con `suspendCancellableCoroutine` en
`data/remote/.../internal/TaskExtensions.kt` (unas quince líneas), evitando una dependencia más en
el camino de datos (política §1, «cuantas menos dependencias, menor superficie»).

### Pruebas de Security Rules (Node, solo desarrollo)

| Paquete | Versión | Fuente | Uso |
|---|---|---|---|
| `firebase-tools` | 15.26.0 | registro de npm | Emulator Suite |
| `@firebase/rules-unit-testing` | 5.0.1 | registro de npm | pruebas de reglas |
| `firebase` | 12.17.1 | registro de npm (verificado 2026-08-10) | SDK modular cliente (`doc`, `setDoc`, `getDoc`, `Bytes`, `Timestamp`) que `@firebase/rules-unit-testing` necesita para construir y leer documentos de prueba contra el emulador; no se usa como dependencia de red real, solo contra `localhost` |

Se instalan como dependencias de **desarrollo local** dentro de `firebase/`, no en el APK, y
`node_modules/` está en `.gitignore`. El motor de pruebas es el runner nativo de Node
(`node --test`), sin añadir Mocha ni Jest: Node 22 ya lo trae y evita una dependencia más en el
camino de pruebas (T-16).

## 4. Dependencias explícitamente rechazadas

| Rechazada | Motivo |
|---|---|
| `androidx.security:security-crypto` (EncryptedSharedPreferences) | descartada por los requisitos como núcleo de seguridad; además no debe guardarse contenido sensible en preferencias |
| SQLCipher (`net.zetetic`) | aplazada como defensa en profundidad (ADR-013): dependencia nativa en el camino crítico y complica las pruebas en la JVM |
| Firebase Analytics / Crashlytics / Performance | ADR-018: rutas de fuga de contenido sensible |
| Cualquier SDK de publicidad, tracking o grabación de sesión | prohibido por los requisitos |
| `argon2kt` (por ahora) | ADR-006: JNI impediría probar el núcleo criptográfico en la JVM; se mantiene como alternativa tras el benchmark |
| Bibliotecas de «cifrado fácil» que envuelvan AES con parámetros ocultos | se prohíbe la criptografía opaca; se usa Tink directamente |

## 5. Trabajo pendiente de esta política

| # | Pendiente | Fase | Comando o acción |
|---|---|---|---|
| D-01 | **Parcial 2026-08-10:** OSV-Scanner 2.5.0 verificó todos los lockfiles. Reportó avisos críticos/altos en configuraciones de herramientas de Gradle, pero `releaseRuntimeClasspath` no contiene Netty/OpenTelemetry/protobuf-java ni Bouncy Castle vulnerable; usa `bcprov-jdk18on` 1.85 y `protobuf-javalite` 3.25.5. `npm audit --audit-level=high` quedó con 0 altas/críticas y 5 moderadas transitivas, solo en Firebase Tools de desarrollo. | 9 | repetir OSV en CI; no confundir herramientas de build con contenido del APK; reevaluar las 5 moderadas cuando Firebase Tools actualice sus transitivas |
| D-02 | **Cerrado 2026-08-10:** `gradle/verification-metadata.xml` generado con SHA-256 al resolver `test lint detekt assembleDebug`; la misma ejecución terminó `BUILD SUCCESSFUL`. | — | regenerar conscientemente al cambiar dependencias y revisar el diff |
| D-03 | **Cerrado 2026-08-10:** bloqueo activado para toda configuración resoluble y lockfiles versionados por módulo. | — | actualizar con `--write-locks` solo junto con un cambio de dependencias revisado |
| D-04 | Confirmar la compatibilidad real de la matriz AGP 8.13.1 / Kotlin 2.2.20 / Compose BOM 2024.06.00 | 1 | Cerrado 2026-07-30: compiló con éxito validando AGP, Kotlin, Gradle y Detekt. Las librerías declaradas y no consumidas (Compose, Hilt, Room, Tink) quedan pendientes de validación al momento de su uso en fases posteriores. |
| D-05 | **Cerrado 2026-07-31:** `tink-android` **no** requiere Robolectric para pruebas en la JVM (riesgo R-04). `.\gradlew.bat :core:crypto:test` ejecuta 60 pruebas reales de cifrado/descifrado AEAD, envoltorio/desenvoltorio de keysets (`TinkProtoKeysetFormat`) e importación de KEK externas (`KeysetHandle.importKey`), todas en JVM pura, 0 fallos | — | — |
| D-06 | **Cerrado 2026-07-29:** versiones de integración y tests fijadas; existencia de cada POM confirmada en el repositorio oficial | — | volver a validar compatibilidad ejecutando la compilación real de Fase 1 (D-04) |

## 6. Procedimiento para añadir una dependencia

1. Justificar por qué no se puede resolver con la plataforma o con lo ya presente.
2. Consultar la fuente oficial: última versión, actividad, compatibilidad, riesgos.
3. Añadir la fila correspondiente a §3 con la fecha de verificación.
4. Fijar la versión en `gradle/libs.versions.toml`.
5. Si toca el camino criptográfico, abrir un ADR en `DECISIONS.md`.
6. Ejecutar `cryptography-reviewer` o `android-architect` según corresponda antes de dar la fase
   por cerrada.
