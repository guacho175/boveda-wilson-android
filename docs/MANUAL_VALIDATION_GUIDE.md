# Guía de validación manual avanzada

**Estado:** pendiente. Esta guía no convierte ninguna prueba en aprobada: cada casilla se marca
solo después de ejecutar el recorrido en un dispositivo real y registrar el resultado.

## Antes de comenzar

1. Trabaja con una bóveda de prueba y entradas ficticias; no copies aquí, en capturas ni en
   informes contraseñas, frases de recuperación, notas reales, identificadores personales ni
   contenido de Firestore.
2. Antes de M-05, M-06, M-19 o M-20, confirma que conoces la contraseña maestra de prueba y que
   conservas la frase de recuperación fuera del dispositivo. Esas pruebas pueden invalidar el
   desbloqueo biométrico o exigir una recuperación.
3. Si un paso obliga a cambiar la configuración del teléfono o de Firebase, no lo hagas en la
   bóveda cotidiana. Usa un perfil, cuenta o bóveda de prueba cuando sea posible.
4. Una prueba que falla, queda bloqueada o no es aplicable **no se marca como aprobada**. Anota
   el estado y una descripción sin datos sensibles en la tabla de registro.

## Cómo registrar el resultado

Al terminar cada prueba, cambia la casilla correspondiente de
[`security-checklist.md`](security-checklist.md) a `✅ AAAA-MM-DD; ver esta guía`, y añade una
fila a la tabla siguiente. No hacen falta capturas de secretos: basta con el resultado, versión de
la app, modelo de dispositivo y una nota redactada.

| ID | Estado (`pendiente` / `pasa` / `falla` / `bloqueada`) | Fecha | Dispositivo / versión | Nota sin datos sensibles |
|---|---|---|---|---|
| M-02 | pendiente |  |  |  |
| M-03 | pendiente |  |  |  |
| M-04 | pendiente |  |  |  |
| M-05 | pendiente |  |  |  |
| M-06 | pendiente |  |  |  |
| M-07 | pendiente |  |  |  |
| M-08 | pendiente |  |  |  |
| M-09 | pendiente |  |  |  |
| M-10 | pendiente |  |  |  |
| M-11 | pendiente |  |  |  |
| M-12 | pendiente |  |  |  |
| M-13 | pendiente |  |  |  |
| M-14 | pendiente |  |  |  |
| M-15 | pendiente |  |  |  |
| M-16 | pendiente |  |  |  |
| M-18 | pendiente |  |  |  |
| M-19 | pendiente |  |  |  |
| M-20 | pendiente |  |  |  |
| M-21 | pendiente |  |  |  |
| M-24 | pendiente |  |  |  |
| M-25 | pendiente |  |  |  |

---

## Biometría

### M-02 — Desbloqueo con biometría

1. Desbloquea la bóveda con la contraseña maestra y confirma que biometría sigue activada.
2. Bloquea la aplicación o ciérrala hasta ver la pantalla de desbloqueo.
3. Elige el desbloqueo biométrico y autentícate con una huella ya inscrita.

**Resultado esperado:** la bóveda se abre sin pedir la contraseña maestra y muestra el contenido
que ya estaba sincronizado.

### M-03 — Cancelación biométrica

1. Desde la pantalla bloqueada, inicia el desbloqueo biométrico.
2. Pulsa cancelar, atrás o la alternativa equivalente del sistema sin autenticarte.

**Resultado esperado:** la bóveda no se abre; la pantalla permanece bloqueada y ofrece usar la
contraseña maestra.

### M-04 — Huella no reconocida

1. Inicia el desbloqueo biométrico de la bóveda bloqueada.
2. Presenta una huella que el dispositivo no reconozca varias veces, sin exceder los límites del
   sistema.
3. Si Android aplica una espera temporal, espera y vuelve a la aplicación.

**Resultado esperado:** no se abre la bóveda ni se borran datos. Cualquier límite o espera debe
ser del sistema Android, no un bloqueo permanente de la aplicación.

### M-05 — Reinscripción de huella

1. Con una bóveda de prueba desbloqueable por contraseña, deja biometría activada y luego bloquea
   la aplicación.
2. En Ajustes de Android, inscribe una huella nueva. No reveles ni registres datos biométricos.
3. Vuelve a la aplicación e intenta desbloquearla con biometría.
4. Si la app pide la contraseña, introdúcela y verifica que aún puedes abrir la bóveda.

**Resultado esperado:** Android invalida la clave biométrica; la app no desbloquea con huella,
el material biométrico local se elimina y se exige la contraseña maestra. Los datos cifrados de la
bóveda permanecen accesibles con la contraseña.

### M-06 — Quitar el bloqueo de pantalla

1. Asegura que sabes la contraseña maestra de la bóveda de prueba.
2. En Ajustes de Android, elimina temporalmente el bloqueo de pantalla del dispositivo.
3. Vuelve a la app e intenta el desbloqueo biométrico.
4. Restaura inmediatamente un bloqueo de pantalla seguro al terminar.

**Resultado esperado:** el desbloqueo biométrico deja de estar disponible y la aplicación exige la
contraseña maestra; nunca abre la bóveda sin autenticar.

### M-07 — Desactivar biometría

1. Desbloquea la bóveda con la contraseña y desactiva biometría desde Ajustes de la aplicación.
2. Bloquea la aplicación y vuelve a la pantalla de desbloqueo.
3. Si tienes herramientas de inspección local autorizadas, verifica solo la ausencia del blob e IV
   biométricos; no extraigas ni copies datos de la bóveda.

**Resultado esperado:** no se ofrece el desbloqueo biométrico y se requiere contraseña maestra.
El almacenamiento biométrico local queda eliminado.

## Interfaz, sesión y filtraciones

### M-08 — Captura de pantalla

1. Abre una entrada ficticia dentro de una bóveda desbloqueada.
2. Usa la combinación normal de captura de pantalla del teléfono.
3. Si Android crea una vista previa, revísala sin compartirla y elimínala después de la prueba.

**Resultado esperado:** el sistema bloquea la captura o el resultado no contiene el texto de la
bóveda (por ejemplo, negro o vacío).

### M-09 — Vista de aplicaciones recientes

1. Con una entrada ficticia abierta, abre la vista de aplicaciones recientes.
2. Revisa la tarjeta de Bóveda Wilson sin tocar ni copiar el contenido.

**Resultado esperado:** no se ve el contenido de notas ni de campos sensibles; la vista está
oculta, vacía o protegida.

### M-10 — Bloqueo por inactividad

1. Desbloquea la bóveda y abre una entrada ficticia.
2. Deja el teléfono sin interacción durante un tiempo mayor que el configurado para bloqueo
   automático.
3. Vuelve a la aplicación.

**Resultado esperado:** la app vuelve a la pantalla bloqueada y solicita el método de desbloqueo;
el contenido no queda visible.

### M-11 — Bloqueo al ir a segundo plano

1. Activa la opción de bloqueo inmediato en segundo plano, si está disponible.
2. Desbloquea la bóveda y abre una entrada ficticia.
3. Envía la aplicación al fondo y vuelve a abrirla.

**Resultado esperado:** la app aparece bloqueada de inmediato y no muestra el contenido anterior.

### M-12 — Limpieza del portapapeles

1. Copia desde la app un valor ficticio que no sea una contraseña ni una frase de recuperación.
2. Espera el tiempo de limpieza configurado, sin copiar otra cosa.
3. Intenta pegar en un campo de prueba fuera de la bóveda.

**Resultado esperado:** el valor ya no está disponible en el portapapeles. Si Android muestra un
aviso de privacidad, no debe revelar contenido sensible.

### M-13 — Notificaciones

1. Con la bóveda abierta sobre una entrada ficticia, provoca una notificación inocua del sistema u
   otra aplicación.
2. Observa la pantalla bloqueada y el panel de notificaciones.

**Resultado esperado:** la notificación no contiene títulos, notas, contraseñas ni otra
información proveniente de Bóveda Wilson.

### M-14 — Cambio de orientación

1. Abre una entrada ficticia en una bóveda desbloqueada.
2. Gira el dispositivo y vuelve a su orientación original.
3. Comprueba que la sesión sigue protegida y que el contenido se comporta normalmente.

**Resultado esperado:** el contenido no aparece en pantallas del sistema ni se pierde por la
rotación. Si la interfaz no admite rotación, documenta ese comportamiento y confirma que no hay
filtración.

### M-15 — Muerte del proceso

1. Abre una entrada ficticia y envía la app a segundo plano.
2. Fuerza el cierre desde Ajustes de Android o mediante una herramienta de depuración autorizada.
3. Abre Bóveda Wilson otra vez.

**Resultado esperado:** vuelve bloqueada y no reconstruye la entrada que estaba abierta. Para
continuar hay que desbloquear otra vez.

### M-16 — Revisión de logs

1. Usa una entrada y valores ficticios claramente distinguibles de datos reales.
2. Limpia la vista de Logcat o inicia un filtro por el proceso de Bóveda Wilson.
3. Recorre creación, bloqueo, desbloqueo, copia ficticia, cambio de contraseña y sincronización.
4. Busca visualmente los valores ficticios y términos sensibles como contraseña, frase, clave,
   nota o contenido. No archives ni compartas logs que pudieran contener información privada.

**Resultado esperado:** no aparece contenido de la bóveda, contraseña maestra, frase de
recuperación, claves ni plaintext. Los errores, si los hay, son genéricos y no incluyen datos.

## Recuperación

### M-18 — No volver a mostrar la frase

1. Desbloquea una bóveda creada para pruebas.
2. Recorre Ajustes y las pantallas de recuperación sin elegir regenerar la frase.

**Resultado esperado:** no existe una opción para volver a mostrar las 24 palabras originales;
solo puede ofrecerse regenerar una nueva frase con advertencias apropiadas.

### M-19 — Recuperación con frase

1. Usa una bóveda de prueba con una entrada ficticia y conserva su frase de 24 palabras fuera del
   dispositivo.
2. Inicia el flujo de recuperación indicado por la interfaz, en un estado de prueba que no ponga
   en riesgo la bóveda cotidiana.
3. Introduce la frase sin guardarla en capturas, notas, terminal o este archivo.
4. Define la nueva contraseña maestra solicitada y abre la bóveda.

**Resultado esperado:** la recuperación solicita una contraseña maestra nueva y permite leer la
entrada ficticia. La frase no se registra ni queda disponible después del flujo.

### M-20 — Regeneración y frase anterior

1. Usa una bóveda de prueba, confirma que conoces la contraseña maestra y conserva la frase
   anterior fuera del dispositivo.
2. Regenera la frase siguiendo las advertencias de la interfaz y guarda la nueva fuera del
   dispositivo.
3. En un flujo de recuperación de prueba, intenta usar la frase anterior contra el estado actual.

**Resultado esperado:** la frase anterior no abre el envoltorio de recuperación actual. La
aplicación no debe prometer que se pueden revocar copias de respaldo antiguas que hubieran sido
creadas fuera de la aplicación.

### M-21 — Textos de irrecuperabilidad

1. Lee los textos de creación, cambio de contraseña, recuperación y regeneración de frase.
2. Busca menciones a soporte, desarrollador, Google o Firebase.

**Resultado esperado:** los textos explican que perder contraseña maestra y frase implica pérdida
irreversible; no sugieren que soporte, Google, Firebase o el desarrollador puedan recuperar la
bóveda.

## Firebase real

### M-24 — Aislamiento entre dos cuentas

1. Prepara dos cuentas de prueba autorizadas: A y B. En A, crea una bóveda y una entrada ficticia
   sincronizada; no uses datos reales.
2. Cierra sesión de A, bloquea la app y entra con B.
3. Intenta abrir, listar o modificar los datos creados por A. Repite el intento desde una sesión
   limpia si la interfaz lo permite.

**Resultado esperado:** B no puede leer ni modificar los datos de A. La interfaz no mezcla las
bóvedas locales; cualquier acceso remoto entre cuentas es denegado por las reglas.

### M-25 — Confirmación de despliegue de reglas

Esta prueba modifica infraestructura y solo la ejecuta el propietario con el proyecto correcto.
No despliegues reglas si no estás seguro de qué proyecto está seleccionado.

1. Revisa el diff de `firebase/firestore.rules` y confirma que coincide con la versión aprobada.
2. Ejecuta primero las pruebas de reglas con Java 21: desde `firebase/`, ejecuta `npm test`.
3. Confirma en la consola de Firebase que el proyecto seleccionado es el de pruebas o el destino
   autorizado.
4. Solo con esa confirmación explícita, ejecuta `firebase deploy --only firestore:rules` desde la
   carpeta `firebase/`.
5. Comprueba en la consola que la fecha de publicación de las reglas se actualizó y repite M-24.

**Resultado esperado:** el despliegue termina correctamente, la consola muestra la versión nueva y
el aislamiento de M-24 sigue denegando el acceso entre cuentas. Si ya hay reglas desplegadas, deja
M-25 pendiente hasta que se pueda vincular esta comprobación a un despliegue autorizado.

## Cierre del recorrido

Cuando todas las filas pendientes necesarias para el objetivo elegido estén en estado `pasa`,
actualiza `docs/security-checklist.md` y añade un informe breve, sin datos sensibles, en
`docs/VERIFICATION_STATUS.md`. Si alguna queda pendiente, el estado del proyecto debe seguir diciendo
explícitamente cuáles son; no se infiere una aprobación global.
