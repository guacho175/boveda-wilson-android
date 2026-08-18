package cl.bovedawilson.data.sync.di

import android.content.Context
import cl.bovedawilson.core.common.AppDispatchers
import cl.bovedawilson.data.local.dao.BiometricUnlockDao
import cl.bovedawilson.data.local.store.VaultMetaStore
import cl.bovedawilson.data.sync.biometric.BiometricUnlock
import cl.bovedawilson.data.sync.engine.ConflictResolver
import cl.bovedawilson.data.sync.repo.BiometricUnlockRepository
import cl.bovedawilson.data.sync.session.VaultSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Proveedores del desbloqueo biométrico:
 * separado de [SyncModule] para que ninguno de los dos objetos supere el límite de
 * funciones por clase de Detekt. */
@Module
@InstallIn(SingletonComponent::class)
object BiometricModule {

    @Singleton
    @Provides
    fun provideBiometricUnlock(@ApplicationContext context: Context): BiometricUnlock = BiometricUnlock(context)

    @Singleton
    @Provides
    // El provider refleja las dependencias explícitas del ciclo biométrico y de staging.
    @Suppress("LongParameterList")
    fun provideBiometricUnlockRepository(
        metaStore: VaultMetaStore,
        session: VaultSession,
        dispatchers: AppDispatchers,
        biometricUnlockDao: BiometricUnlockDao,
        biometricUnlock: BiometricUnlock,
        conflictResolver: ConflictResolver
    ): BiometricUnlockRepository =
        BiometricUnlockRepository(
            metaStore,
            session,
            dispatchers,
            biometricUnlockDao,
            biometricUnlock,
            conflictResolver
        )
}
