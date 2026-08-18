# docs/security-checklist.md — Lista de verificación de seguridad

Verificaciones automáticas y **manuales**. Las manuales existen porque algunas propiedades no se
pueden comprobar sin una persona: nunca se declaran como «pruebas que pasan».

Estado de la columna «verificado»: se marca solo con evidencia real, indicando la fecha.

---

## 1. Criptografía (automático — Fase 2)

| # | Verificación | Verificado |
|---|---|---|
| C-01 | Cifrado y descifrado correctos con AAD | ☐ |
| C-02 | Mismo contenido produce ciphertext distinto | ☐ |
| C-03 | AAD incorrecta falla | ☐ |
| C-04 | Cualquier byte alterado del ciphertext falla | ☐ |
| C-05 | Contraseña incorrecta falla con error genérico | ☐ |
| C-06 | La recuperación abre la misma VDEK | ☐ |
| C-07 | Una palabra incorrecta falla | ☐ |
| C-08 | Versión desconocida se rechaza | ☐ |
| C-09 | Nombre, memoria, iteraciones, paralelismo, salida y salts exactos del perfil KDF se validan antes de reservar memoria | ☐ |
| C-10 | Downgrade de parámetros rompe el unwrap | ☐ |
| C-11 | Cambiar la contraseña conserva notas y recuperación | ☐ |
| C-12 | Regenerar la frase hace fallar la anterior contra el envoltorio actual | ☐ |
| C-13 | Errores sin material sensible | ☐ |
| C-14 | Buffers a cero tras su uso | ☐ |
| C-15 | Vectores públicos de BIP-39 coinciden | ☐ |
| C-16 | Argon2id es modo **id**, no i ni d | ☐ |
| C-17 | Aleatoriedad solo por `SecureRandom`, sin semillas | ☐ |
| C-18 | Contextos HKDF únicos y sin prefijos ambiguos | ☐ |
| C-19 | AAD canónica: identidad byte a byte, sin separadores ambiguos ni campos sensibles | ☐ |
| C-20 | Trasplante entre `itemId`, `vaultId` o alias biométrico falla | ☐ |
| C-21 | Ambos envoltorios abren la misma VDEK antes de persistir | ☐ |
| C-22 | Cada epoch regresivo se rechaza y un cambio no invalida los otros caminos | ☐ |

## 2. Almacenamiento local (automático — Fase 3)

| # | Verificación | Verificado |
|---|---|---|
| L-01 | Señuelos ausentes en `.db`, `-wal`, `-shm` | ☐ |
| L-02 | Señuelos ausentes en caché y archivos de la app | ☐ |
| L-03 | Esquema sin columnas de contenido ni texto buscable | ☐ |
| L-04 | Sin `fallbackToDestructiveMigration`; `exportSchema = true` | ☐ |
| L-05 | DataStore solo con preferencias no sensibles | ☐ |
| L-06 | Al bloquear, el estado queda limpio | ☐ |

## 3. Firestore y reglas (automático — Fase 4)

| # | Verificación | Verificado |
|---|---|---|
| F-01 | Denegación por defecto en `/{document=**}` | ☐ |
| F-02 | No autenticado denegado | ☐ |
| F-03 | A no lee ni escribe datos de B | ☐ |
| F-04 | Lista blanca de campos aplicada | ☐ |
| F-05 | Tipos validados | ☐ |
| F-06 | Tamaño máximo del ciphertext aplicado (límite y +1) | ☐ |
| F-07 | `createdAt` inmutable; propiedad por ruta | ☐ |
| F-08 | `revision` no retrocede | ☐ |
| F-09 | Ítem solo se borra si ya es tombstone; bóveda solo por el propietario | ☐ |
| F-10 | Ninguna regla abierta, ni comentada | ☐ |
| F-11 | El emulador solo se usa en debug | ☐ |
| F-12 | Tombstone equivale a ciphertext vacío; activo vacío se deniega | ☐ |
| F-13 | `revision > 1` se acepta al crear; monotonía se aplica al actualizar | ☐ |
| F-14 | Consultas de grupo y rutas hermanas quedan denegadas | ☐ |

## 4. Plataforma Android (automático + revisión — Fases 1, 7 y 9)

| # | Verificación | Verificado |
|---|---|---|
| A-01 | `allowBackup="false"` y reglas de extracción excluyendo todo | ☐ |
| A-02 | NSC release sin cleartext; debug limitado a `10.0.2.2` y `localhost` | ☐ |
| A-03 | Solo la actividad lanzadora exportada | ☐ |
| A-04 | Permisos mínimos, cada uno justificado | ☐ |
| A-05 | `FLAG_SECURE` en actividad y en cada `SecureDialog` | ☐ |
| A-06 | Contenido oculto en recientes | ☐ |
| A-07 | Sin plaintext en `Bundle`, `SavedStateHandle`, `rememberSaveable` ni previews | ☐ |
| A-08 | Sin `android.util.Log` ni `println` fuera del logger seguro | ☐ |
| A-09 | Sin `debuggable` en release; R8 activo | ☐ |
| A-10 | Sin deep links no validados | ☐ |

## 5. Repositorio y cadena de suministro (automático — Fase 9)

| # | Verificación | Verificado |
|---|---|---|
| R-01 | Sin secretos en el árbol de trabajo | ☐ |
| R-02 | Sin secretos en el **historial** de commits | ☐ |
| R-03 | Sin `google-services.json` real; existe el `.example` | ☐ |
| R-04 | Versiones fijas, sin rangos dinámicos | ☐ |
| R-05 | Cada dependencia registrada con versión, fecha y fuente | ☐ |
| R-06 | Vulnerabilidades conocidas verificadas (pendiente D-01) | ☐ |
| R-07 | `dependency verification` / `locking` activados o justificada su ausencia | ☐ |

---

## 6. Verificaciones MANUALES (requieren una persona)

Estas **no** se pueden automatizar. Se ejecutan sobre el dispositivo real, se anotan con fecha y
resultado, y nunca se declaran como pruebas automáticas superadas.

La guía con el procedimiento paso a paso, preparación segura, resultado esperado y tabla de
registro está en [`MANUAL_VALIDATION_GUIDE.md`](MANUAL_VALIDATION_GUIDE.md). Todas las casillas no
marcadas a continuación continúan **pendientes** hasta que se registre evidencia real.

### 6.1 Biometría (Fase 6)

| # | Paso | Resultado esperado | Verificado |
|---|---|---|---|
| M-01 | Activar biometría con la bóveda desbloqueada | se pide huella y queda activada | ✅ 2026-08-17; ver `docs/VERIFICATION_STATUS.md` |
| M-02 | Bloquear y desbloquear con huella | la bóveda se abre sin pedir contraseña | ☐ |
| M-03 | Cancelar el diálogo biométrico | la bóveda **no** se abre; se ofrece la contraseña | ☐ |
| M-04 | Presentar una huella no inscrita varias veces | fallo; sin desbloqueo; sin bloqueo permanente de la app | ☐ |
| M-05 | **Inscribir una huella nueva** en el sistema y volver a la app | la clave se invalida, el blob se borra y se exige la contraseña maestra | ☐ |
| M-06 | Quitar el bloqueo de pantalla del dispositivo y volver | se exige la contraseña maestra | ☐ |
| M-07 | Desactivar biometría y comprobar el almacenamiento local | el blob y el IV desaparecen | ☐ |

### 6.2 Interfaz y filtraciones (Fase 7)

| # | Paso | Resultado esperado | Verificado |
|---|---|---|---|
| M-08 | Intentar una captura de pantalla con la bóveda abierta | el sistema la impide o la imagen sale en negro | ☐ |
| M-09 | Abrir el selector de recientes con la bóveda abierta | no se ve contenido de notas | ☐ |
| M-10 | Dejar la app inactiva más que el tiempo de bloqueo | se bloquea y vuelve a desbloqueo | ☐ |
| M-11 | Enviar la app al fondo con la opción activada | se bloquea de inmediato | ☐ |
| M-12 | Copiar un campo secreto y esperar | el portapapeles se vacía solo | ☐ |
| M-13 | Provocar una notificación mientras la bóveda está abierta | no aparece contenido sensible | ☐ |
| M-14 | Girar el dispositivo en una nota abierta | el contenido se conserva sin escribirse en `Bundle` | ☐ |
| M-15 | Matar el proceso y reabrir | vuelve bloqueado; no reconstruye contenido | ☐ |
| M-16 | Revisar `logcat` durante un flujo completo | ningún contenido, contraseña ni palabra aparece | ☐ |

### 6.3 Recuperación (Fase 7)

| # | Paso | Resultado esperado | Verificado |
|---|---|---|---|
| M-17 | Crear bóveda y comprobar la pantalla de la frase | advertencia visible; no se puede continuar sin verificar | ✅ 2026-08-17; ver `docs/VERIFICATION_STATUS.md` |
| M-18 | Buscar en la app una forma de volver a ver la frase | **no existe**; solo «regenerar» | ☐ |
| M-19 | Recuperar con las 24 palabras (frase ficticia de prueba) | pide contraseña nueva y las notas siguen legibles | ☐ |
| M-20 | Regenerar la frase y probar la anterior contra el estado actual | el envoltorio actual la rechaza; la UI no promete revocar respaldos antiguos | ☐ |
| M-21 | Leer los textos de la interfaz | declaran la irrecuperabilidad; no insinúan rescate por soporte | ☐ |

### 6.4 Firebase real (bloqueos B-01 y B-02)

| # | Paso | Resultado esperado | Verificado |
|---|---|---|---|
| M-22 | Google Sign-In con proyecto real | inicia sesión; la bóveda sigue exigiendo contraseña maestra | ✅ 2026-08-17; ver `docs/VERIFICATION_STATUS.md` |
| M-23 | Inspeccionar los documentos en la consola de Firestore | solo ciphertext, identificadores y versiones; **nada legible** | ✅ 2026-08-17; ver `docs/VERIFICATION_STATUS.md` |
| M-24 | Intentar leer datos de otra cuenta con la sesión abierta | denegado por las reglas | ☐ |
| M-25 | Desplegar las reglas probadas | despliegue correcto (lo ejecuta el propietario) | ☐ |

---

## 7. Cómo se registra

Al completar una verificación se marca la casilla, se añade la fecha y, si hubo hallazgos, se
registra el resultado en `docs/VERIFICATION_STATUS.md`. Las verificaciones manuales pendientes se listan en
`PROJECT_STATE.md` §7 como «no ejecutadas», nunca como pasadas.
