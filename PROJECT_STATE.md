# Estado verificable del proyecto

**Actualización:** 2026-08-18
**Estado:** MVP Android funcional; código fuente publicado sin APK ni configuración privada.

## Alcance implementado

- Cifrado local con Tink, Argon2id, envelope encryption y AAD versionada.
- Persistencia Room y sincronización Firestore limitadas a ciphertext y metadatos mínimos.
- Contraseña maestra independiente de Firebase Authentication.
- Recuperación local mediante 24 palabras y biometría opcional respaldada por Android Keystore.
- Respaldo/restauración cifrados, conflictos, tombstones y bloqueo automático.
- Security Rules con denegación por defecto y pruebas en emuladores.

## Evidencia

La evidencia automatizada y manual disponible, junto con sus límites, se resume en `docs/VERIFICATION_STATUS.md`. Este proyecto no se presenta como una certificación de seguridad ni como invulnerable.

## Riesgos y trabajo pendiente

- Varias comprobaciones manuales avanzadas continúan pendientes y se enumeran en `docs/security-checklist.md`.
- App Check / Play Integrity no se fuerza sin validar previamente el flujo de tokens de producción.
- Un dispositivo comprometido o con depuración autorizada queda fuera de las garantías completas; ver `THREAT_MODEL.md`.
- Las vulnerabilidades moderadas transitivas de herramientas de desarrollo Firebase deben reevaluarse al actualizar dependencias.

## Publicación

Este repositorio es una instantánea pública curada. Su historial se creó de nuevo para excluir metadatos internos, rutas locales, objetos sueltos y material operativo. El repositorio privado de desarrollo conserva su historial por separado.
