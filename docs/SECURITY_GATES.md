# docs/SECURITY_GATES.md — Puertas de seguridad obligatorias

Comprobaciones que **deben pasar** antes de cerrar una entrega. Una puerta no se «salta»:
si no se puede comprobar, se declara como no verificada en
`PROJECT_STATE.md` y la fase queda abierta.

---

## G0 — Puertas permanentes (en toda fase, sin excepción)

| # | Comprobación | Cómo |
|---|---|---|
| G0-1 | No hay secretos en el diff ni en el árbol | revisión del diff + `git ls-files` filtrando patrones de secreto |
| G0-2 | No hay plaintext persistido en ninguna ruta nueva | revisión de la capa de datos + prueba de señuelos cuando aplique |
| G0-3 | No hay `android.util.Log` ni `println` fuera del logger seguro | prueba de higiene |
| G0-4 | No hay criptografía propia ni APIs prohibidas (ECB, hash como cifrado, `Random` en rutas criptográficas) | prueba de higiene + revisión |
| G0-5 | Ninguna regla de Firestore abierta, ni comentada | lectura completa de `firestore.rules` |
| G0-6 | Los parámetros de Argon2id coinciden con el perfil cerrado de su versión | `KdfPolicy` + rechazo de cada valor distinto |
| G0-7 | Ninguna prueba fue borrada, ignorada o debilitada para pasar | revisión del diff de pruebas |
| G0-8 | Toda dependencia nueva está registrada en `docs/DEPENDENCY_POLICY.md` | revisión del catálogo de versiones |
| G0-9 | La documentación describe lo implementado, sin funciones fantasma | revisión cruzada docs ↔ código |
| G0-10 | `PROJECT_STATE.md` refleja resultados reales, con lo no ejecutado declarado | lectura del documento |

---

## G1 — Fase 1 (bootstrap)

| # | Comprobación |
|---|---|
| G1-1 | `allowBackup="false"` y reglas de extracción de datos excluyendo todo |
| G1-2 | NSC de main/release sin tráfico en claro; excepción debug limitada a `10.0.2.2` y `localhost` |
| G1-3 | Solo la actividad lanzadora está exportada |
| G1-4 | El grafo coincide con ADR-019/033: app sin crypto; sync conoce modelo y capacidad opaca; local/remoto sin dominio y con escrituras `Ciphertext`; primitivas confinadas a crypto |
| G1-5 | Sin `google-services.json` real; existe el `.example` |
| G1-6 | `FLAG_SECURE` aplicado en la actividad |
| G1-7 | Detekt y Lint sin hallazgos nuevos |

## G2 — Fase 2 (núcleo criptográfico)

| # | Comprobación |
|---|---|
| G2-1 | Los casos criptográficos de `docs/TEST_STRATEGY.md` para Fase 2 pasan en la JVM |
| G2-2 | Nombre, memoria, iteraciones, paralelismo, salida y salts exactos se validan antes de reservar memoria |
| G2-3 | El downgrade de parámetros rompe el unwrap (AAD) |
| G2-4 | La AAD se reconstruye byte a byte al descifrar |
| G2-5 | Las cadenas de contexto HKDF son únicas y ninguna es prefijo de otra |
| G2-6 | Ningún mensaje de error ni excepción contiene material sensible |
| G2-7 | Los buffers sensibles quedan a cero (prueba, no confianza) |
| G2-8 | Ningún fixture contiene secretos reales; los vectores son públicos y citados |
| G2-9 | El benchmark de Argon2id está **medido** o declarado como no ejecutado |

## G3 — Fase 3 (persistencia)

| # | Comprobación |
|---|---|
| G3-1 | La prueba de señuelos no encuentra nada en `.db`, `-wal`, `-shm`, caché ni archivos |
| G3-2 | El esquema no tiene columnas de título, cuerpo, etiquetas ni texto buscable |
| G3-3 | Sin `fallbackToDestructiveMigration`; migraciones explícitas y `exportSchema = true` |
| G3-4 | DataStore solo contiene preferencias no sensibles |
| G3-5 | Al bloquear, el estado y los buffers quedan sin contenido descifrado |
| G3-6 | El esquema v1 contiene staging de conflictos e índices; el ciphertext local permanece intacto con sesión bloqueada |
| G3-7 | `assembleRelease` pasa con el endurecimiento disponible hasta la fase |
| G3-8 | `ownerUid` aísla la bóveda local: una sesión B no abre ni sincroniza la fila de A |

## G4 — Fase 4 (auth y reglas)

| # | Comprobación |
|---|---|
| G4-1 | La matriz de reglas G-19…G-28 y G-56…G-60 pasa |
| G4-2 | Cada caso negativo **falla** si se relaja su regla (verificación de que la prueba sirve) |
| G4-3 | Denegación por defecto en `/{document=**}` |
| G4-4 | La propiedad viene de la ruta (`uid`), no de un campo del documento |
| G4-5 | El emulador se usa solo en debug; release nunca llama a `useEmulator` |
| G4-6 | App Check usa proveedor debug en `debug` y Play Integrity en `release`; enforcement pendiente de tokens válidos del APK sideload firmado |

## G5 — Fase 5 (sincronización)

| # | Comprobación |
|---|---|
| G5-1 | Ninguna escritura remota acepta dominio ni bytes arbitrarios: solo `Ciphertext` opaco por la ruta estructural única |
| G5-2 | Los datos de entrada de WorkManager no contienen secretos |
| G5-3 | El conflicto no sobrescribe en silencio |
| G5-4 | Un fallo permanente no genera un bucle de reintentos |
| G5-5 | El límite de tamaño del ciphertext se comprueba en el cliente antes de subir |
| G5-6 | Adopción de bóveda remota cubre cero, una y múltiples bóvedas sin selección automática |
| G5-7 | Marcas de agua de revisión y de ambos epochs remotos rechazan retrocesos visibles |
| G5-8 | `lastPullAt` nunca avanza más allá del reloj local por un `updatedAt` remoto |
| G5-9 | El staging guarda el DTO remoto completo y permite resolver el conflicto sin otra lectura de red |

## G6 — Fase 6 (biometría)

| # | Comprobación |
|---|---|
| G6-1 | Clave no exportable, `userAuthenticationRequired`, `invalidatedByBiometricEnrollment`, `unlockedDeviceRequired` |
| G6-2 | StrongBox intentado; el resultado real (TEE en este dispositivo) queda registrado |
| G6-3 | `BiometricPrompt` usa `CryptoObject` (garantía criptográfica, no solo de interfaz) |
| G6-4 | La invalidación borra ambos blobs, IV, epoch, registro activo y alias propio; exige la contraseña maestra |
| G6-5 | El blob biométrico no se sincroniza ni entra en el respaldo |
| G6-6 | La contraseña maestra **no** se almacena en ningún caso |
| G6-7 | Activación y desbloqueo exigen autenticación por operación `BIOMETRIC_STRONG`, sin `DEVICE_CREDENTIAL` ni duración de validez |
| G6-8 | El Keystore envuelve una `BiometricKEK`; la VDEK usa keyset cifrado de Tink y nunca se serializa en claro |
| G6-9 | La reconfiguración de dos fases resiste fallos en cada frontera y limpia solo aliases biométricos huérfanos propios |

## G7 — Fase 7 (interfaz)

| # | Comprobación |
|---|---|
| G7-1 | Ninguna ventana sensible carece de `FLAG_SECURE`; actividad y cada `SecureDialog` se prueban |
| G7-2 | Sin plaintext en `SavedStateHandle`, `Bundle`, `rememberSaveable` ni previews |
| G7-3 | Bloqueo automático con valor seguro por defecto |
| G7-4 | Reautenticación para exportar, cambiar contraseña, regenerar frase, desactivar biometría y eliminar bóveda |
| G7-5 | Portapapeles marcado como sensible y con borrado automático; nada se copia solo |
| G7-6 | Sin contenido sensible en notificaciones |
| G7-7 | Los textos declaran la irrecuperabilidad y no insinúan que alguien pueda rescatar la bóveda |

## G8 — Fase 8 (respaldo)

| # | Comprobación |
|---|---|
| G8-1 | El archivo exportado no contiene señuelos |
| G8-2 | El parser rechaza todas las entradas inválidas de `BACKUP_FORMAT.md` §6 |
| G8-3 | Fuzzing con semilla fija sin OOM, bucles ni aceptación parcial |
| G8-4 | Restauración con contraseña **y** con frase |
| G8-5 | El respaldo no incluye el blob biométrico |
| G8-6 | Los parámetros del archivo se validan contra el perfil cerrado y longitudes exactas antes de derivar |
| G8-7 | Exportación rechaza antes de cargar blobs o escribir más de 5 000 ítems o un respaldo estimado sobre 8 MiB; importación limita complejidad JSON antes del DOM |
| G8-8 | Ambos envoltorios se verifican contra la misma VDEK antes de persistir la restauración |
| G8-9 | Un respaldo anterior a las marcas locales se restaura reemitiendo epochs nuevos |
| G8-10 | Publicar valida snapshot; no sobrescribe ítems remotos existentes; un avance bloquea y una creación parcial se reanuda idempotentemente |

## G9 — Fase 9 (entrega)

| # | Comprobación |
|---|---|
| G9-1 | Release con R8 activo, sin `debuggable`, sin logging sensible |
| G9-2 | Detección de secretos sobre el árbol **y el historial**, sin hallazgos |
| G9-3 | Permisos, componentes exportados, backups y manifiesto/NSC **fusionados de release** revisados uno por uno |
| G9-4 | `dependency verification` y `locking` activados o su imposibilidad justificada |
| G9-5 | Vulnerabilidades conocidas de dependencias verificadas (D-01) |
| G9-6 | Los 15 criterios de aceptación verificados con evidencia |
| G9-7 | Revisión independiente resumida en `docs/VERIFICATION_STATUS.md` |
| G9-8 | **Ningún hallazgo crítico o alto abierto** |
| G9-9 | Ninguna afirmación de «seguridad absoluta» en el repositorio |

---

## Procedimiento ante un hallazgo

| Severidad | Acción |
|---|---|
| **Crítico** | se detiene el avance; se corrige antes de cualquier otro trabajo; se añade la prueba que lo habría detectado |
| **Alto** | se corrige antes de cerrar la fase; se añade prueba |
| **Medio** | se corrige en la fase o se registra como riesgo abierto con fecha objetivo |
| **Bajo / informativo** | se registra; se corrige cuando toque el área |

Un hallazgo crítico o alto **nunca** se cierra sin evidencia de la corrección (prueba nueva, salida
de comando o cita del código corregido).
