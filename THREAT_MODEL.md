# THREAT_MODEL.md — Modelo de amenazas de Bóveda Wilson

Qué protege Bóveda Wilson, contra quién, y **qué no protege**. Este documento no afirma en
ningún punto que la aplicación sea invulnerable.

Estado: especificado en la Etapa 1. Las mitigaciones marcadas como *pendiente* todavía no están
implementadas; ver `PROJECT_STATE.md`.

---

## 1. Activos

| # | Activo | Consecuencia si se compromete |
|---|---|---|
| A-1 | Contenido de las notas (título, cuerpo, etiquetas, campos) | pérdida total de confidencialidad |
| A-2 | Contraseña maestra | acceso completo a la bóveda, en cualquier dispositivo |
| A-3 | Frase de 24 palabras / entropía de recuperación | acceso completo a la bóveda, en cualquier dispositivo |
| A-4 | VDEK sin envolver | acceso completo al contenido sincronizado |
| A-5 | KEK derivadas (PasswordKEK, RecoveryKEK) | permiten desenvolver la VDEK |
| A-6 | Blob biométrico local + clave del Keystore | acceso a la bóveda **en ese dispositivo** |
| A-7 | Metadatos (número de ítems, marcas de tiempo, tamaños, frecuencia) | inferencias sobre la vida del usuario |
| A-8 | Credenciales de Firebase del usuario | descarga y borrado de ciphertext; **no** descifrado |
| A-9 | Integridad y disponibilidad de los datos | pérdida o corrupción de notas |

---

## 2. Límites de confianza

```
┌─ Dispositivo del usuario ──────────────────────────────────────────────┐
│  ┌─ Proceso de la app ─────────────────────────────────────────────┐   │
│  │  CONFIABLE mientras la bóveda está desbloqueada:                │   │
│  │  plaintext en memoria, VDEK viva, KEK transitorias              │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│  ┌─ Almacenamiento local ──────────────────────────────────────────┐   │
│  │  NO CONFIABLE: solo ciphertext + metadatos + blob biométrico    │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│  ┌─ TEE / Keystore ────────────────────────────────────────────────┐   │
│  │  Confianza acotada al hardware: clave no exportable             │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└────────────────────────── frontera de red (solo HTTPS) ────────────────┘
┌─ Firebase (NO CONFIABLE por diseño) ───────────────────────────────────┐
│  Authentication: identidad y autorización, nunca descifrado            │
│  Firestore: ciphertext, identificadores aleatorios, versiones,         │
│             marcas de tiempo. Se asume que puede filtrarse por completo│
└────────────────────────────────────────────────────────────────────────┘
┌─ Cadena de suministro (NO CONFIABLE) ──────────────────────────────────┐
│  Dependencias, Gradle, SDK, herramientas de compilación                │
└────────────────────────────────────────────────────────────────────────┘
```

Supuesto rector: **una filtración completa de Firestore no debe revelar contenido.** La seguridad
no depende de que Firebase sea impenetrable.

---

## 3. Adversarios considerados

| # | Adversario | Capacidad asumida |
|---|---|---|
| ADV-1 | Ladrón oportunista | posee el dispositivo bloqueado |
| ADV-2 | Ladrón con el dispositivo desbloqueado | acceso a la interfaz en ese momento |
| ADV-3 | Analista forense | extrae el almacenamiento del dispositivo, sin las credenciales |
| ADV-4 | Atacante de Firebase | dump completo de Firestore y de Authentication |
| ADV-5 | Atacante con la cuenta de Google del usuario | inicia sesión como el usuario en la app |
| ADV-6 | Malware en el dispositivo | root, accesibilidad, captura de pantalla o de teclado |
| ADV-7 | Ingeniería social / phishing | engaña al usuario para que revele contraseña o frase |
| ADV-8 | Atacante de la cadena de suministro | dependencia maliciosa o repositorio del desarrollador comprometido |
| ADV-9 | Observador de red | intercepta el tráfico |

---

## 4. Amenazas y mitigaciones

### T-01 — Robo del teléfono **bloqueado** (ADV-1, ADV-3)
El atacante extrae el almacenamiento. Encuentra ciphertext, salts, envoltorios y metadatos.
**Mitigación:** todo el contenido está cifrado con la VDEK; la VDEK solo existe envuelta;
las KEK exigen la contraseña maestra, la frase o autenticación en el propio dispositivo. La clave
del Keystore requiere que el dispositivo esté desbloqueado (`setUnlockedDeviceRequired`).
**Residual:** metadatos visibles (A-7). El ataque de diccionario sobre `passwordWrappedVdek` es
posible offline, encarecido por Argon2id: **la fortaleza de la contraseña maestra es determinante**.

### T-02 — Robo del teléfono **desbloqueado con la bóveda abierta** (ADV-2)
**Mitigación:** bloqueo automático por inactividad con valor seguro por defecto, opción de
bloqueo inmediato al pasar a segundo plano, «bloquear ahora», reautenticación para exportar,
cambiar contraseña, regenerar la frase o eliminar la bóveda, campos secretos ocultos por defecto,
sin copia automática al portapapeles. Implementado; las comprobaciones visuales aplicables se
registran en `docs/security-checklist.md`.
**Residual:** durante la ventana en que está abierta, el contenido visible es accesible. No se
puede evitar. La frase de recuperación **no** es accesible ni en este escenario (ADR-011).

### T-03 — Malware con servicio de accesibilidad o captura de pantalla (ADV-6)
**Mitigación parcial:** `FLAG_SECURE` en la actividad y en cada diálogo/hoja modal mediante
`SecureDialog`, contenido oculto en recientes, ausencia de contenido sensible en notificaciones,
portapapeles marcado como sensible y borrado automático.
**Residual — fuera de alcance:** un servicio de accesibilidad malicioso o un teclado
comprometido pueden leer lo que el usuario ve y escribe. **Ninguna aplicación puede defenderse
de esto.** Se documenta explícitamente; `FLAG_SECURE` **no** impide todas las capturas.

### T-04 — Dispositivo con root (ADV-6)
**Residual — fuera de alcance:** con root y la bóveda desbloqueada, la memoria del proceso es
legible y el plaintext es alcanzable. No se implementa detección de root, porque es evadible y
daría una falsa sensación de seguridad. Con la bóveda **bloqueada**, el root no ayuda a descifrar
sin las credenciales.

### T-05 — Extracción de la base de datos local (ADV-3)
**Mitigación:** Room persiste solo ciphertext (`BLOB`) y metadatos no sensibles; no hay columnas
de título, cuerpo, etiquetas ni índice de búsqueda; `allowBackup="false"` y reglas de extracción
de datos que excluyen todo; prueba obligatoria de cadenas señuelo sobre el `.db`, el `-wal`, el
`-shm`, la caché y los archivos de la aplicación. Implementado y cubierto por pruebas
instrumentadas con cadenas señuelo ficticias.
**Residual:** número de ítems y marcas de tiempo (decisión ADR-013, sin SQLCipher en el MVP).

### T-06 — Filtración completa de Firestore (ADV-4)
**Mitigación:** solo se sube ciphertext, identificadores aleatorios, versiones, `revision`,
`tombstone`, salts y envoltorios. Las capas local/remota no compilan contra modelos descifrados,
sus DTO/entidades son internos y sus escrituras aceptan únicamente `Ciphertext` opaco producido
por `:core:crypto`; no aceptan bytes arbitrarios. Una prueba de API y otra estructural fijan la
única ruta `encrypt → Ciphertext → source`, y las pruebas de señuelo verifican el resultado real.
Código deliberadamente malicioso dentro del proceso podría eludir cualquier convención y queda
cubierto como riesgo de cadena de suministro en T-17.
**Residual:** metadatos (A-7) y la posibilidad de ataque offline contra los envoltorios, acotada
por Argon2id y por la entropía de 256 bits de la recuperación.

### T-07 — Compromiso de la cuenta de Firebase / Google del usuario (ADV-5)
El atacante inicia sesión como el usuario y descarga sus blobs.
**Mitigación:** Firebase Authentication **no** deriva ni posee claves. Sin la contraseña maestra o
la frase, los blobs son inútiles. La contraseña de la cuenta y la contraseña maestra son
credenciales distintas y la interfaz no sugiere reutilizarlas.
**Residual:** el atacante puede **borrar** o corromper datos (disponibilidad, A-9). Mitigación:
respaldo cifrado exportable por el usuario. Además puede aprender los metadatos.

### T-08 — Phishing de la contraseña maestra o de la frase (ADV-7)
**Mitigación:** la aplicación nunca pide la frase salvo en la recuperación explícita iniciada por
el usuario; nunca la envía a ningún sitio; la interfaz declara con claridad que nadie —soporte,
Google, Firebase ni el desarrollador— puede recuperar la bóveda, de modo que cualquier petición
externa sea reconocible como fraude. Advertencias explícitas al mostrar la frase.
**Residual — fuera de alcance:** un usuario convencido de escribir sus palabras en otro sitio
pierde la bóveda. Es una amenaza de formación, no técnica.

### T-09 — Pérdida de la frase y/o de la contraseña
**Mitigación:** dos caminos independientes (contraseña y frase); verificación obligatoria de
palabras elegidas al azar durante la configuración; posibilidad de **regenerar** la frase con la
bóveda desbloqueada (ADR-011); respaldo cifrado exportable.
**Residual, por diseño:** perder **ambas** hace los datos **irrecuperables**. Es una propiedad
deseada del conocimiento cero, no un defecto.

### T-10 — Captura de pantalla y vista de recientes
**Mitigación:** `FLAG_SECURE` en la actividad y `SecureFlagPolicy.SecureOn` en el único componente
permitido para diálogos y hojas modales; ocultación en recientes; prueba instrumentada que abre
cada ventana sensible. Implementado; la comprobación humana de capturas y recientes permanece en
la lista manual.
**Residual:** cámaras externas, capturas a nivel de sistema en dispositivos modificados,
y los límites documentados de `FLAG_SECURE`.

### T-11 — Portapapeles
**Mitigación:** nada se copia automáticamente; copiar exige acción explícita; el contenido se
marca como sensible (`EXTRA_IS_SENSITIVE`, disponible en todo el rango porque `minSdk = 33`) y se borra
automáticamente tras un plazo corto; advertencia antes de copiar. Implementado.
**Residual:** otra aplicación puede leer el portapapeles durante esa ventana. En Android moderno
el acceso está restringido a la app en primer plano, pero la ventana existe.

### T-12 — Registros y trazas
**Mitigación:** prohibido `android.util.Log` directo; logger propio que descarta lo no operativo
en release y no acepta material sensible; excepciones criptográficas sin secretos; sin
Crashlytics en flujos sensibles; prueba de higiene que detecta el uso prohibido.
**Residual:** un fallo del sistema fuera de nuestro control podría volcar memoria del proceso.

### T-13 — Backups del sistema y sincronización de archivos
**Mitigación:** `allowBackup="false"`, reglas de extracción de datos que excluyen todo, y el blob
biométrico y la base local nunca se incluyen en respaldos automáticos. Implementado.
**Residual:** el propio repositorio de desarrollo vive en OneDrive (riesgo R-01 de
`PROJECT_STATE.md`); eso afecta al **código**, no a los datos del usuario.

### T-14 — Respaldo exportado por el usuario
**Mitigación:** el archivo solo contiene ciphertext y parámetros públicos; nunca plaintext;
formato versionado con validación de integridad; restauración exige contraseña maestra o frase;
no incluye el blob biométrico; parser defensivo. Implementado y probado contra entradas hostiles.
**Residual:** un respaldo es tan fuerte como la contraseña maestra o la frase. Si el usuario lo
guarda en un sitio público, queda expuesto a ataque offline. Un respaldo antiguo conserva el
envoltorio y la credencial válidos cuando se exportó: cambiar después la contraseña o la frase no
revoca esa copia.

### T-15 — Restauración de un respaldo malicioso (ADV-8)
**Mitigación:** parser defensivo con esquema estricto, límites de tamaño por campo y totales,
rechazo de versiones desconocidas, sin confianza en el nombre del archivo, sin path traversal,
sin deserialización de tipos arbitrarios, sin ejecución de contenido; pruebas de fuzzing con
semilla fija. Implementado.
**Residual:** un respaldo manipulado puede provocar el rechazo del archivo (denegación de
servicio local), nunca ejecución ni descifrado indebido.

### T-16 — Ataque a la cadena de suministro / dependencia maliciosa (ADV-8)
**Mitigación:** dependencias mínimas y de proyectos reconocidos, registradas con versión, fecha y
fuente en `docs/DEPENDENCY_POLICY.md`; versiones fijas sin rangos dinámicos; verificación y
bloqueo de dependencias cuando sea viable; análisis estático; sin SDK de telemetría.
La verificación y el bloqueo de dependencias están activados; los avisos de herramientas de
desarrollo se documentan en `docs/DEPENDENCY_POLICY.md`.
**Residual:** una dependencia legítima comprometida en origen podría exfiltrar material. La
mitigación real es minimizar el número de dependencias y fijar versiones.

### T-17 — Repositorio del desarrollador comprometido (ADV-8)
**Mitigación:** el repositorio **no contiene ningún secreto**: ni claves, ni cuentas de servicio,
ni `google-services.json` real, ni frases, ni contraseñas. Detección de secretos antes de cada
commit y en la auditoría final. Un atacante que lea el repositorio obtiene el diseño completo —lo
cual es aceptable: la seguridad no depende del secreto del diseño— pero **no** obtiene datos.
**Residual:** un atacante con escritura podría introducir código malicioso en una versión futura.
Mitigación: revisión completa de diffs y revisión independiente de los cambios sensibles.

### T-18 — Observador de red (ADV-9)
**Mitigación:** solo HTTPS, Network Security Config que prohíbe tráfico en claro, y el contenido
ya viaja cifrado de extremo a extremo por la aplicación. Un TLS roto revelaría ciphertext y
metadatos, no contenido.
**Residual:** metadatos de tráfico (cuándo y cuánto sincroniza el usuario).

### T-19 — Servidor malicioso que degrada parámetros (ADV-4)
Firestore devuelve el documento de bóveda con parámetros de Argon2id rebajados para que la
PasswordKEK sea barata de atacar.
**Mitigación:** perfil v1 único validado antes de reservar memoria —nombre, memoria, iteraciones,
paralelismo, salida y salts exactos— y todo envoltorio nuevo usa ese perfil del binario. Los valores
persistidos se serializan canónicamente en la AAD como defensa en profundidad; una alteración
produce un fallo explícito, pero la protección primaria es la lista cerrada y no adoptar parámetros
externos al reenvolver.

### T-20 — Sustitución o mezcla de ciphertext (ADV-4)
El servidor devuelve el ciphertext del ítem A en el lugar del ítem B, o de otra bóveda.
**Mitigación:** la AAD liga cada ciphertext a `vaultId`, `itemId` y versiones; una sustitución
hace fallar el descifrado en lugar de mostrar contenido equivocado.

### T-21 — Otro usuario autenticado accede a datos ajenos (ADV-5)
**Mitigación:** Security Rules con denegación por defecto, ruta anclada al propio uid, lista
blanca de campos, validación de tipos y tamaños, inmutabilidad de `createdAt`, `revision` que no
retrocede y borrado físico limitado: un ítem solo se puede borrar si ya es tombstone. Las reglas
permiten al propietario borrar el documento de bóveda; que la aplicación invoque esa operación
solo durante la eliminación total es una restricción de flujo del cliente, no una propiedad que
Firestore pueda distinguir. Suite de pruebas contra el Emulator Suite implementada y ejecutada.
**Residual:** las reglas no sustituyen al cifrado; si fallaran, el atacante obtendría ciphertext.

### T-22 — Invalidación o abuso de la clave biométrica (ADV-1, ADV-6)
**Mitigación:** clave no exportable, autenticación por operación con `BIOMETRIC_STRONG` y sin
credencial del dispositivo, invalidación por reinscripción biométrica, `setUnlockedDeviceRequired`,
doble envoltorio de una `BiometricKEK` aleatoria y borrado de ambos blobs locales ante cualquier
invalidación, con vuelta obligatoria a la contraseña maestra. Implementado y cubierto por pruebas
instrumentadas; la reinscripción de huellas sigue como comprobación manual adicional.
**Residual:** quien pueda inscribir su propia biometría en un dispositivo desbloqueado y sin
supervisión podría desbloquear la bóveda; `setInvalidatedByBiometricEnrollment(true)` invalida la
clave precisamente en ese caso. El teléfono definitivo ofrece StrongBox; los fallos observados
fueron `DEVICE_LOCKED`, que se trata sin borrar ni degradar la clave (ADR-050).

### T-23 — Reposición de un envoltorio antiguo (ADV-4, ADV-5)
Un servidor comprometido, otro dispositivo o una sesión robada intenta reemplazar el documento de
bóveda por uno anterior y aumentar solo la revisión para que parezca nuevo.
**Mitigación:** cada camino tiene su epoch en la AAD (`passwordWrapEpoch`, `recoveryWrapEpoch` y el
local `biometricWrapEpoch`); las reglas exigen que crezca el correspondiente cuando cambia su
envoltorio y el cliente conserva una marca de agua por camino que rechaza valores inferiores con
un aviso visible.
**Residual:** quien ya posea un envoltorio antiguo y su credencial puede abrir la misma VDEK fuera
del estado actual. Los epochs detectan retrocesos, no borran copias. La revocación completa exige
rotar la VDEK y recifrar todos los ítems.

---

## 5. Explícitamente fuera de alcance

No se pretende defender de:

- **Malware con root o servicio de accesibilidad** con la bóveda desbloqueada (T-03, T-04).
- **Teclado o pantalla comprometidos** (captura de la contraseña al escribirla).
- **Coerción física o legal** sobre el usuario para que entregue la contraseña o la frase. No hay
  bóveda señuelo ni negación plausible.
- **Ataques de canal lateral** sobre el hardware del dispositivo (temporización a nivel de caché,
  análisis de consumo, fallos inducidos).
- **Análisis de memoria del proceso en vivo** por un depurador con privilegios.
- **Confidencialidad de los metadatos**: cantidad de ítems, tamaños y marcas de tiempo.
- **Disponibilidad frente a un Firebase comprometido**: pueden borrar ciphertext; la defensa es el
  respaldo del usuario.
- **Recuperación tras perder contraseña y frase**: imposible por diseño.
- **Errores del propio usuario**: anotar la frase en un sitio inseguro, usar una contraseña débil,
  aceptar un phishing.
- **Compromiso del sistema operativo o del firmware** del dispositivo.
- **Publicación en tiendas y su cadena de distribución**: fuera del MVP.

---

## 6. Riesgos residuales que el propietario debe aceptar

1. La **fortaleza de la contraseña maestra** determina la resistencia a un ataque offline contra
   los envoltorios filtrados. Argon2id encarece, no hace imposible.
2. Los **metadatos** (número de notas, cuándo se editan, tamaño) son visibles para el servidor y
   para quien extraiga la base local.
3. Un **dispositivo comprometido con la bóveda abierta** expone el contenido, y eso no se puede
   evitar desde la aplicación.
4. Perder la contraseña maestra **y** la frase implica **pérdida definitiva** de los datos.
5. La **frase de 24 palabras equivale a la bóveda**: quien la tenga, entra.
6. La clave biométrica intenta StrongBox y queda respaldada por **TEE** solo cuando Android informa
   que StrongBox no está disponible. `KeyInfo` debe confirmar uno de esos dos niveles.
7. El **borrado de secretos en memoria** en la JVM es una buena práctica, no una garantía.
8. **Sin SQLCipher** en el MVP: los metadatos de la base local no están protegidos (ADR-013).
9. La aplicación **no ha sido auditada por terceros** y no existe ninguna afirmación de seguridad
   absoluta.
10. Cambiar la contraseña o regenerar la frase **no revoca respaldos ni envoltorios antiguos**; sin
    rotación de VDEK, los epochs solo detectan la reposición en clientes que conservan sus marcas
    de agua.
11. `revision` y `tombstone` quedan fuera de la AAD del ítem. Una marca de agua local mitiga
    retrocesos, pero un servidor malicioso todavía puede ocultar o enterrar una versión auténtica.
12. **App Check todavía no está impuesto en el proyecto real** (ADR-048). El código release instala
    `PlayIntegrityAppCheckProviderFactory`, pero sin registrar la firma, observar tokens válidos y
    habilitar enforcement, App Check **no aporta una mitigación efectiva** contra tráfico
    automatizado o abusivo. Para distribución por Drive debe configurarse como aplicación fuera de
    Google Play; exigir `PLAY_RECOGNIZED` o `LICENSED` bloquearía instalaciones laterales. Las
    Security Rules siguen siendo la defensa primaria de propiedad y validación; App Check es una
    capa adicional contra abuso, nunca una protección de confidencialidad. Un dispositivo rooteado
    puede no satisfacer los veredictos de integridad y, con la bóveda abierta, ya está fuera del
    alcance de protección (T-04).
