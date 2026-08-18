# BACKUP_FORMAT.md — Formato del respaldo cifrado

Formato del archivo de exportación y restauración (ADR-016). Estado: **exportación,
restauración local y publicación remota con control de conflictos implementadas**. La
publicación se prueba con fuentes remotas falsas; no se desplegó ni usó Firebase real.

---

## 1. Propiedades

- El archivo **nunca contiene plaintext**: solo ciphertext, material envuelto y parámetros
  públicos.
- Es **autocontenido**: se puede restaurar sin Firebase y en otra cuenta, porque la AAD no incluye
  el uid (ADR-009).
- Está **versionado** y su versión se valida antes de interpretar su estructura completa.
- El manifiesto canónico completo queda autenticado con Tink AEAD bajo la VDEK. Quitar ítems,
  cambiar activo/tombstone o metadatos, o mezclar ciphertext invalida el archivo.
- **No incluye** el blob biométrico local ni la clave del Keystore: son específicos del dispositivo.
- La restauración exige **contraseña maestra o frase de 24 palabras**.

---

## 2. Estructura

Archivo de texto UTF-8 con un objeto JSON en la raíz. Extensión recomendada `.bwvault`. El nombre
del archivo **no** se interpreta: no aporta información y no se confía en él.

```json
{
  "magic": "bw-vault-backup",
  "formatVersion": 2,
  "cryptoVersion": 1,
  "schemaVersion": 1,
  "passwordWrapEpoch": 1,
  "recoveryWrapEpoch": 1,
  "vaultId": "<uuid>",
  "createdAt": 1,
  "updatedAt": 1,
  "metaRevision": 1,
  "kdf": {
    "name": "argon2id",
    "memoryKib": 65536,
    "iterations": 3,
    "parallelism": 4,
    "outputLen": 32,
    "salt": "<base64url del passwordSalt>"
  },
  "recovery": {
    "kdf": "hkdf-sha256",
    "outputLen": 32,
    "salt": "<base64url del recoverySalt>",
    "entropyBits": 256,
    "words": 24,
    "wordlist": "english"
  },
  "passwordWrappedVdek": "<base64url>",
  "recoveryWrappedVdek": "<base64url>",
  "manifestAuthenticator": "<base64url del autenticador Tink>",
  "items": [
    {
      "itemId": "<uuid>",
      "ciphertext": "<base64url>",
      "cryptoVersion": 1,
      "schemaVersion": 1,
      "revision": 1,
      "tombstone": false,
      "createdAt": 1,
      "updatedAt": 1
    }
  ]
}
```

Codificación binaria: **base64url sin relleno**, la misma que se usa en la AAD para los salts, de
modo que la reconstrucción de la AAD sea idéntica byte a byte.

En v2, el manifiesto canónico es el mismo objeto JSON sin `manifestAuthenticator`, con los campos
en el orden de §2 y los ítems ordenados por `itemId`. Tink AES-256-GCM cifra un plaintext vacío con
`bw2|backup-manifest|` seguido de esos bytes canónicos como AAD. El ciphertext Tink resultante se
guarda en `manifestAuthenticator`; no es una primitiva propia ni expone la VDEK.

Se eligió JSON en lugar de un formato binario propio porque es inspeccionable —el usuario puede
comprobar por sí mismo que no hay texto legible dentro— y porque un formato binario propio añadiría
un parser más frágil sin beneficio de seguridad. El coste es ~33 % de tamaño extra por base64.

---

## 3. Validación al importar (parser defensivo)

En este orden, y **fallando cerrado** en cualquier punto:

1. Tamaño total del archivo ≤ **8 MiB**. Se comprueba antes de leerlo en memoria. Este límite
   conservador acota la amplificación del lector, el `String`, el árbol JSON y los buffers Base64
   en dispositivos Android.
2. Antes de construir el árbol JSON, un escáner acotado limita profundidad, miembros por objeto,
   elementos por array y cantidad global de valores; también rechaza claves duplicadas (incluidos
   escapes equivalentes). Después se parsea con esquema **estricto**: campos desconocidos →
   rechazo; sin polimorfismo abierto ni deserialización de tipos arbitrarios.
3. `magic` debe ser exactamente `bw-vault-backup`.
4. `formatVersion` debe ser `2`. v1 se rechaza porque no autenticaba el manifiesto completo; una
   versión futura desconocida se clasifica como no soportada, no como JSON malformado.
5. `cryptoVersion` y `schemaVersion` conocidas; si no, rechazo.
6. `kdf.name` debe ser exactamente `argon2id`; para `cryptoVersion = 1`, memoria
   `65 536` KiB, iteraciones `3`, paralelismo `4` y `outputLen == 32`, todos exactos. Se valida el
   perfil **antes** de reservar memoria.
7. Longitudes exactas: `kdf.salt` 16 B y `recovery.salt` 32 B; cada `WrappedVdek` 1–8 192 B y
   `manifestAuthenticator` 1–256 B.
   Recuperación: `kdf == "hkdf-sha256"`, `entropyBits == 256`, `words == 24` y
   `wordlist == "english"`.
8. `createdAt > 0`, `updatedAt >= createdAt`, `metaRevision >= 1`;
   `passwordWrapEpoch` y `recoveryWrapEpoch` son enteros ≥ 1. Una restauración explícita
   autenticada puede importar epochs inferiores a las marcas locales; no se aplican directamente,
   sino que se reemiten según §5. La sincronización remota ordinaria sí rechaza todo retroceso.
9. `items`: máximo **5 000** elementos; cada `ciphertext` 0–262 144 B; `itemId` con formato de
   UUID; sin duplicados; `revision` ≥ 1; tipos correctos; `tombstone == true` si y solo si
   `ciphertext` está vacío.
10. Suma de tamaños de los ciphertext y del JSON codificado ≤ el límite total del archivo.
11. Solo después de toda la validación estructural se intenta desenvolver la VDEK. Antes de
    modificar Room o conceder la capacidad de publicación, la VDEK debe abrir el autenticador con
    el manifiesto canónico exacto; cualquier fallo se clasifica como integridad.

Nada de lo que hay en el archivo se ejecuta, se interpreta como ruta ni se usa como nombre de
archivo. No hay riesgo de path traversal porque **no se escribe ningún archivo derivado del
contenido**: los ítems van a Room.

---

## 4. Flujo de exportación

```
requiere: sesión desbloqueada + reautenticación (contraseña maestra o credencial fuerte)
1. dentro de una única transacción Room leer `vault_meta`, consultar en SQL `COUNT(*)`,
   `SUM(LENGTH(ciphertext))` y `MAX(LENGTH(ciphertext))`, rechazar un ciphertext de 256 KiB + 1
   antes de cargar blobs, cargar la lista, recalcular el total real y construir el snapshot
   coherente
2. aplicar una cota conservadora de expansión Base64 + JSON; rechazar si hay más de 5 000 ítems,
   un CT excede 256 KiB o el JSON podría superar 8 MiB. Solo si pasan el preflight y el recálculo
   transaccional se conservan los ciphertext; después de validar y autenticar se abre y escribe
   el destino
3. reautenticar la contraseña, construir el manifiesto canónico, generar su autenticador Tink con
   la VDEK y serializar el JSON de §2 (no se descifra ningún ítem: el CT se copia tal cual)
4. escribir mediante el selector de documentos del sistema (SAF), sin permisos de almacenamiento
5. advertir: «este archivo se abre con tu contraseña maestra o con tus 24 palabras;
   guárdalo como guardarías la bóveda»
```

El respaldo **no** se descifra durante la exportación: se copian los mismos ciphertext, con lo que
no hay ventana adicional de plaintext en disco.

---

## 5. Flujo de restauración

```
1. seleccionar archivo (SAF) → lector acotado a 8 MiB → validación completa de §3
2. el usuario elige la vía:
     a) contraseña maestra → PasswordKEK con los parámetros y el salt DEL ARCHIVO
     b) frase de 24 palabras → RecoveryKEK con el recoverySalt DEL ARCHIVO
3. desenvolver la VDEK con la AAD reconstruida a partir del vaultId y los parámetros del archivo
     fallo → error genérico; el archivo no se aplica
4. reconstruir en memoria el segundo camino de acceso para poder verificar ambos envoltorios:
     a) si se usó la contraseña, generar y verificar una frase de recuperación nueva
     b) si se usó la frase, definir y confirmar una contraseña maestra nueva
5. verificar `manifestAuthenticator` con la VDEK restaurada contra el manifiesto canónico completo
   antes de modificar Room o crear una autorización de publicación; fallo → error de integridad
6. si se publicará ahora en Firebase, leer un snapshot remoto del documento y de todos sus ítems.
   `cryptoVersion`/`schemaVersion` desconocidas o superiores bloquean la publicación. Si el remoto
   existe, solo se permite la publicación directa cuando `metaRevision`, identificadores,
   revisiones, tombstones y ciphertext coinciden con la línea base del respaldo; cualquier avance,
   ausencia o ítem extra se muestra como conflicto y no se sobrescribe;
   crear ambos envoltorios con la misma VDEK y parámetros de producción; asignar a cada uno
   `max(epochDelArchivo, marcaLocalDelCamino, epochRemotoDelCamino)+1` (los valores inexistentes se
   consideran `0`);
   desenvolver ambos en memoria y comprobar que coinciden antes de persistir
7. bloquear la sesión y, bajo la misma barrera usada por sincronización y escrituras locales,
   escribir `vault_meta` y los ítems en Room de forma transaccional; si falla, no reabrir la sesión
8. subir a Firestore si el usuario lo desea. Inmediatamente antes, releer el snapshot. Para una
   creación ausente, crear como línea base la metadata exacta del respaldo, subir cada ítem con
   creación idempotente y, solo cuando el conjunto remoto coincida, aplicar CAS de la metadata
   baseline a los envoltorios reemitidos. Un corte durante los ítems conserva la autorización en
   memoria y el reintento continúa el subconjunto seguro; el CAS final se ejecuta una sola vez.
   Si el remoto ya era la línea base, no se reescriben sus ítems. Una edición concurrente nunca se
   sobrescribe y el siguiente pull la trata con el protocolo normal de conflictos.
   Si no hubo red, la restauración queda local. Antes de una publicación diferida se vuelve a leer
   el snapshot completo: si avanzó, se muestra conflicto accionable y se exige repetir la
   importación en línea. Para reemplazar deliberadamente una versión remota diferente, el usuario
   debe ejecutar primero el flujo separado de eliminación total, fuertemente confirmado, verificar
   que la ruta quedó vacía y después publicar como creación; nunca se mezcla ni sobreescribe
   parcialmente una versión más nueva
9. mantener la sesión bloqueada y navegar explícitamente a desbloqueo; el archivo restaurado
   solo se abre mediante una nueva autenticación
```

**Restaurar reemplaza la bóveda local.** Si ya existía una bóveda con otro `vaultId`, la aplicación
exige una confirmación fuerte y explica que el contenido local actual se sustituirá. Nunca se
mezclan dos bóvedas con `vaultId` distinto, porque la AAD ata cada ciphertext a su `vaultId`.

---

## 6. Pruebas obligatorias (Fase 8)

- El archivo exportado no contiene ninguna cadena señuelo (prueba de fuga).
- Ida y vuelta: exportar → restaurar → todas las notas se descifran igual.
- Restauración con contraseña maestra **y** con frase de recuperación.
- Rechazo de: `magic` incorrecto, versión desconocida, campo desconocido, tipo incorrecto,
  clave JSON duplicada (incluidas claves equivalentes por escape),
  parámetros fuera del perfil v1 cerrado, `itemId` duplicado, ciphertext vacío cuando no es tombstone,
  ciphertext por encima del límite, tombstone con contenido, archivo por encima del límite total,
  JSON truncado, base64 inválido, epochs menores que 1 y salts de longitud incorrecta.
- Restaurar explícitamente un respaldo anterior a las marcas locales reemite ambos envoltorios
  con epochs nuevos y no lo confunde con un pull remoto regresivo.
- Restaurar un respaldo antiguo en una instalación limpia mientras el mismo `vaultId` remoto tiene
  epochs o estado mayores: la restauración local funciona, pero la publicación se bloquea como
  conflicto antes de escribir; no se intenta degradar ni fusionar silenciosamente.
- Un remoto con `metaRevision`, ítems, revisiones, tombstones, ciphertext o versiones distintos se
  bloquea antes de sobrescribir; una precondición del documento de bóveda que cambia aborta su
  transacción y ninguna edición remota de ítem se reescribe.
- La exportación rechaza antes de cargar los blobs o abrir el destino 5 001 ítems y un archivo
  estimado por encima de 8 MiB; la prueba instrumentada usa múltiples ciphertext máximos y no OOM.
- Antes de persistir una restauración, ambos envoltorios se abren y producen la misma VDEK.
- Fuzzing con **semilla fija impresa en el fallo**: bytes aleatorios, truncados, longitudes
  declaradas falsas, anidamiento profundo y números fuera de rango.
- Ningún caso provoca `OutOfMemoryError`, bucle infinito ni aceptación parcial del archivo.
- El respaldo **no** contiene el blob biométrico (verificado por prueba).
- El autenticador detecta eliminación de ítems, cambios activo/tombstone, metadatos alterados y
  mezcla de ciphertext; v1 y toda versión futura se rechazan como versiones no soportadas.
- Una interrupción determinista tras subir un subconjunto de ítems conserva la autorización; el
  reintento completa solo los faltantes y finaliza los wrappers mediante CAS.

---

## 7. Compatibilidad futura

`formatVersion` se incrementa cuando cambia la estructura. La única versión soportada es v2. v1 se
retiró conscientemente porque aceptar un manifiesto no autenticado reabriría un hallazgo alto; se
clasifica como no soportada. Toda versión futura exige ADR, migración explícita y una prueba propia.
