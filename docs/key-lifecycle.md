# docs/key-lifecycle.md — Ciclo de vida de las claves

Complementa `CRYPTOGRAPHY.md` (contrato) describiendo **cuándo** nace, se usa y muere cada clave.
El ciclo está implementado; sus pruebas y límites se registran en `PROJECT_STATE.md`.

---

## 1. Resumen por clave

| Clave | Nace | Vive en | Muere | Se persiste |
|---|---|---|---|---|
| Contraseña maestra | la escribe el usuario | `CharArray` transitorio | borrada tras derivar, en `finally` | **nunca** |
| `argonOut` (salida de Argon2id) | al derivar | `ByteArray` | borrada tras HKDF | nunca |
| PasswordKEK | al derivar | `SecureBytes` | borrada tras envolver/desenvolver | nunca |
| Entropía de recuperación (32 B) | `SecureRandom` al crear la bóveda, o al reconstruirla desde la frase | `SecureBytes` | borrada tras derivar la RecoveryKEK | **nunca** (ADR-011) |
| Frase de 24 palabras | codificación de la entropía | lista de palabras en memoria | borrada al salir de la pantalla | **nunca** |
| RecoveryKEK | al derivar | `SecureBytes` | borrada tras envolver/desenvolver | nunca |
| VDEK | `SecureRandom` (keyset Tink) al crear la bóveda | `Aead` privado dentro de la capacidad `UnlockedVault` de `:core:crypto` | al bloquear la sesión | solo **envuelta** |
| BiometricKEK (32 B) | `SecureRandom` al activar la biometría | `SecureBytes` transitorio | tras importar a Tink o al desbloquear | solo envuelta por el Keystore, local |
| Clave biométrica del Keystore | Android Keystore al activar | TEE/StrongBox, no exportable | al desactivar o invalidarse | su material nunca sale; solo blob + IV locales |

---

## 2. Creación de la bóveda

```
1. vaultId        = UUID aleatorio (SecureRandom); es el id del documento, no un campo remoto
2. VDEK           = keyset Tink AES256-GCM nuevo
3. passwordSalt   = 16 B aleatorios
   PasswordKEK    = HKDF(Argon2id(contraseña, passwordSalt, m,t,p), passwordSalt, info password)
   passwordWrappedVdek = serializeEncryptedKeyset(VDEK, PasswordKEK, aad password)
4. entropía       = 32 B aleatorios
   frase          = BIP-39(entropía)                 → se muestra UNA vez
   recoverySalt   = 32 B aleatorios
   RecoveryKEK    = HKDF(entropía, recoverySalt, info recovery)
   recoveryWrappedVdek = serializeEncryptedKeyset(VDEK, RecoveryKEK, aad recovery)
5. verificación de palabras elegidas al azar por la aplicación
6. se desenvuelven ambos envoltorios en memoria y se comprueba que producen la MISMA VDEK
7. se persisten en Room y se suben a Firestore SOLO:
     versiones, kdfName + parámetros, passwordSalt, passwordWrappedVdek,
     recoverySalt, recoveryWrappedVdek, passwordWrapEpoch=1, recoveryWrapEpoch=1,
     marcas de tiempo
8. se borran de memoria: contraseña, argonOut, PasswordKEK, entropía, frase, RecoveryKEK
9. la VDEK permanece viva en VaultSession (sesión abierta)
```

Orden obligatorio: **la bóveda no se marca como creada hasta que la verificación de la frase se
supera y ambos envoltorios están escritos**. Si el proceso se interrumpe antes, la bóveda queda
incompleta y se reinicia el flujo: nunca queda una bóveda con un único camino de acceso.

---

## 3. Desbloqueo

### Con contraseña maestra

```
leer parámetros y passwordSalt (Room; Firestore si es un dispositivo nuevo)
  → verificar el perfil v1 cerrado antes de reservar memoria → si no cumple: RECHAZAR
  → rechazar passwordWrapEpoch inferior a su marca de agua local
  → PasswordKEK = HKDF(Argon2id(contraseña, salt, params), salt, info password)
  → VDEK = parseEncryptedKeyset(passwordWrappedVdek, PasswordKEK, aad password)
       ├─ éxito → VaultSession = Unlocked(vaultId, openedAt), sin payload de clave
       └─ fallo  → CryptoError.InvalidCredentials (genérico)
  → borrar contraseña, argonOut y PasswordKEK
```

### Con biometría

```
BiometricPrompt(CryptoObject(Cipher en DECRYPT_MODE con el IV almacenado))
  → autenticación por operación con BIOMETRIC_STRONG, sin DEVICE_CREDENTIAL
  → cipher.updateAAD(aad biometric-kek)
  → cipher.doFinal(wrappedBiometricKek) → BiometricKEK transitoria
  → importar BiometricKEK a Tink
  → parseEncryptedKeyset(biometricWrappedVdek, BiometricKEK, aad biometric)
  → VaultSession = Unlocked(vaultId, openedAt), sin exponer el Aead
  → borrar BiometricKEK y buffers transitorios
  → si KeyPermanentlyInvalidatedException u otra invalidación:
       borrar ambos blobs + IV + epoch + registro activo; eliminar alias propio;
       cerrar sesión y exigir contraseña maestra
```

La biometría **no** deriva ninguna clave de la contraseña maestra. La clave del hardware desenvuelve
una `BiometricKEK` aleatoria y esa KEK desenvuelve la VDEK mediante Tink, solo en ese dispositivo.

---

## 4. Uso durante la sesión

- La VDEK vive como `Aead` privado dentro de `UnlockedVault`, implementada por `:core:crypto`.
  `VaultSession` conserva privadamente esa capacidad opaca y solo delega operaciones concretas:
  ninguna propiedad, parámetro, retorno ni callback entrega `Aead`, VDEK o una referencia
  conservable. La UI solo observa `Locked`/`Unlocked(vaultId, openedAt)`.
- Cada operación de cifrado usa un nonce nuevo generado por Tink, con la AAD del ítem.
- La búsqueda ocurre en memoria sobre los ítems descifrados; no se persiste ningún índice.
- Cualquier tarea en curso que use la VDEK se cancela al bloquear, sin dejar buffers vivos.

---

## 5. Bloqueo

```
disparadores: inactividad · segundo plano (opcional) · «bloquear ahora»
              · clave del Keystore invalidada · cierre de sesión de Firebase
acciones:     descartar el Aead de la VDEK · borrar buffers (SecureBytes.close)
              · cancelar los Job sensibles · limpiar los UiState
              · navegar a la pantalla de desbloqueo
```

Después del bloqueo, ningún objeto vivo debe contener plaintext ni material de clave. Esto se
verifica con la prueba de estado de la Fase 3.

---

## 6. Cambio de contraseña maestra

```
1. desbloquear la VDEK con la PasswordKEK antigua (exige la contraseña actual)
2. passwordSalt' = 16 B nuevos; parámetros' = los de producción del binario
3. PasswordKEK'  = HKDF(Argon2id(contraseña nueva, passwordSalt', params'), ...)
4. passwordWrappedVdek' = serializeEncryptedKeyset(MISMA VDEK, PasswordKEK', aad password')
5. desenvolver el nuevo password wrapper y comprobar que abre la VDEK de sesión; comprobar que
   `recoveryWrappedVdek`, `recoverySalt` y `recoveryWrapEpoch` permanecen idénticos byte a byte
6. escritura ATÓMICA: salt' + params' + wrapped' + passwordWrapEpoch+1 + metaRevision+1
7. borrar todo el material transitorio
```

**No** se recifran las notas. **No** se toca `recoveryWrappedVdek`. Si la escritura falla, se
conserva el estado anterior: la contraseña antigua sigue siendo válida y no queda una bóveda
inaccesible.

---

## 7. Regeneración de la frase de recuperación (ADR-011)

```
requiere: bóveda desbloqueada + contraseña maestra + advertencia explícita
1. entropía'  = 32 B nuevos;  frase' = BIP-39(entropía')  → se muestra UNA vez
2. recoverySalt' = 32 B nuevos;  RecoveryKEK' = HKDF(entropía', recoverySalt', info recovery)
3. recoveryWrappedVdek' = serializeEncryptedKeyset(MISMA VDEK, RecoveryKEK', aad recovery')
4. verificación de palabras al azar ANTES de escribir
5. verificar en memoria que los envoltorios de contraseña y recuperación abren la misma VDEK
6. escritura ATÓMICA: recoverySalt' + recoveryWrappedVdek' + recoveryWrapEpoch+1 + metaRevision+1
7. borrar entropía', frase' y RecoveryKEK'
```

Consecuencia inmediata: **la frase anterior deja de abrir el envoltorio almacenado actualmente**.
Una copia antigua del envoltorio junto con la frase anterior todavía abre la misma VDEK. Si el
usuario declara un posible compromiso, la interfaz ofrece la rotación completa de la VDEK y explica
que el simple reenvoltorio no revoca copias previas. No existe forma de volver a ver una frase ya
generada.

---

## 8. Recuperación (contraseña maestra perdida)

```
1. iniciar sesión en Firebase (autoriza la descarga del documento de bóveda)
2. descargar recoverySalt + recoveryWrappedVdek + versiones; rechazar `recoveryWrapEpoch`
   inferior a su marca local
3. el usuario introduce las 24 palabras inglesas → NFKD → recortar → colapsar espacios a U+0020
   → minúsculas con Locale.ROOT → validar lista y checksum BIP-39 en tiempo constante
4. entropía = decodificar(frase);  RecoveryKEK = HKDF(entropía, recoverySalt, info recovery)
5. VDEK = parseEncryptedKeyset(recoveryWrappedVdek, RecoveryKEK, aad recovery)
     └─ fallo → error genérico; no se distingue palabra mal de datos alterados
6. el usuario define y confirma una contraseña maestra NUEVA
7. passwordSalt' + parámetros de producción + PasswordKEK' + passwordWrappedVdek'
8. verificar que ambos envoltorios abren la misma VDEK
9. escritura ATÓMICA de los metadatos de contraseña + passwordWrapEpoch+1 + metaRevision+1;
   `recoveryWrapEpoch` y su envoltorio no cambian
10. borrar entropía, frase, RecoveryKEK y todo el material transitorio
```

El servidor solo interviene entregando `recoveryWrappedVdek` y los parámetros públicos. No
participa en ninguna derivación.

---

## 9. Activación y desactivación de la biometría

```
activar:     requiere sesión desbloqueada
             crear la clave del Keystore (alias `boveda_wilson_biometric_kek_v1`, no exportable,
             userAuthenticationRequired, autenticación por operación BIOMETRIC_STRONG,
             invalidatedByBiometricEnrollment, unlockedDeviceRequired, StrongBox si está disponible;
             fallback a TEE solo por StrongBoxUnavailableException y nivel verificado, ADR-050)
             → generar BiometricKEK aleatoria de 32 B
             → importar la KEK a Tink y envolver la VDEK con AAD biometric
             → BiometricPrompt + CryptoObject en ENCRYPT_MODE
             → cifrar BiometricKEK con AAD biometric-kek
             → verificar que el conjunto nuevo abre la VDEK
             → confirmar atómicamente en Room el alias activo + wrappedBiometricKek + IV
               + biometricWrappedVdek + biometricWrapEpoch
             → si falla o se cancela antes del commit, borrar el alias nuevo; no existe un
               registro parcial
reconfigurar: conservar el conjunto activo anterior; generar con alias versionado nuevo la
             BiometricKEK, ambos blobs, IV y epoch; verificar que abre la VDEK; cambiar
             atómicamente solo el registro/puntero de Room; después eliminar el alias anterior
arranque:     eliminar aliases biométricos huérfanos que no sean el alias activo de Room; un cierre
             deja siempre utilizable el conjunto anterior o el nuevo, nunca una mezcla
desactivar:  borrar ambos blobs, el IV y el epoch local, y eliminar la clave del Keystore
invalidar:   ante cualquier excepción de invalidación → borrar ambos blobs, IV, epoch y registro;
             eliminar alias propio, cerrar sesión, exigir contraseña y ofrecer reconfigurar
```

El blob es **desechable**: perderlo no afecta a la bóveda. Nunca se sincroniza y nunca entra en el
respaldo.

---

## 10. Rotación de la VDEK (preparada, no en el MVP)

```
1. VDEK' = keyset nuevo (o clave nueva primaria en el keyset existente)
2. para cada ítem, por lotes: descifrar con la versión antigua → cifrar con VDEK'
   y subir la revisión;  cada ítem guarda su cryptoVersion (estados mixtos tolerados)
3. reenvolver VDEK' con PasswordKEK y RecoveryKEK; escribir atómicamente
4. cuando no queden ítems con la versión antigua, retirar la clave antigua del keyset
```

Interrumpir la rotación deja una bóveda **consistente y legible**, con ítems en dos versiones.

---

## 11. Eliminación de la bóveda

```
requiere: reautenticación + confirmación fuerte
1. convertir cada ítem remoto a tombstone con ciphertext vacío y revisión creciente
2. borrar físicamente los ítems ya enterrados y luego el documento remoto de bóveda
3. borrar todas las filas locales (ítems, meta, ambos blobs/registro biométrico, conflictos,
   sincronización)
4. eliminar el alias propio del Keystore
5. cerrar la sesión criptográfica y la de Firebase
```

Tras esto, ni la contraseña ni la frase sirven para nada: no queda material que desenvolver. La
interfaz debe declararlo como irreversible antes de ejecutarlo.
