package cl.bovedawilson.data.sync

import cl.bovedawilson.core.crypto.ciphertext.Ciphertext
import cl.bovedawilson.data.local.store.EncryptedItemStore
import cl.bovedawilson.data.remote.firestore.FirestoreVaultSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.valueParameters

class ArchitectureTest {

    @Test
    fun testDataLocalAcceptsOnlyCiphertext() {
        val putMethod = EncryptedItemStore::class.memberFunctions.find { it.name == "put" }
        requireNotNull(putMethod) { "put method not found in EncryptedItemStore" }

        val hasCiphertextParam = putMethod.valueParameters.any { it.type.classifier == Ciphertext::class }
        val hasByteArrayParam = putMethod.valueParameters.any { it.type.classifier == ByteArray::class }

        assertTrue(
            "EncryptedItemStore.put MUST accept a Ciphertext parameter to enforce opaqueness",
            hasCiphertextParam
        )
        assertEquals(
            "EncryptedItemStore.put MUST NOT accept a raw ByteArray to prevent accidental plaintext leaks",
            false,
            hasByteArrayParam
        )
    }

    @Test
    fun testDataRemoteAcceptsOnlyCiphertext() {
        val uploadItemMethod = FirestoreVaultSource::class.memberFunctions.find { it.name == "uploadItem" }
        requireNotNull(uploadItemMethod) { "uploadItem method not found in FirestoreVaultSource" }

        val hasCiphertextParam = uploadItemMethod.valueParameters.any { it.type.classifier == Ciphertext::class }
        val hasByteArrayParam = uploadItemMethod.valueParameters.any { it.type.classifier == ByteArray::class }

        assertTrue(
            "FirestoreVaultSource.uploadItem MUST accept a Ciphertext parameter to enforce opaqueness",
            hasCiphertextParam
        )
        assertEquals(
            "FirestoreVaultSource.uploadItem MUST NOT accept a raw ByteArray to prevent accidental plaintext leaks",
            false,
            hasByteArrayParam
        )
    }
}
