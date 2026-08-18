# docs/release-checklist.md — Antes de generar un release

Lista previa a construir un APK/AAB de release. Se ejecuta completa; ningún punto se omite «por
esta vez». Estado: se aplicará en la Fase 9.

---

## 1. Puertas previas

| # | Requisito |
|---|---|
| 1 | Estado y puertas aplicables documentados en `PROJECT_STATE.md` y `docs/SECURITY_GATES.md` |
| 2 | Todas las puertas de `docs/SECURITY_GATES.md` cumplidas |
| 3 | **Ningún hallazgo crítico o alto abierto** en `docs/VERIFICATION_STATUS.md` |
| 4 | `PROJECT_STATE.md` refleja el estado real, con riesgos y bloqueos |
| 5 | Informe de `final-security-auditor` archivado, con veredicto |

---

## 2. Compilación y pruebas

```powershell
.\gradlew.bat clean
.\gradlew.bat assembleDebug
.\gradlew.bat test
.\gradlew.bat lint detekt
.\gradlew.bat connectedDebugAndroidTest
cd firebase; npm test; cd ..
.\gradlew.bat assembleRelease
```

| # | Verificación |
|---|---|
| 6 | Compilación limpia desde cero (tras `clean`), siguiendo solo el `README.md` |
| 7 | Pruebas unitarias en verde, con salida real registrada |
| 8 | Pruebas instrumentadas en verde en el dispositivo |
| 9 | Pruebas de Security Rules en verde contra el emulador |
| 10 | Lint y Detekt sin hallazgos nuevos |
| 11 | El release **se instala y funciona** en el dispositivo (R8 puede romper reflexión) |

---

## 3. Configuración del release

| # | Verificación |
|---|---|
| 12 | `isMinifyEnabled = true` y `isShrinkResources` coherente |
| 13 | `proguard-rules.pro` con los keep necesarios (Tink, BouncyCastle, kotlinx.serialization, Room, Hilt) y sin keeps innecesariamente amplios |
| 14 | Sin `android:debuggable` |
| 15 | El logger seguro descarta lo no operativo en release (verificado, no supuesto) |
| 16 | Sin `useEmulator` en release |
| 17 | `versionCode` y `versionName` actualizados |
| 18 | `allowBackup="false"` y reglas de extracción vigentes |
| 19 | Manifiesto y Network Security Config **fusionados de release** sin cleartext ni excepción del emulador |
| 20 | Todo componente exportado del manifiesto fusionado está inventariado y justificado; no queda ningún receptor o servicio de diagnóstico innecesario |
| 21 | Permisos revisados uno por uno; ninguno sobrante |

---

## 4. Firma

| # | Verificación |
|---|---|
| 22 | La clave de firma está **fuera** del repositorio y respaldada por el propietario |
| 23 | `keystore.properties` y `*.jks` ignorados por Git (comprobado con `git ls-files`) |
| 24 | El APK/AAB está firmado con la clave de release, no con la de depuración |
| 25 | La huella del certificado registrada en Firebase si se usa Google Sign-In |

---

## 5. Secretos y privacidad

| # | Verificación |
|---|---|
| 26 | Detección de secretos sobre el árbol **y el historial**, sin hallazgos |
| 27 | Sin `google-services.json` real versionado |
| 28 | Sin SDK de analítica, publicidad, tracking ni informes de fallos |
| 29 | El APK no contiene cadenas señuelo de pruebas ni datos de fixtures |
| 30 | Revisión de `logcat` durante un recorrido completo: sin contenido sensible |

---

## 6. Documentación

| # | Verificación |
|---|---|
| 31 | `README.md` describe lo que la app hace **hoy**, sin funciones fantasma |
| 32 | `CRYPTOGRAPHY.md` coincide con el código, incluido el benchmark **medido** |
| 33 | `THREAT_MODEL.md` con los riesgos residuales actualizados |
| 34 | `SECURITY.md` sin ninguna afirmación de seguridad absoluta |
| 35 | `docs/security-checklist.md` con las verificaciones manuales marcadas y fechadas |
| 36 | `DECISIONS.md` con todas las decisiones vigentes; las sustituidas marcadas |

---

## 7. Verificaciones manuales imprescindibles

Del bloque §6 de `docs/security-checklist.md`, como mínimo: M-05 (invalidación por huella nueva),
M-08 (captura de pantalla), M-09 (recientes), M-10 (bloqueo automático), M-15 (muerte del proceso),
M-16 (`logcat` limpio), M-18 (no existe forma de volver a ver la frase) y M-23 (inspección de
Firestore sin nada legible).

---

## 8. Después de generar el release

| # | Acción |
|---|---|
| 37 | Instalar el artefacto en un dispositivo limpio y recorrer el flujo completo: crear bóveda → frase → notas → bloquear → desbloquear → respaldo → restaurar |
| 38 | Guardar el artefacto y su mapa de R8 (`mapping.txt`) fuera del repositorio |
| 39 | Anotar en `PROJECT_STATE.md` la versión publicada y qué quedó pendiente |
| 40 | No publicar en ninguna tienda sin una decisión explícita del propietario (fuera del alcance del MVP) |
