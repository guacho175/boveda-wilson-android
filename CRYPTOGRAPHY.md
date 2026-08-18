# CRYPTOGRAPHY.md — Contrato criptográfico de Bóveda Wilson

Este documento define **exactamente** qué implementa el núcleo criptográfico. Es el contrato
que la Fase 2 debe cumplir y contra el que revisa `cryptography-reviewer`. Las decisiones y sus
motivos están en `DECISIONS.md` (ADR-004 a ADR-012).

Estado de implementación: **Fase 2 CERRADA (2026-07-31)** — `:core:crypto` implementado y probado
en la JVM (101 pruebas, 0 fallos), revisado de forma independiente por `cryptography-reviewer` y
`test-engineer` (hallazgos críticos/altos corregidos, detalle en `PROJECT_STATE.md`), y con el
benchmark real de Argon2id medido en dispositivo (§15: 1456 ms).
Ningún valor de este documento es un secreto real; los ejemplos son estructuras, no valores.

> **Revisado el 2026-07-29.** Este documento incorpora las correcciones de
> `DECISIONS.md`: ADR-020/030 (epochs independientes), ADR-021/031
> (perfil KDF cerrado y AAD normativa), ADR-022 (codificación BIP-39 fijada), ADR-026
> (verificación de ambos envoltorios), ADR-027 (`minSdk 33`) y ADR-028 (doble envoltorio biométrico).

---

## 1. Objetivo y modelo

Conocimiento cero respecto del servidor. El servidor almacena y sincroniza ciphertext y
metadatos mínimos, y no posee ninguna clave capaz de descifrarlo.

Quien puede descifrar la bóveda:

1. quien conozca la **contraseña maestra**, o
2. quien posea la **frase de recuperación de 24 palabras**, o
3. quien tenga el **dispositivo desbloqueado con biometría configurada** (solo en ese dispositivo).

Firebase Authentication **no** es una de esas vías: solo autoriza la descarga de blobs cifrados.

---

## 2. Jerarquía de claves

```
                        ┌──────────────────────────────────────────┐
  contraseña maestra ──▶│ Argon2id(salt=passwordSalt, m,t,p)       │──▶ 32 B
  (CharArray, nunca     └──────────────────────────────────────────┘      │
   se almacena)                                                           ▼
                        ┌──────────────────────────────────────────────────────────┐
                        │ HKDF-SHA-256(ikm, salt=passwordSalt,                     │
                        │              info="BovedaWilson/v1/password-kek", L=32)  │──▶ PasswordKEK
                        └──────────────────────────────────────────────────────────┘

  256 bits de entropía  ┌─────────────────────┐
  (SecureRandom)  ─────▶│ BIP-39: 24 palabras │  (codificación reversible con checksum)
        ▲               └─────────────────────┘
        │ se reconstruye al recuperar
        ▼
                        ┌──────────────────────────────────────────────────────────┐
                        │ HKDF-SHA-256(ikm=entropía, salt=recoverySalt,            │
                        │              info="BovedaWilson/v1/recovery-kek", L=32)  │──▶ RecoveryKEK
                        └──────────────────────────────────────────────────────────┘

  SecureRandom ───────────────────────────────────────────────────────────▶ BiometricKEK (32 B)
                                                                                │
  Android Keystore ────▶ clave AES/GCM no exportable, autenticación por operación
  (nunca sale del dispositivo)                    │
                                                  └── envuelve BiometricKEK
                                                      (blob + IV SOLO local)

                        ┌─────────────────────────────────────────┐
                        │ VDEK = keyset Tink, 1 clave AES256-GCM  │  generada con SecureRandom
                        └─────────────────────────────────────────┘
                            │              │              │
              PasswordKEK ──┤  RecoveryKEK─┤ BiometricKEK─┤     (tres keysets cifrados de Tink
                            ▼              ▼              ▼      de la MISMA VDEK)
                  passwordWrappedVdek  recoveryWrappedVdek  biometricWrappedVdek
                   (Firestore + Room)   (Firestore + Room)   (SOLO local, nunca sincronizado,
                                                              nunca en el respaldo)

  VDEK ──AEAD(AAD por ítem)──▶ ciphertext de cada nota  (Room + Firestore)
```

La VDEK nunca se persiste sin envolver y nunca sale del dispositivo. Las KEK son efímeras: se
derivan, se usan y se borran.

---

## 3. Primitivas y bibliotecas

| Función | Primitiva | Biblioteca | Notas |
|---|---|---|---|
| AEAD de contenido | AES256-GCM (AEAD de Tink) | Google Tink (`tink-android`) | nonce de 96 bits generado por Tink en cada operación |
| Envoltorio de la VDEK | keyset cifrado de Tink | Google Tink | `TinkProtoKeysetFormat` con AAD |
| Derivación desde contraseña | Argon2id | BouncyCastle `Argon2BytesGenerator` | JVM puro; ver §5 |
| Derivación de subclaves | HKDF-SHA-256 | Tink / BouncyCastle (fachada `Hkdf` propia) | contextos en §6 |
| Frase de recuperación | codificación BIP-39 con checksum | `cash.z.ecc.android:kotlin-bip39` | **sin** derivación PBKDF2 de semilla |
| Clave anclada al hardware | AES/GCM en Android Keystore | plataforma Android | no exportable; ver §10 |
| Aleatoriedad | `java.security.SecureRandom` sin semilla propia | plataforma | prohibido `Random` y semillas fijas |

Versiones exactas, fechas de verificación y fuentes: `docs/DEPENDENCY_POLICY.md`.

**Prohibido**: implementar AES-GCM o cualquier KDF a mano, AES-ECB, cifrado determinista para
contenido, hashes como cifrado, Base64 como protección, reutilización de nonce, IV con contador
propio, semillas fijas en producción.

---

## 4. VDEK — clave de datos de la bóveda

- Es un **keyset de Tink** con una única clave AES256-GCM primaria, creada en el dispositivo con
  la plantilla recomendada por Tink para datos generales.
- Variante con prefijo de clave (la de la plantilla estándar), lo que permite añadir una clave
  nueva al keyset para rotación y seguir descifrando lo antiguo. El prefijo de 5 bytes es
  metadato de identificación de clave, no material secreto.
- Se genera una sola vez al crear la bóveda. Se conserva en memoria únicamente mientras la
  sesión está desbloqueada, como primitiva `Aead` privada dentro de la capacidad opaca
  `UnlockedVault` de `:core:crypto`; ninguna API pública devuelve la primitiva.
- Nunca se serializa en claro a disco, red, log ni respaldo. La biometría tampoco requiere
  serializar el keyset en claro: el Keystore envuelve una `BiometricKEK` aleatoria y esa KEK usa
  el mismo formato de keyset cifrado de Tink que las otras vías (§10).

---

## 5. Argon2id — PasswordKEK

**Parámetros de producción (versión 1):**

| Parámetro | Valor |
|---|---|
| Variante | Argon2**id** (obligatoria; `i` y `d` se rechazan) |
| Memoria | 65 536 KiB (64 MiB) |
| Iteraciones (t) | 3 |
| Paralelismo (p) | 4 |
| Longitud de salida | 32 bytes |
| Salt | 16 bytes de `SecureRandom`, único por bóveda, **no secreto**, persistido |
| Entrada | contraseña maestra codificada en UTF-8 desde `CharArray` |

**Perfil v1 cerrado, verificado en código (ADR-021, precisado por ADR-031).** No basta un suelo:
un techo es igual de necesario, porque un documento remoto con una memoria absurda provoca
`OutOfMemoryError` y deja la bóveda inabrible en todos los dispositivos. Como todavía no existe
una matriz de perfiles medida en dispositivos, `cryptoVersion = 1` acepta **un único perfil**
publicado y lo valida **antes de reservar memoria**:

| Parámetro | Valor aceptado en v1 | Valor distinto |
|---|---|---|
| `kdfName` | exactamente `argon2id` | `CryptoError.MalformedInput` |
| Memoria (KiB) | **exactamente** 65 536 | `CryptoError.WeakParameters` |
| Iteraciones | **exactamente** 3 | `CryptoError.WeakParameters` |
| Paralelismo | **exactamente** 4 | `CryptoError.WeakParameters` |
| Longitud de salida | **exactamente** 32 | `CryptoError.MalformedInput` |
| `passwordSalt` | **exactamente** 16 bytes | `CryptoError.MalformedInput` |

El perfil se comprueba al derivar **y** al leer parámetros recibidos del servidor o de un respaldo:
nunca se «intenta de todos modos». El mismo perfil se replica en las Security Rules (ADR-023) y en
el importador del respaldo. Un perfil nuevo exige una versión criptográfica nueva o una lista
cerrada publicada mediante ADR después de medirla; no se amplía silenciosamente el conjunto v1.

**Parámetros al crear un envoltorio (ADR-021).** Al **crear** cualquier envoltorio —creación de la
bóveda, cambio de contraseña, recuperación, restauración— se usan **los parámetros de producción del
binario**, nunca los que vengan del servidor. Los parámetros externos solo sirven para **reproducir
una derivación v1 existente** y deben coincidir exactamente con ese perfil. Sin esta regla, un
documento envenenado podría forzar una reserva de memoria no medida.

**Persistencia.** `kdfName`, memoria, iteraciones, paralelismo, longitud, salt y
`passwordWrapEpoch` (ADR-030) se guardan en el documento de la bóveda (Firestore y Room) y se
incluyen en la AAD del envoltorio (§7). Esa inclusión es **defensa en profundidad**: hace explícito
el fallo. La protección primaria contra el downgrade es el perfil verificado en código —los
parámetros alimentan la derivación, así que alterarlos ya produce una KEK distinta con o sin AAD.

**Calibración.** Los parámetros de producción se validan midiendo el tiempo real de derivación en
el dispositivo de pruebas durante la Fase 2. El objetivo de diseño es que el desbloqueo tarde
entre aproximadamente 0,5 y 2 segundos en el dispositivo objetivo. La medición real, con su
fecha y su dispositivo, se anotará en §15 cuando exista; **hasta entonces no hay cifras de
rendimiento en este documento**, porque no se publican valores no medidos. Si la medición
resultara inaceptable, se cambia la implementación (interfaz `PasswordKdf`, ADR-006) o se define
un perfil/versión nueva mediante ADR y se reenvuelve la VDEK. `cryptoVersion = 1` no se recalibra
ni acepta valores alternativos. Nunca se debilita el perfil para acelerar una prueba.

**Derivación final:**

```
argonOut    = Argon2id(password_utf8, passwordSalt, m=65536, t=3, p=4, len=32)
PasswordKEK = HKDF-SHA-256(ikm = argonOut,
                           salt = passwordSalt,
                           info = "BovedaWilson/v1/password-kek",
                           L    = 32)
```

El paso por HKDF separa el dominio y permite derivar subclaves futuras sin reutilizar material.
`argonOut` se borra inmediatamente después.

---

## 6. HKDF — contextos exactos

HKDF-SHA-256 (extract-then-expand), con `salt` explícito e `info` de contexto. Las cadenas de
`info` son constantes congeladas por versión, en ASCII, y **cualquier cambio invalida el material
derivado**, por lo que están cubiertas por pruebas:

| Clave derivada | ikm | salt | info | L |
|---|---|---|---|---|
| PasswordKEK | salida de Argon2id | `passwordSalt` (16 B) | `BovedaWilson/v1/password-kek` | 32 |
| RecoveryKEK | entropía de 32 B de la frase | `recoverySalt` (32 B) | `BovedaWilson/v1/recovery-kek` | 32 |

No existen otras derivaciones en la versión 1. Ninguna cadena de contexto es prefijo de otra.
Una versión futura usará `BovedaWilson/v2/...` y `cryptoVersion` indicará qué contexto aplicar.

---

## 7. AAD — datos asociados autenticados

La AAD se **autentica pero no se cifra**: es visible para quien tenga el ciphertext. Por eso
**nunca contiene información sensible**: solo identificadores aleatorios, versiones y parámetros
públicos.

Formato: cadena UTF-8, campos separados por `|`, prefijo de versión `bw1`. La construcción es
**canónica y determinista**: al descifrar se reconstruye byte a byte a partir de los metadatos, y
una prueba verifica esa igualdad.

Reglas normativas de codificación (ADR-021):

- todo campo textual variable se valida antes de construir la AAD y debe pertenecer a su juego de
  caracteres permitido; `|`, `,` y `=` están prohibidos en todos ellos y provocan
  `CryptoError.MalformedInput`;
- los identificadores aleatorios (`vaultId`, `itemId`) y el alias del Keystore usan únicamente
  ASCII alfanumérico, `.`, `_`, `:` y `-`, con longitud entre 1 y 128 bytes;
- los enteros son ASCII decimal sin signo, sin ceros a la izquierda —salvo el valor `0`— y sin
  separadores;
- cada `salt=` se genera recodificando los bytes validados como base64url **sin relleno**; nunca se
  copia literalmente una cadena recibida;
- el orden de campos y de claves de `paramsCanónicos` está congelado. No se ordenan mapas en tiempo
  de ejecución ni se omiten valores por coincidir con un valor por defecto.

**Ítems (notas):**

```
bw1|item|<vaultId>|<itemId>|<schemaVersion>|<cryptoVersion>
```

Liga cada ciphertext a su bóveda, su ítem y sus versiones: mover un registro a otro `itemId` o
declarar otra versión hace fallar el descifrado.

**Envoltorios de la VDEK:**

```
bw1|vdek-wrap|<vaultId>|<wrapType>|<cryptoVersion>|<paramsCanónicos>
```

donde `wrapType` ∈ {`password`, `recovery`, `biometric`} y `paramsCanónicos` es:

| wrapType | paramsCanónicos |
|---|---|
| `password` | `argon2id,m=<memoryKib>,t=<iterations>,p=<parallelism>,len=32,salt=<passwordSalt>,epoch=<passwordWrapEpoch>` |
| `recovery` | `hkdf-sha256,len=32,salt=<recoverySalt>,entropyBits=256,words=24,wordlist=english,epoch=<recoveryWrapEpoch>` |
| `biometric` | `tink-aes256-gcm,alias=<alias>,epoch=<biometricWrapEpoch>` |

Los valores de `paramsCanónicos` se serializan **desde los metadatos persistidos o recibidos**,
nunca desde literales de compilación. Incluirlos en la AAD es defensa en profundidad: una
alteración produce un fallo explícito de autenticación. La protección primaria contra un downgrade
es validar el perfil cerrado antes de derivar y crear todo envoltorio nuevo con los parámetros de
producción del binario (§5).

El blob externo que protege la `BiometricKEK` con el Keystore usa una AAD distinta y exacta:

```
bw1|biometric-kek|<vaultId>|<alias>|<cryptoVersion>
```

El `Cipher` recibe esa AAD mediante `updateAAD` **antes** de `update` o `doFinal`. Trasplantar el
blob entre bóvedas o aliases debe fallar.

**La AAD no incluye el uid de Firebase** (ADR-009): el anclaje es el `vaultId` aleatorio local,
para que el respaldo siga siendo restaurable si el usuario cambia de cuenta o de proveedor de
acceso.

---

## 8. Envoltorio y desenvolvido de la VDEK

Se usa el formato de **keyset cifrado de Tink**; no hay mecanismo propio de wrapping.

```
envolver:    wrapped = TinkProtoKeysetFormat.serializeEncryptedKeyset(vdekHandle, kekAead, aad)
desenvolver: vdekHandle = TinkProtoKeysetFormat.parseEncryptedKeyset(wrapped, kekAead, aad)
```

`kekAead` es una primitiva AEAD de Tink construida importando la KEK de 32 bytes como clave
AES256-GCM sin prefijo. La importación usa la API de Tink para material de clave externo; no se
manipulan bytes de protobuf a mano.

Propiedades que esto garantiza:

- Sin la KEK correcta, el desenvolvido falla por autenticación: no hay «descifrado parcial».
- Con la KEK correcta pero AAD distinta (otro `vaultId`, otro `wrapType`, otros parámetros de
  KDF), también falla.
- Una contraseña incorrecta es indistinguible de un ciphertext alterado desde el punto de vista
  del atacante; hacia el usuario se muestran mensajes distintos solo en lo imprescindible para
  que pueda actuar, y nunca se revela material.

---

## 9. Cifrado de ítems

Cada ítem se cifra **directamente con la VDEK** (ADR-010).

Plaintext: JSON canónico del ítem, serializado con un esquema estricto:

```json
{
  "v": 1,
  "title": "…",
  "body": "…",
  "tags": ["…"],
  "fields": [{"k": "…", "v": "…", "secret": true}],
  "createdAt": 0,
  "updatedAt": 0
}
```

Todo lo sensible va **dentro** del ciphertext: título, cuerpo, etiquetas, campos personalizados y
las fechas autorizadas del contenido. Fuera solo quedan `itemId` aleatorio, versiones,
`revision`, `tombstone` y marcas de tiempo aproximadas de sincronización.

```
ciphertext = vdekAead.encrypt(json_utf8, aad_item)
plaintext  = vdekAead.decrypt(ciphertext, aad_item)
```

Cada cifrado usa un nonce nuevo generado por Tink: dos cifrados del mismo contenido producen
ciphertext distinto, y eso está cubierto por prueba.

**Límite del nonce aleatorio.** Con nonce aleatorio de 96 bits, el margen de seguridad de AES-GCM
se degrada al acercarse al orden de 2^32 cifrados con la misma clave. Una bóveda personal está
muchos órdenes de magnitud por debajo, pero el límite existe, se documenta y la ruta de rotación
de la VDEK (§12) es la mitigación si alguna vez se acercara.

---

## 10. Biometría y Android Keystore

- Alias de clave implementado y versionado: `boveda_wilson_biometric_kek_v1` (ADR-042).
- `KeyGenParameterSpec` con: AES/GCM/NoPadding, 256 bits, `setUserAuthenticationRequired(true)`,
  `setInvalidatedByBiometricEnrollment(true)`, `setUnlockedDeviceRequired(true)` y
  `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`. Está prohibido
  `setUserAuthenticationValidityDurationSeconds`: cada operación requiere una autenticación
  nueva. Se intenta `setIsStrongBoxBacked(true)` y, si el dispositivo no lo soporta, se cae a TEE
  y se registra el hecho (el dispositivo de pruebas **no** expone StrongBox).
- La clave es **no exportable**: nunca se puede leer su material.
- La activación genera una `BiometricKEK` aleatoria de 32 bytes. El `Cipher` autenticado del
  Keystore cifra **esa KEK**, no la VDEK ni su keyset. La KEK se importa a Tink mediante el mismo
  `KekImporter` de las otras vías y produce `biometricWrappedVdek` con
  `serializeEncryptedKeyset`. Los buffers transitorios se borran en `finally`.
- La activación y el desbloqueo usan `BiometricPrompt` con `CryptoObject`; en ambos casos la
  autenticación es una precondición criptográfica. El prompt usa exclusivamente
  `BIOMETRIC_STRONG`, sin `DEVICE_CREDENTIAL`. Si
  `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` no lo permite, la función no se ofrece.
- Para desbloquear, el `Cipher` se inicializa en modo descifrado con el IV almacenado **antes** de
  mostrar el prompt. Después de recuperar la `BiometricKEK`, Tink desenvuelve la VDEK con la AAD
  biométrica de §7. El keyset de la VDEK nunca se serializa en claro.
- El blob de la `BiometricKEK`, su IV y `biometricWrappedVdek` se guardan **solo localmente**:
  nunca en Firestore ni en el respaldo.
- `:data:sync/biometric` posee la integración Android Keystore y el alias; `:core:crypto` conserva
  únicamente el envoltorio Tink opaco (`BiometricWrap`). `:app` presenta el prompt y entrega el
  `CryptoObject` autenticado al repositorio; la UI nunca recibe una KEK ni la primitiva de la VDEK.
- Invalidación (`KeyPermanentlyInvalidatedException`, cambio de huellas, cambio del bloqueo de
  pantalla, eliminación de credenciales): se borran `wrappedBiometricKek`,
  `biometricWrappedVdek`, IV, `biometricWrapEpoch`, registro/puntero activo y alias propio; se
  cierra la sesión criptográfica, se exige la contraseña maestra y se ofrece reconfigurar.
- La biometría **no** guarda la contraseña maestra, **no** sustituye a la frase de recuperación y
  **no** permite recuperar nada en otro dispositivo.

---

## 11. Recuperación

Detalle de experiencia de usuario en `RECOVERY.md`. Contrato criptográfico:

1. Se generan **256 bits** de entropía con `SecureRandom` en el dispositivo.
2. Se codifican con la lista **inglesa** de BIP-39 como 24 palabras, con su checksum. La frase es
   una **codificación de la entropía**, no una semilla de billetera: no se aplica la derivación
   PBKDF2 del estándar, no hay passphrase y no hay rutas de derivación.
3. Se genera `recoverySalt` (32 bytes, `SecureRandom`, no secreto, persistido).
4. `RecoveryKEK = HKDF-SHA-256(entropía, recoverySalt, "BovedaWilson/v1/recovery-kek", 32)`.
5. `recoveryWrappedVdek = serializeEncryptedKeyset(VDEK, RecoveryKEK, aad_recovery)`.
6. La entropía y la frase se **borran de memoria**. **No se persisten nunca**, en ningún soporte,
   ni envueltas (ADR-011). No existe función para volver a mostrar la frase.

Al recuperar, cada palabra se normaliza en este orden exacto: Unicode NFKD → recorte de extremos →
colapso de todo espacio interno a un único `U+0020` → minúsculas con `Locale.ROOT`. Después se
valida la pertenencia a la lista inglesa y el checksum BIP-39 —con comparación en tiempo
constante—, se reconstruye la entropía, se deriva la RecoveryKEK y se desenvuelve la VDEK.
Entonces el usuario **debe** definir una contraseña maestra nueva. Se genera un `passwordSalt`
nuevo, se crea el envoltorio con parámetros de producción, se incrementa `passwordWrapEpoch` y se
reescribe `passwordWrappedVdek` de forma atómica. `recoveryWrapEpoch` no cambia.

Una sola palabra incorrecta hace fallar el checksum o, si el error mantiene el checksum válido,
hace fallar la autenticación del desenvolvido. En ambos casos el resultado es un fallo limpio.

Si se pierden la contraseña maestra **y** la frase, la bóveda es **irrecuperable**. Nadie —ni el
soporte, ni Google, ni Firebase, ni el desarrollador— puede revertirlo.

---

## 12. Versionado y rotación

Todo formato persistido o transmitido lleva:

- `cryptoVersion`: versión del contrato criptográfico (primitivas, KDF, AAD, wrapping). Actual: **1**.
- `schemaVersion`: versión del esquema del contenido del ítem. Actual: **1**.

Una versión **desconocida se rechaza** con un error explícito; nunca se intenta descifrar «por si
acaso».

| Operación | Qué cambia | Qué NO cambia | Atomicidad |
|---|---|---|---|
| **Cambio de contraseña maestra** | `passwordSalt`, parámetros del KDF, `passwordWrappedVdek`, `passwordWrapEpoch` | la VDEK, `recoveryWrappedVdek`, `recoveryWrapEpoch`, los ítems | una sola escritura del documento de bóveda |
| **Recalibración de parámetros del KDF** | parámetros, `passwordWrappedVdek`, `passwordWrapEpoch` | la VDEK, la recuperación, los ítems | igual que el cambio de contraseña |
| **Regeneración de la frase** | entropía, `recoverySalt`, `recoveryWrappedVdek`, `recoveryWrapEpoch` | la VDEK, la contraseña, `passwordWrapEpoch`, los ítems | una sola escritura del documento de bóveda |
| **Reconfiguración de biometría** | clave/alias del Keystore, `BiometricKEK`, `wrappedBiometricKek`, IV, `biometricWrappedVdek`, `biometricWrapEpoch` | envoltorios remotos e ítems | protocolo crash-safe de dos fases (ADR-032); solo el puntero/registro Room se confirma atómicamente |
| **Rotación de la VDEK** | clave primaria del keyset y **todos** los ítems recifrados | la contraseña y la frase (se reenvuelve la VDEK nueva) | por lotes, con `cryptoVersion` por ítem para tolerar estados mixtos |

Cambiar la contraseña maestra **no** recifra las notas y **no** invalida la recuperación. Ambas
propiedades están cubiertas por pruebas.

Antes de escribir atómicamente una creación, regeneración o restauración, se desenvuelven en
memoria `passwordWrappedVdek` y `recoveryWrappedVdek` y se comprueba que producen la **misma**
VDEK. Si no coinciden, la operación se aborta sin persistir un estado parcial.

Cada camino tiene un entero monótono independiente: `passwordWrapEpoch`, `recoveryWrapEpoch` y,
solo localmente, `biometricWrapEpoch` (ADR-030). Toda operación que reescribe un envoltorio
incrementa únicamente su epoch y lo autentica en su AAD. El cliente conserva una marca de agua por
camino y rechaza valores remotos inferiores. Esto detecta y dificulta la reposición de un
envoltorio antiguo, pero **no revoca** las copias ya obtenidas: la revocación completa exige rotar
la VDEK y recifrar los ítems.

---

## 13. Manejo de secretos en memoria y errores

- La contraseña maestra viaja como `CharArray`; se convierte a `ByteArray` UTF-8 y ese
  `ByteArray` se borra (`fill(0)`) en `finally`. Nunca se usa `String` para secretos. El
  `CharArray` de entrada es propiedad de quien llama a `:core:crypto` (`VaultCrypto`): no se
  borra dentro del módulo, porque algunas operaciones (`createVault`, `changeMasterPassword`,
  `regenerateRecovery`) reutilizan la misma contraseña más de una vez dentro de la misma
  llamada para autoverificarse (`VaultWrapping.verifySameVdek`) antes de devolver un
  resultado; borrarlo a mitad de la operación rompería esa verificación. Quien invoque estas
  operaciones es responsable de borrar el `CharArray` en cuanto reciba el resultado.
- La entropía, las KEK y las serializaciones transitorias de la VDEK viven en un tipo con cierre
  explícito que borra al salir, incluso ante excepción.
- Los tipos que contienen material sensible redefinen `toString()` para devolver una etiqueta
  redactada, y no se declaran como `data class` con esos campos.
- Las excepciones criptográficas son de un conjunto cerrado y **no** llevan material sensible ni
  en el mensaje, ni en la causa, ni en campos adicionales: credencial inválida, integridad
  fallida, versión no soportada, parámetros débiles, entrada malformada, error interno.
- Los secretos no se pasan por `Intent`, `Bundle`, `SavedStateHandle`, argumentos de navegación
  ni datos de entrada de WorkManager.
- Java no garantiza que un `ByteArray` no haya sido copiado por el recolector de basura o
  paginado a disco por el sistema: el borrado es una mitigación de buena práctica, **no** una
  garantía absoluta. Se documenta como limitación real en §16.

---

## 14. Autenticación del manifiesto y prueba efímera de publicación

El respaldo v2 autentica el manifiesto canónico completo con la propia VDEK, sin introducir una
MAC separada. `UnlockedVault.authenticateBackupManifest` usa la primitiva Tink AES-256-GCM de la
VDEK para cifrar un plaintext vacío. La AAD es la concatenación exacta de
`bw2|backup-manifest|` y el JSON canónico sin `manifestAuthenticator`, con todos los wrappers,
salts, versiones, timestamps, flags, identificadores y ciphertext; los ítems se ordenan por
`itemId`. La restauración debe verificar ese ciphertext Tink antes del commit Room y la
autorización de publicación vuelve a verificarlo antes de cualquier escritura remota. v1 se
rechaza porque no ofrece esta propiedad.

La autorización de publicación no almacena una VDEK ni una `UnlockedVault`. Durante la
restauración se cifra un payload fijo no secreto con la VDEK restaurada y una AAD de ítem que usa
un identificador aleatorio, `vaultId`, `schemaVersion` y `cryptoVersion`. Al consumir la capacidad,
la sesión desbloqueada actual debe abrir el reto y obtener exactamente ese payload. La capacidad
es de un solo uso y además está ligada al usuario Firebase, generación de sesión y SHA-256 del
snapshot ciphertext-only. El hash no sustituye el autenticador Tink: liga la capacidad al archivo
v2 ya autenticado. La capacidad puede consultarse sin consumirla mientras se suben ítems
idempotentes; solo se consume después de verificar el estado remoto final, de modo que una
interrupción parcial pueda reintentarse sin ampliar la sesión ni retener la VDEK. Ver ADR-047 y
ADR-051.

---

## 15. Mediciones reales

**Medido el 2026-07-31.** Prueba instrumentada
`core/crypto/src/androidTest/kotlin/.../kdf/Argon2idBenchmarkTest.kt`, ejecutada vía
`adb shell am instrument -w -e class cl.bovedawilson.core.crypto.kdf.Argon2idBenchmarkTest
cl.bovedawilson.core.crypto.test/androidx.test.runner.AndroidJUnitRunner` tras instalar
`crypto-debug-androidTest.apk` en el dispositivo conectado (`adb install -r -t`). Valor leído de
`INSTRUMENTATION_STATUS: argon2id_production_profile_ms=1456`.

| Campo | Valor |
|---|---|
| Derivación | `Argon2idPasswordKdf().derive(...)` con `KdfPolicy.newProductionParameters()` (m=65536 KiB, t=3, p=4, salida=32 bytes) |
| Dispositivo | `2312DRAABG`, Android 13 (API 33), arm64-v8a, sin StrongBox |
| Fecha | 2026-07-31 |
| Tiempo medido | **1456 ms** (una sola derivación, tras descartar la primera carga de clases con una derivación de calentamiento previa no medida) |

1456 ms cae dentro del objetivo de diseño (0,5-2 s) para el desbloqueo con contraseña maestra en
este dispositivo. Referencia no equivalente: en la JVM de este entorno de desarrollo (no el
dispositivo objetivo), la misma derivación con BouncyCastle tomó ~170-240 ms medidos con un
microbenchmark manual fuera del árbol de pruebas; esa cifra no sustituye la medición en
dispositivo y queda solo como referencia de contexto.

---

## 16. Limitaciones conocidas

- **Metadatos.** El servidor y quien extraiga la base local ven: cuántos ítems existen, cuándo se
  crearon y modificaron, el tamaño aproximado de cada ítem y la frecuencia de uso. El contenido no,
  pero esos metadatos filtran información de comportamiento. No se ofuscan en el MVP.
- **Dispositivo comprometido.** Con la bóveda desbloqueada en un dispositivo con malware, root o
  un servicio de accesibilidad malicioso, el plaintext está en memoria y es alcanzable. Ninguna
  criptografía de aplicación resuelve eso.
- **Borrado de memoria.** Mitigación de buena práctica, no garantía (§13).
- **Fortaleza de la contraseña.** Argon2id encarece el ataque por fuerza bruta, pero una
  contraseña débil sigue siendo débil. La aplicación mide la fortaleza localmente y advierte.
- **La frase de 24 palabras es equivalente a la bóveda.** Quien la posea puede abrirla. No es una
  billetera y no sirve en software de criptomonedas.
- **Nonce aleatorio de 96 bits** con el límite descrito en §9.
- **Revocación parcial sin rotar la VDEK.** Cambiar la contraseña o regenerar la frase invalida el
  envoltorio almacenado actualmente, pero una copia antigua y su credencial siguen pudiendo abrir
  la misma VDEK. Los epochs independientes detectan retrocesos; la revocación criptográfica
  completa exige rotar la VDEK y recifrar.
- **StrongBox ausente** en el dispositivo de pruebas: la clave biométrica queda respaldada por TEE.
- **Sin protección de metadatos de la base local**: se decidió no usar SQLCipher en el MVP
  (ADR-013).

Bóveda Wilson **no** es invulnerable y este documento no afirma seguridad absoluta. Ver
`THREAT_MODEL.md` para el alcance completo y los riesgos residuales.

---

## 17. Referencias

- Google Tink — documentación oficial y guía de tipos de clave AEAD: <https://developers.google.com/tink>
- Tink, formatos de keyset y keysets cifrados: <https://developers.google.com/tink/design/keysets>
- RFC 5869 — HKDF: <https://www.rfc-editor.org/rfc/rfc5869>
- RFC 9106 — Argon2: <https://www.rfc-editor.org/rfc/rfc9106>
- OWASP Password Storage Cheat Sheet (parámetros mínimos de Argon2id):
  <https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html>
- BIP-39 — codificación mnemónica: <https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki>
- NIST SP 800-38D — GCM: <https://csrc.nist.gov/publications/detail/sp/800-38d/final>
- Android Keystore y `KeyGenParameterSpec`:
  <https://developer.android.com/privacy-and-security/keystore>
- AndroidX Biometric y `CryptoObject`:
  <https://developer.android.com/identity/sign-in/biometric-auth>
