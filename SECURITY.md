# SECURITY.md — Modelo de seguridad de Bóveda Wilson

Qué promete Bóveda Wilson, qué **no** promete, y cómo reportar un problema.

Estado: MVP implementado. Este documento describe las garantías y límites vigentes; la evidencia de
pruebas, riesgos abiertos y validaciones no ejecutadas se mantiene en `PROJECT_STATE.md`.

---

## 1. Lo que promete

- **Conocimiento cero respecto del servidor.** El contenido se cifra en el dispositivo antes de
  persistirse o sincronizarse. Firebase almacena ciphertext, identificadores aleatorios, versiones y
  marcas de tiempo, y no posee ninguna clave para descifrarlo.
- **La contraseña maestra nunca sale del dispositivo.** No se envía, no se almacena y no se registra.
  Se procesa localmente con Argon2id.
- **La frase de recuperación de 24 palabras nunca sale del dispositivo** y **nunca se almacena**, ni
  en el servidor ni localmente, ni cifrada.
- **Firebase Authentication no descifra nada.** Solo identifica al usuario y autoriza la descarga de
  blobs cifrados. Si alguien roba la cuenta de Google del usuario, no obtiene el contenido.
- **Cifrado autenticado con datos asociados.** Un ciphertext alterado o movido de contexto no se
  descifra: falla.
- **Aislamiento entre usuarios** mediante Firestore Security Rules de mínimo privilegio, con
  denegación por defecto.
- **Solo ciphertext en el almacenamiento local.** Room no guarda títulos, cuerpos ni etiquetas en
  claro.
- **Sin telemetría.** Sin analítica, publicidad, tracking ni informes de fallos en flujos sensibles.
- **Funciona sin conexión.** La sincronización es un añadido, no un requisito para usar la bóveda.

Detalle técnico completo: `CRYPTOGRAPHY.md`. Análisis de amenazas: `THREAT_MODEL.md`.

---

## 2. Lo que NO promete

Bóveda Wilson **no es invulnerable** y este documento no afirma seguridad absoluta.

- **No protege un dispositivo comprometido con la bóveda abierta.** Con root, un servicio de
  accesibilidad malicioso o un teclado comprometido, el contenido visible es alcanzable. Ninguna
  aplicación puede evitarlo.
- **No oculta los metadatos.** El servidor y quien extraiga la base local pueden ver cuántas notas
  existen, cuándo se modificaron y su tamaño aproximado.
- **No compensa una contraseña maestra débil.** Argon2id encarece un ataque offline; no lo hace
  imposible.
- **No hay recuperación si se pierden la contraseña maestra y la frase.** Los datos quedan
  **irrecuperables**. Nadie —ni el soporte, ni Google, ni Firebase, ni el desarrollador— puede
  revertirlo.
- **`FLAG_SECURE` no impide todas las capturas.** No bloquea una cámara apuntando a la pantalla ni
  todos los escenarios de sistemas modificados.
- **La biometría no es una vía de recuperación.** Solo desbloquea en el dispositivo donde se
  configuró y se invalida si cambia la biometría o el bloqueo de pantalla.
- **No hay negación plausible ni bóveda señuelo.** Frente a coerción, no hay defensa técnica.
- **No ha sido auditada por terceros.**
- **Cambiar una credencial no revoca copias antiguas.** Una contraseña o frase anterior junto con
  su envoltorio o respaldo antiguo todavía abre la misma VDEK. La revocación completa exige rotar
  la VDEK y recifrar.
- **No protege frente a ataques de canal lateral** sobre el hardware, ni frente a un sistema
  operativo o firmware comprometido.

---

## 3. Separación de credenciales

| Credencial | Para qué sirve | Quién la conoce |
|---|---|---|
| Cuenta de Firebase / Google | identificar al usuario y autorizar descargas | Google y Firebase |
| **Contraseña maestra** | descifrar la bóveda | **solo el usuario** |
| **Frase de 24 palabras** | descifrar la bóveda si se olvida la contraseña | **solo el usuario** |
| Bloqueo del teléfono / biometría | desbloqueo rápido en ese dispositivo | el sistema Android |

Las cuatro son **distintas por diseño** y la interfaz nunca sugiere reutilizarlas. La contraseña de
la cuenta de Google **no** abre la bóveda.

---

## 4. Sobre las claves de configuración de Firebase

La clave de API que aparece en la configuración cliente de Firebase (`google-services.json`)
**no es un secreto de autorización**: es un identificador público del proyecto. La seguridad depende
de Firebase Authentication, las Security Rules, App Check y —sobre todo— del cifrado local.

Esto **no** se aplica a las claves administrativas ni a las cuentas de servicio, que **sí** son
secretos. Este proyecto nunca las maneja: no están en el repositorio, no se piden y no se usan.

---

## 5. Prácticas del repositorio

- El repositorio **no contiene ningún secreto**: ni claves, ni cuentas de servicio, ni
  `google-services.json` real, ni almacenes de firma, ni contraseñas, ni frases.
- Antes de cada commit se revisa el diff completo buscando patrones de secreto.
- Las dependencias se fijan a versiones exactas y se registran con su fuente y fecha en
  `docs/DEPENDENCY_POLICY.md`.
- Todo el desarrollo con Firebase ocurre contra **Emulator Suite**; no se despliega nada.
- El diseño es público y eso es aceptable: la seguridad no depende de mantenerlo en secreto.

---

## 6. Recomendaciones para el usuario

1. Usa una contraseña maestra **larga** y que no uses en ningún otro sitio. Una frase de varias
   palabras es mejor que una contraseña corta con símbolos.
2. Escribe las 24 palabras **en papel** y guárdalas donde guardarías un documento importante. No en
   una foto, ni en una nota del teléfono, ni en el correo, ni en la nube.
3. Exporta un respaldo cifrado de vez en cuando y guárdalo en un lugar distinto del teléfono.
4. Activa el bloqueo automático con un tiempo corto.
5. Si crees que alguien vio tus 24 palabras, regenera el envoltorio desde ajustes y elige la
   rotación completa de la VDEK cuando se ofrezca; regenerar sin rotar no revoca copias antiguas.
6. Desconfía de cualquiera que te pida tu contraseña maestra o tus palabras: **la aplicación nunca
   las pide fuera de sus propias pantallas**, y nadie puede recuperar tu bóveda por ti.

---

## 7. Reportar un problema de seguridad

Este es un proyecto personal. Si encuentras un problema:

1. **No** lo publiques en un lugar público antes de avisar.
2. Escribe al propietario del repositorio describiendo el problema, cómo reproducirlo y su impacto.
3. **No incluyas datos reales, contraseñas, frases ni claves** en el reporte.

Se agradece cualquier revisión. La respuesta no está sujeta a un plazo comprometido, porque no
existe un equipo de seguridad detrás.
