# docs/TEST_STRATEGY.md — Estrategia de pruebas

Qué se prueba, en qué nivel y con qué datos. Las reglas vinculantes están en
`docs/TEST_STRATEGY.md`; este documento organiza la cobertura por fase.

Estado: estrategia definida; ver la columna «Cobertura» de cada garantía y `PROJECT_STATE.md`
para el estado real por fase.

---

## 1. Niveles y dónde vive cada uno

| Nivel | Ubicación | Ejecuta con | Requiere dispositivo |
|---|---|---|---|
| Unitarias JVM | `<módulo>/src/test` | `.\gradlew.bat test` | no |
| Instrumentadas | `<módulo>/src/androidTest` | `.\gradlew.bat connectedDebugAndroidTest` | **sí** (hay uno conectado por USB) |
| Security Rules | `firebase/` (Node) | `cd firebase; npm test` | no (usa el Emulator Suite) |
| Higiene del repositorio | `src/test` | `.\gradlew.bat test` | no |
| Manual | `docs/security-checklist.md` | persona | sí |

**Restricción de diseño:** todo el núcleo criptográfico debe probarse en la JVM. Es la razón por la
que Argon2id usa una implementación pura de JVM (ADR-006).

---

## 2. Datos de prueba

- **Vectores públicos**: los del estándar BIP-39, citados como tales.
- **Datos ficticios**: constantes con prefijo `FIXTURE_` y un comentario «valor ficticio, no usar en
  producción».
- **Cadenas señuelo**: constantes improbables usadas para detectar fugas de plaintext, por ejemplo
  con la forma `BW-CANARY-<identificador>-DO-NOT-PERSIST`.
- **Prohibido**: cualquier contraseña real, frase real, clave real o `google-services.json` real.
- Los parámetros reducidos de Argon2id para acelerar pruebas solo se inyectan por la interfaz
  `PasswordKdf` y están **marcados como de prueba**; existe además una prueba que ejercita los
  parámetros de producción. Casos separados varían nombre, memoria, iteraciones, paralelismo,
  salida y salt, y todos se rechazan antes de invocar Argon2id.

---

## 3. Matriz garantía → prueba

Se rellena la columna «prueba» a medida que se implementan. Una garantía sin prueba es un hallazgo
para `test-engineer`.

| # | Garantía | Nivel | Fase | Prueba |
|---|---|---|---|---|
| G-01 | Cifrar y descifrar es correcto | JVM | 2 | hecho — `UnlockedVaultTest`. El caso «sin AAD» es inalcanzable por diseño: `Aad` tiene constructor `internal` y la única fábrica pública (`AadBuilder`) siempre produce una AAD no vacía |
| G-02 | Mismo contenido → ciphertext distinto | JVM | 2 | hecho — `UnlockedVaultTest` |
| G-03 | AAD incorrecta falla | JVM | 2 | hecho — `UnlockedVaultTest` |
| G-04 | Ciphertext alterado falla (todas las posiciones) | JVM | 2 | hecho — `UnlockedVaultTest`, más vacío/1 byte/truncado/extendido |
| G-05 | Contraseña incorrecta falla con error genérico | JVM | 2 | hecho — `VaultCryptoTest` |
| G-06 | La recuperación abre **la misma** VDEK | JVM | 2 | hecho — `VaultCryptoTest` |
| G-07 | Una palabra incorrecta falla | JVM | 2 | hecho — `VaultCryptoTest`, `Bip39CodecTest` |
| G-08 | Versión desconocida falla de forma segura | JVM | 2 | hecho — `VaultCryptoTest` |
| G-09 | Para v1 se rechaza antes de Argon2id cada valor distinto de 65 536/3/4/32 y todo nombre/salt inválido | JVM | 2 | hecho — `KdfPolicyTest`, `Argon2idPasswordKdfTest` |
| G-10 | Downgrade de parámetros rompe el unwrap | JVM | 2 | hecho — `VaultCryptoTest` |
| G-11 | Cambio de contraseña conserva las notas y la recuperación | JVM | 2 | hecho — `VaultCryptoTest` |
| G-12 | Regenerar la frase hace fallar la anterior contra el envoltorio actual y conserva las notas | JVM | 2 | hecho — `VaultCryptoTest` |
| G-13 | Errores y excepciones sin material sensible | JVM | 2 | hecho — `VaultCryptoTest` |
| G-14 | Los buffers quedan a cero | JVM | 2 | parcial — probado en runtime para las utilidades (`WipeTest`, `CharArraysTest`, `SecureBytesTest`) y para `RecoveryEntropy`; el borrado interno de `VaultWrapping` (`argonOut`/`kek`) solo se verifica por inspección de código (ver `PROJECT_STATE.md`) |
| G-15 | Vectores BIP-39 públicos coinciden | JVM | 2 | hecho — `Bip39CodecTest`, vectores de `vectors.json` de `trezor/python-mnemonic` descargados el 2026-07-31 |
| G-16 | Room no contiene plaintext (señuelos en `.db`/`-wal`/`-shm`/caché) | instrumentada | 3 | hecho — `BackupRepositoryTest` busca canarios ficticios en base, WAL, SHM, caché y respaldo; ejecución 12/12 registrada en `PROJECT_STATE.md` |
| G-17 | El esquema no tiene columnas de contenido | instrumentada | 3 | hecho — `VaultDatabaseTest` inspecciona el esquema real de Room; ejecución instrumentada registrada en `PROJECT_STATE.md` |
| G-18 | Al bloquear, el estado queda sin contenido descifrado | JVM/instrumentada | 3 | parcial — `VaultSessionTest` y `AutoLockControllerTest` cubren la invalidación; la evidencia del orquestador de muerte de proceso debe registrarse de forma uniforme |
| G-19 | No autenticado: denegado | reglas | 4 | hecho — `vault.test.js`, `items.test.js` |
| G-20 | A no lee datos de B | reglas | 4 | hecho — `isolation.test.js` (doc y `list`) |
| G-21 | A no escribe datos de B | reglas | 4 | hecho — `isolation.test.js` |
| G-22 | Campos no permitidos: denegados | reglas | 4 | hecho — `vault.test.js`, `items.test.js` |
| G-23 | Tipos incorrectos: denegados | reglas | 4 | hecho — `vault.test.js`, `items.test.js` |
| G-24 | Tamaño excesivo: denegado (límite exacto y +1) | reglas | 4 | hecho — `vault.test.js`, `items.test.js` |
| G-25 | `createdAt` inmutable; propietario no reasignable | reglas | 4 | hecho — `vault.test.js`, `items.test.js` |
| G-26 | `revision` no retrocede | reglas | 4 | hecho — `vault.test.js` (`metaRevision`), `items.test.js` (`revision`) |
| G-27 | Borrado físico: ítem solo si ya es tombstone; bóveda solo por su propietario | reglas | 4 | hecho — `vault.test.js`, `items.test.js` |
| G-28 | Estructura válida aceptada | reglas | 4 | hecho — `vault.test.js`, `items.test.js` |
| G-29 | Edición sin conexión y subida posterior | JVM/instr. | 5 | hecho — `SyncEngineTest` conserva `dirty` ante red caída y confirma la subida posterior; ejecución JVM registrada en `PROJECT_STATE.md` |
| G-30 | Tombstones se propagan | JVM/instr. | 5 | hecho — `SyncEngineTest`, `RoomEncryptedItemStoreTest` y `items.test.js` cubren la forma canónica y su sincronización |
| G-31 | Conflicto sin sobrescritura silenciosa | JVM | 5 | hecho — `SyncEngineTest` conserva la edición local, hace staging y crea copia de conflicto al desbloquear |
| G-32 | WorkManager sin secretos en sus datos de entrada | JVM/higiene | 5 | hecho — `RepositoryHygieneTest > G-90 WorkManager input hygiene` rechaza `Data.Builder`, `workDataOf` y `setInputData` en fuentes productivas de sincronización |
| G-33 | Clave del Keystore con los atributos exigidos y no exportable | instrumentada | 6 | hecho — `BiometricUnlockTest` comprueba creación, no exportabilidad, validez e `KeyInfo` |
| G-34 | Invalidación borra ambos blobs, IV, epoch, registro y alias propio; exige contraseña | instrumentada | 6 | parcial — `BiometricUnlockTest` invalida la clave y las pruebas de almacenamiento cubren limpieza; la reinscripción real de huella sigue en la lista manual |
| G-35 | El blob biométrico no se sincroniza ni entra en el respaldo | instrumentada | 6/8 | hecho — `BackupRepositoryTest` verifica exclusión explícita; ejecución 12/12 en dispositivo registrada en `PROJECT_STATE.md` |
| G-36 | Bloqueo automático dispara | JVM/instrumentada | 7 | hecho — `AutoLockControllerTest` y `VaultSessionTest` cubren inactividad y segundo plano; la interacción visual se mantiene en la lista manual |
| G-37 | `FLAG_SECURE` aplicado en la actividad y en cada diálogo/hoja modal | instrumentada/higiene | 7 | hecho — `WindowSecurityTest` pasó en dispositivo y G-69 exige `SecureDialog` para modales |
| G-38 | Muerte y restauración del proceso sin plaintext en `Bundle` | instrumentada | 7 | hecho — `tools/verify-process-death.ps1` ejecuta `am force-stop` externamente, verifica cambio de PID, desbloqueo visible y canario ausente; `ProcessDeathTest` 1/1 |
| G-39 | El respaldo no contiene plaintext (señuelos) | JVM/instr. | 8 | hecho — archivo ciphertext-only y búsqueda UTF-8/UTF-16LE en DB, WAL, SHM y directorios; incluida en 13/13 connected de `:data:sync` |
| G-40 | Ida y vuelta del respaldo conserva el contenido | JVM/instr. | 8 | hecho — round-trip estructural y restauración Room ejecutados en dispositivo |
| G-41 | Restauración con contraseña **y** con frase | JVM/instr. | 8 | hecho — `VaultCryptoTest` y ambos caminos de `BackupRepositoryTest` ejecutados |
| G-42 | El parser rechaza todas las entradas inválidas de `BACKUP_FORMAT.md` §6 | JVM | 8 | hecho — matriz determinista en `BackupFormatTest`: magic/versiones futuras y v1 no soportadas, tipos, perfil KDF, duplicados, tombstones, Base64, epochs, salts, enteros fuera de rango, truncado, límites exactos/+1 y fuzzing fijo |
| G-43 | Fuzzing con semilla fija: sin OOM, sin bucles, sin aceptación parcial | JVM | 8 | hecho — `BackupFormatTest`, semilla fija reportada en fallos |
| G-44 | Higiene: patrones prohibidos ausentes del árbol de fuentes | JVM | 1→9 | hecho — `RepositoryHygieneTest`; repetido dentro de `gradlew test` el 2026-08-10 |
| G-45 | Higiene: los DTO remotos y las entidades locales no tienen campos de texto de contenido | JVM | 3 | hecho — `RepositoryHygieneTest`; repetido dentro de `gradlew test` el 2026-08-10 |
| G-46 | El repositorio no contiene secretos (árbol e historial) | JVM/script | 9 | hecho — `scripts/scan-secrets.ps1 -History`, exit 0 el 2026-08-10; CI repite con historial completo |
| G-47 | La AAD se reconstruye con identidad byte a byte, sin campos sensibles | JVM | 2 | hecho — `AadBuilderTest` |
| G-48 | Trasplantar ciphertext entre `itemId` o `vaultId` falla | JVM | 2 | hecho — `UnlockedVaultTest` |
| G-49 | `argon2i`, `argon2d` y todo `kdfName` inesperado se rechazan | JVM | 2 | hecho — `KdfPolicyTest` |
| G-50 | Los contextos HKDF son únicos y ninguno es prefijo de otro | JVM | 2 | hecho — `HkdfTest` |
| G-51 | Blob biométrico con otro `vaultId` o alias falla por AAD | instrumentada | 6 | hecho — las cinco pruebas biométricas pasaron con huella fuerte inscrita dentro de 13/13 connected |
| G-52 | Recuperar con la frase no la invalida; sigue abriendo el envoltorio actual | JVM | 2 | hecho — `VaultCryptoTest` |
| G-53 | Todo parámetro distinto del perfil Argon2id v1 se rechaza antes de reservar memoria | JVM | 2 | hecho — `Argon2idPasswordKdfTest` |
| G-54 | Antes de persistir, ambos envoltorios abren la misma VDEK | JVM | 2/8 | hecho para la Fase 2 (`VaultWrapping.verifySameVdek`, ejercitado por `VaultCryptoTest`); Fase 8 lo reutiliza en la restauración de respaldo |
| G-55 | Cada epoch regresivo se rechaza contra su marca de agua; cambiar un camino no invalida los otros | JVM | 2/5/6 | parcial — el caso de la Fase 2 (AAD atada al epoch, un camino no invalida el otro) está cubierto por `VaultCryptoTest`; la marca de agua por camino en la sincronización remota es de la Fase 5 |
| G-56 | Tombstone vacío aceptado; tombstone con contenido y activo vacío denegados | reglas | 4 | hecho — `items.test.js` |
| G-57 | `create` de ítem con `revision > 1` se acepta | reglas | 4 | hecho — `items.test.js` |
| G-58 | `metaRevision`, versiones, perfil KDF y ambos epochs cumplen el contrato; cada grupo exige solo su epoch; borrar un obligatorio se deniega. Una fixture de reglas con `migrationFixture` opcional prueba presencia, ausencia, campo ajeno y promoción; se replica al existir el primer opcional real | reglas | 4 | hecho — `vault.test.js` (versiones, KDF, epochs), `items.test.js` (versiones), `migration_fixture.test.js` (fixture completa, incluida la promoción) |
| G-59 | Salts, envoltorios y marcas de tiempo respetan límites exactos | reglas/JVM | 4/8 | hecho — reglas cubren tamaños y tolerancia de reloj; `BackupFormatTest` cubre exacto/+1 de ciphertext, wrappers, autenticador, timestamps y campos estrictos |
| G-60 | `collectionGroup('items')` y rutas hermanas reales quedan denegadas por defecto | reglas | 4 | hecho — `isolation.test.js` |
| G-61 | Cuenta A → cerrar sesión → B no mezcla ni expone la bóveda local de A | JVM/manual | 3/5 | parcial — `CloudAccessRepositoryTest` liga la selección al uid y la invalida al cambiar sesión; falta una ejecución manual entre dos cuentas reales |
| G-62 | El staging conserva el DTO remoto completo y resuelve sin red un cambio de versión y un tombstone | JVM/instrumentada | 3/5 | hecho — `SyncEngineTest` y `RoomEncryptedItemStoreTest` cubren staging completo, tombstone y resolución sin una segunda lectura remota |
| G-63 | Fallo inyectado en cada frontera biométrica deja utilizable el conjunto anterior o el nuevo y limpia aliases huérfanos | instrumentada/JVM | 6 | parcial — hay cobertura de invalidación y reintentos, pero no de cada frontera de fallo; se conserva como endurecimiento pendiente |
| G-64 | Publicación valida snapshot: remoto idéntico no reescribe ítems; cambio remoto bloquea; creación ausente se reanuda por lotes idempotentes | JVM/integración | 8 | hecho — prueba determinista interrumpe tras un subconjunto, conserva la autorización, reintenta solo faltantes y comprueba CAS final de metadata/wrappers |
| G-65 | API compilada sin `Aead`/VDEK/`KeysetHandle`; stores aceptan solo `Ciphertext`; único flujo `encrypt → Ciphertext → store/source` | JVM/arquitectura | 1/3/5 | hecho — `ArchitectureTest` y `RepositoryHygieneTest` verifican las fronteras públicas y el grafo de módulos |
| G-66 | El grafo de módulos coincide con ADR-019/033 **arista por arista**: para cada `build.gradle.kts` el conjunto de `project(...)` declarados debe ser **igual** al de la tabla normativa, no solo estar libre de prohibidos; además falla ante cualquier `api(project(`. Verificado por mutación: añadir `:data:local` a `:app` hace fallar la prueba | JVM/higiene | 1 | `RepositoryHygieneTest > G-66 module graph hygiene` |
| G-67 | Higiene de registro: ninguna fuente fuera de `:core:common/log/` usa `android.util.Log`, `println`, `print(`, `System.out` ni `printStackTrace()` | JVM/higiene | 1 | `RepositoryHygieneTest > G-67 logging hygiene` |
| G-68 | Higiene criptográfica: ninguna fuente contiene `AES/ECB`, `java.util.Random`, `Math.random()`; `Cipher.getInstance` y `MessageDigest` (hash como cifrado) están prohibidos **fuera de `:core:crypto`** (dentro del módulo, ambos tienen usos legítimos: Keystore y el checksum SHA-256 de BIP-39, ADR-035) | JVM/higiene | 1/2 | `RepositoryHygieneTest > G-68 cryptographic hygiene` |
| G-69 | Higiene de persistencia y estado: ninguna fuente contiene `fallbackToDestructiveMigration`, `rememberSaveable` ni `SavedStateHandle` en paquetes de UI sensibles, ni `Dialog(` fuera de `SecureDialog` | JVM/higiene | 1 | `RepositoryHygieneTest > G-69 persistence and state hygiene` |
| G-70 | Higiene de secretos: en los archivos de extensión `kt`, `kts`, `xml`, `properties`, `toml`, `json`, `jks` y `keystore` fuera de `build/` y `.gradle/`, ninguno coincide con patrones de clave/token ni es un archivo `.jks`/`.keystore`. Un `google-services.json` real solo puede existir ignorado localmente y la prueba falla si aparece versionado, incluso mediante `git add -f`. **No cubre** `.md`, `.pro`, `.yml`, `.txt` ni archivos sin extensión | JVM/higiene | 1 | `RepositoryHygieneTest > G-70 secrets hygiene` |
| G-71 | Comportamiento de `SecureLogger`: en release se descartan `v`/`d`/`i` y se conservan `w`/`e`; un campo `Redact` se emite por su etiqueta y nunca por su valor (`idPrefix` trunca un `RandomId`, `type` emite la clase, `redacted` emite `[REDACTED]`). `idPrefix` solo acepta `RandomId`, cuyo constructor es `internal`: fuera de `:core:common` no hay forma de construirlo desde un `String` arbitrario, verificado por mutación (`Redact.idPrefix("...")` con un `String` libre no compila). **No demuestra** que ningún llamante interpole un secreto en `event`: eso lo custodia G-72 | JVM | 1 | `SecureLoggerTest`, `RandomIdTest` |
| G-72 | Higiene de registro seguro: en el árbol de fuentes fuera de `:core:common/log/`, ninguna **línea** que contenga una llamada `SecureLogger.v/d/i/w/e(` contiene interpolación (`$`), y no existe construcción directa `Redact(` saltándose las fábricas. Alcance por línea: una llamada repartida en varias líneas cuya interpolación caiga fuera de la línea de la llamada no se detecta; la frontera de tipos de la API es la defensa principal | JVM/higiene | 1 | `RepositoryHygieneTest > G-72 secure logging call hygiene` |
| G-73 | Clases que contienen título/cuerpo/campos descifrados no pueden ser `data class`, para evitar `toString`/`copy`/`equals` generados sobre plaintext | JVM/higiene | 7 | `RepositoryHygieneTest > G-73 higiene de clases con contenido de nota` |
| G-74 | `Ciphertext.fromPersisted` queda confinado a mappers internos y pruebas | JVM/higiene | 4 | `RepositoryHygieneTest > G-74 Ciphertext fromPersisted confinado a mappers internos y a pruebas` |
| G-75 | `Aead` y `KeysetHandle` quedan confinados a los archivos internos autorizados de `:core:crypto` | JVM/higiene | 2/4 | `RepositoryHygieneTest > G-75 Aead y KeysetHandle confinados a los archivos internos de core crypto` |
| G-88 | Contraseñas/frases no se conservan en estado Compose basado en `String`; se prohíben conversiones tardías desde `String` y `data class` de UI con frase de recuperación | JVM/higiene | 7 | `RepositoryHygieneTest > G-88 secretos de autenticacion no usan estado String de Compose` |
| G-89 | El manifiesto completo del respaldo se autentica con Tink/VDEK antes de restaurar o publicar | JVM | 8 | hecho — `BackupFormatTest` cubre eliminación, activo/tombstone, metadatos y mezcla de ciphertext; `BackupPublicationAuthorizerTest` liga la capacidad al snapshot autenticado |
| G-90 | WorkManager no recibe `Data.Builder`, `workDataOf` ni `setInputData` desde fuentes productivas de sincronización | JVM/higiene | 5 | hecho — `RepositoryHygieneTest` impide esas tres rutas de entrada de WorkManager en producción |

Las garantías G-66 … G-75 y G-88 son la cobertura estructural acumulada. Las pruebas de higiene recorren el
árbol de fuentes desde la raíz del repositorio y **se excluyen a sí mismas y a los documentos**, ya
que los patrones prohibidos aparecen literalmente en ellos.

---

## 4. Pruebas de fuga de plaintext (detalle)

Procedimiento común, reutilizado en las fases 3 y 8:

1. Se crean ítems cuyo contenido incluye cadenas señuelo.
2. Se cierra la base de datos y se fuerza el volcado del WAL.
3. Se leen los **bytes** de: archivo de la base, `-wal`, `-shm`, `cacheDir`, `filesDir`,
   `noBackupFilesDir` y el archivo de respaldo si aplica.
4. Se busca cada señuelo en UTF-8 y en UTF-16LE (Android usa UTF-16 en memoria; buscar ambos evita
   falsos negativos).
5. Aparecer una vez = fallo de la prueba.

Esta prueba **no se desactiva nunca** y no se «arregla» cambiando el señuelo.

---

## 5. Property-based y fuzzing

Sin dependencia externa: generación aleatoria con **semilla fija** definida por constante y
**impresa en el mensaje de fallo**, para que cualquier fallo sea reproducible. Se cubren:

- parser del respaldo (`BACKUP_FORMAT.md` §6);
- parser de registros cifrados (ciphertext truncado, con bytes invertidos, con prefijo de clave
  alterado);
- tamaños extremos: vacío, un byte, en el límite, un byte por encima;
- versiones desconocidas hacia arriba y hacia abajo.

Criterio de aceptación: ningún caso produce `OutOfMemoryError`, bucle infinito, excepción no
tipificada ni aceptación parcial de datos inválidos.

---

## 6. Qué no se prueba automáticamente

| Caso | Motivo | Dónde queda |
|---|---|---|
| Diálogos de `BiometricPrompt` | exigen un toque humano | checklist manual |
| Reinscripción de huellas → invalidación real de la clave | exige cambiar la biometría del dispositivo | checklist manual |
| Google Sign-In real | requiere proyecto de Firebase y cliente OAuth reales (B-01) | checklist manual + `FIREBASE_SETUP.md` |
| App Check con Play Integrity | requiere proyecto y app registrada (B-02) | documentado, no activado |
| Compatibilidad adicional | se verificó API 36 en el teléfono definitivo; otros fabricantes/niveles requieren su propio recorrido | matriz de dispositivos |
| Auditoría criptográfica por terceros | fuera de alcance | declarado en `THREAT_MODEL.md` |

Nada de esta lista se declarará nunca como «prueba que pasa».

---

## 7. Registro de resultados

Al cerrar cada fase se copian a `PROJECT_STATE.md`:

- las pruebas que **pasan**, con el comando que las ejecutó;
- las que **fallan**, con el mensaje real;
- las que **no se ejecutaron**, con el motivo y el comando exacto pendiente.

No se escribe ningún resultado que no provenga de una ejecución real.

### Evidencia de cierre de esta continuación (2026-08-01)

```text
.\gradlew.bat :data:local:compileDebugKotlin :data:sync:testDebugUnitTest :app:testDebugUnitTest --console=plain
→ BUILD SUCCESSFUL in 5s (143 tareas up-to-date; no se usó --rerun-tasks)

.\gradlew.bat :data:sync:connectedDebugAndroidTest --console=plain
→ BUILD SUCCESSFUL in 1m 29s; 13/13, 0 omitidas, 0 fallidas

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\verify-process-death.ps1
→ exit 0; OK (1 test); PID anterior terminado, desbloqueo visible y canario ausente

.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
→ BUILD SUCCESSFUL in 40s; la variante interna de ProcessDeath se omite por diseño y se
  cubre con el comando externo anterior

.\gradlew.bat :data:sync:testDebugUnitTest :data:sync:detekt :app:detektDebug --console=plain
→ BUILD SUCCESSFUL in 52s

.\gradlew.bat :app:lintDebug --console=plain
→ BUILD SUCCESSFUL in 1m 55s

.\gradlew.bat :app:assembleRelease --console=plain
→ BUILD SUCCESSFUL in 3m 52s; sin --rerun-tasks
```

Antes de la repetición verde, Detekt falló una vez por `LongParameterList` en la capacidad de
publicación. Se agrupó el reto AEAD en un tipo propio y se repitieron pruebas y Detekt con éxito.
Google Sign-In real y cualquier publicación contra Firebase real no se ejecutaron por B-01 y por
la prohibición de modificar infraestructura externa.

Después de corregir los dos hallazgos altos finales de lifecycle, se ejecutó de nuevo:

```text
.\gradlew.bat :app:testDebugUnitTest :app:detektDebug --console=plain
→ BUILD SUCCESSFUL in 54s
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
→ BUILD SUCCESSFUL in 1m 11s
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\verify-process-death.ps1
→ exit 0 in 39.2s; OK (1 test)
.\gradlew.bat :app:lintDebug --console=plain
→ BUILD SUCCESSFUL in 35s
.\gradlew.bat :app:assembleRelease --console=plain
→ BUILD SUCCESSFUL in 1m 52s; sin --rerun-tasks
```

La ejecución posterior de G-68 falló una vez porque `MessageDigest` estaba fuera de
`:core:crypto`. Tras confinar SHA-256 en el módulo autorizado, el resultado final fue:

```text
.\gradlew.bat :core:common:testDebugUnitTest :data:sync:testDebugUnitTest :data:sync:detekt --console=plain
→ BUILD SUCCESSFUL in 2m 05s; 33/33 pruebas de :core:common
.\gradlew.bat :core:crypto:testDebugUnitTest :app:detektDebug --console=plain
→ BUILD SUCCESSFUL in 44s
.\gradlew.bat :app:lintDebug --console=plain
→ BUILD SUCCESSFUL in 59s
.\gradlew.bat :app:assembleRelease --console=plain
→ BUILD SUCCESSFUL in 1m 43s; sin --rerun-tasks
```
