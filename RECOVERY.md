# RECOVERY.md — Recuperación con la frase de 24 palabras

Cómo funciona la recuperación de Bóveda Wilson, qué protege y qué la pierde. Contrato
criptográfico en `CRYPTOGRAPHY.md` §11; ciclo de vida en `docs/key-lifecycle.md` §7 y §8.
Estado: flujo implementado. Las pruebas automatizadas y la evidencia de release se registran en
`PROJECT_STATE.md`; este documento describe el contrato de uso y sus límites.

---

## 1. Qué es y qué no es

La frase de 24 palabras es una **codificación legible de 256 bits de entropía aleatoria** generada
en el dispositivo. Usa la lista **inglesa** y la codificación con checksum de BIP-39 porque es un
formato conocido y verificable, pero:

- **No es una billetera.** No sirve en software de criptomonedas y no controla ningún fondo.
- **No es una contraseña que el usuario elige.** Es azar real de 256 bits: no se puede adivinar.
- **No usa la derivación de semilla de BIP-39.** No hay PBKDF2 del estándar, no hay passphrase y no
  hay rutas de derivación. La frase se decodifica a su entropía y esa entropía alimenta HKDF con un
  contexto propio de Bóveda Wilson.

Su función es exactamente una: derivar la **RecoveryKEK**, que desenvuelve una copia de la clave de
la bóveda (VDEK) si el usuario pierde su contraseña maestra.

---

## 2. Advertencia que la aplicación muestra

> **Quien posea estas 24 palabras podrá abrir tu bóveda.**
> Escríbelas en papel y guárdalas en un lugar seguro. No las guardes en una foto, en una nota del
> teléfono, en el correo ni en la nube.
>
> **No podrás volver a verlas.** Se muestran una sola vez.
>
> Si pierdes tu contraseña maestra **y** estas palabras, tus datos serán **irrecuperables**. Nadie
> podrá recuperarlos: ni el soporte, ni Google, ni Firebase, ni el desarrollador.

---

## 3. Configuración inicial

1. Se generan 256 bits de entropía con el generador criptográfico del sistema, **en el dispositivo**.
2. Se codifican como 24 palabras y se muestran en pantalla, con la advertencia de §2.
3. Se pide al usuario que las escriba en papel.
4. La aplicación **verifica** varias palabras elegidas al azar antes de continuar. Sin esa
   verificación la bóveda no se termina de crear.
5. Se deriva la RecoveryKEK y se envuelve la VDEK con ella.
6. Se sube **únicamente** `recoveryWrappedVdek`, el `recoverySalt` y la versión criptográfica.
7. La entropía y las palabras se **borran de memoria**.

Lo que **nunca** ocurre: las palabras no se envían por red, no se guardan en Firebase, no se guardan
en Room, no se escriben en logs ni excepciones, no se copian automáticamente al portapapeles, no
aparecen en capturas de pruebas y no entran en el repositorio.

---

## 4. No se pueden volver a ver: se regeneran (ADR-011)

La entropía **no se persiste nunca**, ni siquiera cifrada. Por lo tanto **no existe** la función de
volver a mostrar la frase.

Se evaluó guardarla envuelta con la VDEK para poder re-mostrarla y se descartó: cualquiera con
acceso a la bóveda desbloqueada —un ladrón con el teléfono abierto, malware— podría copiar la frase
y conservar acceso permanente incluso después de un cambio de contraseña. Además, los requisitos
prohíben expresamente almacenar la entropía.

La alternativa implementada es **regenerar la frase**:

```
Ajustes de seguridad → Regenerar frase de recuperación
requiere: bóveda desbloqueada + contraseña maestra + advertencia explícita
resultado: se genera una frase nueva, se verifica, y la frase anterior deja de abrir el
           envoltorio almacenado actualmente
```

Casos de uso: el usuario perdió el papel, cree que alguien vio sus palabras, o quiere rotarlas por
higiene. La bóveda y las notas no cambian: solo cambia el envoltorio de recuperación.

«Deja de funcionar» significa exactamente que la frase anterior ya no abre el
`recoveryWrappedVdek` **almacenado actualmente**. Si alguien conserva una copia antigua de ese
envoltorio —por ejemplo, desde un respaldo exportado— y la frase correspondiente, todavía puede
abrir la misma VDEK. Cuando el motivo sea un posible compromiso, la interfaz ofrece rotar la VDEK
y explica que esa operación, con recifrado de ítems, es la única revocación criptográfica completa.

---

## 5. Recuperar el acceso

Requisitos simultáneos:

1. **Iniciar sesión en Firebase** con la misma cuenta (autoriza la descarga del documento cifrado).
2. **Introducir correctamente las 24 palabras.**
3. **Definir una contraseña maestra nueva** y confirmarla.

Proceso:

```
sesión de Firebase ──▶ descargar recoverySalt + recoveryWrappedVdek + versiones
   │
   ▼
24 palabras ──normalizar──▶ validar checksum ──▶ entropía ──HKDF──▶ RecoveryKEK
   │
   ▼
desenvolver la VDEK  ──▶  éxito: la bóveda es accesible
   │                        │
   │                        ▼
   │                 contraseña maestra nueva ──▶ salt nuevo ──Argon2id + HKDF──▶ PasswordKEK nueva
   │                        │
   │                        ▼
   │                 verificar ambos envoltorios → incrementar passwordWrapEpoch
   │                 → reescribir passwordWrappedVdek de forma atómica
   ▼
fallo: error genérico. No se distingue una palabra mal de un dato alterado.
```

Puntos importantes:

- El servidor **solo** entrega `recoveryWrappedVdek` y parámetros públicos. No participa en ninguna
  derivación y no puede descifrar nada.
- La **misma VDEK** se recupera: todas las notas siguen siendo legibles, sin recifrado.
- La recuperación **no** invalida la frase: sigue siendo válida después. Si el usuario quiere
  invalidarla, debe regenerarla (§4).
- Los parámetros externos solo reproducen el envoltorio existente. El envoltorio nuevo de
  contraseña usa los parámetros de producción del binario; nunca adopta parámetros remotos.
- Una sola palabra incorrecta hace fallar el checksum, o —si por casualidad el checksum siguiera
  siendo válido— hace fallar la autenticación del desenvolvido. En ambos casos el fallo es limpio.

---

## 6. Entrada de las palabras

- Campo por palabra, con autocompletado **local** contra la lista del estándar (la lista de palabras
  no es secreta y va incluida en la aplicación; el autocompletado no consulta la red).
- Normalización exacta: Unicode NFKD → recorte de extremos → colapso de espacios internos a un
  único `U+0020` → minúsculas con `Locale.ROOT`. Después se comprueba la lista inglesa y el checksum
  en tiempo constante.
- La pantalla tiene protección de captura, no envía nada mientras se escribe y no guarda borradores.
- No se ofrece «pegar desde el portapapeles» como camino recomendado, aunque no se bloquea.

---

## 7. Qué pierde la bóveda para siempre

| Situación | Resultado |
|---|---|
| Se olvida la contraseña maestra, pero se tiene la frase | recuperable (§5) |
| Se pierde la frase, pero se recuerda la contraseña | accesible; conviene **regenerar** la frase (§4) |
| Se pierde el teléfono, pero se recuerda la contraseña | accesible en otro dispositivo tras iniciar sesión en Firebase |
| Se pierde el acceso a la cuenta de Firebase, pero se tiene contraseña o frase y un respaldo exportado | recuperable desde el respaldo (`BACKUP_FORMAT.md`) |
| Se pierden la contraseña maestra **y** la frase | **irrecuperable, sin excepción** |

La última fila es una propiedad deseada del conocimiento cero: el precio de que nadie más pueda
leer la bóveda es que nadie más puede rescatarla.

Un respaldo antiguo conserva válidos los envoltorios y las credenciales vigentes cuando se creó.
Cambiar la contraseña o regenerar la frase no modifica copias exportadas previamente.

---

## 8. Reglas para el desarrollo

- **Nunca** se genera una frase para uso real durante el desarrollo.
- **Nunca** se pide al propietario una frase real.
- **Nunca** se muestra una frase en informes, respuestas, logs de prueba o capturas.
- Las pruebas usan **vectores públicos del estándar BIP-39** o datos marcados explícitamente como
  ficticios (constantes con prefijo `FIXTURE_` y un comentario de «valor ficticio, no usar en
  producción»).
- Existe una prueba de higiene que falla si aparece algo con forma de frase mnemónica fuera de los
  fixtures declarados.
