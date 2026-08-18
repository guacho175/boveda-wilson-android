# Estado de verificación

Este documento separa resultados ejecutados, evidencia informada y comprobaciones pendientes. No constituye una auditoría externa ni una certificación de seguridad.

## Evidencia automatizada de la base fuente

La base privada seleccionada registró el 2026-08-17:

- `gradlew test lint detekt assembleDebug`: compilación satisfactoria, 496 tareas y cero fallos.
- escaneo de secretos del árbol e historial: sin coincidencias de alta confianza.
- instalación reproducible y auditoría de `firebase/`: sin vulnerabilidades críticas o altas; cinco avisos moderados transitivos de herramientas de desarrollo.
- Security Rules: última evidencia registrada de 44/44 pruebas aprobadas con Java 21.

Estos resultados deben distinguirse de las validaciones que se ejecuten sobre el SHA público exacto.

## Verificación del espejo público

El 2026-08-18, antes de crear el remoto público, se ejecutó sobre este espejo:

- `gradlew.bat --no-daemon test lint detekt assembleDebug`: aprobado, 494 tareas y cero fallos.
- escaneo de secretos de alta confianza, búsqueda de rutas locales, enlaces privados y metadatos internos: sin coincidencias en el árbol curado.
- revisión de enlaces Markdown: sin enlaces relativos rotos.
- `npm ci` en `firebase/`: aprobado; el gestor informó cinco avisos moderados transitivos.

`npm test` en `firebase/` **no se ejecutó**: la herramienta Firebase requiere Java 21 y el entorno disponible expone Java 17. No se atribuye a esta ejecución el resultado histórico de las 44 pruebas hasta repetirlo con Java 21.

## Validación manual informada

El propietario informó una prueba satisfactoria de una APK release firmada en un dispositivo físico: creación de bóveda, recuperación de 24 palabras, biometría, cambio de contraseña maestra, reinstalación, autenticación y recuperación de entradas sincronizadas. También informó que la inspección acordada de Firestore no mostró contenido legible.

Esta evidencia es un recorrido funcional informado por el propietario. No reemplaza pruebas instrumentadas completas, revisión forense ni auditoría independiente.

## Pendientes

Las comprobaciones manuales sin evidencia siguen sin marcar en `docs/security-checklist.md`. Son prioritarias las relacionadas con memoria, capturas y recientes, portapapeles, dispositivo rooteado, manipulación de respaldo y política de red.

La suite Firebase requiere Java 21. Si el entorno activo no lo proporciona, debe informarse como no ejecutada; una evidencia histórica no cuenta como una nueva ejecución.

## Límites de la publicación

- No se publican APKs, configuración Firebase real, material de firma ni respaldos.
- Los datos de prueba usan dominios reservados, vectores públicos o fixtures marcados como ficticios.
- La apertura del código permite revisión, pero no demuestra por sí sola ausencia de vulnerabilidades.
