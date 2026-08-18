package cl.bovedawilson.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cl.bovedawilson.core.crypto.ciphertext.Ciphertext
import cl.bovedawilson.data.remote.auth.FirebaseAuthSourceImpl
import cl.bovedawilson.data.remote.firestore.FirestoreVaultSourceImpl
import cl.bovedawilson.data.remote.firestore.RemoteItemMetadata
import cl.bovedawilson.data.remote.firestore.RemoteVaultMetadata
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Verificación real de `FirebaseAuthSourceImpl`/`FirestoreVaultSourceImpl` contra el
 * Firebase Emulator Suite (`firebase/firebase.json`), no contra un doble simulado.
 * Requiere:
 *   1. el Emulator Suite corriendo (`firebase emulators:start --project demo-boveda-wilson-public
 *      --only auth,firestore`, con JDK 21 — ADR-036);
 *   2. si se ejecuta en un dispositivo físico (no un AVD), reenviar los puertos por USB:
 *      `adb reverse tcp:9099 tcp:9099` y `adb reverse tcp:8080 tcp:8080`, y pasar
 *      `EMULATOR_HOST = "localhost"` (ver ADR-037 sobre por qué "10.0.2.2" no sirve aquí).
 *
 * El `projectId` `demo-boveda-wilson-public` (prefijo `demo-`, igual que `firebase/.firebaserc`)
 * hace que el SDK de Firebase hable únicamente con el emulador local: `FIXTURE_APP_ID` y
 * `FIXTURE_API_KEY` son valores ficticios sin validez fuera de él, no secretos.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseRemoteEmulatorTest {

    private val authSource = FirebaseAuthSourceImpl(auth = firebaseAuth, emulatorHost = EMULATOR_HOST)
    private val remoteSource = FirestoreVaultSourceImpl(
        firestore = firebaseFirestore,
        auth = firebaseAuth,
        emulatorHost = EMULATOR_HOST
    )

    @Test
    fun g74EmailSignUpThenSignInResolveTheSameUid() = runBlocking {
        val email = "${UUID.randomUUID()}@example.invalid"
        val password = "FIXTURE_password_1234".toCharArray() // valor ficticio, no usar en producción

        val signedUpUid = authSource.signUpWithEmail(email, password.copyOf())
        assertNotNull(signedUpUid)
        assertEquals(signedUpUid, authSource.currentUserId)

        authSource.signOut()
        assertNull(authSource.currentUserId)

        val signedInUid = authSource.signInWithEmail(email, password.copyOf())
        assertEquals(signedUpUid, signedInUid)
    }

    @Test
    fun g74VaultMetaRoundTripsThroughFirestoreRules() = runBlocking {
        val email = "${UUID.randomUUID()}@example.invalid"
        val uid = authSource.signUpWithEmail(email, "FIXTURE_password_1234".toCharArray())

        val vaultId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val fixtureMeta = RemoteVaultMetadata(
            schemaVersion = 1,
            cryptoVersion = 1,
            kdfName = "argon2id",
            kdfMemoryKib = 65536,
            kdfIterations = 3,
            kdfParallelism = 4,
            kdfOutputLen = 32,
            passwordSalt = ByteArray(16) { it.toByte() }, // valor ficticio, no usar en producción
            passwordWrappedVdek = ByteArray(48) { 1 }, // valor ficticio, no usar en producción
            recoverySalt = ByteArray(32) { it.toByte() }, // valor ficticio, no usar en producción
            recoveryWrappedVdek = ByteArray(48) { 2 }, // valor ficticio, no usar en producción
            passwordWrapEpoch = 1,
            recoveryWrapEpoch = 1,
            createdAt = now,
            updatedAt = now,
            metaRevision = 1
        )

        remoteSource.createVaultMeta(uid, vaultId, fixtureMeta)
        val readBack = remoteSource.getVaultMeta(uid, vaultId)

        assertNotNull(readBack)
        assertEquals(fixtureMeta.schemaVersion, readBack!!.schemaVersion)
        assertEquals(fixtureMeta.kdfName, readBack.kdfName)
        assertTrue(fixtureMeta.passwordSalt.contentEquals(readBack.passwordSalt))
        assertTrue(fixtureMeta.recoveryWrappedVdek.contentEquals(readBack.recoveryWrappedVdek))
        assertEquals(fixtureMeta.metaRevision, readBack.metaRevision)
    }

    @Test
    fun g74ItemRoundTripsAsOpaqueCiphertext() = runBlocking {
        val email = "${UUID.randomUUID()}@example.invalid"
        val uid = authSource.signUpWithEmail(email, "FIXTURE_password_1234".toCharArray())

        val vaultId = UUID.randomUUID().toString()
        val itemId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val fixtureCiphertext = Ciphertext.fromPersisted(
            ByteArray(64) { it.toByte() } // valor ficticio, no es ciphertext real
        )
        val fixtureMeta = RemoteItemMetadata(
            cryptoVersion = 1,
            schemaVersion = 1,
            revision = 1,
            tombstone = false,
            createdAt = now,
            updatedAt = now
        )

        remoteSource.uploadItem(uid, vaultId, itemId, fixtureCiphertext, fixtureMeta)
        val readBack = remoteSource.getItem(uid, vaultId, itemId)

        assertNotNull(readBack)
        val (readCiphertext, readMeta) = readBack!!
        assertTrue(fixtureCiphertext.bytes.contentEquals(readCiphertext.bytes))
        assertEquals(fixtureMeta.revision, readMeta.revision)
        assertEquals(fixtureMeta.tombstone, readMeta.tombstone)
    }

    @Test
    fun g75PurgeIsTerminalAndIdempotent() = runBlocking {
        val email = "${UUID.randomUUID()}@example.invalid"
        val uid = authSource.signUpWithEmail(email, "FIXTURE_password_1234".toCharArray())
        val vaultId = UUID.randomUUID().toString()
        val itemId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        remoteSource.createVaultMeta(
            uid,
            vaultId,
            RemoteVaultMetadata(
                1,
                1,
                "argon2id",
                65_536,
                3,
                4,
                32,
                ByteArray(16) { 1 },
                ByteArray(48) { 2 },
                ByteArray(32) { 3 },
                ByteArray(48) { 4 },
                1,
                1,
                now,
                now,
                1
            )
        )
        remoteSource.uploadItem(
            uid,
            vaultId,
            itemId,
            Ciphertext.fromPersisted(ByteArray(32) { 5 }),
            RemoteItemMetadata(1, 1, 1, false, now, now)
        )

        remoteSource.purgeVault(uid, vaultId, now)
        remoteSource.purgeVault(uid, vaultId, now)

        assertNull(remoteSource.getVaultMeta(uid, vaultId))
        assertTrue(remoteSource.listItems(uid, vaultId).isEmpty())
        assertTrue(vaultId in remoteSource.listDeletedVaultIds(uid))
    }

    private companion object {
        // "localhost" + adb reverse porque estas pruebas corren en el dispositivo físico
        // conectado por USB, no en un AVD: "10.0.2.2" no lo resuelve.
        const val EMULATOR_HOST = "localhost"
        const val DEMO_PROJECT_ID = "demo-boveda-wilson-public"
        const val FIXTURE_APP_ID = "1:000000000000:android:0000000000000000"
        const val FIXTURE_API_KEY = "fixture-api-key-not-a-secret"

        lateinit var firebaseApp: FirebaseApp
        lateinit var firebaseAuth: FirebaseAuth
        lateinit var firebaseFirestore: FirebaseFirestore

        @BeforeClass
        @JvmStatic
        fun initFirebaseApp() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val options = FirebaseOptions.Builder()
                .setProjectId(DEMO_PROJECT_ID)
                .setApplicationId(FIXTURE_APP_ID)
                .setApiKey(FIXTURE_API_KEY)
                .build()
            firebaseApp = FirebaseApp.initializeApp(context, options, "boveda-wilson-emulator-test")
            firebaseAuth = FirebaseAuth.getInstance(firebaseApp)
            firebaseFirestore = FirebaseFirestore.getInstance(firebaseApp)
        }
    }
}
