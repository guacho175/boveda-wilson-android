# docs/data-flow.md — Recorrido de los datos

Dónde existe cada dato, en qué forma y qué frontera cruza. El flujo está implementado; la evidencia
de pruebas y sus límites vigentes están en `PROJECT_STATE.md` y `docs/TEST_STRATEGY.md`.

---

## 1. Leyenda

- `PT` plaintext: solo en memoria, solo con la sesión abierta.
- `CT` ciphertext AEAD con la VDEK.
- `W` material envuelto (keyset cifrado de Tink).
- `M` metadato no sensible (identificador aleatorio, versión, revisión, marca de tiempo).

---

## 2. Crear o editar una nota

```
usuario escribe
   │ PT
   ▼
Composable ──▶ ViewModel (UiState en memoria, sin campos persistibles)
   │ PT: VaultItem (:core:model)
   ▼
ItemRepository (:data:sync)
   │  ItemPayload = map(VaultItem)
   │  aad = "bw1|item|<vaultId>|<itemId>|<schemaVersion>|<cryptoVersion>"
   │  CT = VaultSession.encrypt(ItemPayload, aad) → Ciphertext opaco
   │                                                    ◀── FRONTERA CRIPTOGRÁFICA
   ▼
   ├─▶ EncryptedItemStore.put(Ciphertext, M…) ──▶ entidad interna ──▶ Room (BLOB)
   │
   └─▶ SyncWorker (WorkManager, sin secretos en sus datos de entrada)
          │ CT + M
          ▼
       FirestoreVaultSource.upload(Ciphertext, M) ──▶ DTO interno ──▶ Firestore
```

Nada por debajo de la frontera puede nombrar `VaultItem`: `:data:local` y `:data:remote` no
dependen de `:core:model` y sus escrituras solo aceptan `Ciphertext` opaco (ADR-033). Sus
entidades/DTO son internos; el ensamblado desde bytes arbitrarios no forma parte de su API.

---

## 3. Abrir la bóveda y listar

```
Room ──CT+M──▶ ItemRepository ──VaultSession.decrypt(CT, aad)──▶ PT: List<VaultItem> (memoria)
                                                                   │
                                                                   ▼
                                                            ViewModel ──▶ UiState ──▶ UI
```

La lista descifrada vive en memoria mientras la sesión está abierta. La búsqueda filtra esa lista
en memoria: **no** hay índice persistente ni consulta de texto en Room o Firestore.

Al bloquear, la lista se descarta junto con el resto del estado.

---

## 4. Sincronización

```
subida:   Room(dirty) ──CT+M──▶ Firestore          (nunca PT)
bajada:   Firestore ──CT+M──▶ Room ──▶ descifrado en memoria si la sesión está abierta
conflicto: revisión remota > lastSyncedRevision y dirty local
           → sesión abierta: copia local recifrada con conflictOf
           → sesión bloqueada: DTO remoto cifrado completo en staging; CT local intacto
borrado:  tombstone = true + CT vacío + revisión nueva → se propaga
          delete físico solo después del tombstone o al eliminar toda la bóveda
```

La caché offline de Firestore contiene **solo** ciphertext y metadatos, porque es lo único que se
le entrega.

---

## 5. Datos que salen del dispositivo

| Dato | Sale | Forma |
|---|---|---|
| Contenido de las notas | sí | `CT` |
| Título, etiquetas, campos | sí | dentro del `CT` |
| `itemId`, `vaultId` | sí | `M` (aleatorios) |
| Versiones, `revision`, `tombstone`, marcas de tiempo | sí | `M` |
| `passwordSalt`, `recoverySalt`, parámetros del KDF | sí | públicos por diseño |
| `passwordWrappedVdek`, `recoveryWrappedVdek` | sí | `W` |
| Contraseña maestra | **no** | — |
| Frase de 24 palabras / entropía | **no** | — |
| VDEK sin envolver | **no** | — |
| KEK derivadas | **no** | — |
| `wrappedBiometricKek` + `biometricWrappedVdek` + IV | **no** | solo local |
| Correo / uid de Firebase | sí | necesario para la autenticación; no participa en la criptografía |

---

## 6. Datos que se persisten en el dispositivo

| Destino | Contenido permitido |
|---|---|
| Room `encrypted_items` | `CT` + `M` |
| Room `vault_meta` | `W` + salts + parámetros + `M` |
| Room `biometric_unlock` | `BiometricKEK` envuelta + VDEK envuelta por Tink + IV + `M` |
| DataStore | preferencias no sensibles: tiempo de bloqueo, bloqueo en segundo plano, biometría activada |
| Archivo de respaldo (elegido por el usuario) | `W` + `CT` + parámetros públicos (ver `BACKUP_FORMAT.md`) |
| Logs | códigos de error redactados; **jamás** `PT`, secretos ni `CT` completo |

Prohibido persistir `PT` en cualquier destino, incluidos `SavedStateHandle`, `Bundle`,
`rememberSaveable`, caché, archivos temporales, notificaciones, previews y backups del sistema.

---

## 7. Exportación y restauración

```
exportar:   requiere reautenticación
            Room(CT) + vault_meta(W, salts, params) ──▶ BackupWriter ──▶ archivo vía SAF
            (nunca PT; nunca el blob biométrico)

restaurar:  archivo ──▶ BackupReader (parser defensivo, límites, versión conocida)
            → desenvolver la VDEK con contraseña maestra O frase de 24 palabras
            → recrear el segundo camino y verificar que ambos envuelven la misma VDEK
            → insertar CT en Room → descifrado en memoria al abrir la sesión
```

---

## 8. Registro de eventos

```
código ──▶ SecureLogger ──▶ (debug) salida del sistema con identificadores redactados
                        └──▶ (release) descarta todo lo que no sea error operativo

nunca acepta: PT de notas, contraseñas, frases, entropía, claves, CT completo
```

Sin telemetría, sin analítica, sin Crashlytics en flujos sensibles (ADR-018).
