package cl.bovedawilson.core.crypto.aead

/**
 * Datos asociados autenticados ya codificados. Se autentican pero no se cifran: por
 * construcción de [AadBuilder] nunca contienen material sensible, solo identificadores
 * aleatorios, versiones y parámetros públicos (`CRYPTOGRAPHY.md` §7).
 */
@JvmInline
value class Aad internal constructor(val bytes: ByteArray)
