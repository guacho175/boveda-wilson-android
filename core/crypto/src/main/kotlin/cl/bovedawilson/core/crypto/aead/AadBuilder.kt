package cl.bovedawilson.core.crypto.aead

import cl.bovedawilson.core.crypto.error.CryptoError
import cl.bovedawilson.core.crypto.kdf.KdfParameters
import cl.bovedawilson.core.crypto.version.CryptoVersion
import cl.bovedawilson.core.crypto.version.SchemaVersion
import cl.bovedawilson.core.crypto.wrap.WrapType
import java.util.Base64

/**
 * Construye las cadenas canónicas de `CRYPTOGRAPHY.md` §7, campo por campo, con las reglas
 * normativas de ADR-021: juego de caracteres cerrado por campo, enteros en ASCII decimal
 * sin ceros a la izquierda, salts recodificados en base64url sin relleno **desde los bytes
 * validados** (nunca copiados de una cadena recibida), y orden de campos congelado.
 *
 * Estas mismas reglas son las que hacen que la AAD se pueda **reconstruir** byte a byte al
 * descifrar: es una función pura de los metadatos, sin estado ni orden dependiente del
 * tiempo de ejecución.
 */
object AadBuilder {
    private const val VERSION_PREFIX = "bw1"
    private const val MAX_IDENTIFIER_LENGTH = 128
    private val IDENTIFIER_PATTERN = Regex("^[A-Za-z0-9._:-]{1,$MAX_IDENTIFIER_LENGTH}$")
    private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()

    private fun validIdentifier(value: String): String {
        if (!IDENTIFIER_PATTERN.matches(value)) throw CryptoError.MalformedInput
        return value
    }

    private fun validInt(value: Int): String {
        if (value < 0) throw CryptoError.MalformedInput
        val text = value.toString()
        if (text.length > 1 && text[0] == '0') throw CryptoError.MalformedInput
        return text
    }

    private fun base64Url(bytes: ByteArray): String = base64UrlEncoder.encodeToString(bytes)

    private fun encode(parts: List<String>): Aad = Aad(parts.joinToString("|").toByteArray(Charsets.UTF_8))

    /** `bw1|item|<vaultId>|<itemId>|<schemaVersion>|<cryptoVersion>` */
    fun forItem(
        vaultId: String,
        itemId: String,
        schemaVersion: SchemaVersion,
        cryptoVersion: CryptoVersion,
    ): Aad = encode(
        listOf(
            VERSION_PREFIX,
            "item",
            validIdentifier(vaultId),
            validIdentifier(itemId),
            validInt(schemaVersion.value),
            validInt(cryptoVersion.value),
        ),
    )

    /** `bw1|vdek-wrap|<vaultId>|password|<cryptoVersion>|argon2id,m=…,t=…,p=…,len=32,salt=…,epoch=…` */
    fun forPasswordWrap(
        vaultId: String,
        cryptoVersion: CryptoVersion,
        kdfParameters: KdfParameters,
        passwordWrapEpoch: Int,
    ): Aad {
        if (kdfParameters.kdfName != "argon2id") throw CryptoError.MalformedInput
        val canonicalParams = "argon2id" +
            ",m=${validInt(kdfParameters.memoryKib)}" +
            ",t=${validInt(kdfParameters.iterations)}" +
            ",p=${validInt(kdfParameters.parallelism)}" +
            ",len=${validInt(kdfParameters.outputLength)}" +
            ",salt=${base64Url(kdfParameters.salt)}" +
            ",epoch=${validInt(passwordWrapEpoch)}"
        return forWrap(vaultId, WrapType.PASSWORD, cryptoVersion, canonicalParams)
    }

    /**
     * `bw1|vdek-wrap|<vaultId>|recovery|<cryptoVersion>|hkdf-sha256,len=32,salt=…,` +
     * `entropyBits=256,words=24,wordlist=english,epoch=…`
     */
    fun forRecoveryWrap(
        vaultId: String,
        cryptoVersion: CryptoVersion,
        recoverySalt: ByteArray,
        recoveryWrapEpoch: Int,
    ): Aad {
        val canonicalParams = "hkdf-sha256,len=32,salt=${base64Url(recoverySalt)}" +
            ",entropyBits=256,words=24,wordlist=english,epoch=${validInt(recoveryWrapEpoch)}"
        return forWrap(vaultId, WrapType.RECOVERY, cryptoVersion, canonicalParams)
    }

    /** `bw1|vdek-wrap|<vaultId>|biometric|<cryptoVersion>|tink-aes256-gcm,alias=…,epoch=…` */
    fun forBiometricWrap(
        vaultId: String,
        alias: String,
        cryptoVersion: CryptoVersion,
        biometricWrapEpoch: Int,
    ): Aad {
        val canonicalParams = "tink-aes256-gcm,alias=${validIdentifier(alias)},epoch=${validInt(biometricWrapEpoch)}"
        return forWrap(vaultId, WrapType.BIOMETRIC, cryptoVersion, canonicalParams)
    }

    private fun forWrap(
        vaultId: String,
        wrapType: WrapType,
        cryptoVersion: CryptoVersion,
        canonicalParams: String,
    ): Aad = encode(
        listOf(
            VERSION_PREFIX,
            "vdek-wrap",
            validIdentifier(vaultId),
            wrapType.canonical,
            validInt(cryptoVersion.value),
            canonicalParams,
        ),
    )

    /** `bw1|biometric-kek|<vaultId>|<alias>|<cryptoVersion>` */
    fun forBiometricKek(vaultId: String, alias: String, cryptoVersion: CryptoVersion): Aad = encode(
        listOf(
            VERSION_PREFIX,
            "biometric-kek",
            validIdentifier(vaultId),
            validIdentifier(alias),
            validInt(cryptoVersion.value),
        ),
    )
}
