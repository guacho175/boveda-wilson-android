package cl.bovedawilson.data.sync.mapper

import cl.bovedawilson.core.crypto.kdf.KdfParameters
import cl.bovedawilson.core.crypto.vault.PasswordWrap
import cl.bovedawilson.core.crypto.vault.RecoveryWrap
import cl.bovedawilson.core.crypto.vault.VaultRecord
import cl.bovedawilson.core.crypto.version.CryptoVersion
import cl.bovedawilson.core.crypto.version.SchemaVersion
import cl.bovedawilson.core.crypto.wrap.WrappedVdek
import cl.bovedawilson.data.local.entity.VaultMetaEntity

/**
 * Traduce entre el envoltorio criptográfico de `:core:crypto` ([VaultRecord]) y la fila de
 * `vault_meta` de `:data:local`.
 *
 * Todo lo que cruza aquí es **público y no sensible**: identificadores, versiones,
 * parámetros de KDF, salts y keysets ya envueltos (`CRYPTOGRAPHY.md` §5). Ni la VDEK, ni
 * la KEK, ni la contraseña, ni la entropía de recuperación pasan por este mapeo.
 */
object VaultRecordMapper {

    /**
     * @param ownerUid vacío mientras no haya sesión de Firebase Authentication. La bóveda
     *   es local-first: el `uid` solo se necesita para autorizar descargas de ciphertext,
     *   nunca para descifrar (ADR-009).
     */
    @Suppress("LongParameterList")
    fun toEntity(
        record: VaultRecord,
        ownerUid: String,
        createdAt: Long,
        updatedAt: Long,
        metaRevision: Int
    ): VaultMetaEntity = VaultMetaEntity(
        vaultId = record.vaultId,
        ownerUid = ownerUid,
        schemaVersion = record.schemaVersion.value,
        cryptoVersion = record.cryptoVersion.value,
        kdfName = record.password.parameters.kdfName,
        kdfMemoryKib = record.password.parameters.memoryKib,
        kdfIterations = record.password.parameters.iterations,
        kdfParallelism = record.password.parameters.parallelism,
        kdfOutputLen = record.password.parameters.outputLength,
        passwordSalt = record.password.parameters.salt,
        passwordWrappedVdek = record.password.wrappedVdek.bytes,
        recoverySalt = record.recovery.salt,
        recoveryWrappedVdek = record.recovery.wrappedVdek.bytes,
        passwordWrapEpoch = record.password.epoch,
        recoveryWrapEpoch = record.recovery.epoch,
        createdAt = createdAt,
        updatedAt = updatedAt,
        metaRevision = metaRevision
    )

    fun toRecord(entity: VaultMetaEntity): VaultRecord = VaultRecord(
        vaultId = entity.vaultId,
        cryptoVersion = CryptoVersion(entity.cryptoVersion),
        schemaVersion = SchemaVersion(entity.schemaVersion),
        password = PasswordWrap(
            parameters = KdfParameters(
                kdfName = entity.kdfName,
                memoryKib = entity.kdfMemoryKib,
                iterations = entity.kdfIterations,
                parallelism = entity.kdfParallelism,
                outputLength = entity.kdfOutputLen,
                salt = entity.passwordSalt
            ),
            wrappedVdek = WrappedVdek(entity.passwordWrappedVdek),
            epoch = entity.passwordWrapEpoch
        ),
        recovery = RecoveryWrap(
            salt = entity.recoverySalt,
            wrappedVdek = WrappedVdek(entity.recoveryWrappedVdek),
            epoch = entity.recoveryWrapEpoch
        )
    )
}
