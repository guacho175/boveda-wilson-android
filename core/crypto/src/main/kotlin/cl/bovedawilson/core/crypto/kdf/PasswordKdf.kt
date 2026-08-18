package cl.bovedawilson.core.crypto.kdf

/**
 * Deriva material de 32 bytes desde una contraseña maestra. Quien llame es responsable de
 * borrar el `ByteArray` devuelto tras usarlo. Implementaciones de prueba con parámetros
 * reducidos deben marcarse explícitamente como tales (`docs/TEST_STRATEGY.md` §1) y
 * nunca sustituir a [Argon2idPasswordKdf] fuera de pruebas que no verifican el KDF.
 */
interface PasswordKdf {
    fun derive(password: ByteArray, params: KdfParameters): ByteArray
}
