# docs/sync-protocol.md — Protocolo de sincronización

Local-first, solo ciphertext (ADR-015). El protocolo está implementado y se verifica con pruebas
JVM, instrumentadas y de reglas; ver `PROJECT_STATE.md` y `docs/TEST_STRATEGY.md`.

---

## 1. Principios

1. La escritura del usuario va **primero a Room**; la subida es asíncrona. La aplicación funciona
   por completo sin conexión.
2. A Firestore solo viaja `ciphertext` y metadatos. El cifrado ocurre antes de la capa remota.
3. Cada ítem tiene `revision` monótona creciente. La revisión la asigna el cliente que escribe.
4. Todo borrado se propaga primero como **tombstone con ciphertext vacío**. La eliminación física
   solo se permite después de que el documento ya esté enterrado o durante la eliminación total.
5. Un conflicto **no se resuelve sobrescribiendo en silencio**.

---

## 2a. Estado por ítem

| Campo | Significado |
|---|---|
| `revision` | versión lógica del ítem; se incrementa en cada edición local |
| `lastSyncedRevision` | revisión que se confirmó subida o bajada; base de comparación |
| `dirty` | hay cambios locales sin subir |
| `tombstone` | el ítem está borrado y el borrado debe propagarse |
| `conflictOf` | si no es nulo, este ítem es una copia local en conflicto del `itemId` indicado |
| `pendingRemoteCiphertext` | versión remota cifrada en staging mientras la sesión está bloqueada |
| `pendingRemoteRevision` | revisión asociada al staging |
| `pendingRemoteCryptoVersion` / `pendingRemoteSchemaVersion` | versiones remotas asociadas |
| `pendingRemoteTombstone` | estado de borrado remoto |
| `pendingRemoteCreatedAt` / `pendingRemoteUpdatedAt` | marcas de tiempo remotas |

El cliente conserva además la mayor revisión remota aceptada por `itemId`. Una revisión inferior a
esa marca de agua se rechaza y se informa; no se aplica en silencio.
Todos los campos `pendingRemote*` se escriben y limpian atómicamente: juntos representan el DTO
remoto cifrado completo.

## 2b. Adopción de una bóveda existente

Un dispositivo nuevo no tiene `vault_meta`, así que no puede desbloquear hasta descubrir y guardar
los parámetros públicos y envoltorios remotos:

```
autenticar con Firebase
  → si vault_meta local existe y ownerUid == uid autenticado: continuar
  → si vault_meta local existe y ownerUid != uid autenticado:
       cerrar la sesión de bóveda; nunca leer, desbloquear ni sincronizar esa fila con la cuenta nueva
       exigir sincronizar o descartar explícitamente cambios dirty de la cuenta anterior
       tras confirmación, transacción Room borra ítems, staging/conflictos, estado de sync,
       registro biométrico y vault_meta; después borrar alias Keystore propio y preferencia
       biométrica; reiniciar el descubrimiento para el uid nuevo
  → si no existe: listar users/{uid}/vaults
       cero documentos → ofrecer crear una bóveda
       un documento    → adoptar: insertar vault_meta con ownerUid local,
                          passwordWrapEpoch, recoveryWrapEpoch y metaRevision remotos
       más de uno      → preguntar al usuario; nunca elegir automáticamente
  → permitir desbloqueo por contraseña o recuperación
  → solo después, iniciar el pull de ítems
```

No existe nombre de bóveda en claro. La selección, si hubiera varios documentos, muestra
identificadores abreviados y metadatos mínimos. Las reglas no acotan todavía el número de bóvedas ni
el tamaño de `list`; ese riesgo queda asignado a la Fase 4.
`ownerUid` es exclusivamente local: no se añade a Firestore, a la AAD ni al respaldo.

---

## 3. Subida (push)

```
para cada ítem con dirty = true:
    remoto = leer documento remoto
    si no existe:
        crear con revision = local.revision (la regla acepta cualquier revision >= 1)
    si existe y remoto.revision == local.lastSyncedRevision:
        actualizar con revision = local.revision   (la regla exige revision creciente)
    si existe y remoto.revision  > local.lastSyncedRevision:
        → CONFLICTO (ver §5)
    éxito → dirty = false; lastSyncedRevision = local.revision
    fallo de red → se reintenta (ver §6); el ítem sigue dirty
```

Antes de subir, el cliente comprueba que `ciphertext.size() <= 262144`; si un ítem excediera el
límite, se rechaza en la aplicación con un error claro en lugar de fallar en las reglas.

---

## 4. Bajada (pull)

```
consultar los ítems del usuario con updatedAt posterior a lastPullAt
para cada documento remoto:
    si remoto.revision < marcaDeAgua[itemId] → rechazar y avisar
    si no existe en local:
        insertar (CT + M), dirty = false, lastSyncedRevision = remoto.revision
    si existe y local.dirty == false:
        si remoto.revision > local.revision → reemplazar local
        si remoto.revision <= local.revision → ignorar
    si existe y local.dirty == true:
        si remoto.revision > local.lastSyncedRevision → CONFLICTO (§5)
        si no → conservar el local (se subirá en el próximo push)
tombstone remoto = true → marcar el local como tombstone y descartar su ciphertext
al terminar una página correcta → lastPullAt = min(reloj local actual, instante de finalización)
```

No se usa el máximo `updatedAt` recibido como cursor: un reloj de cliente adelantado podría dejar
ítems posteriores sin bajar. Las reglas acotan las marcas de tiempo y exigen que no retrocedan; la
paginación por una revisión global queda fuera del MVP como riesgo R-09.

Los ítems con `tombstone` se ocultan en la interfaz y su ciphertext queda como BLOB vacío local y
remotamente. La purga física solo borra documentos que ya son tombstones, después de una política
de retención que se definirá y probará antes de activarla.

---

## 5. Conflictos

Se detecta conflicto cuando **el registro local tiene cambios sin subir** (`dirty`) **y** la
revisión remota avanzó por encima de `lastSyncedRevision`.

Resolución del MVP, **sin pérdida silenciosa**:

```
1. la versión remota se acepta como la versión «oficial» del itemId
2. la versión local se copia a un ítem NUEVO con itemId nuevo y conflictOf = itemId original
   (se recifra con la AAD del nuevo itemId, porque la AAD liga el ciphertext a su itemId)
3. la copia en conflicto se marca como dirty y se sube como un ítem más
4. la interfaz avisa al usuario de que existe una copia en conflicto y le permite comparar,
   quedarse con una y borrar la otra
```

Por qué no last-write-wins puro: los requisitos lo permitían para el MVP, pero pierde datos en
silencio. El coste de este diseño es un ítem duplicado que el usuario debe resolver; el beneficio
es que **ninguna edición se pierde**. Riesgo documentado: si el usuario ignora los avisos,
acumulará duplicados.

Detalle importante: al copiar la versión local a un `itemId` nuevo hay que **recifrar** el
contenido, porque la AAD incluye el `itemId` (ADR-009). Eso exige que la sesión esté desbloqueada.
Si está bloqueada, el worker guarda atómicamente el DTO remoto completo en todos los campos
`pendingRemote*`, inserta `pending_conflicts(itemId, detectedAt, remoteRevision)` y conserva el
ciphertext local **intacto byte a byte**. Al desbloquear, el caso de uso resuelve el staging sin
depender de otra lectura de red, recifra la copia local y limpia todos los campos y la fila
pendiente en otra transacción.

---

## 6. Reintentos y programación

- La sincronización se ejecuta con WorkManager, con restricción de red conectada y backoff
  exponencial.
- Los datos de entrada del worker **no contienen secretos**: solo identificadores y banderas.
- El worker no necesita la sesión desbloqueada para subir ni bajar ciphertext; sí la necesita para
  resolver conflictos (§5).
- Errores clasificados: transitorios (red, indisponibilidad) → reintento; permanentes (regla
  denegada, versión no soportada) → se registra un código de error redactado en `sync_state` y se
  informa en la interfaz sin reintentar en bucle.
- Un fallo de sincronización **nunca** bloquea el uso local de la aplicación.

---

## 7. Documento de bóveda

El documento de bóveda usa `metaRevision` con la misma lógica de revisión creciente. Las
operaciones que lo tocan son atómicas y poco frecuentes: cambio de contraseña, recalibración de
parámetros, recuperación y regeneración de la frase. Cada una incrementa únicamente el epoch del
envoltorio que reescribe: `passwordWrapEpoch` o `recoveryWrapEpoch`.
Las reglas exigen monotonía relativa de `metaRevision`, de ambos epochs, versiones y parámetros; el
cliente conserva una marca de agua local por envoltorio y rechaza retrocesos.

Esto impide que un cliente honesto acepte en silencio el envoltorio almacenado anterior, pero no
borra copias ya obtenidas. Cambiar la contraseña o regenerar la frase invalida el envoltorio
**actual**; la revocación completa de una credencial comprometida exige rotar la VDEK y recifrar.

---

## 8. Metadatos que la sincronización expone

Marcas de tiempo, número de ítems, tamaño aproximado de cada ítem y frecuencia de cambios. No se
ofusca en el MVP; está declarado como riesgo residual en `THREAT_MODEL.md` (A-7, T-06).

---

## 9. Pruebas del protocolo (Fase 5)

- Crear y editar sin conexión; subir al recuperar la conexión.
- Bajar ítems creados en otro cliente simulado.
- Propagación de tombstones en ambos sentidos.
- Conflicto: se conserva la copia local y no se sobrescribe.
- Conflicto con la sesión bloqueada: staging cifrado, local intacto y resolución al desbloquear.
- Reintento tras fallo transitorio; no hay bucle infinito ante un fallo permanente.
- Un ítem que excede el límite de tamaño se rechaza en el cliente.
- Primera subida con `revision > 1` aceptada.
- Adopción: cero, una y múltiples bóvedas remotas.
- Revisión, `passwordWrapEpoch` o `recoveryWrapEpoch` inferior a su marca de agua local: rechazo
  visible.
- Cambio de cuenta A → cerrar sesión → B: B no observa, desbloquea ni sincroniza la bóveda local
  de A; los cambios pendientes de A no se borran sin confirmación explícita y los fallos inyectados
  alrededor del commit dejan íntegramente A o un estado local vacío.
- Conflicto remoto con versión criptográfica distinta y conflicto tombstone: ambos se resuelven
  sin red desde el staging completo después de desbloquear.
- Tombstone implica ciphertext vacío y solo un tombstone se puede purgar físicamente.
- Un `updatedAt` adelantado no mueve `lastPullAt` más allá del reloj local.
- Ninguna ruta remota acepta un modelo de dominio descifrado (verificado por el grafo de módulos y
  por prueba de higiene).
