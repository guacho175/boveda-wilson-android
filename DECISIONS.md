# DECISIONS.md — Registro de decisiones (ADR)

Decisiones arquitectónicas y criptográficas vigentes de Bóveda Wilson. Una decisión no se
borra: se marca como sustituida y se añade la nueva. Formato definido en
`este documento`, junto con la estrategia de migración de cada ADR.

Índice:

| ADR | Título | Estado |
|---|---|---|
| ADR-001 | Alcance: solo aplicación Android nativa | aceptada |
| ADR-002 | Cadena de herramientas y niveles de SDK | aceptada; `minSdk` modificado por ADR-027 |
| ADR-003 | Estructura modular y patrón de presentación | aceptada |
| ADR-004 | AEAD: Google Tink con AES256-GCM | aceptada |
| ADR-005 | Jerarquía de claves por envelope encryption | modificada por ADR-028 |
| ADR-006 | Argon2id mediante BouncyCastle tras una interfaz propia | modificada por ADR-021 |
| ADR-007 | HKDF-SHA-256 con separación de dominio | aceptada |
| ADR-008 | Frase de 24 palabras como codificación BIP-39 de entropía | modificada por ADR-022 |
| ADR-009 | AAD versionada, sin el uid de Firebase | modificada por ADR-020/021/022/028 |
| ADR-010 | Cifrado de notas directamente con la VDEK | aceptada |
| ADR-011 | La frase de recuperación no se puede volver a ver; se regenera | aceptada; revocación aclarada por ADR-020 |
| ADR-012 | Biometría: el Keystore envuelve la VDEK solo localmente | modificada por ADR-028 |
| ADR-013 | Room almacena solo ciphertext, sin SQLCipher en el MVP | aceptada |
| ADR-014 | Modelo de Firestore y reglas cerradas desde el primer commit | modificada por ADR-023 |
| ADR-015 | Sincronización local-first con revisiones y tombstones | modificada por ADR-025 |
| ADR-016 | Formato de respaldo cifrado y versionado | modificada por ADR-026/051 |
| ADR-017 | Firebase solo en Emulator Suite durante el desarrollo | aceptada |
| ADR-018 | Sin telemetría; logger seguro propio | aceptada |
| ADR-019 | La sesión no expone el material de clave; grafo de módulos cerrado | modificada por ADR-033/042 |
| ADR-020 | `wrapEpoch`: frescura del envoltorio y revocación real | modificada por ADR-030 |
| ADR-021 | Rango cerrado de parámetros del KDF y AAD normativa | modificada por ADR-031 |
| ADR-022 | Codificación BIP-39 fijada: lista, normalización y longitud | aceptada |
| ADR-023 | Correcciones de las Security Rules y contrato del tombstone | aceptada |
| ADR-024 | Cadena de herramientas completa y plugins de convención | aceptada |
| ADR-025 | Adopción de bóveda existente y conflictos con sesión bloqueada | aceptada |
| ADR-026 | Límites coherentes del respaldo y verificación de ambos envoltorios | aceptada |
| ADR-027 | `minSdk = 33` | aceptada (modifica ADR-002) |
| ADR-028 | Biometría: doble envoltorio y autenticación por operación | modificada por ADR-042 |
| ADR-029 | Protecciones de ventana y de red por variante de build | aceptada |
| ADR-030 | Epoch independiente por camino de acceso | aceptada (modifica ADR-020) |
| ADR-031 | Perfil Argon2id v1 único y acotado | aceptada (modifica ADR-021) |
| ADR-032 | Reconfiguración biométrica crash-safe entre Keystore y Room | aceptada (complementa ADR-028) |
| ADR-033 | Capacidad criptográfica opaca y ruta de ciphertext auditable | aceptada (modifica ADR-019) |
| ADR-034 | Concreciones de la Fase 1: tipo de plugin por módulo y motor de pruebas | aceptada (complementa ADR-024) |
| ADR-035 | Alcance de G-68 ajustado para permitir `MessageDigest` dentro de `:core:crypto` | aceptada |
| ADR-036 | Fase 4: JDK 21 local solo para el Emulator Suite, `node:test` como motor y monotonía de versión también en ítems | aceptada |
| ADR-037 | Fase 4: `:data:remote` real, App Check por variante y verificación desde el dispositivo físico | modificada por ADR-048 |
| ADR-038 | Directorios de build fuera del árbol sincronizado por OneDrive | aceptada |
| ADR-039 | Hilt fijado a 2.57.2 por compatibilidad con AGP 8.13.1 | aceptada |
| ADR-040 | Fuente remota sin red cuando no hay proyecto Firebase configurado | aceptada |
| ADR-041 | Proyecto Firebase real: región de Firestore y servicios desactivados | aceptada |
| ADR-042 | Frontera biométrica Android fuera del núcleo criptográfico | aceptada |
| ADR-043 | Creación atómica condicionada a verificar la frase de recuperación | aceptada |
| ADR-044 | Entrada de contraseñas maestras con búfer limpiable | aceptada |
| ADR-045 | Respaldo ciphertext-only con restauración local transaccional | modificada por ADR-051 |
| ADR-046 | Parser de respaldo rechaza claves JSON duplicadas y UTF-8 inválido | aceptada |
| ADR-047 | Publicación de respaldo con CAS y autorización efímera verificable | modificada por ADR-051 |
| ADR-048 | Release fail-closed, firma externa y Firebase para distribución por Drive | aceptada |
| ADR-049 | Respaldo acotado a 8 MiB con preflight antes de cargar blobs | aceptada |
| ADR-050 | Nivel hardware verificado y bloqueo transitorio sin degradación | aceptada |
| ADR-051 | Respaldo v2 con manifiesto Tink y publicación reanudable | aceptada |

Los ADR-019 a ADR-029 surgen de la revisión consolidada de la Etapa 1 y ADR-030 a ADR-033 de su
revisión de cierre registrada en el historial privado. ADR-034 cierra las últimas
ambigüedades del plan antes de escribir código.

---

## ADR-001 — Alcance: solo aplicación Android nativa

- **Fecha:** 2026-07-29
- **Estado:** aceptada
- **Contexto:** los requisitos originales mencionaban la posibilidad de otros clientes. El
  propietario fijó como definitivo que el MVP es exclusivamente Android nativo.
- **Decisión:** se implementa únicamente una aplicación Android nativa. No se crea interfaz
  web, panel, extensión de navegador, aplicación de escritorio, cliente multiplataforma ni
  backend con acceso a plaintext. La arquitectura documenta los contratos criptográficos
  (formatos versionados, AAD, jerarquía de claves) para que otros clientes sean evaluables en
  el futuro, sin implementarlos y sin compartir claves en texto plano.
- **Alternativas consideradas:** núcleo criptográfico multiplataforma (Kotlin Multiplatform)
  desde el inicio — rechazado por ampliar la superficie de ataque y el coste de verificación
  sin beneficio para el MVP.
- **Consecuencias:** menor superficie de ataque y menos código que auditar. Un cliente futuro
  tendrá que reimplementar el contrato criptográfico a partir de la documentación.
- **Estrategia de migración:** el contrato criptográfico versionado permite escribir otro
  cliente sin cambiar el formato; requeriría un ADR nuevo y una revisión de seguridad propia.

---

## ADR-002 — Cadena de herramientas y niveles de SDK

- **Fecha:** 2026-07-29
- **Estado:** aceptada; `minSdk` modificado por ADR-027
- **Contexto:** en la máquina de desarrollo solo hay **JDK 17** (Temurin 17.0.19 y el JBR de
  Android Studio, también 17). El SDK tiene las plataformas 34, 35 y 36. El dispositivo de
  pruebas conectado por USB es Android 13 (**API 33**), arm64-v8a. Las líneas más recientes de
  AGP (9.x) no se pueden garantizar con JDK 17 en este entorno.
- **Decisión:** Kotlin + Jetpack Compose + Material 3, Gradle Kotlin DSL y Version Catalog.
  Wrapper de Gradle 8.14.3, AGP de la línea 8.13.x, Kotlin 2.2.x con KSP a juego,
  `compileSdk = 36`, `targetSdk = 36`, `minSdk = 29`, `jvmTarget = 17`, Hilt para inyección de
  dependencias, Coroutines y Flow para asincronía, `namespace` base `cl.bovedawilson.app`.
  Las versiones exactas viven en `gradle/libs.versions.toml` y se registran con su fecha y
  fuente en `docs/DEPENDENCY_POLICY.md`.
- **Alternativas consideradas:** AGP 9.x + Gradle 9.x + Kotlin 2.4.x (más reciente, pero sin
  garantía de funcionar con JDK 17 y con cambios de ruptura que no aportan al MVP); `minSdk`
  más bajo (aumentaría el código de compatibilidad de Keystore y biometría sin usuarios que lo
  requieran).
- **Consecuencias:** se compila y se prueba en el dispositivo real disponible. `minSdk 29`
  permite `setUnlockedDeviceRequired`, StrongBox cuando exista y BiometricPrompt moderno sin
  ramas de compatibilidad antiguas. Se renuncia a las novedades de AGP 9.x.
- **Estrategia de migración:** al instalar un JDK 21 se puede evaluar AGP 9.x; el Version
  Catalog concentra el cambio y este ADR se sustituiría.

**Vigencia:** ADR-027 sustituye únicamente `minSdk = 29` por `minSdk = 33`. El resto de esta
decisión continúa vigente.

---

## ADR-003 — Estructura modular y patrón de presentación

- **Fecha:** 2026-07-29
- **Estado:** aceptada
- **Contexto:** hace falta una frontera **verificable por el compilador** que impida que las
  capas de persistencia y red vean contenido descifrado. Una convención documentada no basta.
- **Decisión:** módulos Gradle `:app`, `:core:model`, `:core:common`, `:core:crypto`,
  `:data:local`, `:data:remote`, `:data:sync`. `:data:local` y `:data:remote` **no dependen de
  `:core:model`**, por lo que no pueden nombrar un modelo descifrado. `:core:crypto` no depende
  de Room, Firebase ni UI. Presentación con MVVM de estado unidireccional: un `UiState`
  inmutable por pantalla expuesto como `StateFlow`, eventos de un disparo por canal, y ningún
  campo sensible en estado persistible.
- **Alternativas consideradas:** módulo único con separación por paquetes (no da garantía del
  compilador); MVI con reductores formales (más ceremonia sin beneficio de seguridad
  adicional); un módulo por pantalla (coste de mantenimiento desproporcionado para el MVP).
- **Consecuencias:** un intento de filtrar plaintext a la capa de datos **no compila**. Hay que
  escribir mapeadores explícitos entre dominio, entidades cifradas y DTO. Un único objeto de
  estado hace verificable el borrado al bloquear.
- **Estrategia de migración:** las pantallas pueden extraerse a módulos `:feature:*` sin tocar
  las fronteras de datos.

---

## ADR-004 — AEAD: Google Tink con AES256-GCM

- **Fecha:** 2026-07-29
- **Estado:** aceptada
- **Contexto:** se necesita cifrado autenticado con datos asociados, sin implementar
  primitivas propias y sin gestionar nonces a mano.
- **Decisión:** todo el cifrado de datos usa la primitiva AEAD de Google Tink con claves
  AES256-GCM, el tipo recomendado por la documentación oficial de Tink para datos generales.
  Tink genera y gestiona el nonce en cada operación. Está prohibido implementar AES-GCM a mano,
  usar AES-ECB, cifrado determinista para contenido, hashes como cifrado o Base64 como
  protección.
- **Alternativas consideradas:** XChaCha20-Poly1305 (nonce de 192 bits, margen mayor, pero sin
  aceleración de hardware en ARM y no es la recomendación por defecto de Tink);
  AES-GCM-SIV (resistente a reutilización de nonce, pero su rendimiento en Android depende del
  proveedor disponible); `javax.crypto` directo (obligaría a gestionar nonces manualmente).
- **Consecuencias:** aceleración por hardware en el dispositivo objetivo y una superficie de
  error pequeña. Con nonce aleatorio de 96 bits, el límite práctico de seguridad está en el
  orden de 2^32 cifrados por clave; una bóveda personal queda muy por debajo, y el límite se
  documenta en `CRYPTOGRAPHY.md` junto a la ruta de rotación.
- **Estrategia de migración:** `cryptoVersion` en todo formato permite introducir una primitiva
  nueva y descifrar lo antiguo durante la migración.

---

## ADR-005 — Jerarquía de claves por envelope encryption

- **Fecha:** 2026-07-29
- **Estado:** modificada por ADR-028
- **Contexto:** la contraseña maestra debe poder cambiarse sin recifrar las notas, y deben
  existir dos caminos independientes para abrir la bóveda (contraseña y recuperación), más un
  atajo local opcional (biometría).
- **Decisión:** una única **VDEK** (keyset de Tink AES256-GCM generada en el dispositivo) cifra
  el contenido. La VDEK se envuelve por separado con cada clave de acceso:
  `passwordWrappedVdek` (PasswordKEK derivada de la contraseña maestra),
  `recoveryWrappedVdek` (RecoveryKEK derivada de la entropía de las 24 palabras) y
  `biometricWrappedVdek` (BiometricKEK protegida por Android Keystore, **solo local, nunca se sincroniza**). El
  envoltorio usa el formato de keyset cifrado de Tink, no un mecanismo propio. La VDEK nunca se
  persiste sin envolver ni sale del dispositivo.
- **Alternativas consideradas:** derivar la clave de contenido directamente de la contraseña
  (cambiar la contraseña obligaría a recifrar todo y la recuperación sería imposible); envolver
  con una clave del Keystore como raíz única (la pérdida del dispositivo destruiría la bóveda).
- **Consecuencias:** cambiar la contraseña maestra solo reescribe `passwordWrappedVdek`. Cada
  envoltorio es un camino de acceso: la seguridad global es la del envoltorio más débil, por lo
  que todos deben derivarse de material de alta entropía o estar anclados al hardware.
- **Estrategia de migración:** rotar la VDEK implica recifrar los ítems; cada ítem lleva su
  `cryptoVersion` para tolerar estados mixtos durante la migración.

**Vigencia:** ADR-028 conserva los tres caminos, pero sustituye el cifrado biométrico directo por
doble envoltorio.

---

## ADR-006 — Argon2id mediante BouncyCastle tras una interfaz propia

- **Fecha:** 2026-07-29
- **Estado:** modificada por ADR-021
- **Contexto:** hay que derivar la PasswordKEK de una contraseña humana con un KDF resistente a
  hardware especializado. En Android hay dos caminos: una implementación nativa por JNI
  (`argon2kt`, envoltorio de la implementación de referencia) o una implementación pura de JVM
  (`Argon2BytesGenerator` de BouncyCastle).
- **Decisión:** Argon2id mediante `Argon2BytesGenerator` de BouncyCastle
  (`org.bouncycastle:bcprov-jdk18on`), detrás de una interfaz `PasswordKdf` propia. Parámetros
  iniciales: **m = 64 MiB, t = 3, p = 4, salida de 32 bytes, salt aleatorio de 16 bytes por
  bóveda**. Suelo duro verificado **en código**: se rechazan parámetros por debajo de
  m = 19 MiB, t = 2, p = 1 (mínimo recomendado por OWASP). Los parámetros se persisten por
  bóveda para permitir migración, y quedan autenticados en la AAD del envoltorio (ADR-009).
  La calibración se hace midiendo en el dispositivo real y el número medido se registra en
  `CRYPTOGRAPHY.md`; no se publican cifras no medidas.
- **Alternativas consideradas:** `com.lambdapioneer.argon2kt:argon2kt` 1.6.0 — más rápido al ser
  nativo, pero exige JNI y bibliotecas por ABI, lo que impediría probar el núcleo criptográfico
  en la JVM sin dispositivo y añadiría una dependencia binaria al camino crítico. PBKDF2
  (disponible en la plataforma, pero mucho más débil frente a GPU/ASIC). scrypt (aceptable, sin
  la resistencia a ataques de canal lateral de Argon2id).
- **Consecuencias:** el núcleo criptográfico completo es probable en la JVM, sin dispositivo ni
  JNI, lo que hace verificables todas las propiedades criptográficas en `./gradlew test`. Se
  acepta que la derivación sea más lenta que una implementación nativa; se mide y, si la latencia
  resulta inaceptable en el dispositivo, se cambia la implementación **sin tocar el formato**
  gracias a la interfaz.
- **Estrategia de migración:** `PasswordKdf` permite añadir un backend nativo; `kdfName` y los
  parámetros persistidos permiten recalibrar y reenvolver sin recifrar las notas.

---

## ADR-007 — HKDF-SHA-256 con separación de dominio

- **Fecha:** 2026-07-29
- **Estado:** aceptada
- **Contexto:** hay que derivar claves distintas para propósitos distintos a partir de material
  con entropía suficiente, sin que dos propósitos compartan nunca la misma clave.
- **Decisión:** HKDF-SHA-256 con `salt` explícito e `info` de contexto propio del proyecto.
  Cadenas de contexto exactas, únicas y sin prefijos ambiguos, definidas en `CRYPTOGRAPHY.md`:
  `BovedaWilson/v1/password-kek` y `BovedaWilson/v1/recovery-kek`. La salida de Argon2id pasa
  por HKDF antes de usarse como PasswordKEK, para separar el dominio y permitir derivar
  subclaves futuras sin reutilizar material.
- **Alternativas consideradas:** usar la salida de Argon2id directamente como clave (funciona,
  pero no deja margen para subclaves ni separación de dominio); derivar por concatenación y hash
  propio (criptografía casera, prohibida).
- **Consecuencias:** cada clave tiene un propósito único y demostrable. Un cambio en una cadena
  de contexto invalida el material derivado, por lo que las cadenas quedan congeladas por
  versión y cubiertas por pruebas.
- **Estrategia de migración:** una versión nueva usa `BovedaWilson/v2/...`; `cryptoVersion`
  indica qué contexto aplicar.

---

## ADR-008 — Frase de 24 palabras como codificación BIP-39 de entropía

- **Fecha:** 2026-07-29
- **Estado:** modificada por ADR-022
- **Contexto:** el propietario quiere una experiencia parecida a la frase de respaldo de una
  billetera, pero sin improvisar criptografía y sin que las palabras funcionen como una
  contraseña humana de baja entropía.
- **Decisión:** se generan **256 bits de entropía con `SecureRandom`** en el dispositivo y se
  representan como 24 palabras usando la codificación con checksum de BIP-39 mediante
  `cash.z.ecc.android:kotlin-bip39`. La frase se trata **exclusivamente como una codificación
  legible de la entropía**: no se usa la derivación de semilla PBKDF2 del estándar, no hay
  passphrase de BIP-39 y no hay derivación de rutas. Al recuperar, se valida el checksum, se
  reconstruye la entropía y se deriva la RecoveryKEK con HKDF (ADR-007). Nunca se generan ni se
  muestran frases reales durante el desarrollo; las pruebas usan vectores públicos del estándar
  o datos marcados como ficticios.
- **Alternativas consideradas:** `io.github.novacrypto:BIP39` (menos actividad de
  mantenimiento); `bitcoinj` (arrastra un cliente Bitcoin completo al camino crítico);
  implementar la lista de palabras y el checksum a mano (rechazado: se prohíbe la criptografía
  propia, incluso cuando el checksum es «solo» SHA-256).
- **Consecuencias:** la frase representa entropía real de 256 bits y el checksum detecta errores
  de transcripción. La codificación es un formato conocido, lo que ayuda al usuario a
  entenderlo y a copiarlo. Queda documentado que **no** es una billetera y que no sirve en
  software de criptomonedas.
- **Estrategia de migración:** `cryptoVersion` permite otra codificación; la entropía es el
  valor real y la codificación es intercambiable.

---

## ADR-009 — AAD versionada, sin el uid de Firebase

- **Fecha:** 2026-07-29
- **Estado:** modificada por ADR-020, ADR-021, ADR-022 y ADR-028
- **Contexto:** cada ciphertext debe estar ligado a su contexto para que no se pueda mover un
  registro de un ítem a otro, ni reutilizar un envoltorio con parámetros distintos. La AAD se
  autentica pero no se cifra, así que no puede contener nada sensible.
- **Decisión:** AAD canónica en UTF-8, versionada con el prefijo `bw1`:
  - ítems: `bw1|item|<vaultId>|<itemId>|<schemaVersion>|<cryptoVersion>`;
  - envoltorios de la VDEK: `bw1|vdek-wrap|<vaultId>|<password|recovery|biometric>|<cryptoVersion>`
    más la serialización canónica de los parámetros del KDF y su salt.
  Incluir los parámetros del KDF en la AAD hace que un intento de **downgrade** de los
  parámetros en el documento remoto rompa el desenvolvido en lugar de debilitarlo.
  La AAD **no incluye el uid de Firebase**: el identificador de anclaje es el `vaultId`
  aleatorio generado en el dispositivo.
- **Alternativas consideradas:** incluir el uid de Firebase (lo sugería el texto original de los
  requisitos) — rechazado porque ataría el ciphertext a la cuenta: cambiar de proveedor de
  acceso, migrar de cuenta o restaurar un respaldo en otra cuenta dejaría toda la bóveda
  indescifrable. El propio requisito admite «un identificador apropiado», y `vaultId` lo es.
  No usar AAD (perdería la ligadura de contexto).
- **Consecuencias:** un ciphertext solo se descifra en su ítem, su bóveda y su versión. El
  respaldo se puede restaurar en otra cuenta de Firebase, que es un requisito del propietario.
  La AAD queda congelada por versión y cubierta por pruebas que reconstruyen la AAD byte a byte.
- **Estrategia de migración:** un prefijo `bw2` define una AAD nueva; `cryptoVersion` selecciona
  la construcción al descifrar.

---

## ADR-010 — Cifrado de notas directamente con la VDEK

- **Fecha:** 2026-07-29
- **Estado:** aceptada
- **Contexto:** los requisitos piden evaluar dos opciones: cifrar cada nota con la VDEK, o
  generar una DEK por nota y envolverla con la VDEK.
- **Decisión:** cada ítem se cifra **directamente con la VDEK**, con AAD por ítem (ADR-009) y
  nonce aleatorio gestionado por Tink. Cada ítem persiste su `cryptoVersion`.
- **Alternativas consideradas:** DEK por nota — permitiría compartir un ítem concreto y rotar
  claves por ítem, pero añade un envoltorio por registro, más estados de fallo, más código que
  auditar y no aporta nada al MVP, que no contempla compartir. Se descarta por simplicidad
  verificable.
- **Consecuencias:** menos piezas y menos modos de fallo. Rotar la VDEK exige recifrar todos los
  ítems, operación viable en una bóveda personal y tolerada por el `cryptoVersion` por ítem. El
  número de cifrados con una misma clave queda muy por debajo del límite del nonce aleatorio
  (ADR-004).
- **Estrategia de migración:** se puede introducir la DEK por nota como `cryptoVersion` nueva sin
  tocar los ítems existentes.

---

## ADR-011 — La frase de recuperación no se puede volver a ver; se regenera

- **Fecha:** 2026-07-29
- **Estado:** aceptada; semántica de revocación aclarada por ADR-020
- **Contexto:** los requisitos piden evaluar si se permite volver a mostrar la frase de 24
  palabras tras desbloquear, y proponer una alternativa si no es recomendable. Para
  re-mostrarla habría que persistir la entropía (aunque fuera envuelta con la VDEK), lo que crea
  una ruta de exfiltración permanente si alguien accede a la bóveda desbloqueada. Los requisitos
  también prohíben explícitamente almacenar la entropía original.
- **Decisión:** la entropía **nunca se persiste**. Se genera, se muestra una única vez durante la
  configuración, se verifican varias palabras elegidas al azar y se borra de memoria. **No
  existe la función de volver a ver la frase.** En su lugar existe **«Regenerar frase de
  recuperación»**: se genera entropía nueva, se deriva una RecoveryKEK nueva, se reenvuelve la
  misma VDEK y se reemplaza `recoveryWrappedVdek` de forma atómica; la frase anterior deja de
  abrir el envoltorio almacenado actualmente. Una copia antigua no queda revocada sin rotación de
  VDEK (ADR-020). La operación exige bóveda desbloqueada, contraseña maestra y una
  advertencia explícita. Decisión confirmada por el propietario el 2026-07-29.
- **Alternativas consideradas:** persistir `entropyWrappedByVdek` para poder re-mostrarla
  (rechazado: contradice el requisito de no almacenar la entropía y convierte cualquier acceso a
  la bóveda abierta en acceso permanente); ofrecerlo como opción avanzada desactivada por
  defecto (rechazado: el usuario podría activarla sin comprender la consecuencia).
- **Consecuencias:** ni un atacante con la bóveda abierta ni el propio dispositivo pueden
  revelar la frase después de la configuración. El usuario que pierda el papel debe regenerar,
  lo que exige volver a anotar 24 palabras. La interfaz debe explicar esto antes de continuar.
- **Estrategia de migración:** ninguna prevista; revertirla exigiría un ADR nuevo y una revisión
  de seguridad, porque cambia el modelo de amenazas.

---

## ADR-012 — Biometría: el Keystore envuelve la VDEK solo localmente

- **Fecha:** 2026-07-29
- **Estado:** modificada por ADR-028
- **Contexto:** la biometría debe ser una comodidad de desbloqueo, no una raíz de recuperación, y
  no puede almacenar la contraseña maestra. El dispositivo de pruebas tiene huella y Keystore
  respaldado por TEE (`hardware_keystore=100`), pero **no expone StrongBox**.
- **Decisión:** una clave AES/GCM **no exportable** en Android Keystore, creada con
  `setUserAuthenticationRequired(true)`, `setInvalidatedByBiometricEnrollment(true)` y
  `setUnlockedDeviceRequired(true)`, usando autenticación biométrica fuerte cuando esté
  disponible. Se intenta StrongBox y, si el dispositivo no lo soporta, se cae a TEE
  registrándolo. Esa clave cifra el keyset de la VDEK en un blob **exclusivamente local**
  (`biometricWrappedVdek` + IV), que **nunca se sincroniza** ni se incluye en el respaldo. El
  desbloqueo usa `BiometricPrompt` con `CryptoObject`. Ante
  `KeyPermanentlyInvalidatedException` o cualquier invalidación equivalente: se borra el blob
  local, se cierra la sesión criptográfica y se exige la contraseña maestra, ofreciendo
  reconfigurar la biometría después.
- **Alternativas consideradas:** guardar la contraseña maestra protegida por el Keystore
  (prohibido por los requisitos y peor: expone la credencial reutilizable); usar la clave del
  Keystore como envoltorio sincronizado (imposible: la clave no sale del dispositivo, y
  sincronizarla sería un fallo grave); autenticación biométrica solo como comprobación de UI sin
  `CryptoObject` (no aporta garantía criptográfica).
- **Consecuencias:** la biometría no permite recuperar nada si el dispositivo se pierde, y no
  sustituye a la contraseña maestra ni a la frase. Un cambio de huellas o del bloqueo de pantalla
  obliga a usar la contraseña maestra. El blob local añade una superficie de ataque limitada al
  dispositivo y anclada al hardware.
- **Estrategia de migración:** el blob local es desechable: se puede borrar y recrear en
  cualquier momento sin afectar a la bóveda.

**Vigencia:** ADR-028 sustituye el cifrado directo del keyset por el doble envoltorio de una
`BiometricKEK`, exige autenticación por operación y prohíbe degradar a credencial del dispositivo.

---

## ADR-013 — Room almacena solo ciphertext, sin SQLCipher en el MVP

- **Fecha:** 2026-07-29
- **Estado:** aceptada; tombstones aclarados por ADR-023
- **Contexto:** el almacenamiento local no puede contener plaintext. Existen dos capas posibles:
  cifrado a nivel de columna en la aplicación, y cifrado de toda la base de datos con SQLCipher.
- **Decisión:** Room persiste **exclusivamente** columnas de ciphertext (`BLOB`) y metadatos no
  sensibles: identificadores aleatorios, versiones, revisiones, marcas de tiempo y banderas de
  sincronización. No existe ninguna columna de título, cuerpo, etiqueta ni texto buscable, ni
  índice de búsqueda persistente. No se usa SQLCipher en el MVP. Migraciones explícitas y
  probadas; `fallbackToDestructiveMigration` prohibido; el esquema se versiona en el repositorio.
- **Alternativas consideradas:** SQLCipher (`net.zetetic`) — protegería además los metadatos
  (número de ítems, marcas de tiempo) frente a la extracción del archivo, pero añade una
  dependencia nativa al camino crítico, obliga a gestionar la clave de la base de datos en
  memoria durante toda la sesión y complica las pruebas en la JVM; se aplaza como defensa en
  profundidad. `EncryptedSharedPreferences` como núcleo de seguridad: descartado
  explícitamente por los requisitos.
- **Consecuencias:** quien extraiga la base local obtiene solo ciphertext, pero **sí** ve cuántos
  ítems existen y cuándo se modificaron. Este riesgo residual se documenta en `THREAT_MODEL.md`.
  La búsqueda ocurre en memoria sobre los datos descifrados, con el límite práctico documentado.
- **Estrategia de migración:** añadir SQLCipher es un cambio de la capa de almacenamiento, no del
  formato criptográfico: el contenido ya está cifrado y seguiría estándolo.

---

## ADR-014 — Modelo de Firestore y reglas cerradas desde el primer commit

- **Fecha:** 2026-07-29
- **Estado:** modificada por ADR-023
- **Contexto:** Firestore no debe poder descifrar nada, y un usuario autenticado no debe poder
  tocar los datos de otro. Las reglas no sustituyen al cifrado, pero una regla laxa permite
  destruir o corromper datos ajenos.
- **Decisión:** modelo `users/{uid}/vaults/{vaultId}` y `users/{uid}/vaults/{vaultId}/items/{itemId}`.
  El documento de bóveda contiene solo versiones, nombre y parámetros del KDF, salts,
  `passwordWrappedVdek`, `recoveryWrappedVdek` y marcas de tiempo. Cada ítem contiene solo
  `ciphertext` (tipo `bytes`), versiones, `revision`, `tombstone` y marcas de tiempo. Reglas de
  mínimo privilegio y **denegación por defecto** desde el primer commit: `request.auth != null`,
  ruta anclada al propio uid, lista blanca de campos con `hasOnly`, validación de tipos, límite
  de tamaño del ciphertext, `createdAt` e identificadores inmutables, `revision` estrictamente
  creciente y borrado directo denegado (los borrados son tombstones). Nunca se abren las reglas,
  ni temporalmente ni «solo para desarrollo». La suite de pruebas de reglas se ejecuta contra el
  Emulator Suite.
- **Alternativas consideradas:** un único documento con todos los ítems (simplificaría las reglas
  pero rompería la sincronización granular y el límite de tamaño de documento); permitir borrado
  directo (impediría propagar los borrados a otros dispositivos).
- **Consecuencias:** una filtración completa de Firestore revela ciphertext, identificadores
  aleatorios, versiones, número de ítems y marcas de tiempo. Esa fuga de metadatos se documenta
  como riesgo residual. Los tombstones acumulan documentos; la política de purga se documenta.
- **Estrategia de migración:** `schemaVersion` en el documento de bóveda permite evolucionar el
  modelo con reglas que acepten las versiones vigentes durante la transición.

**Vigencia:** ADR-023 sustituye la denegación absoluta de `delete`, define el tombstone con
ciphertext vacío y añade monotonía y rangos cerrados. La denegación por defecto permanece vigente.

---

## ADR-015 — Sincronización local-first con revisiones y tombstones

- **Fecha:** 2026-07-29
- **Estado:** modificada por ADR-025
- **Contexto:** la aplicación debe funcionar sin conexión y no debe perder ediciones concurrentes
  en silencio. Solo puede viajar ciphertext.
- **Decisión:** local-first: la escritura va primero a Room y una tarea de WorkManager empuja el
  ciphertext a Firestore. Cada ítem lleva `revision`; los borrados son tombstones sincronizables.
  Los conflictos se detectan comparando la revisión remota con la última sincronizada cuando el
  registro local está marcado como sucio; en ese caso se conserva una copia local en conflicto en
  lugar de sobrescribir, y se informa al usuario. Las operaciones criptográficas ocurren siempre
  antes de la capa remota; los DTO remotos no pueden nombrar modelos de dominio (ADR-003).
- **Alternativas consideradas:** last-write-wins puro (aceptado por los requisitos para el MVP,
  pero pierde datos en silencio); CRDT o fusión a nivel de campo (exigiría estructura
  semántica del contenido cifrado y complicaría mucho el MVP).
- **Consecuencias:** no se pierden ediciones concurrentes, a costa de que el usuario resuelva
  manualmente los duplicados en conflicto. La caché offline de Firestore contiene solo
  ciphertext, lo que se acepta y se documenta.
- **Estrategia de migración:** la fusión más fina se puede añadir sin cambiar el formato, porque
  el conflicto se resuelve sobre plaintext en memoria.

**Vigencia:** ADR-025 añade el flujo de adopción, el staging de conflictos con sesión bloqueada y
las marcas de agua locales; el principio local-first permanece vigente.

---

## ADR-016 — Formato de respaldo cifrado y versionado

- **Fecha:** 2026-07-29
- **Estado:** modificada por ADR-026
- **Contexto:** el usuario debe poder exportar y restaurar su bóveda sin que el archivo contenga
  plaintext y sin depender de Firebase.
- **Decisión:** un archivo con envoltura versionada (identificador de formato y versión
  explícitos) que contiene únicamente: parámetros y nombre del KDF, salts,
  `passwordWrappedVdek`, `recoveryWrappedVdek`, el `vaultId`, y los ciphertexts de los ítems con
  sus identificadores, versiones y revisiones. **No** incluye el blob biométrico local. La
  restauración exige contraseña maestra **o** frase de 24 palabras. El parser es defensivo:
  esquema estricto, límites de tamaño por campo y totales, rechazo de versiones desconocidas, sin
  confianza en el nombre del archivo, sin ejecución de contenido y sin deserialización de tipos
  arbitrarios. La exportación usa el selector de documentos del sistema. El formato exacto se
  documenta en `BACKUP_FORMAT.md`.
- **Alternativas consideradas:** exportar plaintext protegido por contraseña del archivo
  (prohibido); un formato binario propio sin envoltura declarada (más frágil de versionar);
  reutilizar el volcado de Room (acoplaría el respaldo al esquema interno).
- **Consecuencias:** el respaldo es tan fuerte como la contraseña maestra o la frase, y es
  portable entre cuentas de Firebase porque la AAD no incluye el uid (ADR-009). Un respaldo
  antiguo con `cryptoVersion` anterior debe seguir siendo restaurable, lo que exige mantener las
  rutas de descifrado antiguas.
- **Estrategia de migración:** la versión del formato se incrementa y el importador acepta el
  conjunto de versiones soportadas, con pruebas por versión.

---

## ADR-017 — Firebase solo en Emulator Suite durante el desarrollo

- **Fecha:** 2026-07-29
- **Estado:** aceptada
- **Contexto:** los requisitos prohíben pedir o manejar credenciales reales, cuentas de servicio y
  despliegues en producción. No hay proyecto de Firebase configurado.
- **Decisión:** todo el desarrollo y todas las pruebas usan Firebase Emulator Suite con
  configuración local ficticia. `google-services.json` real **no** entra al repositorio; se
  publica `google-services.json.example` con placeholders evidentes y los pasos manuales en
  `FIREBASE_SETUP.md`. No se despliegan reglas, servicios ni aplicaciones. App Check se evalúa y
  se implementa con el proveedor de depuración; Play Integrity queda documentado como paso manual
  del propietario, sin activarse.
- **Alternativas consideradas:** pedir al propietario un proyecto real para probar de extremo a
  extremo (rechazado: exige credenciales reales y contradice los requisitos).
- **Consecuencias:** todo lo que dependa de un proyecto real (Google Sign-In con cliente OAuth
  real, App Check con Play Integrity, despliegue de reglas) queda como bloqueo externo documentado
  con instrucciones reproducibles. El resto se prueba de verdad contra el emulador.
- **Estrategia de migración:** al crear el proyecto real, basta colocar `google-services.json`,
  configurar el cliente OAuth y desplegar las reglas ya probadas.

---

## ADR-018 — Sin telemetría; logger seguro propio

- **Fecha:** 2026-07-29
- **Estado:** aceptada
- **Contexto:** cualquier SDK de telemetría podría capturar contenido sensible, y `android.util.Log`
  escribe en un buffer legible por herramientas de depuración.
- **Decisión:** no se integra Analytics, publicidad, tracking, grabación de sesión, Performance
  Monitoring ni logging remoto. Crashlytics no se usa en flujos sensibles. Se implementa un logger
  propio en `:core:common` que en release descarta todo lo que no sea un error operativo, **no
  acepta** contenido de notas, contraseñas, frases, entropía, claves ni ciphertext, y redacta
  identificadores. El uso directo de `android.util.Log` y `println` está prohibido y lo verifica
  una prueba de higiene del repositorio.
- **Alternativas consideradas:** Crashlytics con filtros (un filtro fallido significa una fuga
  permanente y fuera de control); Timber (útil, pero sigue permitiendo registrar cualquier cosa).
- **Consecuencias:** menos visibilidad de fallos en producción: la depuración depende de
  reproducir localmente. Se acepta a cambio de eliminar la ruta de fuga.
- **Estrategia de migración:** si en el futuro se quiere reportar fallos, se haría con un canal
  que solo transmita códigos de error y sin datos de usuario, y exigiría un ADR nuevo.

---

## ADR-019 — La sesión no expone el material de clave; grafo de módulos cerrado

- **Fecha:** 2026-07-29
- **Estado:** modificada por ADR-033 y ADR-042
- **Contexto:** el diseño inicial definía `SessionState = Locked | Unlocked(vdekAead)` y declaraba la
  dependencia `:app → :core:crypto`. Cualquier ViewModel podía extraer la primitiva `Aead`, cifrar
  fuera de los repositorios y retenerla más allá del bloqueo, anulando la garantía de que el bloqueo
  destruye todas las referencias al material de clave. Además, el grafo declarado no podía compilar
  el flujo descrito: ni `:core:crypto` ni `:data:sync` declaraban `:core:model`, y la salida más
  rápida al implementarlo (mover los modelos de dominio a `:core:common`, del que sí dependen las
  capas de datos) habría eliminado en silencio la única frontera verificable por el compilador.
- **Decisión:**
  1. `SessionState` público **sin payload**: `Locked` y `Unlocked(vaultId, openedAt)`. El `Aead` de la
     VDEK vive en un campo **privado** de `VaultSession`. Solo expone operaciones concretas; ninguna
     propiedad, parámetro, retorno ni callback permite obtener o conservar `Aead` o VDEK.
  2. Se **elimina** la arista `:app → :core:crypto`. `CryptoError` permanece interno al camino
     criptográfico y `:data:sync` lo traduce a `AppError` de `:core:common` antes de cruzar a `:app`.
  3. `:core:crypto` **no conoce el dominio**: `ItemCryptor` opera sobre `ItemPayload` (formato propio
     del contrato criptográfico) y `ByteArray`. La conversión `VaultItem ↔ ItemPayload` vive en
     `:data:sync`, que **sí** declara `:core:model`.
  4. `:core:common` **no puede contener ningún tipo de dominio descifrado**: solo utilidades sin
     contenido de usuario. Es una regla dura, porque `:data:local` y `:data:remote` dependen de él.
  5. `:core:crypto/keystore/` es el dueño del alias y de la clave del Android Keystore. `:app` solo
     lanza el `BiometricPrompt` y entrega el `CryptoObject` autenticado a un caso de uso de
     `:data:sync`; el material nunca se materializa en la capa de UI.
  6. Se añade `SettingsRepository` en `:data:sync`, porque `:app` no puede acceder a `:data:local` y
     las pantallas de seguridad necesitan las preferencias.
  7. La puerta G1-4 pasa a verificar el **grafo completo**, arista por arista, y no solo dos
     prohibiciones.
- **Alternativas consideradas:** dejar `Unlocked(vdekAead)` y confiar en la revisión de código
  (rechazado: convierte una garantía estructural en una convención); mover `VaultItem` a
  `:core:crypto` (rechazado: acopla el contrato criptográfico al dominio y arrastra el modelo a las
  capas de datos por transitividad).
- **Consecuencias:** hay que escribir mapeadores explícitos y casos de uso en lugar de exponer la
  primitiva; a cambio, una fuga de material de clave a la UI **no compila**. El bloqueo vuelve a ser
  verificable: solo existe una referencia al `Aead`.
- **Estrategia de migración:** ninguna prevista; relajar el grafo exigiría un ADR nuevo y una
  revisión de seguridad.

**Vigencia del punto 5:** ADR-042 trasladó la propiedad de la integración Android Keystore a
`:data:sync/biometric`; la garantía de que `:app` no recibe material de clave permanece vigente.

**Vigencia:** ADR-033 sustituye el punto 1 en cuanto al dueño material: `VaultSession` conserva una
capacidad opaca; el `Aead` nunca sale de `:core:crypto`. También endurece las escrituras
local/remota con `Ciphertext` opaco.

---

## ADR-020 — `wrapEpoch`: frescura del envoltorio y revocación real

- **Fecha:** 2026-07-29
- **Estado:** aceptada (corrige el alto A-2)
- **Contexto:** tres revisores coincidieron en que el diseño **prometía revocación que no podía
  cumplir**. Cambiar la contraseña maestra o regenerar la frase solo reescribe un envoltorio de la
  **misma** VDEK, y las reglas solo exigían `metaRevision` creciente y `createdAt` inmutable: quien
  conservara un envoltorio anterior (historial de Firestore, un respaldo antiguo, otro dispositivo,
  un token de sesión robado) podía reponerlo con `metaRevision+1` y volver a abrir la bóveda, además
  de descifrar las notas creadas **después** del cambio. `docs/sync-protocol.md` afirmaba
  explícitamente lo contrario.
- **Decisión:**
  1. Se añade `wrapEpoch` (entero monótono creciente, por bóveda) al documento de bóveda, a Room y
     a los `paramsCanónicos` de la AAD de **cada** envoltorio. Se incrementa en toda operación que
     reescriba un envoltorio: cambio de contraseña, recalibración, regeneración de la frase y
     restauración.
  2. El cliente guarda localmente el último `wrapEpoch` conocido y **rechaza** cualquier documento
     remoto con un `wrapEpoch` inferior, avisando al usuario en lugar de fallar en silencio.
  3. Las reglas exigen `wrapEpoch` estrictamente creciente en el `update` del documento de bóveda,
     además de la monotonía de versiones y parámetros de ADR-023.
  4. Se corrige la redacción absoluta de ADR-011, `RECOVERY.md`, `docs/key-lifecycle.md` y
     `docs/sync-protocol.md`: la frase o la contraseña anteriores dejan de abrir **el envoltorio
     almacenado actualmente**, no dejan de existir como material.
  5. La **revocación completa** exige rotación de la VDEK con recifrado de los ítems. Cuando el
     motivo declarado por el usuario sea compromiso, la interfaz **ofrece** la rotación y explica la
     diferencia. La rotación queda fuera del MVP (ADR-010) pero su ausencia se declara.
  6. Se añade la amenaza **T-23** (reposición de un envoltorio antiguo) y su riesgo residual al
     modelo de amenazas, y se declara en `THREAT_MODEL.md` T-14 que un respaldo exportado conserva
     válida la credencial vigente en el momento de exportarlo.
- **Alternativas consideradas:** rotar la VDEK en cada cambio de contraseña (rechazado: recifrar toda
  la bóveda en una operación rutinaria, y el requisito explícito es que cambiar la contraseña **no**
  recifre las notas); confiar solo en la regla de Firestore (rechazado: el servidor no es confiable
  por diseño y puede servir lo que quiera, así que la comprobación debe estar también en el cliente).
- **Consecuencias:** la reposición se detecta en el cliente incluso con Firestore comprometido, y se
  deniega en el servidor para otros clientes. Se acepta que la revocación sin rotación de la VDEK es
  **parcial** y se dice con claridad, en lugar de prometer lo contrario.
- **Estrategia de migración:** ADR-030 sustituye el único `wrapEpoch` por epochs independientes
  antes de que exista implementación o dato persistido.

**Vigencia:** se conserva la finalidad antirretroceso. ADR-030 sustituye los puntos 1–3 que usaban
un epoch global, porque cambiar un solo envoltorio invalidaba la AAD de los demás.

---

## ADR-021 — Rango cerrado de parámetros del KDF y AAD normativa

- **Fecha:** 2026-07-29
- **Estado:** aceptada (corrige los altos A-3 y A-5 y el medio M-5; modifica ADR-006 y ADR-009)
- **Contexto:** el revisor criptográfico encontró tres defectos encadenados. (a) `paramsCanónicos`
  estaba escrito con los números literales, así que una implementación fiel habría codificado
  constantes y la AAD habría sido idéntica cualesquiera que fuesen los parámetros persistidos,
  eliminando la ligadura. (b) Al cambiar la contraseña, el diseño **adoptaba** los parámetros
  «vigentes» leídos de Firestore: un documento envenenado bajaba los parámetros al suelo de forma
  permanente y silenciosa. (c) Solo existía suelo, sin techo: un `memoryKib` cercano al máximo de un
  entero provoca `OutOfMemoryError` en todos los dispositivos y deja la bóveda inabrible. Además la
  AAD se definía por ejemplo, con separador `|` sin escape (AAD no inyectiva) y base64url de
  procedencia ambigua.
- **Decisión:**
  1. **Rango cerrado**, verificado en el mismo punto y **antes de reservar memoria**: memoria
     [19 456, 1 048 576] KiB, iteraciones [2, 10], paralelismo [1, 8], `outputLen == 32` exacto,
     `kdfName == "argon2id"`, salt de longitud exacta según §5 de `CRYPTOGRAPHY.md`. Fuera de rango →
     `CryptoError.WeakParameters` o `MalformedInput`. El mismo rango se replica en las Security Rules
     y en el importador del respaldo.
  2. `paramsCanónicos` se serializa **siempre** desde los valores persistidos o recibidos, nunca
     desde constantes de compilación.
  3. Al **crear** cualquier envoltorio (creación, cambio de contraseña, recuperación, restauración)
     se usan **los parámetros de producción del binario**. Los parámetros de una fuente externa se
     usan **solo** para reproducir una derivación existente; si son inferiores a los de producción,
     tras un desbloqueo correcto se reenvuelve hacia arriba.
  4. La afirmación «incluir los parámetros en la AAD impide el downgrade» se rebaja a lo que es:
     **defensa en profundidad** que hace explícito el fallo. La protección primaria es el rango
     verificado en código más la regla 3.
  5. **AAD normativa:** cada campo se valida contra un juego de caracteres que **excluye** `|`, `,`
     y `=` (incluido el alias del Keystore) con rechazo tipificado; los enteros se representan en
     ASCII decimal sin signo, sin ceros a la izquierda y sin separadores; `salt=` es **siempre** la
     recodificación canónica en base64url sin relleno de los bytes decodificados, nunca la cadena
     tal como aparece en el archivo; el orden de las claves de `paramsCanónicos` queda congelado.
- **Alternativas consideradas:** codificación con longitud por campo en lugar de separador
  (elimina la clase de problema, pero cambia el formato y complica la inspección; se anota como
  candidata para `bw2`); validar solo en el cliente (insuficiente: las reglas son la segunda capa).
- **Consecuencias:** un documento remoto no puede debilitar ni inutilizar la derivación, y la AAD es
  inyectiva. El coste es más validación explícita y un contrato más largo.
- **Estrategia de migración:** ADR-031 precisa el conjunto aceptado antes de que exista
  implementación o dato persistido. Un perfil futuro exige versión o lista publicada y medida.

**Vigencia:** la AAD normativa y la validación previa siguen vigentes. ADR-031 sustituye el rango
numérico amplio por un perfil v1 único para que ninguna entrada aceptada reserve memoria no medida.

---

## ADR-022 — Codificación BIP-39 fijada: lista, normalización y longitud

- **Fecha:** 2026-07-29
- **Estado:** aceptada (corrige el alto A-4; complementa ADR-008)
- **Contexto:** el contrato nombraba la biblioteca pero no fijaba **qué lista de palabras** se usa,
  ni la normalización Unicode exigida por el estándar, ni ligaba la longitud de la entropía en la
  AAD. Como la entropía **nunca se persiste** y la frase se muestra una sola vez, un cambio de lista
  o de normalización al actualizar la biblioteca rompería la recuperación de forma **permanente y
  silenciosa**: no hay ningún valor almacenado contra el que detectarlo.
- **Decisión:** como parte de `cryptoVersion = 1`:
  1. **Lista de palabras: inglesa** (la lista por defecto del estándar).
  2. **Normalización exacta** de la entrada del usuario: NFKD → recorte de extremos → colapso de
     espacios internos a un único `U+0020` → minúsculas con regla invariante (`Locale.ROOT`).
  3. `paramsCanónicos` de `recovery` incluye `entropyBits=256,words=24,wordlist=english`, de modo que
     un envoltorio con menos entropía no pueda producir una AAD indistinguible.
  4. La comparación del checksum y la verificación de las palabras elegidas al azar se hacen en
     **tiempo constante**.
  5. Existe una prueba que afirma la identidad de la lista de palabras del codec, para que una
     actualización de la biblioteca que la cambie **rompa la compilación de las pruebas** en lugar de
     romper silenciosamente la recuperación de los usuarios.
- **Alternativas consideradas:** dejar la lista al criterio de la biblioteca (rechazado: es el
  parámetro cuyo cambio es más difícil de detectar y de consecuencias más graves); permitir varios
  idiomas (rechazado en el MVP: multiplica los casos y no aporta seguridad).
- **Consecuencias:** la conversión entropía ↔ frase es reproducible por contrato y auditable. El
  usuario no puede usar una lista en otro idioma.
- **Estrategia de migración:** otra lista exigiría `cryptoVersion` nueva y reenvolver la
  recuperación, es decir, regenerar la frase.

---

## ADR-023 — Correcciones de las Security Rules y contrato del tombstone

- **Fecha:** 2026-07-29
- **Estado:** aceptada (corrige los altos A-6, A-7, A-8, A-9 y el medio M-11; modifica ADR-014)
- **Contexto:** el revisor de reglas y el de arquitectura Android encontraron que el borrador era
  **más estricto que los flujos que debía soportar**, con cuatro contradicciones que producían o
  retención permanente de datos que el usuario cree borrados, o denegaciones clasificadas como
  errores permanentes sin reintento.
- **Decisión:**
  1. **Contrato del tombstone:** `tombstone == true ⟺ ciphertext.size() == 0`, aplicado en las
     reglas, en el esquema de Room y en el formato de respaldo. Enterrar un ítem **elimina su
     ciphertext** del servidor y del dispositivo. La columna guarda un BLOB vacío, no `NULL`.
  2. **`create` de ítems:** `revision >= 1` en lugar de `revision == 1`. La garantía
     antirreproducción la aporta `revision > resource.data.revision` en el `update`; exigir 1 al
     crear no añadía seguridad y bloqueaba de forma permanente los ítems editados sin conexión, las
     copias en conflicto y la restauración de respaldos.
  3. **Borrado:** `delete` permitido **al propietario**, en ítems solo si ya están enterrados
     (`resource.data.tombstone == true`) y en el documento de bóveda sin condición adicional. Existe
     exclusivamente para la purga y para el flujo de eliminación total de la bóveda, que antes era
     inejecutable y dejaba el material en Firestore.
  4. **Monotonía relativa en el `update` de la bóveda:** `cryptoVersion`, `schemaVersion`,
     `kdfMemoryKib`, `kdfIterations` y `kdfParallelism` no pueden decrecer, `kdfOutputLen` no puede
     cambiar. Cada grupo de campos de envoltorio exige incrementar su epoch independiente
     (ADR-030) y deja el otro exactamente igual; no se permite incrementar un epoch vacío.
  5. **Techos y lista blanca de valores:** el perfil v1 cerrado de ADR-031 se replica en las reglas,
     incluido `kdfName == 'argon2id'`.
  6. **Marcas de tiempo acotadas:** `createdAt > 0`, `updatedAt >= createdAt`, `updatedAt` no
     decreciente en los `update` y acotado contra el reloj del servidor con una tolerancia declarada.
  7. **Listas de campos separadas** en obligatorios y opcionales, aunque hoy la lista de opcionales
     esté vacía, con la regla de despliegue: todo campo nuevo entra primero como **opcional**, se
     despliega, y solo pasa a obligatorio cuando no queden clientes antiguos. Sin esto, `hasOnly` +
     `hasAll` sobre la misma lista hacía imposible cualquier migración escalonada.
  8. Las funciones se declaran **dentro** del bloque `match /databases/{database}/documents`, donde su
     validez es inequívoca. El `match /{document=**}` final se conserva con un comentario que aclara
     que es **documental**: el motor ya deniega por defecto.
  9. El `vaultId` es el **identificador del documento**, no un campo: `VaultDocDto` no lo serializa, y
     lo mismo con `itemId` en `EncryptedItemDto`. Los campos locales `dirty`, `lastSyncedRevision`,
     `conflictOf` y todos los campos `pendingRemote*` **nunca** viajan, y hay pruebas que lo
     comprueban por nombre.
- **Alternativas consideradas:** mantener `delete: if false` y redefinir la eliminación como borrado
  lógico (rechazado: obligaría a admitir un documento de bóveda «vacío» y dejaría material residual
  con una interfaz que promete irreversibilidad); normalizar la revisión a 1 en el cliente antes del
  primer push (rechazado: añade estado y sigue rompiendo la restauración de respaldos).
- **Consecuencias:** las reglas siguen siendo de mínimo privilegio y denegación por defecto, pero
  ahora soportan los flujos reales. Permitir `delete` es un aflojamiento consciente y acotado que se
  compensa exigiendo el tombstone previo en los ítems.
- **Estrategia de migración:** `schemaVersion` más la lista de campos opcionales permiten evolucionar
  el modelo sin ventanas de denegación.

---

## ADR-024 — Cadena de herramientas completa y plugins de convención

- **Fecha:** 2026-07-29
- **Estado:** aceptada (corrige el alto A-10; complementa ADR-002)
- **Contexto:** el catálogo de dependencias registraba artefactos pero **ningún plugin ni ninguna
  dependencia de pruebas**. Faltaban piezas obligatorias, no opcionales: con Kotlin 2.x el
  compilador de Compose se aplica como **plugin propio** (`org.jetbrains.kotlin.plugin.compose`) y
  sin él no compila un solo composable; faltaba el plugin de Hilt; con `exportSchema = true` y sin
  `room.schemaLocation` el esquema **no se exporta**, y la puerta de la Fase 3 exige que esté
  versionado; faltaban `hilt-work` y `HiltWorkerFactory` para inyectar en el worker de
  sincronización (lo que además obliga a retirar el proveedor de inicialización por defecto de
  WorkManager del manifiesto); y faltaban `room-testing` (`MigrationTestHelper`), `work-testing`,
  el runner instrumentado, el runner de Hilt y `kotlinx-coroutines-test`, sin los cuales las puertas
  de las fases 3 y 5 son inalcanzables.
- **Decisión:**
  1. `docs/DEPENDENCY_POLICY.md` §3.4 registra **plugins** y **dependencias de prueba** con el mismo
     rigor que el resto. D-06 exige fijar todas esas versiones antes de cerrar la Etapa 2.
  2. Se añaden plugins de convención en `build-logic` (android-application, android-library,
     jvm-library) desde la Fase 1, en lugar de repetir la configuración en siete módulos. El
     `jvmToolchain(17)` se aplica desde el plugin de convención, no desde el `build.gradle.kts` raíz.
  3. El argumento `room.schemaLocation` se configura explícitamente y la existencia del archivo de
     esquema es parte de la puerta de la Fase 3.
  4. Se retira el proveedor de inicialización por defecto de WorkManager en el manifiesto y se usa
     `Configuration.Provider` con `HiltWorkerFactory`; la revisión de componentes exportados de la
     Fase 9 debe encontrar coherente esa eliminación.
  5. `assembleRelease` se adelanta a la puerta de la **Fase 3** (riesgo R-11): así los problemas de
     `keep` de Tink, BouncyCastle, kotlinx.serialization, Room y Hilt aparecen de uno en uno y no
     todos juntos en la auditoría final.
- **Alternativas consideradas:** configurar cada módulo a mano (rechazado: siete copias divergentes);
  dejar el catálogo incompleto y resolverlo al compilar (rechazado: es exactamente lo que la política
  de dependencias prohíbe, y oculta decisiones en el momento de más prisa).
- **Consecuencias:** la Fase 1 tiene más trabajo inicial y a cambio las fases 3, 5 y 9 dejan de estar
  bloqueadas por piezas ausentes.
- **Estrategia de migración:** el catálogo y los plugins de convención concentran cualquier cambio de
  versión en un punto.

---

## ADR-025 — Adopción de bóveda existente y conflictos con sesión bloqueada

- **Fecha:** 2026-07-29
- **Estado:** aceptada (corrige los altos A-11 y A-12; complementa ADR-015)
- **Contexto:** dos huecos que se habrían descubierto tarde. (a) No existía el flujo de **adopción**:
  un dispositivo nuevo no puede abrir la bóveda sin `vault_meta`, y no había descubrimiento del
  `vaultId` ni orden de arranque, aunque el desbloqueo mencionara «Firestore si es un dispositivo
  nuevo». Sin esto, sincronizar con Firebase no tiene sentido para el usuario. (b) La resolución de
  conflictos exige recifrar (la AAD liga el ciphertext al `itemId`), por lo que necesita la sesión
  desbloqueada; pero el worker corre en segundo plano con la sesión **bloqueada**, y el esquema tenía
  una sola columna `ciphertext` por ítem y ningún sitio donde registrar el conflicto pendiente. Las
  dos únicas salidas eran sobrescribir el local (pérdida silenciosa, prohibida por ADR-015) o
  bloquear el pull indefinidamente.
- **Decisión:**
  1. **Secuencia de arranque**, documentada en `docs/sync-protocol.md`: autenticar → comprobar que
     el `ownerUid` local coincide con el UID autenticado → si `vault_meta` está vacía, listar
     `users/{uid}/vaults` → cero documentos: flujo de creación; uno: adoptar (insertar `vault_meta`
     con `ownerUid` local, ambos epochs y `metaRevision` remotos); más de uno: **preguntar al
     usuario**, sin elegir automáticamente → solo entonces permitir el desbloqueo → luego el pull.
     Al cambiar A → B, B nunca puede abrir la fila de A; los cambios pendientes de A requieren
     sincronización o descarte explícito antes de limpiar su ciphertext local. `ownerUid` no entra
     en la AAD, Firestore ni el respaldo.
  2. **Staging de conflictos en el esquema v1:** columnas nullable para el DTO remoto cifrado
     completo: `pendingRemoteCiphertext`, `pendingRemoteRevision`, `pendingRemoteCryptoVersion`,
     `pendingRemoteSchemaVersion`, `pendingRemoteTombstone`, `pendingRemoteCreatedAt` y
     `pendingRemoteUpdatedAt`, más `pending_conflicts(itemId, detectedAt, remoteRevision)`. El
     conjunto se escribe o limpia atómicamente. Con la sesión bloqueada, el ciphertext local
     permanece **intacto byte a byte** y la copia en conflicto se genera al desbloquear sin red.
  3. **Marca de agua por ítem:** el cliente rechaza una revisión remota inferior a la última
     aceptada, mitigación parcial del riesgo R-07 (un servidor malicioso puede servir una versión
     antigua auténtica). La protección completa exigiría un manifiesto autenticado de la bóveda y
     queda fuera del MVP, declarada como riesgo.
  4. `lastPullAt` se acota a «ahora» en lugar de al máximo `updatedAt` recibido, y las marcas de
     tiempo quedan acotadas por las reglas (ADR-023), para que un reloj desviado no deje ítems
     permanentemente sin bajar (riesgo R-09).
  5. Se añaden índices en `encrypted_items` para `dirty`, `tombstone` y `updatedAt`: son necesarios
     para las consultas reales y no filtran contenido.
- **Alternativas consideradas:** exigir la sesión desbloqueada para sincronizar (rechazado: elimina
  la sincronización en segundo plano, que es el motivo de usar WorkManager); resolver el conflicto
  sobre ciphertext sin recifrar (imposible: la AAD ata el ciphertext a su `itemId`).
- **Consecuencias:** el esquema v1 nace con las columnas necesarias y se evita una migración de Room
  más un rediseño del motor de sincronización. El coste es un estado intermedio más que documentar y
  probar.
- **Estrategia de migración:** las columnas de staging son locales y desechables.

---

## ADR-026 — Límites coherentes del respaldo y verificación de ambos envoltorios

- **Fecha:** 2026-07-29
- **Estado:** aceptada; el límite total fue endurecido por ADR-049
- **Contexto:** el importador aceptaba hasta 32 MiB en total pero también hasta 50 000 ítems de
  256 KiB cada uno —del orden de gigabytes—, y la exportación **no comprobaba ningún tamaño**: una
  bóveda mediana producía un archivo que nunca podría restaurarse, y el usuario solo lo descubriría
  al necesitarlo. Como el respaldo es la única defensa documentada frente al borrado por una cuenta
  comprometida, un respaldo irrestaurable es una falsa sensación de seguridad. Además, nada
  verificaba que `passwordWrappedVdek` y `recoveryWrappedVdek` envolvieran la **misma** VDEK: una
  regeneración interrumpida podía dejar la bóveda con una sola vía real de acceso, y el fallo se
  manifestaría el día en que el usuario ya hubiera perdido la contraseña.
- **Decisión:**
  1. **Límites coherentes y alcanzables:** máximo 5 000 ítems por bóveda, 256 KiB por ciphertext y
     64 MiB de archivo total. El mismo tope de ítems se aplica en la aplicación, coherente con el
     límite práctico del descifrado en memoria (riesgo R-08).
  2. La **exportación comprueba el total antes de escribir** y falla con un error accionable que
     indica el tamaño y el número de ítems, en lugar de producir un archivo inservible.
  3. Antes de cada escritura atómica de creación, regeneración de la frase y restauración, se
     desenvuelven **ambos** envoltorios en memoria y se comprueba que producen la misma VDEK; si no
     coinciden, la operación se aborta. No requiere ninguna primitiva nueva.
  4. El importador comprueba `kdfName == "argon2id"`, las longitudes **exactas** de los salts (16 B
     para el de contraseña, 32 B para el de recuperación) y el perfil v1 cerrado de ADR-031,
     alineando el formato aceptado con el contrato en lugar de ensancharlo.
- **Alternativas consideradas:** exportación por fragmentos (rechazada para el MVP: más formato y más
  parser; se anota como candidata si el tope resulta insuficiente).
- **Consecuencias:** un respaldo exportado siempre se puede restaurar, y una bóveda nunca queda en
  silencio con un único camino de acceso. El coste es un tope explícito de tamaño de bóveda, que se
  documenta al usuario.
- **Estrategia de migración:** subir los topes es compatible hacia atrás; `formatVersion` cubre
  cualquier cambio estructural.

---

## ADR-027 — `minSdk = 33`

- **Fecha:** 2026-07-29
- **Estado:** aceptada (modifica ADR-002; corrige el medio M-1)
- **Contexto:** con `minSdk 29`, dos mitigaciones declaradas no existían en un tercio del rango
  soportado: `ClipDescription.EXTRA_IS_SENSITIVE` (marcar el portapapeles como sensible) se
  introduce en API 33, y `KeyGenParameterSpec.Builder.setUserAuthenticationParameters` —la forma
  moderna de exigir autenticación por operación con biometría fuerte— existe desde API 30, lo que
  obligaba a una rama de compatibilidad para API 29 **imposible de verificar** con el equipo
  disponible (el dispositivo de pruebas es API 33). El modelo de amenazas justificaba la mitigación
  del portapapeles diciendo que estaba «disponible en el dispositivo de pruebas», confundiendo el
  dispositivo de verificación con el rango soportado.
- **Decisión:** `minSdk = 33`. El dispositivo de pruebas es API 33 y el dispositivo objetivo del
  propietario es más moderno, de modo que no se pierde ningún usuario real. Con ello: todas las
  mitigaciones declaradas existen en todo el rango soportado, desaparece la rama de API 29 del
  Keystore, y no queda ninguna ruta de código imposible de verificar en el hardware disponible.
- **Alternativas consideradas:** mantener `minSdk 29` y degradar la función por nivel de API
  (rechazado: añade ramas no verificables en la ruta de seguridad y complica el modelo de amenazas
  con residuales por versión); mantener `minSdk 29` y declarar los residuales (rechazado: se estaría
  aceptando una protección peor sin ganar ningún usuario real).
- **Consecuencias:** se renuncia a los dispositivos con Android 10, 11 y 12. Cada mitigación de
  interfaz declarada en `THREAT_MODEL.md` debe indicar de todas formas su API mínima, para que la
  próxima vez la confusión no sea posible.
- **Estrategia de migración:** bajar `minSdk` en el futuro exigiría revisar cada mitigación por nivel
  de API y un ADR nuevo.

---

## ADR-028 — Biometría: doble envoltorio y autenticación por operación

- **Fecha:** 2026-07-29
- **Estado:** modificada por ADR-042 (originalmente modificó ADR-012)
- **Contexto:** tres defectos en el camino biométrico. (a) La **activación** estaba descrita como
  «crear la clave y cifrar el keyset», pero con `setUserAuthenticationRequired(true)` y sin
  autenticación previa la operación de cifrado falla; el atajo previsible (fijar una duración de
  validez o retirar el requisito) habría convertido la biometría en una comprobación de interfaz,
  dejando el blob descifrable con el dispositivo simplemente desbloqueado. (b) Era el **único**
  envoltorio fuera del formato de keyset cifrado de Tink, y exponía el keyset de la VDEK **en claro**
  en memoria en cada activación y cada desbloqueo, con dos implementaciones de wrapping que auditar
  en lugar de una. (c) «Biometría fuerte cuando esté disponible» admitía por lectura literal una
  degradación silenciosa a biometría débil o a credencial de dispositivo.
- **Decisión:**
  1. **Doble envoltorio.** El `Cipher` del Keystore cifra y descifra **32 bytes aleatorios**
     (`BiometricKEK`), no el keyset. Esos 32 bytes se importan a Tink con el mismo `KekImporter` que
     las otras dos vías, y la VDEK se envuelve con `serializeEncryptedKeyset` y la AAD `biometric`.
     Desaparece el keyset en claro y queda **una sola** implementación de wrapping en todo el
     proyecto.
  2. La **activación** usa también `BiometricPrompt` con `CryptoObject` en modo cifrado. El usuario
     autentica al activar, no solo al desbloquear.
  3. **Prohibido** `setUserAuthenticationValidityDurationSeconds`: autenticación **por operación**.
     Con `minSdk 33` (ADR-027) se usa `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`.
  4. `setAllowedAuthenticators(BIOMETRIC_STRONG)` en el prompt, **sin** `DEVICE_CREDENTIAL`. Si
     `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` no lo permite, el desbloqueo biométrico
     **no se ofrece**: no hay alternativa degradada.
  5. `updateAAD` se invoca **antes** de cualquier `update`/`doFinal`, y el buffer transitorio vive en
     `SecureBytes` con borrado en `finally`.
  6. La clave y el alias los posee `:core:crypto/keystore/` (ADR-019); `:app` solo lanza el prompt.
  7. Pruebas nuevas: desenvolver el blob con la AAD de otro `vaultId` o de otro alias debe fallar; una
     prueba de higiene prohíbe la API de serialización de keyset en claro; una aserción sobre
     `KeyInfo` comprueba el autenticador exigido y que no hay duración de validez.
- **Alternativas consideradas:** conservar el cifrado directo del keyset especificando la API exacta y
  el orden de `updateAAD` (rechazado: mantiene el keyset en claro y dos mecanismos de wrapping);
  permitir credencial de dispositivo como fallback (rechazado: sitúa la VDEK detrás de un autenticador
  que el modelo de amenazas no evalúa).
- **Consecuencias:** el material de la VDEK nunca aparece en claro fuera de la sesión, y la biometría
  es una garantía criptográfica real. En dispositivos sin biometría fuerte, la función simplemente no
  está disponible.
- **Estrategia de migración:** el blob local es desechable: se borra y se recrea sin afectar a la
  bóveda.

---

## ADR-029 — Protecciones de ventana y de red por variante de build

- **Fecha:** 2026-07-29
- **Estado:** aceptada (corrige los medios M-6 y M-7)
- **Contexto:** dos huecos de endurecimiento que se habrían descubierto tarde y con prisa. (a)
  `FLAG_SECURE` en la actividad **no** se hereda a los diálogos de Compose, que crean su propia
  ventana; y los diálogos son precisamente donde viven la frase de 24 palabras, la confirmación de
  la contraseña maestra y la revelación de campos secretos: se capturarían en pantalla y en la
  miniatura de recientes. (b) Un único Network Security Config sin tráfico en claro haría fallar la
  conexión al Emulator Suite en la Fase 4 con un error de red opaco, y el atajo evidente —relajar el
  archivo global— afectaría también a release.
- **Decisión:**
  1. Un componente único `SecureDialog` en `ui/components` que aplica
     `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)`. Una prueba de higiene prohíbe
     `Dialog(`, `AlertDialog(` y `ModalBottomSheet(` fuera de ese componente. La puerta G7-1 pasa de
     «`FLAG_SECURE` aplicado» a «**ninguna ventana** sin `FLAG_SECURE`», con prueba instrumentada que
     abre cada diálogo y comprueba el flag.
  2. Network Security Config **restrictivo en `src/main`** (`cleartextTrafficPermitted="false"`) y un
     NSC **en `src/debug`** que añade un `domain-config` limitado a `10.0.2.2` y `localhost` para el
     emulador. La puerta de la Fase 9 inspecciona el **manifiesto fusionado de release** y el XML
     fusionado, no los archivos de origen.
- **Alternativas consideradas:** relajar el NSC global y confiar en la revisión (rechazado: una
  regresión de seguridad invisible en el diff); aplicar `FLAG_SECURE` diálogo por diálogo (rechazado:
  se olvida uno y no hay forma de detectarlo).
- **Consecuencias:** dos archivos de configuración en lugar de uno y un componente obligatorio para
  los diálogos. A cambio, ambas protecciones son verificables por prueba y no por inspección.
- **Estrategia de migración:** ninguna prevista.

---

## ADR-030 — Epoch independiente por camino de acceso

- **Fecha:** 2026-07-29
- **Estado:** aceptada (modifica ADR-020 tras la revisión de cierre de la Etapa 2)
- **Contexto:** ADR-020 colocaba un único `wrapEpoch` en la AAD de contraseña, recuperación y
  biometría, pero los flujos reescriben un envoltorio por vez. Cambiar la contraseña incrementaba el
  epoch global sin poder reenvolver la recuperación —no se dispone de la frase—, por lo que el
  envoltorio intacto dejaba de autenticar. Regenerar la frase rompía simétricamente la contraseña y
  cualquier cambio remoto rompía el atajo biométrico local.
- **Decisión:**
  1. Se usan `passwordWrapEpoch` y `recoveryWrapEpoch` en Room, Firestore y respaldos, y
     `biometricWrapEpoch` exclusivamente local. Cada valor forma parte solo de la AAD de su camino.
  2. Reescribir un envoltorio incrementa únicamente su epoch. Los demás envoltorios, metadatos y
     epochs permanecen idénticos.
  3. El cliente conserva una marca de agua por camino remoto y rechaza regresiones en un pull.
  4. Las reglas relacionan cada grupo de campos con su epoch: cambiar el grupo exige un incremento;
     no cambiarlo exige igualdad. Incrementar un epoch sin cambiar su envoltorio se deniega.
  5. Una restauración explícita autenticada puede leer un respaldo anterior y reemite cada camino
     con `max(epochDelArchivo, marcaLocal, epochRemoto)+1` solo si el remoto está ausente o coincide
     con la línea base completa del respaldo. Un remoto más nuevo o distinto bloquea la publicación
     como conflicto. Sin snapshot remoto la restauración es local, y una publicación diferida exige
     repetir la validación en línea; no se confunde con sincronización remota ordinaria.
- **Alternativas consideradas:** reenvolver los tres caminos en cada cambio (imposible sin tener
  simultáneamente contraseña, frase y hardware); retirar el epoch de la AAD (pierde la ligadura
  autenticada); mantener uno global solo en Firestore (seguiría invalidando la AAD).
- **Consecuencias:** cambiar una credencial no rompe las otras. Hay dos marcas remotas y una local
  en vez de una global; las reglas y las pruebas son más explícitas.
- **Estrategia de migración:** no existe código ni dato persistido de la Etapa 2, así que el esquema
  v1 nace con los tres campos y no necesita migración de usuarios.

---

## ADR-031 — Perfil Argon2id v1 único y acotado

- **Fecha:** 2026-07-29
- **Estado:** aceptada (modifica ADR-021 tras la revisión de cierre de la Etapa 2)
- **Contexto:** el rango de ADR-021 aceptaba hasta 1 048 576 KiB. Ese techo evitaba un entero
  arbitrario, pero seguía permitiendo reservar aproximadamente 1 GiB y provocar
  `OutOfMemoryError`. Todavía no existe una matriz medida de perfiles alternativos que justifique
  aceptar más de uno.
- **Decisión:**
  1. Para `cryptoVersion = 1` se acepta exactamente `argon2id`, `memoryKib = 65536`,
     `iterations = 3`, `parallelism = 4`, `outputLen = 32` y salt de 16 bytes.
  2. Cualquier valor distinto se rechaza antes de invocar Argon2id, tanto en cliente como en el
     importador; las Security Rules aplican la misma lista blanca.
  3. La Fase 2 mide este perfil en el dispositivo declarado y registra el resultado real. Si no es
     aceptable, no se ensancha v1 en silencio: se registra una versión/perfil nuevo mediante ADR y
     se define el reenvoltorio.
- **Alternativas consideradas:** conservar el techo de 1 GiB (riesgo de OOM); elegir otro máximo
  sin evidencia medida (inventaría una garantía); permitir cualquier valor por encima del suelo
  (entrada hostil no acotada).
- **Consecuencias:** toda entrada v1 aceptada tiene un consumo de memoria acotado al perfil
  publicado. La recalibración futura requiere una evolución explícita del contrato.
- **Estrategia de migración:** no hay implementación ni usuarios; el contrato v1 se corrige antes
  de persistir el primer documento.

---

## ADR-032 — Reconfiguración biométrica crash-safe entre Keystore y Room

- **Fecha:** 2026-07-29
- **Estado:** aceptada (complementa ADR-028 tras la revisión de cierre de la Etapa 2)
- **Contexto:** Android Keystore y Room no comparten una transacción. Prometer que una clave/alias y
  los blobs locales se sustituyen atómicamente no es implementable y un cierre entre operaciones
  puede dejar un alias que no corresponde al registro activo.
- **Decisión:**
  1. La activación/reconfiguración crea un alias versionado nuevo sin tocar el conjunto activo.
  2. Genera ambos blobs, IV y epoch nuevos y verifica que el conjunto abre la VDEK.
  3. Solo entonces una transacción Room cambia el registro/puntero activo completo.
  4. Después del commit se elimina el alias anterior. Un cierre antes del commit conserva el
     conjunto anterior; uno después deja un alias huérfano, no un registro roto.
  5. Al arrancar se eliminan aliases biométricos del espacio de nombres de la aplicación que no
     coincidan con el alias activo. Nunca se enumera ni toca una clave ajena.
  6. Una prueba inyecta fallo en cada frontera y exige que sea utilizable íntegramente el conjunto
     anterior o el nuevo.
- **Alternativas consideradas:** tratar Keystore y Room como una transacción (imposible); borrar el
  alias anterior antes del commit (puede perder el único atajo); sobrescribir el mismo alias
  (dificulta distinguir y recuperar estados parciales).
- **Consecuencias:** puede quedar temporalmente un alias huérfano tras un cierre, pero se limpia de
  forma determinista y no afecta a los envoltorios remotos.
- **Estrategia de migración:** el esquema v1 nace con alias activo versionado; no existen datos
  anteriores.

---

## ADR-033 — Capacidad criptográfica opaca y ruta de ciphertext auditable

- **Fecha:** 2026-07-29
- **Estado:** aceptada (modifica ADR-019 tras la revisión security-architect de cierre)
- **Contexto:** hacer privado el `Aead` dentro de un `VaultSession` situado en `:data:sync` seguía
  obligando a que `:core:crypto` entregase esa primitiva al desenvolver la VDEK. Además,
  `:data:sync` conocía dominio y constructores de DTO/entidad que aceptaban `ByteArray`, por lo que
  la garantía de que una fuga accidental «no compila» era más fuerte que el diseño real.
- **Decisión:**
  1. `Aead`, `KeysetHandle`, VDEK, `KekImporter`, `VdekFactory`, `VdekWrapper` e `ItemCryptor`
     permanecen internos a `:core:crypto`.
  2. La API pública de crypto entrega `UnlockedVault`, una capacidad opaca cuyo `Aead` es privado y
     que solo ofrece operaciones concretas. `VaultSession` conserva privadamente esa capacidad.
  3. El cifrado devuelve `Ciphertext`, tipo inmutable con copias defensivas. La fábrica desde
     plaintext es interna a crypto. La fábrica `fromPersisted(ByteArray)` debe ser pública para el
     pull/push bloqueado y **no** demuestra autenticidad; solo los mappers internos local/remoto
     pueden invocarla según una prueba estructural.
  4. Entidades y DTO son internos a sus módulos. Las APIs públicas de
     `:data:local`/`:data:remote` intercambian `Ciphertext` y metadatos, nunca `ItemPayload` ni
     dominio. Estos módulos dependen de `:core:crypto` solo para ese tipo y siguen sin depender de
     `:core:model`.
  5. Una prueba inspecciona la API compilada y falla si una primitiva cruza el módulo o si
     `:data:sync` importa Room/Firebase para eludir los stores. Una prueba estructural restringe
     `fromPersisted`, prohíbe serializers de dominio en sync y fija el único flujo que parte de
     plaintext. Las pruebas de señuelo demuestran el comportamiento real.
- **Alternativas consideradas:** mover `Aead` a `:data:sync` (expone la primitiva por API); confiar
  solo en nombres de variables `ByteArray` (no ofrece separación de tipos); crear otro módulo solo
  para ciphertext (más módulos sin mejorar la fábrica privada).
- **Consecuencias:** local/remoto añaden dependencia de tipo hacia crypto, sin conocer dominio ni
  primitivas. El compilador garantiza que esas capas no nombran dominio y que las primitivas no
  salen de crypto; la semántica ciphertext/plaintext dentro de sync se garantiza por una única ruta
  auditable y pruebas, no por el sistema de tipos. Código deliberadamente malicioso dentro del
  proceso sigue siendo un riesgo de cadena de suministro.
- **Estrategia de migración:** no existe código; el grafo de Fase 1 nace con estas firmas.

---

## ADR-034 — Concreciones de la Fase 1: tipo de plugin por módulo y motor de pruebas

- **Fecha:** 2026-07-30
- **Estado:** aceptada (complementa ADR-024)
- **Contexto:** el diseño inicial dejaba abierto si `:core:model` sería una
  biblioteca de Android o un módulo JVM puro («o JVM puro si no necesita Android»), y el plan no
  fijaba el motor de pruebas ni la versión exacta de Gradle en el catálogo. Una ambigüedad en el
  plan la resuelve quien ejecuta, y quien ejecuta no participó en el diseño: es exactamente el
  hueco por el que se cuela una decisión de arquitectura tomada por conveniencia.
- **Decisión:**
  1. `:core:model` es un módulo **JVM puro** (`org.jetbrains.kotlin.jvm`), no una biblioteca de
     Android. Los modelos de dominio descifrados quedan así incapacitados por construcción para
     tocar API de Android, de persistencia o de red, y no solo por convención.
  2. `:core:common`, `:core:crypto`, `:data:local`, `:data:remote` y `:data:sync` son bibliotecas
     de Android (`com.android.library`); `:app` es `com.android.application`.
  3. El motor de pruebas unitarias es **JUnit 4** (`junit:junit` 4.13.2), coherente con
     `androidx.test.ext:junit` 1.3.0 ya registrado para las instrumentadas. No se introduce JUnit 5:
     obligaría a un runner adicional en Android sin aportar nada al núcleo criptográfico.
  4. El wrapper queda fijado en **Gradle 8.14.3** con `distributionSha256Sum`, y su valor se lee de
     `services.gradle.org` en el momento de generarlo; no se transcribe de memoria.
  5. Las bibliotecas de fases posteriores (Tink, BouncyCastle, bip39, Room, Firebase, biometric,
     WorkManager) se declaran en `gradle/libs.versions.toml` desde la Fase 1 pero **no se consumen**
     hasta su fase. Declarar no es depender: fija la versión en un único lugar sin entrar al APK.
- **Alternativas consideradas:** `:core:model` como biblioteca de Android (rechazada: permitiría
  que un modelo de dominio importara `android.os.Bundle` o una anotación de Room sin que nada
  fallara); JUnit 5 con el runner de compatibilidad (rechazada: complejidad sin beneficio, y el
  núcleo criptográfico debe probarse con el camino más simple posible); declarar cada dependencia
  solo al llegar su fase (rechazada: dispersa las versiones y multiplica los ajustes de
  compatibilidad a lo largo de nueve fases en vez de resolverlos al compilar por primera vez).
- **Consecuencias:** el catálogo nace completo, y el primer `assembleDebug` valida de una vez la
  matriz de versiones (pendiente D-04). A cambio, un fallo de resolución en la Fase 1 puede provenir
  de una biblioteca que todavía no se usa; las pruebas de higiene cubren este caso.
  `:core:model` como JVM puro obliga a que cualquier necesidad futura de una API de Android en el
  dominio pase por un ADR, que es el efecto buscado.
- **Estrategia de migración:** convertir `:core:model` en biblioteca de Android exigiría un ADR que
  justifique qué API de Android necesita un modelo descifrado y por qué no puede vivir en
  `:data:sync`. Cambiar de motor de pruebas exigiría migrar la suite completa en un solo commit.

---

## ADR-035 — Alcance de G-68 ajustado para permitir `MessageDigest` dentro de `:core:crypto`

- **Fecha:** 2026-07-31
- **Estado:** aceptada
- **Contexto:** la prueba de higiene `G-68 cryptographic hygiene` (`core/common/src/test/kotlin/
  cl/bovedawilson/core/common/RepositoryHygieneTest.kt`, escrita en la Fase 1) prohibía la cadena
  `MessageDigest` en **todo** el árbol de fuentes, sin excepción. Al implementar `Bip39Codec`
  (§2.7) para el checksum de BIP-39 —que el estándar define como el primer byte de
  `SHA-256(entropía)», ADR-022— el uso legítimo de `java.security.MessageDigest.getInstance
  ("SHA-256")` dentro de `:core:crypto` rompía esa prueba. La prueba ya trataba
  `Cipher.getInstance` de forma análoga: prohibido en general, permitido solo dentro de
  `core/crypto`.
- **Decisión:** `MessageDigest` queda prohibido en todo el árbol **excepto** dentro de
  `core/crypto`, con la misma condición (`outsideCrypto`) que ya aplicaba a `Cipher.getInstance`.
  No se elimina la prueba ni se debilita en ningún otro sentido: un hash usado como intento de
  cifrado de contenido fuera de `:core:crypto` sigue detectándose.
- **Alternativas consideradas:** reimplementar el checksum de BIP-39 sin `MessageDigest` (rechazada:
  reinventar SHA-256 a mano contradice la política de `SECURITY.md`: «nada de
  criptografía propia»); añadir un `@Suppress` puntual en `Bip39Codec.kt` (rechazada: no existe un
  mecanismo de supresión de detekt aplicable a una prueba JVM ajena, y la prueba de higiene no lee
  anotaciones de supresión de otros archivos); dejar la prueba fallando y posponer la Fase 2
  (rechazada: bloquearía indefinidamente cualquier implementación correcta de BIP-39).
- **Consecuencias:** el checksum de BIP-39 se calcula con la API estándar del JDK, sin criptografía
  propia. La superficie donde `MessageDigest` es legítimo se limita a un módulo pequeño y ya sujeto
  a revisión por `cryptography-reviewer` en cada fase que lo toque.
- **Estrategia de migración:** si una fase futura necesita un hash fuera de `:core:crypto` (por
  ejemplo, para verificar la integridad de un archivo de respaldo sin descifrarlo), ese uso exige su
  propio ADR que documente por qué no es «hash como cifrado» y por qué no puede vivir dentro de
  `:core:crypto`.

---

## ADR-036 — Fase 4: JDK 21 local solo para el Emulator Suite, `node:test` como motor y monotonía de versión también en ítems

- **Fecha:** 2026-07-31
- **Estado:** aceptada
- **Contexto:** al implementar `firebase/firestore.rules` y su suite de pruebas contra el
  Firebase Emulator Suite (Fase 4) surgieron tres decisiones concretas no cubiertas por un ADR
  previo.
  1. `firebase-tools` 15.25.0 (versión ya registrada en `docs/DEPENDENCY_POLICY.md`) exige
     Java 21 para el emulador de Firestore; el entorno de Gradle solo tenía JDK 17.
     La ruta local del JDK no forma parte del repositorio.
  2. La suite de pruebas de reglas necesitaba un motor de ejecución (Mocha, Jest, Vitest, o el
     runner nativo de Node).
  3. El revisor independiente `firebase-rules-reviewer` encontró que `firestore.rules` exigía
     monotonía de `cryptoVersion`/`schemaVersion` en el `update` de la bóveda, pero no en el
     `update` de un ítem, una asimetría sin justificación de diseño que permitía revertir un
     ítem a una versión criptográfica antigua bajo una `revision` artificialmente alta.
- **Decisión:**
  1. Se instaló un JDK 21 (Temurin 21.0.12+8, verificado por checksum SHA-256 contra el
     publicado por Adoptium) en una ruta local no versionada, **sin** modificar el
     `JAVA_HOME` global ni el usado por Gradle. El comando `npm test` de `firebase/` documenta en
     `FIREBASE_SETUP.md` cómo apuntar `JAVA_HOME`/`PATH` a ese JDK solo para esa invocación.
  2. Las pruebas de reglas usan el runner nativo de Node (`node --test`), sin añadir Mocha ni
     Jest como dependencia nueva: Node 22 ya lo trae, y `docs/DEPENDENCY_POLICY.md` §1 prioriza
     minimizar dependencias en el camino de pruebas. Se usa el patrón de archivo `*.test.js` y
     glob explícito (`test/*.test.js`) porque `node --test <directorio>` sin glob no resolvía el
     directorio como raíz de búsqueda en Node 22.13.0 (comportamiento verificado empíricamente,
     no documentado como tal en los release notes consultados).
  3. `firestore.rules` se corrige para que el `update` de un ítem exija también
     `cryptoVersion >= anterior` y `schemaVersion >= anterior`, igual que ya exigía el `update`
     de la bóveda. `docs/architecture.md` §6 se actualiza para que el diseño documentado
     coincida exactamente con la regla implementada.
- **Alternativas consideradas:** para (1), bajar `firebase-tools` a una serie 14.x que todavía
  tolera Java 17 (rechazada por el propietario: es una versión que dejará de recibir soporte y
  solo pospone el problema); no ejecutar el emulador y dejarlo como bloqueo externo (rechazada:
  el propietario prefirió resolverlo ya). Para (3), dejar la asimetría documentada como riesgo
  residual en `THREAT_MODEL.md` en lugar de corregirla (rechazada: el costo de la regla es
  trivial y el diseño ya establecía el patrón correcto para la bóveda).
- **Consecuencias:** la máquina de desarrollo ahora tiene dos JDK independientes (17 para Gradle,
  21 para el Emulator Suite); cualquier sesión futura que ejecute `firebase/npm test` debe fijar
  `JAVA_HOME` explícitamente, ya que el JDK 21 no es el predeterminado del sistema. La suite de
  reglas quedó en 39 pruebas verdes tras la corrección, con evidencia de mutación real (se
  debilitó `isOwner()` y 5 pruebas de aislamiento fallaron; se debilitó la monotonía de versión
  del ítem antes de corregirla y la prueba nueva falló como se esperaba).
- **Estrategia de migración:** si `firebase-tools` eleva de nuevo el mínimo de Java, repetir este
  mismo procedimiento con el JDK correspondiente. Si el proyecto real de Firebase requiere CI, ese
  entorno debe declarar JDK 21+ como requisito explícito para el paso de Security Rules.

---

## ADR-037 — Fase 4: `:data:remote` real, App Check por variante y verificación desde el dispositivo físico

- **Fecha:** 2026-07-31
- **Estado:** modificada por ADR-048
- **Contexto:** con `firestore.rules` ya cerrado (ADR-036), faltaba implementar
  `FirebaseAuthSource`/`FirestoreVaultSource` reales, aplicar el plugin `google-services` y
  conectar los emuladores solo en depuración, según `FIREBASE_SETUP.md`.
  Surgieron seis decisiones concretas no cubiertas por un ADR previo.
- **Decisión:**
  1. **`uid` resuelto internamente, nunca como parámetro.** `FirestoreVaultSourceImpl` lee
     `FirebaseAuth.currentUser?.uid` para construir `users/{uid}/vaults/{vaultId}/...`
     (`error(...)` genérico si no hay sesión); el `uid` no se añade a la interfaz pública ni a
     ningún documento fuera de la ruta, preservando ADR-009 (la AAD sigue sin `uid`).
  2. **Sin `kotlinx-coroutines-play-services`.** El puente `Task<T> → suspend fun` se escribió a
     mano (`suspendCancellableCoroutine`, ~15 líneas, `data/remote/.../internal/TaskExtensions.kt`)
     en vez de añadir una dependencia nueva, siguiendo la política de minimizar dependencias
     (`docs/DEPENDENCY_POLICY.md` §1, T-16).
  3. **`useEmulator(...)` gobernado por `BuildConfig.DEBUG` del propio módulo `:data:remote`.**
     No existe wiring de Hilt todavía en el repositorio (ningún módulo lo usa aún pese a estar
     declarado desde la Fase 1), así que introducir inyección de dependencias solo para elegir
     host de emulador habría sido alcance fuera de esta fase. Se habilitó
     `buildFeatures.buildConfig = true` en `:data:remote` (antes `false` por convención) y
     `EmulatorConfig` (interno) llama `useEmulator("10.0.2.2", puerto)` una sola vez por proceso,
     solo si `BuildConfig.DEBUG`, igual que `SecureLogger.init(production = !BuildConfig.DEBUG)`
     ya hacía en `:app`. Acepta un `host` opcional (por defecto `"10.0.2.2"`) para que la prueba
     instrumentada pueda apuntar a `"localhost"` sin tocar el valor de producción.
  4. **`com.google.gms.google-services` aplicado condicionalmente en `:app`.** `if
     (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")`,
     para que `assembleDebug` compile en este entorno, que solo tiene
     `google-services.json.example` (B-01). Se añadió `BuildConfig.HAS_GOOGLE_SERVICES` para que
     el código de `:app` sepa si hay un `FirebaseApp` por defecto real antes de tocar cualquier
     API de Firebase.
  5. **App Check por variante, mismo nombre de clase.** `firebase-appcheck-debug` es
     `debugImplementation`; referenciarlo desde `BovedaWilsonApp.kt` (compartido) rompía
     `compileReleaseKotlin` (comprobado: el primer intento falló así). Se creó
     `AppCheckInitializer` con una implementación en `app/src/debug/kotlin/...` (instala
     `DebugAppCheckProviderFactory` si `BuildConfig.HAS_GOOGLE_SERVICES`) y otra en
     `app/src/release/kotlin/...` (no-op) con el mismo nombre y paquete. La garantía de que App
     Check nunca entra al binario de release la da el compilador (el archivo de debug no existe
     en el classpath de release), no un `if` en tiempo de ejecución. Play Integrity sigue como
     bloqueo externo B-02: no hay proveedor de App Check instalado en release, así que la
     garantía de App Check en producción sigue siendo cero hasta que se resuelva.
  6. **Verificación real contra el Emulator Suite desde el dispositivo físico, no un doble.**
     Se añadió `data/remote/src/androidTest/.../FirebaseRemoteEmulatorTest.kt`, ejecutada de
     verdad (3/3 verde) contra `firebase emulators:start --project demo-boveda-wilson-public --only
     auth,firestore` (JDK 21, ADR-036) usando un `FirebaseApp` secundario con
     `projectId = "demo-boveda-wilson-public"` (mismo `.firebaserc`) y `FIXTURE_APP_ID`/`FIXTURE_API_KEY`
     explícitamente ficticios — el prefijo `demo-` hace que el SDK de Firebase ignore cualquier
     credencial real y hable solo con el emulador. Como el dispositivo conectado es físico (no un
     AVD), `"10.0.2.2"` no resuelve; se usó `adb reverse tcp:9099 tcp:9099` /
     `tcp:8080 tcp:8080` + host `"localhost"` (punto 3). El APK de pruebas de `:data:remote` no
     hereda el NSC de `:app` (es un módulo sin `Application`, un paquete de prueba
     independiente): se le añadió su propio
     `data/remote/src/androidTest/res/xml/network_security_config.xml` con la misma excepción ya
     acotada en `app/src/debug` (`localhost`/`10.0.2.2`), sin tocar el NSC real de `:app`.
- **Alternativas consideradas:** para (3), introducir Hilt ya para inyectar el host de emulador
  (rechazada: ningún otro módulo lo usa todavía, habría sido una dependencia nueva de arquitectura
  fuera del encargo de esta fase); para (5), un único `if (BuildConfig.DEBUG)` en código
  compartido (rechazada: no compila, `firebase-appcheck-debug` no está en el classpath de
  release — el error real de `compileReleaseKotlin` lo confirmó); para (6), no verificar contra el
  emulador real y declararlo como bloqueo (rechazada: el dispositivo físico y el Emulator Suite
  con JDK 21 ya estaban disponibles de la Fase 2/4 anterior, así que la verificación real era
  alcanzable sin bloqueo externo).
- **Consecuencias:** `:data:remote` compila y se prueba de verdad contra Auth y Firestore reales
  (emulados). Google Sign-In de extremo a extremo y App Check con Play Integrity siguen sin
  poder verificarse porque exigen un proyecto real (B-01/B-02, sin cambios). Probar desde el
  dispositivo físico exige `adb reverse` cada vez que se repita esta prueba; un AVD de Android
  Studio con el host `"10.0.2.2"` por defecto no necesitaría ese paso.
- **Estrategia de migración:** al existir `google-services.json` real, el plugin se activa solo
  con colocar el archivo, sin tocar `build.gradle.kts`. Activar Play Integrity exige registrar la
  app en Play Console y añadir un proveedor de release a `AppCheckInitializer` (`src/release`),
  sin tocar la implementación de debug.

**Correcciones tras la revisión independiente (2026-07-31), `android-architect` y
`security-architect` en paralelo:**

  7. **`GenericIdpActivity` excluida del manifiesto; `RecaptchaActivity` y
     `RevocationBoundService` documentadas y conservadas (hallazgo ALTO de
     `security-architect`).** Al consumir `firebase-auth` real por primera vez, el manifiesto
     fusionado ganó tres componentes `exported="true"` sin declarar ni justificar, repitiendo
     exactamente el patrón ya corregido como N-2 en la Fase 1. Se evaluó cada uno por separado:
     `GenericIdpActivity` (sin permiso, esquema `genericidp://`) es del flujo de `OAuthProvider`
     genérico (proveedores federados no-Google), que este diseño no implementa (`SECURITY.md`:
     solo Google Sign-In y correo/contraseña) — se excluye con `tools:node="remove"` en
     `app/src/main/AndroidManifest.xml`, mismo patrón que `ProfileInstallReceiver` (N-2).
     `RecaptchaActivity` (sin permiso, esquema `recaptcha://`) sí corresponde al flujo de
     correo/contraseña implementado (verificación anti-abuso interna del SDK) y se conserva,
     documentada. `RevocationBoundService` es requerida por cualquier app con Google Sign-In (que
     este diseño sí usa) y ya está protegida por el permiso de firma
     `com.google.android.gms.auth.api.signin.permission.REVOCATION_NOTIFICATION`, exclusivo de
     Google Play Services; se conserva, documentada. El permiso transitivo `READ_GSERVICES` (lee
     configuración de Google Services Framework, no datos de usuario) no requiere exclusión.
     Verificado tras el cambio: `grep 'exported="true"' AndroidManifest.xml` fusionado de release
     devuelve exactamente `MainActivity`, `RecaptchaActivity` y `RevocationBoundService` (esta
     última con permiso).
     **Actualización 2026-08-10 (ADR-048):** al materializar WorkManager, el manifiesto release
     contiene además `SystemJobService`, exportado y protegido por
     `android.permission.BIND_JOB_SERVICE`. `DiagnosticsReceiver` se excluye porque no se necesita
     en producción. El inventario vigente es, por tanto, cuatro componentes: launcher, dos piezas
     obligatorias de Google/Firebase y el servicio protegido de WorkManager; no “solo launcher”.
  8. **Límite de 256 KiB de `ciphertext` aplicado también en el cliente (hallazgo MEDIO de
     `android-architect`).** `docs/architecture.md` §5 prometía la comprobación «en las reglas y
     también en el cliente antes de subir»; `FirestoreVaultSourceImpl.uploadItem` no la tenía.
     Se añadió `check(ciphertext.bytes.size <= 262_144)` antes de tocar la red, con el mismo
     límite que exige `firestore.rules`.
  9. **Pruebas de higiene G-74/G-75 (hallazgo ALTO compartido por ambos revisores).**
     `docs/architecture.md` §3 y ADR-033 (punto 5) afirmaban la existencia de «pruebas
     estructurales» que confinaban `Ciphertext.fromPersisted` a los mappers internos de
     `:data:local`/`:data:remote` y mantenían `Aead`/`KeysetHandle` dentro de `:core:crypto` —
     pruebas que nunca se escribieron, desde la Fase 1 (2026-07-29). Se añadieron
     `RepositoryHygieneTest.kt` `G-74` (confina `fromPersisted` a los mappers internos y a
     pruebas) y `G-75` (confina `Aead`/`KeysetHandle` a los seis archivos internos de
     `:core:crypto` que ya los envuelven: `KekImporter`, `UnlockedVault`, `ItemCryptor`,
     `VdekWrapper`, `VaultWrapping`, `VdekFactory`). Ambas verificadas por mutación real: una
     sonda temporal en `:data:sync` que llamaba `fromPersisted`/importaba `Aead` hizo fallar cada
     prueba; se revirtió y volvieron a verde.
  10. **`app/proguard-rules.pro` corregido (hallazgo BAJO de `android-architect`).** El comentario
      que anticipaba una regla `-keep` para DTO de Firestore deserializados por reflexión ya no
      aplica: `FirestoreVaultSourceImpl` lee cada campo a mano (`getLong`/`getString`/`getBlob`),
      sin `DocumentSnapshot.toObject(...)`. Se corrigió el comentario y se dejó constancia de que
      `assembleRelease` con `firebase-auth`/`firebase-firestore` ya en el grafo no generó
      `missing_rules.txt`.
  11. **`THREAT_MODEL.md` §6 ampliado (hallazgo BAJO de `security-architect`).** Se añadió el
      punto 12: App Check solo tiene proveedor de depuración; en release su garantía es cero
      hasta que se resuelva B-02 (Play Integrity).
- **Hallazgos de la revisión no corregidos, con motivo:** la afirmación de que `:data:sync`
  prohíbe serializers de dominio no tiene tampoco una prueba dedicada (parte de la misma frase de
  `docs/architecture.md` §3 que motivó G-74/G-75); se deja registrado como riesgo abierto (ver
  `PROJECT_STATE.md` §9) en vez de escribir una tercera prueba de higiene sin acotar primero su
  alcance exacto, para no ampliar el diff de esta fase con una prueba mal especificada.

---

## ADR-038 — Directorios de build fuera del árbol sincronizado por OneDrive

- **Fecha:** 2026-07-31
- **Estado:** aceptada
- **Contexto:** el repositorio se encontraba dentro de una carpeta sincronizada.
  El cliente de sincronización abre los archivos recién escritos para sincronizarlos y Gradle falla con
  `FileSystemException: el proceso no tiene acceso al archivo porque está siendo utilizado
  por otro proceso` justo cuando reemplaza un `.jar` intermedio. Es el riesgo R-01, que se
  volvió bloqueante real en la Fase 7: `:core:crypto:bundleLibCompileToJarDebug` falló de
  forma reproducible. `.gitignore` ya excluía `build/` del control de versiones, pero eso
  no impide que OneDrive los sincronice.
- **Decisión:** `settings.gradle.kts` reubica `layout.buildDirectory` de todos los módulos
  a `%LOCALAPPDATA%\BovedaWilson\build\<módulo>`, fuera del árbol sincronizado. La ruta se
  puede sobrescribir con la variable de entorno `BOVEDA_BUILD_DIR` o la propiedad de Gradle
  `buildDirRoot`; si no hay `LOCALAPPDATA` (por ejemplo en CI Linux) no se reubica nada y
  se conserva el comportamiento por defecto.
- **Alternativas consideradas:**
  - Pedir al propietario excluir la carpeta de OneDrive a mano: depende de una acción
    manual que se pierde al reinstalar o al cambiar de máquina, y no protege a nadie más.
  - Reintentar la tarea de Gradle hasta que OneDrive suelte el archivo: oculta la carrera
    en vez de eliminarla y produce compilaciones intermitentes.
  - Mover el repositorio completo fuera de OneDrive: es la solución más limpia, pero es
    decisión del propietario sobre dónde guarda sus documentos, no del proyecto.
- **Consecuencias:** las rutas de artefactos citadas en `PROJECT_STATE.md` (`app/build/...`)
  dejan de ser válidas; ahora cuelgan de `%LOCALAPPDATA%\BovedaWilson\build\app\...`. Los
  informes de lint y Detekt también se mueven. A cambio, R-01 deja de existir como bloqueo
  recurrente y las compilaciones son reproducibles sin intervención humana. Nada versionado
  cambia de sitio.
- **Estrategia de migración:** eliminar el bloque de `settings.gradle.kts` si el repositorio
  deja de estar dentro de OneDrive.

---

## ADR-039 — Hilt fijado a 2.57.2 por compatibilidad con AGP 8.13.1

- **Fecha:** 2026-07-31
- **Estado:** aceptada
- **Contexto:** el catálogo declaraba `hilt = "2.60.1"` desde la Fase 1, pero el plugin de
  Hilt nunca se había aplicado a ningún módulo, así que la incompatibilidad no se había
  manifestado. Al cablear la inyección de dependencias de verdad en la Fase 7, el plugin
  falló al aplicarse: «The Hilt Android Gradle plugin is only compatible with Android
  Gradle plugin (AGP) version 9.0.0 or higher (found Android Gradle Plugin version
  8.13.1)». Es un ejemplo directo de por qué una dependencia declarada y no usada no
  cuenta como verificada.
- **Decisión:** fijar `hilt = "2.57.2"`, versión publicada en Maven Central (verificada
  contra `maven-metadata.xml` real) y compatible con AGP 8.x. `hiltExt` (androidx.hilt) se
  mantiene en 1.3.0, también estable y publicada.
- **Alternativas consideradas:**
  - Subir AGP a 9.0: arrastra un salto mayor de toda la cadena de herramientas (Gradle,
    Kotlin, KSP, Room, Compose BOM) en medio de la fase de interfaz. Riesgo desproporcionado
    para el objetivo de esta fase.
  - Renunciar a Hilt e inyectar a mano: obligaría a reescribir `SyncModule`, `SyncWorker`
    con `@HiltWorker` y los cuatro ViewModels, que ya estaban escritos contra Hilt.
- **Consecuencias:** la inyección funciona con la cadena de herramientas actual. Queda
  anotado en el catálogo que al subir AGP hay que revisar `hilt` y `hiltExt` juntos.
- **Estrategia de migración:** cuando se suba a AGP 9, subir `hilt` a la línea 2.60+ en el
  mismo commit y volver a ejecutar `assembleDebug` y `assembleRelease`.

---

## ADR-040 — Fuente remota sin red cuando no hay proyecto Firebase configurado

- **Fecha:** 2026-07-31
- **Estado:** aceptada
- **Contexto:** `FirestoreVaultSourceImpl` llama a `FirebaseFirestore.getInstance()` en sus
  argumentos por defecto. Sin `google-services.json` no existe `FirebaseApp` por defecto y
  esa llamada lanza. Al construirse dentro del grafo de Hilt, la excepción ocurre al
  inyectar `ItemRepository`, es decir en el arranque de la primera pantalla: la aplicación
  no llegaría a mostrarse. El bloqueo externo B-01 sigue abierto (no hay proyecto real).
- **Decisión:** `RemoteModule` comprueba `FirebaseApp.getApps(context).isEmpty()` antes de
  instanciar. Si no hay proyecto configurado entrega `OfflineFirestoreVaultSource`, que
  lanza `RemoteUnavailableException("remote_not_configured")` en cada operación. La bóveda
  funciona local-first: crear, desbloquear, leer y escribir notas no tocan la red.
- **Alternativas consideradas:**
  - Devolver listas vacías y éxitos silenciosos: el motor de sincronización interpretaría
    «el servidor no tiene nada» y podría enterrar el estado remoto real de un usuario que
    sí lo tenga. Falla abierto en lugar de cerrado.
  - Inyectar `dagger.Lazy<FirestoreVaultSource>`: pospone el fallo pero no lo describe, y
    obliga a cambiar las firmas de `ItemRepository` y `SyncEngine`.
- **Consecuencias:** la aplicación arranca y es usable sin Firebase. La sincronización está
  inhabilitada y lo dice de forma explícita en vez de fingir. Al colocar un
  `google-services.json` real, el mismo proveedor entrega la implementación de red sin
  ningún otro cambio.
- **Estrategia de migración:** cuando exista proyecto real y App Check con Play Integrity
  (B-02), esta rama sigue siendo válida como camino de degradación; conviene añadir una
  prueba instrumentada que verifique que con `FirebaseApp` presente se entrega la
  implementación real.

---

## ADR-041 — Proyecto Firebase real: región de Firestore y servicios desactivados

- **Fecha:** 2026-07-31
- **Estado:** aceptada
- **Contexto:** el bloqueo externo B-01 (no existía proyecto real de Firebase) impedía
  verificar el camino remoto de extremo a extremo. El propietario autorizó explícitamente
  la creación controlada del proyecto desde la consola web. La autorización fue
  específica para esa operación y no amplió el alcance de otras acciones externas.
  La infraestructura continúa sujeta a control de cambios y autorización expresa.
- **Decisión:** se creó el proyecto `boveda-wilson` (plan Spark) con estas elecciones:
  - **Google Analytics desactivado.** No es una preferencia: la política de `SECURITY.md` lo prohíbe.
  - **Gemini en Firebase desactivado.** Es un asistente de consola con acceso a los datos
    del proyecto; superficie innecesaria para una bóveda de conocimiento cero. Reversible.
  - **Firestore edición Standard**, porque el diseño solo usa consultas simples.
  - **Firestore en `southamerica-west1` (Santiago)**, decisión del propietario entre las
    alternativas ofrecidas. Es **irreversible**.
  - **Reglas iniciales en modo producción** (`allow read, write: if false`), nunca en modo
    de prueba, según `SECURITY.md`.
  - **Authentication solo con correo/contraseña**; el acceso por enlace sin contraseña
    queda desactivado por no estar en el diseño.
- **Alternativas consideradas:**
  - `nam5` (EE.UU.), la opción por defecto: multirregional y con mejor disponibilidad, pero
    más latencia desde Chile y metadatos bajo jurisdicción estadounidense.
  - `eur3` (UE): metadatos bajo el RGPD, pero la peor latencia de las tres.
  - Dejar que el propietario creara el proyecto a mano: más lento y sin garantía de que las
    opciones prohibidas por `SECURITY.md` quedaran desactivadas.
- **Consecuencias:** existe un proyecto real contra el que probar. Firestore es regional,
  no multirregional: menor disponibilidad teórica, suficiente para una bóveda personal, y
  **no se puede cambiar**. Con `app/google-services.json` presente el plugin
  `com.google.gms.google-services` pasa a aplicarse y `RemoteModule` entrega el cliente
  real en vez de la variante sin red (ADR-040); verificado que la aplicación sigue
  compilando y arrancando. En compilaciones `debug`, `EmulatorConfig` sigue apuntando al
  Emulator Suite (ADR-037), así que un APK de depuración **no habla con el proyecto real**.
- **Estrategia de migración:** cambiar de región exigiría crear un proyecto nuevo y migrar
  los documentos; como todo el contenido es ciphertext y el cliente es local-first, la
  migración se reduce a volver a subir desde un cliente desbloqueado. Reactivar Gemini o
  Analytics exigiría una nueva decisión de seguridad; actualmente permanece prohibido.

---

## ADR-042 — Frontera biométrica Android fuera del núcleo criptográfico

- **Fecha:** 2026-08-01
- **Estado:** aceptada
- **Contexto:** `BiometricUnlock` estaba en `:core:crypto` y hacía depender el núcleo de APIs de
  AndroidX/UI. La Fase 7 necesita coordinar `BiometricPrompt`, `CryptoObject`, Android Keystore,
  almacenamiento local del envoltorio y `VaultSession`, sin introducir esa dependencia en las
  primitivas criptográficas.
- **Decisión:** mover la implementación Android a `:data:sync/biometric`; mantener en
  `:core:crypto` solo `BiometricWrap` y operaciones de envoltorio opacas. La clave usa alias
  versionado `boveda_wilson_biometric_kek_v1`, AES-256, autenticación por operación con
  `BIOMETRIC_STRONG`, invalidación por cambio de inscripción y StrongBox con fallback a TEE.
  Cualquier cancelación/error de inscripción elimina la clave preparada y el registro local.
- **Alternativas consideradas:** conservar AndroidX en `:core:crypto` (rompe la frontera de
  módulos) o envolver directamente la VDEK con una API propia (duplicaría criptografía y viola el
  uso obligatorio de Tink para datos).
- **Consecuencias:** el núcleo vuelve a ser independiente de la UI; la integración real exige
  pruebas instrumentadas y presencia física. Un dispositivo sin biometría fuerte continúa por
  contraseña maestra.
- **Estrategia de migración:** un cambio futuro de configuración usa un alias `_v2`; se elimina
  el alias anterior solo después de crear y verificar el nuevo envoltorio.

---

## ADR-043 — Creación atómica condicionada a verificar la frase de recuperación

- **Fecha:** 2026-08-01
- **Estado:** aceptada
- **Contexto:** persistir `VaultMetaEntity` antes de comprobar que el usuario conservó la frase
  permitía dejar una bóveda sin recuperación verificada. Además, guardar el estado sensible en un
  ViewModel durante cambios de ciclo de vida ampliaba su tiempo de residencia.
- **Decisión:** `VaultCreationRepository` prepara VDEK, envoltorios y frase únicamente en memoria.
  La interfaz selecciona con `SecureRandom` tres posiciones distintas y solo llama a `commit`
  tras verificarlas. Cancelación, navegación, `ON_STOP` o error descartan y limpian el pendiente;
  una muerte de proceso previa al commit no deja metadata de bóveda.
- **Alternativas consideradas:** persistir y luego borrar si falla (deja ventanas y artefactos en
  WAL) o permitir continuar sin desafío (no demuestra que exista recuperación).
- **Consecuencias:** crear una bóveda requiere un paso adicional y falla cerrado. El flujo aún
  necesita una prueba instrumentada de muerte/restauración antes de cerrar Fase 7.
- **Estrategia de migración:** no altera formatos persistidos; las bóvedas existentes permanecen
  válidas. Si el desafío cambia, solo cambia el estado transitorio de UI.

---

## ADR-044 — Entrada de contraseñas maestras con búfer limpiable

- **Fecha:** 2026-08-01
- **Estado:** aceptada
- **Contexto:** los campos Compose basados en `String` crean copias inmutables de la contraseña en
  estado, recomposición y transformaciones visuales, contrarias al contrato de memoria del
  proyecto.
- **Decisión:** usar `SecurePasswordField`, un `AndroidView(EditText)` respaldado por
  `SecurePasswordState` y un `CharArray` reemplazable/limpiable. Deshabilitar autofill,
  aprendizaje personalizado, extracción de texto y guardado de estado. La operación consume una
  copia `CharArray` y limpia tanto la copia como el campo. La frase de recuperación se captura en
  24 campos separados y nunca se concatena en UI.
- **Alternativas consideradas:** `OutlinedTextField(String)` (no limpiable) o un campo Compose
  personalizado que siga usando `TextFieldValue`/`AnnotatedString` (mantiene copias inmutables).
- **Consecuencias:** se reduce la residencia y serialización accidental, aunque la plataforma/IME
  puede crear copias fuera del control del proceso; esto forma parte del riesgo residual del
  dispositivo comprometido. Firebase Auth exige finalmente un `String`, confinado a la duración
  de la llamada SDK.
- **Estrategia de migración:** si Compose ofrece una API oficial con almacenamiento mutable y
  garantías equivalentes, reemplazar el `AndroidView` tras pruebas de fuga y lifecycle.

---

## ADR-045 — Respaldo ciphertext-only con restauración local transaccional

- **Fecha:** 2026-08-01
- **Estado:** aceptada
- **Contexto:** la Fase 7 necesita exportar y restaurar una bóveda sin introducir una capa que
  pueda leer plaintext ni aplicar parcialmente un archivo malformado. El formato contractual
  está definido en `BACKUP_FORMAT.md`, pero el código inicial era solo un placeholder.
- **Decisión:** implementar el respaldo como JSON estricto de ciphertext, metadatos públicos y
  ambos envoltorios de VDEK. La exportación valida y autentica antes de abrir/escribir el destino.
  La restauración valida y desenvuelve antes de modificar Room; reemite ambos envoltorios con la
  misma VDEK, incrementa sus epochs y bloquea la sesión antes de la transacción local. La
  publicación remota queda separada del commit local, exige una acción explícita y se rige por
  el protocolo de conflictos y autorización efímera de ADR-047.
- **Alternativas consideradas:** escribir directamente mientras se parsea (permite estados
  parciales), reutilizar un único envoltorio (rompe la independencia de las vías de acceso) o
  publicar automáticamente tras restaurar (amplía el alcance y el riesgo de conflictos remotos).
- **Consecuencias:** el archivo es portable y no depende de Firebase, pero una restauración local
  requiere volver a autenticar una vía y proporcionar la otra credencial cuando corresponda.
  La sesión queda bloqueada tras importar y el usuario debe desbloquearla de nuevo.
- **Estrategia de migración:** cualquier cambio de formato incrementa `formatVersion` y conserva
  el rechazo cerrado de versiones desconocidas; la implementación futura de publicación remota
  deberá aplicar el protocolo de conflictos descrito en `BACKUP_FORMAT.md` antes de escribir en
  Firestore; ADR-047 implementa esa publicación con CAS y rechazo cerrado ante divergencias.

---

## ADR-046 — Parser de respaldo rechaza claves JSON duplicadas y UTF-8 inválido

- **Fecha:** 2026-08-01
- **Estado:** aceptada
- **Contexto:** el parser del respaldo usaba un `JsonObject`, cuya colección de claves no
  conserva duplicados: una entrada repetida podía ser reemplazada por la última antes de la
  validación del conjunto estricto. Además, el constructor de `String` podía sustituir bytes
  UTF-8 inválidos en vez de rechazar el archivo.
- **Decisión:** escanear el documento antes de entregarlo a kotlinx.serialization, con un límite
  de anidamiento de 128 niveles, decodificación de escapes de nombres y rechazo de nombres
  repetidos semánticamente. Decodificar los bytes con un `CharsetDecoder` configurado para
  reportar secuencias malformadas o no mapeables.
- **Alternativas consideradas:** confiar solo en `ignoreUnknownKeys = false` (no detecta
  duplicados ya consolidados) o aceptar reemplazos UTF-8 (rompe el contrato del formato).
- **Consecuencias:** el importador falla cerrado antes del parser estructural y evita una clase de
  ambigüedad de interpretación; el escáner añade una pasada lineal y rechaza JSON excesivamente
  anidado antes de provocar presión de pila.
- **Estrategia de migración:** el cambio no altera archivos válidos de la versión 1. Si una futura
  versión admite otra sintaxis, debe definir explícitamente su regla de duplicados y actualizar
  el límite de profundidad dentro de su propio `formatVersion`.

---

## ADR-047 — Publicación de respaldo con CAS y autorización efímera verificable

- **Fecha:** 2026-08-01
- **Estado:** aceptada
- **Contexto:** publicar una restauración sobre Firestore puede sobrescribir cambios concurrentes.
  Además, conservar una `UnlockedVault` para autorizar una publicación posterior extendería
  innecesariamente la vida de la VDEK, y abrir un `OutputStream` SAF antes de validar puede truncar
  el destino aunque la operación sea rechazada.
- **Decisión:** comparar el snapshot remoto completo y usar compare-and-set transaccional para
  metadata e ítems. La autorización de publicación es de un solo uso, solo en memoria y ligada a
  `uid`, `vaultId`, generación de sesión y hash SHA-256 de la línea base. La identidad de VDEK se
  demuestra mediante un reto AEAD con AAD versionada; la capacidad no retiene `UnlockedVault`, VDEK
  ni credenciales. La exportación difiere la apertura del destino hasta validar límites,
  reautenticar y volver a comprobar la misma sesión. El pull de metadata local también usa CAS
  dentro de una transacción Room.
- **Alternativas consideradas:** escrituras ciegas (pierden cambios), retener la sesión abierta
  (amplía la residencia de clave), o abrir SAF antes de autenticar (puede modificar el destino ante
  un rechazo).
- **Consecuencias:** cualquier divergencia aborta sin sobrescribir; una autorización expira al
  consumirse, bloquear/cambiar de sesión o cambiar el snapshot. La creación remota ausente se
  reanuda de forma idempotente. Firebase real permanece fuera del entorno de pruebas.
- **Estrategia de migración:** una futura versión del protocolo deberá versionar el reto/AAD y
  mantener rechazo cerrado de versiones desconocidas; nunca se migra mediante escritura ciega.

---

## ADR-048 — Release fail-closed, firma externa y Firebase para distribución por Drive

- **Fecha:** 2026-08-10
- **Estado:** aceptada
- **Contexto:** el build anterior podía generar `app-release-unsigned.apk` y también compilar sin
  `google-services.json`, produciendo una aplicación local que parecía una entrega válida pero no
  podía iniciar sesión con Google. El proyecto vive dentro de OneDrive, por lo que guardar allí
  contraseñas de firma ampliaría su exposición. La distribución prevista es una APK descargada
  desde Drive, no una publicación exclusiva en Google Play.
- **Decisión:** las tareas `packageRelease` y `bundleRelease` fallan si no existe una configuración
  de firma externa, un almacén externo, `app/google-services.json` o el recurso generado
  `default_web_client_id`. La ruta de propiedades se entrega mediante
  `boveda.signingProperties` o `BOVEDA_SIGNING_PROPERTIES`; tanto ella como el `.jks` deben estar
  fuera del repositorio y de carpetas OneDrive detectadas. Nunca se usa la clave debug como
  fallback. Release instala el proveedor Play Integrity, pero App Check no se impone hasta
  registrar SHA-256, observar tokens válidos y configurar la aplicación como distribuida fuera de
  Play. El usuario final recibe solo la APK ya firmada; nunca manipula archivos Firebase.
- **Alternativas consideradas:** distribuir el APK debug (rechazada: es `debuggable`, usa una clave
  pública de desarrollo y expone el proveedor App Check debug); permitir release local-only
  (rechazada: fallo silencioso); guardar `signing.properties` ignorado dentro del repositorio
  (rechazada: OneDrive puede sincronizar contraseñas); exigir Play Store para el MVP (rechazada:
  contradice el canal Drive solicitado y no es necesario para una APK firmada).
- **Consecuencias:** un clon público puede compilar y probar debug contra Emulator Suite, pero no
  puede producir por accidente una entrega conectada al proyecto del propietario. El propietario
  debe custodiar y respaldar la clave release para todas las actualizaciones. App Check queda como
  riesgo residual explícito mientras B-02 no se cierre; las Security Rules y el cifrado local no
  dependen de él.
- **Estrategia de migración:** para Google Play se conserva la misma identidad y se evalúa Play App
  Signing sin perder la capacidad de actualizar instalaciones existentes. Cualquier cambio de
  clave o canal requiere registrar sus huellas en Firebase, incrementar `versionCode`, regenerar la
  configuración y repetir la puerta de instalación limpia.

---

## ADR-049 — Respaldo acotado a 8 MiB con preflight antes de cargar blobs

- **Fecha:** 2026-08-10
- **Estado:** aceptada (endurece ADR-026)
- **Contexto:** el límite anterior de 64 MiB se aplicaba después de cargar todos los ciphertext,
  copiarlos, expandirlos a Base64 y construir el árbol JSON. Los límites individuales permitían
  intentar materializar más de 1 GiB y la importación amplificaba un archivo hostil varias veces
  dentro del heap Android.
- **Decisión:** limitar el archivo v1 a 8 MiB y, dentro de una única transacción Room, leer los
  metadatos, consultar `COUNT(*)` y `SUM(LENGTH(ciphertext))`, cargar las filas, recalcular su
  tamaño real y construir un snapshot coherente. Una cota
  conservadora de Base64, cabecera y metadata por ítem debe pasar antes de conservar los blobs o
  abrir el destino. En importación, antes del parser DOM un escáner limita profundidad, miembros
  de objeto, elementos de array y cantidad global de valores.
- **Alternativas consideradas:** conservar 64 MiB (rechazada: no está demostrado seguro con el
  parser en memoria) y streaming JSON inmediato (aplazado: aumenta la complejidad del parser MVP).
- **Consecuencias:** se reduce el tamaño máximo exportable a cambio de una cota de memoria
  verificable. Las pruebas instrumentadas fuerzan el rechazo previo con ciphertext válidos
  grandes, confirman cero aperturas/escrituras del destino y rechazan una entrada compacta hostil
  cercana a 8 MiB sin construir el DOM.
- **Estrategia de migración:** el formato sigue siendo v1; respaldos válidos de hasta 8 MiB no
  cambian. Ampliar el límite requiere parser streaming, medición en el dispositivo mínimo y un ADR.

---

## ADR-050 — Nivel hardware verificado y bloqueo transitorio sin degradación

- **Fecha:** 2026-08-10
- **Estado:** aceptada
- **Contexto:** dos ejecuciones biométricas en el Samsung SM-S938B/API 36 fallaron con
  `InvalidKeyException`. Logcat y el estado del sistema demostraron `DEVICE_LOCKED`/Doze, no una
  incompatibilidad StrongBox. Además, omitir `setIsStrongBoxBacked(true)` solicita el Keystore por
  defecto, pero no demuestra por sí solo que la clave termine en TEE.
- **Decisión:** registrar únicamente el tipo de excepción mediante `Redact.type`; no degradar ni
  regenerar por un fallo de operación. StrongBox cae a TEE solo cuando `generateKey()` informa
  `StrongBoxUnavailableException`. Tras cada generación/lectura se inspecciona `KeyInfo` y se
  acepta únicamente `STRONGBOX` o `TRUSTED_ENVIRONMENT`; software/unknown borra el alias y falla
  cerrado. Si el dispositivo está bloqueado, preparar desbloqueo devuelve `null` sin borrar el
  enrolamiento. Las pruebas exigen pantalla despierta/desbloqueada y usan alias aislado.
- **Alternativas consideradas:** reintento o downgrade ante cualquier `InvalidKeyException`
  (rechazado: confunde bloqueo con fallo hardware), desactivar StrongBox siempre (rechazado) o
  aceptar un nivel software no verificado (rechazado).
- **Consecuencias:** se conserva StrongBox cuando funciona, TEE queda como fallback explícito de
  disponibilidad y un bloqueo normal del teléfono no destruye el atajo biométrico.
- **Estrategia de migración:** el blob local registra el nivel observado mediante
  `strongBoxBacked`; no cambia el formato ni la AAD. Cambiar de alias o política exige invalidar el
  enrolamiento local y pedir contraseña.

---

## ADR-051 — Respaldo v2 con manifiesto Tink y publicación reanudable

- **Fecha:** 2026-08-12
- **Estado:** aceptada (modifica ADR-016, ADR-045 y ADR-047)
- **Contexto:** una revisión independiente encontró dos riesgos altos. El formato v1 autenticaba
  cada ciphertext y los wrappers por separado, pero no el conjunto: eliminar un ítem, alterar
  activo/tombstone o mezclar ciphertext podía conservar estructuras criptográficas válidas. La
  creación remota escribía metadata final antes de terminar los ítems y consumía la autorización
  al comenzar, por lo que un corte dejaba un estado que no podía reanudarse en el mismo proceso.
  Además, el preflight SQL no medía el máximo individual y la restauración podía intercalarse con
  sincronización o una escritura local.
- **Decisión:**
  1. `formatVersion = 2` añade `manifestAuthenticator`. El manifiesto canónico contiene todos los
     campos salvo el propio autenticador y ordena ítems por `itemId`. La VDEK Tink AES-256-GCM
     cifra plaintext vacío con `bw2|backup-manifest| || canonicalManifest` como AAD. Restauración
     y autorización de publicación verifican el resultado antes de cualquier persistencia o red.
  2. v1 se rechaza como versión no soportada; las versiones futuras reciben la misma categoría y
     no se confunden con JSON malformado.
  3. La creación remota sigue `baseline exacta del respaldo → ítems create-if-absent-or-identical
     → CAS final a metadata/wrappers reemitidos`. La capacidad se consulta durante los ítems y se
     consume solo al confirmar el estado final, permitiendo reintento tras interrupción parcial.
  4. `COUNT`, `SUM(LENGTH)` y `MAX(LENGTH)` se consultan antes de cargar blobs; 256 KiB + 1 se
     rechaza en SQL. Un `SyncCoordinator` singleton serializa restauración, sync y escrituras
     locales. `BiometricUnlockEntity` permanece fuera del snapshot y tiene prueba explícita.
- **Alternativas consideradas:** firmar solo un hash SHA-256 (rechazada: un hash sin clave no
  autentica), implementar una MAC propia (prohibida y duplicaría primitivas), conservar v1 junto a
  v2 (rechazada: mantendría abierta la ruta vulnerable) y subir metadata final antes de ítems
  (rechazada: no ofrece un punto CAS inequívoco ni recuperación segura).
- **Consecuencias:** los respaldos v1 deben volver a exportarse desde una bóveda que todavía pueda
  abrirse; no se importan. El autenticador añade un ciphertext Tink pequeño y una pasada canónica
  lineal. Un corte durante la subida deja como máximo una baseline reconocible y un subconjunto
  idempotente. La prueba Room nueva compila, pero su ejecución quedó pendiente el 2026-08-12 porque
  ADB no detectó el teléfono.
- **Estrategia de migración:** una versión futura define un nuevo prefijo/AAD y un canonicalizador
  propio, conserva rechazo cerrado de versiones desconocidas y solo se incorpora tras pruebas de
  límites, manipulación y migración. No se crea un conversor v1 sin VDEK desbloqueada.
