# docs/architecture.md — Arquitectura de Bóveda Wilson

Módulos, capas, fronteras y modelos de datos implementados. Las decisiones están en
`DECISIONS.md` (ADR-003, ADR-013, ADR-014). El estado verificable de cada prueba está en
`PROJECT_STATE.md`; el grafo visual está en [`docs/diagrams/architecture.md`](diagrams/architecture.md).

---

## 1. Grafo de módulos

```
                         ┌──────────┐
                         │   :app   │  Compose, navegación, Hilt, ventana, ciclo de vida
                         └────┬─────┘
                ┌─────────────┼────────────────┐
                ▼             ▼                ▼
         ┌────────────┐ ┌────────────┐  ┌─────────────┐
         │:core:model │ │:core:common│  │ :data:sync  │
         └────────────┘ └─────▲──────┘  └──┬──┬──┬──┘
                ▲             │            │  │  │
                └─────────────┼────────────┘  │  │
                              │               │  │
                       ┌──────┴───────┐       │  │
                       │ :core:crypto │◀──────┘  │
                       └──────────────┘          │
                                      ┌─────────┴─────────┐
                                      ▼                   ▼
                               ┌────────────┐       ┌────────────┐
                               │:data:local │       │:data:remote│
                               │Room (BLOB) │       │Firestore   │
                               └────────────┘       └────────────┘
```

Dependencias permitidas:

La tabla siguiente es normativa. El diagrama agrupa visualmente las capas; las aristas de tipo
`Ciphertext` desde local/remoto hacia crypto se detallan en la tabla para no confundirlas con
acceso a primitivas.

| Módulo | Depende de |
|---|---|
| `:app` | `:core:model`, `:core:common`, `:data:sync` |
| `:data:sync` | `:core:model`, `:core:common`, `:core:crypto`, `:data:local`, `:data:remote` |
| `:data:local` | `:core:common`, `:core:crypto` (solo el tipo opaco `Ciphertext`) |
| `:data:remote` | `:core:common`, `:core:crypto` (solo el tipo opaco `Ciphertext`) |
| `:core:crypto` | `:core:common` |
| `:core:common` | — |
| `:core:model` | — |

**Reglas duras, verificables por el compilador:**

1. `:data:local` y `:data:remote` **no dependen de `:core:model`**: no pueden nombrar un modelo de
   dominio descifrado. Sus entidades/DTO son internos y sus APIs públicas intercambian
   `Ciphertext`, no dominio.
2. `:core:crypto` no depende de Room, Firebase ni de la UI: es probable en la JVM sin dispositivo.
   Tampoco depende de `:core:model`: cifra `ItemPayload`, no modelos de dominio. `Aead`,
   `KeysetHandle`, VDEK, `KekImporter` y `VdekWrapper` son internos; la API pública entrega una
   capacidad opaca `UnlockedVault` con operaciones concretas. `Ciphertext` distingue el flujo, pero
   su reconstrucción desde persistencia es necesariamente pública para sincronizar con la sesión
   bloqueada; por eso no se presenta como una garantía compilatoria absoluta.
3. `:core:common` no contiene tipos de dominio descifrado, porque las capas de datos dependen de él.
4. `:app` no depende de `:core:crypto` ni accede a `:data:local` o `:data:remote`: pasa por casos de
   uso de `:data:sync`.
5. `:data:sync` es el único módulo que mapea `VaultItem ↔ ItemPayload`. `VaultSession` conserva
   privadamente una `UnlockedVault`, nunca una primitiva. `CryptoError` se traduce allí a
   `AppError`; ninguna firma consumida por `:app` expone tipos de `:core:crypto`. Ninguna capa
   inferior conoce a `:app`. Sin ciclos.

---

## 2. Paquetes por módulo

```
:core:model      cl.bovedawilson.core.model
                   VaultItem, VaultItemDraft, ItemField, VaultMetaView, SearchQuery
                   (todos son modelos DESCIFRADOS: solo viven en memoria)

:core:common     cl.bovedawilson.core.common
                   log/        SecureLogger, Redact
                   memory/     SecureBytes, CharArrays, Wipe
                   coroutines/ AppDispatchers
                   result/     AppResult, AppError

:core:crypto     cl.bovedawilson.core.crypto
                   aead/       VaultAead, TinkAeadFactory (internos), Aad, AadBuilder
                   session/    UnlockedVault (capacidad opaca; Aead privado)
                   ciphertext/ Ciphertext (inmutable; fábrica crypto interna + fábrica persistida auditada)
                   kdf/        PasswordKdf, Argon2idPasswordKdf, KdfParameters, KdfPolicy
                   hkdf/       Hkdf, HkdfContext
                   keys/       Vdek, VdekFactory, Kek, KekImporter (internos)
                   wrap/       VdekWrapper (interno), WrapType, WrappedVdek
                   recovery/   RecoveryPhrase, RecoveryEntropy, Bip39Codec
                   keystore/   BiometricKeyStore, BiometricKekCipher, alias versionado
                   version/    CryptoVersion, SchemaVersion
                   error/      CryptoError
                   item/       ItemPayload (esquema JSON), ItemCryptor

:data:local      cl.bovedawilson.data.local
                   db/         VaultDatabase, migrations
                   entity/     entidades internas; ninguna se construye desde :data:sync
                   dao/        VaultMetaDao, EncryptedItemDao, BiometricUnlockDao, SyncStateDao
                   store/      EncryptedItemStore.put(Ciphertext, metadatos)
                   prefs/      SettingsDataStore  (solo preferencias no sensibles)

:data:remote     cl.bovedawilson.data.remote
                   auth/       FirebaseAuthSource
                   firestore/  DTO internos; FirestoreVaultSource.upload(Ciphertext, metadatos)
                   mapper/     (entidad cifrada ↔ DTO cifrado; nunca dominio)

:data:sync       cl.bovedawilson.data.sync
                   session/    VaultSession, SessionState, AutoLockController
                   repository/ VaultRepository, ItemRepository, RecoveryRepository, BackupRepository
                               SettingsRepository
                   mapper/     VaultItem ↔ ItemPayload, CryptoError → AppError
                   sync/       SyncEngine, ConflictResolver, SyncWorker
                   backup/     BackupWriter, BackupReader (formato de BACKUP_FORMAT.md)

:app             cl.bovedawilson.app
                   MainActivity, BovedaWilsonApp, di/
                   ui/onboarding, ui/auth, ui/vaultsetup, ui/unlock, ui/items,
                   ui/settings, ui/recovery, ui/backup, ui/theme, ui/components
                   ui/components/SecureDialog
```

---

## 3. Capas de datos y transformaciones

```
UI (Compose)
   │  UiState inmutable, sin campos persistibles sensibles
   ▼
ViewModel  ── plaintext SOLO en memoria ──▶ VaultItem (:core:model)
   │
   ▼
Repository (:data:sync)
   │  VaultItem ──mapper──▶ ItemPayload
   │  VaultSession.encrypt(ItemPayload, aad) ──▶ Ciphertext opaco  ◀── FRONTERA CRIPTOGRÁFICA
   ▼
   ├──▶ EncryptedItemStore.put(Ciphertext, M) ── entidad interna ── Room BLOB
   └──▶ FirestoreVaultSource.upload(Ciphertext, M) ── DTO interno ── Firestore bytes
```

El cifrado ocurre **siempre** antes de la frontera. Las APIs local/remota intercambian
`Ciphertext`; no aceptan `ItemPayload` ni dominio, y los DTO/entidades son internos. Para permitir
pull/push bloqueado, los adaptadores internos reconstruyen `Ciphertext` desde bytes persistidos
mediante `Ciphertext.fromPersisted`; esa fábrica **podría ser mal usada por código deliberado en
`:data:sync`**, así que la propiedad «estos bytes son realmente ciphertext» no la demuestra el
compilador. La capacidad `UnlockedVault`, implementada en `:core:crypto`, mantiene su `Aead` privado;
`VaultSession` solo conserva esa capacidad y nunca recibe `Aead`, VDEK o `KeysetHandle`. Ni la
capacidad ni `Ciphertext` se exponen desde casos de uso hacia `:app`.

Una prueba sobre la API pública compilada falla si `Aead`, `KeysetHandle` o VDEK aparecen fuera de
`:core:crypto` o si `:data:sync` importa Room/Firebase y elude los stores. Otra prueba estructural
permite `Ciphertext.fromPersisted` solo en los mappers internos local/remoto, fija el único
ensamblado de plaintext `encrypt → Ciphertext → store/source` y prohíbe serializers de dominio en
`:data:sync`. Las pruebas de señuelo son la evidencia de comportamiento. Esto protege contra
errores accidentales; código deliberadamente malicioso dentro del proceso sigue fuera del alcance
(T-17).

---

## 4. Esquema de Room (solo ciphertext)

`VaultDatabase`, versión 1. Ninguna tabla tiene columnas de título, cuerpo, etiquetas ni texto
buscable. Sin índice de búsqueda persistente. Migraciones explícitas; `fallbackToDestructiveMigration`
prohibido; `exportSchema = true`.

### `vault_meta` (una sola fila)

| Columna | Tipo | Sensible | Notas |
|---|---|---|---|
| `vaultId` | TEXT PK | no | UUID aleatorio generado en el dispositivo; anclaje de la AAD |
| `ownerUid` | TEXT | identificador de cuenta | vínculo local con Firebase Auth; nunca entra en AAD, Firestore ni respaldo |
| `schemaVersion` | INTEGER | no | versión del esquema del contenido |
| `cryptoVersion` | INTEGER | no | versión del contrato criptográfico |
| `kdfName` | TEXT | no | `argon2id` |
| `kdfMemoryKib` | INTEGER | no | 65536 |
| `kdfIterations` | INTEGER | no | 3 |
| `kdfParallelism` | INTEGER | no | 4 |
| `kdfOutputLen` | INTEGER | no | 32 |
| `passwordSalt` | BLOB | no (público) | 16 B |
| `passwordWrappedVdek` | BLOB | envuelto | keyset cifrado de Tink |
| `recoverySalt` | BLOB | no (público) | 32 B |
| `recoveryWrappedVdek` | BLOB | envuelto | keyset cifrado de Tink |
| `passwordWrapEpoch` | INTEGER | metadato | marca de agua monótona del envoltorio de contraseña |
| `recoveryWrapEpoch` | INTEGER | metadato | marca de agua monótona del envoltorio de recuperación |
| `createdAt` / `updatedAt` | INTEGER | metadato | epoch millis |
| `metaRevision` | INTEGER | metadato | última revisión conocida del documento remoto |

### `encrypted_items`

| Columna | Tipo | Sensible | Notas |
|---|---|---|---|
| `itemId` | TEXT PK | no | UUID aleatorio |
| `ciphertext` | BLOB | **cifrado** | AEAD; BLOB vacío si y solo si `tombstone = true` |
| `cryptoVersion` | INTEGER | no | permite estados mixtos durante una rotación |
| `schemaVersion` | INTEGER | no | |
| `revision` | INTEGER | metadato | monótona creciente |
| `tombstone` | INTEGER (bool) | metadato | borrado sincronizable |
| `createdAt` / `updatedAt` | INTEGER | metadato | aproximados, para orden y sincronización |
| `dirty` | INTEGER (bool) | metadato | pendiente de subir |
| `lastSyncedRevision` | INTEGER | metadato | base para detectar conflictos |
| `conflictOf` | TEXT nullable | metadato | `itemId` del original si es una copia en conflicto |
| `pendingRemoteCiphertext` | BLOB nullable | **cifrado** | staging remoto mientras la sesión está bloqueada |
| `pendingRemoteRevision` | INTEGER nullable | metadato | revisión asociada al staging |
| `pendingRemoteCryptoVersion` | INTEGER nullable | metadato | versión criptográfica remota |
| `pendingRemoteSchemaVersion` | INTEGER nullable | metadato | versión de esquema remota |
| `pendingRemoteTombstone` | INTEGER nullable | metadato | estado de borrado remoto |
| `pendingRemoteCreatedAt` / `pendingRemoteUpdatedAt` | INTEGER nullable | metadato | marcas remotas |

Índices v1: `dirty`, `tombstone` y `updatedAt`. No contienen ni derivan texto del usuario.
Los siete campos `pendingRemote*` representan un `EncryptedItemDto` completo y se escriben o
limpian en una única transacción; jamás queda un staging parcial.

### `pending_conflicts`

| Columna | Tipo | Notas |
|---|---|---|
| `itemId` | TEXT PK/FK | ítem local que conserva la edición sin subir |
| `detectedAt` | INTEGER | momento aproximado de detección |
| `remoteRevision` | INTEGER | revisión remota pendiente de resolver |

### `biometric_unlock` (**solo local, jamás sincronizada, jamás en el respaldo**)

| Columna | Tipo | Notas |
|---|---|---|
| `id` | INTEGER PK | fila única |
| `keyAlias` | TEXT | alias versionado de la clave del Keystore |
| `wrappedBiometricKek` | BLOB | `BiometricKEK` cifrada por la clave del Keystore |
| `biometricWrappedVdek` | BLOB | keyset cifrado de Tink bajo la `BiometricKEK` |
| `biometricWrapEpoch` | INTEGER | epoch monótono solo local, autenticado en la AAD biométrica |
| `iv` | BLOB | IV de GCM del `Cipher` del Keystore |
| `strongBoxBacked` | INTEGER (bool) | resultado real observado en el dispositivo |
| `createdAt` | INTEGER | |

### `sync_state`

| Columna | Tipo | Notas |
|---|---|---|
| `id` | INTEGER PK | fila única |
| `lastPullAt` / `lastPushAt` | INTEGER | |
| `lastError` | TEXT nullable | código de error redactado, nunca contenido |

Las preferencias no sensibles (tiempo de bloqueo, bloqueo al pasar a segundo plano, biometría
activada) van en DataStore, **nunca** secretos.

`ownerUid` es una barrera local, no material criptográfico. Antes de abrir Room para una sesión
autenticada se compara con el UID actual. Si no coincide, la bóveda local anterior permanece
bloqueada y no se adopta, desbloquea ni sincroniza bajo la cuenta nueva. El cambio de cuenta exige
haber sincronizado o descartado explícitamente cambios pendientes de la cuenta anterior. Después:

1. bloquear la sesión y cancelar workers/jobs de A;
2. leer el alias biométrico activo de A;
3. en **una transacción Room**, borrar `encrypted_items`, `pending_conflicts`, `sync_state`,
   `biometric_unlock` y `vault_meta`;
4. después del commit, borrar el alias propio de A del Keystore y normalizar la preferencia
   biométrica no sensible; un cierre deja como máximo un alias huérfano que ADR-032 limpia al
   arrancar.

Si el proceso cae antes del commit, `ownerUid=A` sigue bloqueando a B; si cae después, ya no queda
estado de bóveda vinculable. Nunca se mezcla una fila de A con una sesión de B. La prueba
A → cerrar sesión → B inyecta fallo antes y después del commit.

---

## 5. Modelo de Firestore

```
users/{uid}/vaults/{vaultId}                 ← documento de bóveda
users/{uid}/vaults/{vaultId}/items/{itemId}  ← un documento por ítem
```

### Documento de bóveda — campos permitidos (lista cerrada)

`schemaVersion` (int) · `cryptoVersion` (int) · `kdfName` (string) · `kdfMemoryKib` (int) ·
`kdfIterations` (int) · `kdfParallelism` (int) · `kdfOutputLen` (int) · `passwordSalt` (bytes) ·
`passwordWrappedVdek` (bytes) · `recoverySalt` (bytes) · `recoveryWrappedVdek` (bytes) ·
`passwordWrapEpoch` (int) · `recoveryWrapEpoch` (int) · `createdAt` (int) · `updatedAt` (int) ·
`metaRevision` (int)

No hay ningún campo de contenido. **No** se sube el blob biométrico. **No** se sube el `uid` como
parte de la AAD (ADR-009).

### Documento de ítem — campos permitidos (lista cerrada)

`ciphertext` (bytes) · `cryptoVersion` (int) · `schemaVersion` (int) · `revision` (int) ·
`tombstone` (bool) · `createdAt` (int) · `updatedAt` (int)

Límite de tamaño de `ciphertext`: **262 144 bytes** (256 KiB), muy por debajo del límite de
documento de Firestore, comprobado en las reglas y también en el cliente antes de subir.

---

## 6. Security Rules iniciales (diseño)

Cerradas desde el primer commit. Se implementarán y probarán en la Fase 4 en
`firebase/firestore.rules`. Este es el diseño acordado:

```javascript
rules_version = '2';

service cloud.firestore {
  match /databases/{database}/documents {
    function isOwner(uid) {
      return request.auth != null && request.auth.uid == uid;
    }

    function vaultRequiredFields() {
      return ['schemaVersion','cryptoVersion','kdfName','kdfMemoryKib','kdfIterations',
              'kdfParallelism','kdfOutputLen','passwordSalt','passwordWrappedVdek',
              'recoverySalt','recoveryWrappedVdek','passwordWrapEpoch','recoveryWrapEpoch',
              'createdAt','updatedAt','metaRevision'];
    }

    function vaultOptionalFields() {
      return [];
    }

    function validVault(d) {
      return d.keys().hasOnly(vaultRequiredFields().concat(vaultOptionalFields()))
          && d.keys().hasAll(vaultRequiredFields())
          && d.schemaVersion is int && d.schemaVersion >= 1
          && d.cryptoVersion is int && d.cryptoVersion >= 1
          && d.kdfName is string && d.kdfName == 'argon2id'
          && d.kdfMemoryKib is int && d.kdfMemoryKib == 65536
          && d.kdfIterations is int && d.kdfIterations == 3
          && d.kdfParallelism is int && d.kdfParallelism == 4
          && d.kdfOutputLen is int && d.kdfOutputLen == 32
          && d.passwordSalt is bytes && d.passwordSalt.size() == 16
          && d.recoverySalt is bytes && d.recoverySalt.size() == 32
          && d.passwordWrappedVdek is bytes && d.passwordWrappedVdek.size() > 0
          && d.passwordWrappedVdek.size() <= 8192
          && d.recoveryWrappedVdek is bytes && d.recoveryWrappedVdek.size() > 0
          && d.recoveryWrappedVdek.size() <= 8192
          && d.passwordWrapEpoch is int && d.passwordWrapEpoch >= 1
          && d.recoveryWrapEpoch is int && d.recoveryWrapEpoch >= 1
          && d.createdAt is int && d.createdAt > 0
          && d.updatedAt is int && d.updatedAt >= d.createdAt
          && d.updatedAt <= request.time.toMillis() + 300000
          && d.metaRevision is int && d.metaRevision >= 1;
    }

    function passwordWrapFieldsChanged() {
      return request.resource.data.diff(resource.data).affectedKeys().hasAny(
        ['kdfName','kdfMemoryKib','kdfIterations','kdfParallelism','kdfOutputLen',
         'passwordSalt','passwordWrappedVdek']
      );
    }

    function recoveryWrapFieldsChanged() {
      return request.resource.data.diff(resource.data).affectedKeys().hasAny(
        ['recoverySalt','recoveryWrappedVdek']
      );
    }

    function validWrapEpochUpdate() {
      return ((passwordWrapFieldsChanged()
                 && request.resource.data.passwordWrapEpoch > resource.data.passwordWrapEpoch)
              || (!passwordWrapFieldsChanged()
                 && request.resource.data.passwordWrapEpoch == resource.data.passwordWrapEpoch))
          && ((recoveryWrapFieldsChanged()
                 && request.resource.data.recoveryWrapEpoch > resource.data.recoveryWrapEpoch)
              || (!recoveryWrapFieldsChanged()
                 && request.resource.data.recoveryWrapEpoch == resource.data.recoveryWrapEpoch));
    }

    function itemRequiredFields() {
      return ['ciphertext','cryptoVersion','schemaVersion','revision','tombstone',
              'createdAt','updatedAt'];
    }

    function itemOptionalFields() {
      return [];
    }

    function validItem(d) {
      return d.keys().hasOnly(itemRequiredFields().concat(itemOptionalFields()))
          && d.keys().hasAll(itemRequiredFields())
          && d.ciphertext is bytes && d.ciphertext.size() <= 262144
          && ((d.tombstone == true && d.ciphertext.size() == 0)
              || (d.tombstone == false && d.ciphertext.size() > 0))
          && d.cryptoVersion is int && d.cryptoVersion >= 1
          && d.schemaVersion is int && d.schemaVersion >= 1
          && d.revision is int && d.revision >= 1
          && d.tombstone is bool
          && d.createdAt is int && d.createdAt > 0
          && d.updatedAt is int && d.updatedAt >= d.createdAt
          && d.updatedAt <= request.time.toMillis() + 300000;
    }

    match /users/{uid}/vaults/{vaultId} {
      allow get, list: if isOwner(uid);
      allow create: if isOwner(uid) && validVault(request.resource.data);
      allow update: if isOwner(uid)
                    && validVault(request.resource.data)
                    && request.resource.data.createdAt == resource.data.createdAt
                    && request.resource.data.updatedAt >= resource.data.updatedAt
                    && request.resource.data.metaRevision > resource.data.metaRevision
                    && request.resource.data.cryptoVersion >= resource.data.cryptoVersion
                    && request.resource.data.schemaVersion >= resource.data.schemaVersion
                    && request.resource.data.kdfMemoryKib >= resource.data.kdfMemoryKib
                    && request.resource.data.kdfIterations >= resource.data.kdfIterations
                    && request.resource.data.kdfParallelism >= resource.data.kdfParallelism
                    && request.resource.data.kdfOutputLen == resource.data.kdfOutputLen
                    && validWrapEpochUpdate();
      allow delete: if isOwner(uid);

      match /items/{itemId} {
        allow get, list: if isOwner(uid);
        allow create: if isOwner(uid) && validItem(request.resource.data)
                      && request.resource.data.revision >= 1;
        allow update: if isOwner(uid) && validItem(request.resource.data)
                      && request.resource.data.createdAt == resource.data.createdAt
                      && request.resource.data.updatedAt >= resource.data.updatedAt
                      && request.resource.data.revision > resource.data.revision
                      && request.resource.data.cryptoVersion >= resource.data.cryptoVersion
                      && request.resource.data.schemaVersion >= resource.data.schemaVersion;
        allow delete: if isOwner(uid) && resource.data.tombstone == true;
      }
    }

    match /{document=**} {
      // Documental: Firestore ya deniega por defecto toda ruta sin allow.
      allow read, write: if false;
    }
  }
}
```

Notas de diseño:

- El `uid` de la ruta es la única fuente de propiedad: no existe campo `owner` que se pueda
  falsificar ni reasignar.
- `revision` y `metaRevision` estrictamente crecientes evitan retrocesos relativos.
- `tombstone == true` equivale a `ciphertext` vacío. El propietario solo puede borrar físicamente
  un ítem ya enterrado; el documento de bóveda se puede borrar en la eliminación total.
- El perfil v1 cerrado del KDF y los epochs independientes se comprueban también en las reglas.
  Cambiar campos de un envoltorio exige incrementar **solo** su epoch; cambiar un epoch sin cambiar
  su envoltorio también se deniega. La AAD es defensa en profundidad; el perfil cerrado es la
  protección primaria.
- Los campos obligatorios y opcionales están separados. Un campo nuevo entra primero como opcional
  y solo pasa a obligatorio cuando no queden clientes antiguos. Mientras las listas productivas
  estén vacías, una fixture local de reglas añade únicamente `migrationFixture` a la lista opcional
  y prueba el mismo documento presente/ausente, un campo no listado denegado y su promoción a
  obligatorio. Al aparecer el primer campo opcional real, esa prueba se replica contra producción.
- La tolerancia de reloj del borrador es cinco minutos. Su semántica exacta, `List.concat()` y los
  límites de `bytes.size()` se validarán contra el Emulator Suite antes de considerar estas reglas
  implementadas.
- Las reglas **no sustituyen al cifrado**: si fallaran, un atacante obtendría ciphertext.

---

## 7. Patrón de presentación

MVVM con estado unidireccional (ADR-003):

- `data class XxxUiState` inmutable por pantalla, expuesto como `StateFlow<XxxUiState>`.
- Eventos de un disparo (`navegar`, `mostrar error`) por `Channel`, fuera del estado.
- Los composables son función del estado; sin lógica de negocio ni criptografía.
- Todo diálogo o hoja modal usa el componente único `SecureDialog`, con
  `SecureFlagPolicy.SecureOn`; una prueba de higiene prohíbe crear ventanas paralelas fuera de él.
- Prohibido `rememberSaveable` y `SavedStateHandle` para contenido sensible: sobreviven a la
  muerte del proceso vía `Bundle`.
- Al bloquear, cada ViewModel sensible descarta su estado; `VaultSession` es la única fuente de
  verdad sobre si la sesión está abierta.

---

## 8. Sesión y bloqueo

```
VaultSession (singleton de Hilt, en memoria)
  estado público: Locked | Unlocked(vaultId, openedAt)   (sin material de clave)
  campo private: UnlockedVault opaca de :core:crypto; el Aead no cruza el módulo
  lo dispara a Locked:  temporizador de inactividad · paso a segundo plano (opcional)
                        · «bloquear ahora» · invalidación de la clave del Keystore
                        · cierre de sesión de Firebase
  al bloquear: borra buffers, descarta el Aead, cancela los Job sensibles,
               limpia los UiState y navega a desbloqueo
```

Ninguna clase de UI recibe la primitiva ni conserva referencias al material de clave. La sesión se
observa con Flow y ninguna pantalla sensible se compone si la sesión no está abierta.

---

## 9. Qué queda fuera de esta arquitectura

Otros clientes, compartir ítems, adjuntos binarios, búsqueda en el servidor, SQLCipher y fusión a
nivel de campo. Ver las puertas de `docs/SECURITY_GATES.md`.
