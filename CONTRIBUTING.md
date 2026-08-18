# Cómo contribuir

Bóveda Wilson es un proyecto personal con requisitos de seguridad estrictos. Antes de modificarlo, lee `PROJECT_STATE.md`, `DECISIONS.md`, `CRYPTOGRAPHY.md` y `docs/SECURITY_GATES.md`.

## Reglas no negociables

- No implementar criptografía propia. El cifrado autenticado usa Tink; Argon2id y HKDF usan bibliotecas mantenidas.
- El plaintext no cruza la frontera criptográfica ni se persiste en Room, Firestore, DataStore, estados guardados, caché, archivos temporales, logs o notificaciones.
- No debilitar parámetros, pruebas ni Security Rules para hacer pasar una validación.
- No incorporar secretos reales, configuración Firebase real, almacenes de firma, respaldos ni APKs.
- No afirmar que una prueba pasó sin evidencia de su ejecución.

## Flujo de cambios

1. Mantén cada cambio pequeño y con una sola intención.
2. Si cambia una decisión criptográfica o arquitectónica, actualiza `DECISIONS.md` y el contrato relacionado.
3. Registra dependencias nuevas en `docs/DEPENDENCY_POLICY.md`.
4. Añade casos negativos junto con cada garantía de seguridad nueva.
5. Ejecuta las validaciones aplicables:

```powershell
.\gradlew.bat test lint detekt assembleDebug

Push-Location firebase
npm ci
npm test
Pop-Location
```

6. Revisa el diff completo y ejecuta `scripts/scan-secrets.ps1 -History` antes de publicar.

## Commits

Usa Conventional Commits con un ámbito claro, por ejemplo:

```text
feat(crypto): implement versioned key wrapping
test(sync): cover tampered remote records
docs(security): clarify residual risks
```

No reescribas historial compartido ni publiques cambios sin revisar las pruebas y la documentación afectada.

## Reportes de seguridad

Sigue `SECURITY.md`. No incluyas contenido real de una bóveda, contraseñas, frases de recuperación, claves, tokens ni configuraciones privadas.
