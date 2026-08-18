# FIREBASE_SETUP.md — Configuración de Firebase

Cómo se desarrolla con Firebase en este proyecto y qué pasos manuales necesita el propietario.

**Regla absoluta:** este repositorio **nunca** contiene `google-services.json` real, cuentas de
servicio, claves administrativas ni tokens. El desarrollo ordinario ocurre contra **Firebase
Emulator Suite** (ADR-017); cualquier acceso al proyecto real exige autorización explícita y un
identificador de proyecto indicado en el comando.

Estado: la integración Android, las Security Rules y su suite están implementadas. La última
evidencia registrada de reglas es 44/44; el estado verificable y los límites de una nueva
ejecución están en `PROJECT_STATE.md`. La configuración real permanece fuera del repositorio y
ninguna guía sustituye el control de cambios de infraestructura. Detalle de las decisiones en
ADR-037.

---

## 1. Para qué se usa Firebase

| Servicio | Uso | Qué ve Firebase |
|---|---|---|
| Authentication | identificar al usuario y autorizar el acceso a sus documentos | correo o identidad de Google |
| Cloud Firestore | almacenar y sincronizar blobs cifrados | ciphertext, identificadores aleatorios, versiones, marcas de tiempo |
| App Check | dificultar el acceso desde clientes no legítimos | — |

**No se usa:** Analytics, Crashlytics, Performance Monitoring, Remote Config, Cloud Functions,
Storage ni Cloud Messaging.

Firebase **no puede descifrar nada**: no posee ninguna clave de la bóveda.

---

## 2. Desarrollo con el Emulator Suite (lo normal)

Requisitos: **Node 22** y **JDK 21+** para el emulador de Firestore. `firebase-tools` 15.26.0
(la versión registrada en `docs/DEPENDENCY_POLICY.md`) **exige Java 21**; con Java 17 el comando
falla con `firebase-tools no longer supports Java version before 21`.

El proyecto Gradle compila con **JDK 17**. Para el emulador se requiere un JDK 21 independiente;
se apunta a él **solo para el comando de pruebas**, sin cambiar la configuración global de Gradle:

```powershell
Push-Location firebase
npm ci

# Java 21 solo para este proceso; no toca la configuración de Gradle.
$env:JAVA_HOME = "C:\RUTA_A_JDK_21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
npm test             # arranca los emuladores (auth, firestore) y ejecuta las reglas
Pop-Location
```

En la primera ejecución, `firebase-tools` descarga el binario del emulador de Firestore. Eso
requiere conexión y es normal.

La última ejecución registrada con JDK 21 completó **44/44** pruebas de reglas; no se debe
presentar como una nueva ejecución si el emulador no pudo iniciarse en el entorno actual. La suite
incluye `vault.test.js`, `items.test.js`, `isolation.test.js` y `migration_fixture.test.js`.

Para ejecutar la aplicación contra los emuladores, `data/remote/.../internal/EmulatorConfig.kt`
llama `useEmulator(...)` en Auth y Firestore **solo si `BuildConfig.DEBUG`** del propio módulo
`:data:remote` (ADR-037); en release esa rama no existe. El NSC de `app/src/debug` permite tráfico
en claro únicamente a `10.0.2.2` y `localhost`; el NSC principal/release lo deniega por completo.

**Probar contra un dispositivo físico (no un AVD), como el conectado en este entorno.**
`"10.0.2.2"` es el alias de loopback del Android Emulator y **no lo resuelve** un teléfono real.
Para ejecutar `:data:remote:connectedDebugAndroidTest` contra el Emulator Suite desde el
dispositivo por USB:

```powershell
adb reverse tcp:9099 tcp:9099
adb reverse tcp:8080 tcp:8080
```

La prueba instrumentada (`data/remote/src/androidTest/.../FirebaseRemoteEmulatorTest.kt`) ya usa
el host `"localhost"` para este caso, permitido por un NSC propio de ese APK de pruebas
(`data/remote/src/androidTest/res/xml/network_security_config.xml`, exclusivo del paquete de
pruebas de `:data:remote` — no toca el NSC real de `:app`). Un AVD de Android Studio no necesita
`adb reverse`: usa el host por defecto `"10.0.2.2"` directamente.

**Resultado registrado (2026-07-31):** `.\gradlew.bat :data:remote:connectedDebugAndroidTest` contra el
Emulator Suite local (`firebase emulators:start --project <proyecto-ficticio> --only auth,firestore`,
JDK 21) desde un dispositivo físico de prueba → **3 pruebas, 3 pasan, 0 fallos**: alta de correo y
contraseña seguida de inicio de sesión (mismo `uid`), y el roundtrip de metadatos de bóveda e ítem
a través de las Security Rules reales del emulador, con bytes ficticios marcados como tales (nunca
material criptográfico real).

Puertos fijados en `firebase/firebase.json`:

| Emulador | Puerto |
|---|---|
| Authentication | 9099 |
| Firestore | 8080 |
| Interfaz de emuladores | 4000 |

---

## 3. Archivo de configuración cliente

El plugin de Google Services necesita un `google-services.json`. En este repositorio existe
únicamente **`google-services.json.example`** como esquema documental con placeholders. El ejemplo
no se copia ni se rellena: no contiene clientes OAuth utilizables.

El archivo real se descarga desde la consola Firebase **después** de habilitar Google Sign-In y
registrar la huella SHA-1 del certificado release. La descarga debe incluir un cliente OAuth
Android y un cliente OAuth Web; este último genera `default_web_client_id`, requerido por
Credential Manager.

`app/google-services.json` está en `.gitignore` y **nunca** debe versionarse. Sin ese archivo, la
aplicación debug compila para trabajar contra el emulador. El release falla cerrado si el archivo
real falta o si no genera `default_web_client_id`.

Recordatorio de `SECURITY.md` §4: la clave de API de ese archivo **no es un secreto de
autorización**, pero se mantiene fuera del repositorio por higiene y porque identifica un proyecto
concreto del propietario.

---

## 4. Configuración del proyecto real (bloqueo externo B-01)

Estos pasos se realizan únicamente con autorización del propietario y su sesión autenticada. El
propietario completa personalmente cualquier desbloqueo o segundo factor solicitado por Google.

1. Crear un proyecto en la consola de Firebase.
2. Registrar una aplicación Android con el `applicationId` `cl.bovedawilson.app`.
3. Descargar `google-services.json` y colocarlo en `app/` (no versionarlo).
4. **Authentication → Métodos de acceso:** habilitar *Google* (principal) y, si se desea,
   *Correo y contraseña* (opcional).
5. Para Google Sign-In de la APK distribuida: obtener la huella SHA-1 del certificado de firma
   **release** y añadirla en la configuración del proyecto; después volver a descargar
   `google-services.json`.
   ```powershell
   keytool -list -v -keystore C:\RUTA_PRIVADA\boveda-wilson-release.jks -alias <alias-release>
   ```
   La clave y su contraseña nunca se copian al repositorio ni se muestran en documentación.
6. **Firestore:** crear la base de datos en modo producción, **nunca** en modo de prueba.
7. Desplegar las reglas ya probadas mediante el proceso de control de cambios del propietario. El
   despliegue es una modificación de infraestructura y no se ejecuta desde esta guía ni se asume
   como parte de una prueba local.
8. **App Check** (opcional, bloqueo externo B-02): registrar la aplicación release con Play
   Integrity usando la SHA-256 del mismo certificado de firma. Para una APK instalada desde Drive,
   configurar `PLAY_RECOGNIZED` y `LICENSED` como **no requeridos** y exigir, como máximo inicial,
   integridad del dispositivo. No activar enforcement hasta observar tokens válidos del APK firmado
   en el teléfono definitivo. En depuración se usa exclusivamente el proveedor debug.

App Check no se puede imponer desde las Security Rules de Firestore y, mientras B-02 siga abierto,
su garantía en producción es **cero**. No se presenta como una protección ya activa.

---

## 5. Firma de la aplicación

- La clave release (`.jks`), su contraseña y el archivo real de propiedades **no entran** en el
  repositorio ni en su carpeta OneDrive.
- El propietario los genera, respalda y guarda en una ubicación privada externa.
- `signing.properties.example` documenta las cuatro propiedades requeridas sin valores reales.
- La ruta absoluta del archivo privado se pasa con
  `-Pboveda.signingProperties=C:\RUTA_PRIVADA\signing.properties` o mediante
  `BOVEDA_SIGNING_PROPERTIES`.
- Si falta la configuración, el almacén, Firebase o el cliente OAuth Web, `assembleRelease` falla.
  Nunca cae silenciosamente a la clave de depuración ni produce una entrega local-only.

---

## 6. Verificación de que no se filtró nada

Antes de cualquier commit que toque configuración de Firebase:

```powershell
git status --short
git diff --staged
git ls-files | Select-String -Pattern "google-services\.json$|\.jks$|service.?account|keystore\.properties"
```

Si aparece cualquier resultado en la última orden, **detener el trabajo**: sacarlo del índice,
registrar el incidente en `PROJECT_STATE.md` y avisar al propietario. Un secreto que ya llegó a un
remoto se considera comprometido y debe rotarse.

---

## 7. Qué hacer si el proyecto real no existe todavía

Nada se bloquea: la Fase 4 se completa contra el emulador y las reglas se prueban de verdad. Lo
único que queda pendiente hasta que exista el proyecto real es:

| Pendiente | Motivo | Registrado como |
|---|---|---|
| Google Sign-In de extremo a extremo | requiere cliente OAuth real | B-01 |
| App Check con Play Integrity | requiere app registrada | B-02 |
| Despliegue de las reglas | acción en infraestructura real | B-01 |

Todo lo demás —autenticación por correo contra el emulador, reglas, sincronización— se implementa y
se prueba sin credenciales reales.

---

## 8. Configuración de un proyecto real

Los identificadores, números de proyecto, enlaces de consola y rutas de custodia de una instancia
real no pertenecen al repositorio. Cada persona que despliegue su propia instancia debe completar
la sección 4 con sus propios valores y conservar `google-services.json` únicamente en su entorno
local ignorado.

### Decisiones de configuración

- No habilitar Analytics, Crashlytics, Performance Monitoring ni otras herramientas de telemetría.
- Crear Firestore en modo producción, con reglas que deniegan por defecto hasta desplegar el
  contrato probado.
- Registrar la aplicación Android con `applicationId` `cl.bovedawilson.app` y mantener
  `google-services.json` ignorado por Git.
- Habilitar Google Sign-In únicamente tras registrar la huella pública de la firma release y
  descargar de nuevo la configuración.

Verificar que el archivo local permanece ignorado:

```powershell
git check-ignore -v app/google-services.json
# .gitignore:8:google-services.json	app/google-services.json
.\gradlew.bat :app:assembleDebug     # BUILD SUCCESSFUL
```

### Consecuencia inmediata que hay que tener presente

Con `google-services.json` presente, `app/build.gradle.kts` **ya aplica** el plugin
`com.google.gms.google-services` y `BuildConfig.HAS_GOOGLE_SERVICES` pasa a `true`. Eso
cambia dos comportamientos en tiempo de ejecución:

1. `RemoteModule` deja de entregar `OfflineFirestoreVaultSource` y entrega el cliente real
   (ADR-040).
2. **En compilaciones `debug`, `EmulatorConfig` sigue apuntando Auth y Firestore al
   Emulator Suite** (`10.0.2.2`), no al proyecto real (ADR-037). Es lo correcto y
   deliberado, pero significa que un APK de depuración **no habla con una instancia real**.
   Para una prueba autorizada contra el proyecto real se genera un debug explícito con
   `-Pboveda.useFirebaseEmulator=false`; sin esa propiedad, el comportamiento seguro por
   defecto no cambia:

   ```powershell
   .\gradlew.bat :app:assembleDebug '-Pboveda.useFirebaseEmulator=false' --console=plain
   ```

   La propiedad solo evita `useEmulator(...)`; no despliega reglas, no modifica Firebase y
   no debe usarse en CI como sustituto de Emulator Suite.

### Firebase CLI

Para pruebas locales basta Node y el JDK 21 descrito en §2. La autenticación ante una instancia
real se completa de forma interactiva por el propietario y sus tokens no se muestran, copian ni
versionan. `firebase/` conserva un proyecto ficticio para emuladores; no usarlo como destino de
una modificación real.

Los despliegues de reglas, proveedores de acceso o App Check se realizan mediante control de
cambios externo y con autorización explícita. Esta guía solo cubre la configuración reproducible
del código y los emuladores.

### Estado de integración

1. **Security Rules:** la suite tiene evidencia histórica de 44/44. Toda confirmación de reglas
   desplegadas corresponde a infraestructura externa; el repositorio no registra el identificador
   ni los enlaces de esa instancia.
2. **Google Sign-In:** el flujo de la APK release fue validado manualmente por el propietario;
   consultar `docs/VERIFICATION_STATUS.md`. La configuración OAuth real
   sigue ignorada localmente.
3. **App Check con Play Integrity:** no se fuerza en la distribución actual hasta
   validar tokens reales. Hasta entonces su garantía de producción es cero.
4. **Emuladores:** `.firebaserc` usa un proyecto ficticio. Es correcto para desarrollo local y
   nunca debe confundirse con infraestructura real.
