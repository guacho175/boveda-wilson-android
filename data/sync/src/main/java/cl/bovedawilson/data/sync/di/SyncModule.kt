package cl.bovedawilson.data.sync.di

import cl.bovedawilson.core.common.AppDispatchers
import cl.bovedawilson.data.local.dao.EncryptedItemDao
import cl.bovedawilson.data.local.dao.VaultMetaDao
import cl.bovedawilson.data.local.prefs.SettingsDataStore
import cl.bovedawilson.data.local.store.EncryptedItemStore
import cl.bovedawilson.data.local.store.LocalVaultDataWiper
import cl.bovedawilson.data.local.store.RoomVaultMetaStore
import cl.bovedawilson.data.local.store.VaultMetaStore
import cl.bovedawilson.data.remote.auth.FirebaseAuthSource
import cl.bovedawilson.data.remote.firestore.FirestoreVaultSource
import cl.bovedawilson.data.sync.engine.ConflictResolver
import cl.bovedawilson.data.sync.engine.SyncCoordinator
import cl.bovedawilson.data.sync.engine.SyncEngine
import cl.bovedawilson.data.sync.repo.AndroidBiometricKeyInvalidator
import cl.bovedawilson.data.sync.repo.BackupRepository
import cl.bovedawilson.data.sync.repo.BiometricKeyInvalidator
import cl.bovedawilson.data.sync.repo.CloudAccessRepository
import cl.bovedawilson.data.sync.repo.ItemRepository
import cl.bovedawilson.data.sync.repo.MasterPasswordVerifier
import cl.bovedawilson.data.sync.repo.RemoteDeletionHandler
import cl.bovedawilson.data.sync.repo.SettingsRepository
import cl.bovedawilson.data.sync.repo.VaultCreationRepository
import cl.bovedawilson.data.sync.repo.VaultLifecycleRepository
import cl.bovedawilson.data.sync.repo.VaultMasterPasswordVerifier
import cl.bovedawilson.data.sync.repo.VaultRepository
import cl.bovedawilson.data.sync.session.AutoLockController
import cl.bovedawilson.data.sync.session.VaultSession
import cl.bovedawilson.data.sync.worker.AndroidSyncScheduler
import cl.bovedawilson.data.sync.worker.AndroidSyncWorkCanceller
import cl.bovedawilson.data.sync.worker.SyncScheduler
import cl.bovedawilson.data.sync.worker.SyncWorkCanceller
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Proveedores de sincronización y ciclo de vida de la bóveda. El desbloqueo biométrico
 * tiene su propio módulo, [BiometricModule]: juntar todo aquí habría superado el límite
 * de funciones por objeto de Detekt.
 */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("LongParameterList", "TooManyFunctions") // Proveedores DI explícitos y cohesivos.
object SyncModule {

    @Singleton
    @Provides
    fun provideAppDispatchers(): AppDispatchers = AppDispatchers()

    /** Una sola sesión criptográfica en todo el proceso: es la que conserva la VDEK
     * desbloqueada en memoria (`docs/architecture.md` §5). */
    @Singleton
    @Provides
    fun provideVaultSession(): VaultSession = VaultSession()

    @Singleton
    @Provides
    fun provideSyncCoordinator(): SyncCoordinator = SyncCoordinator()

    @Singleton
    @Provides
    fun provideVaultMetaStore(dao: VaultMetaDao): VaultMetaStore = RoomVaultMetaStore(dao)

    @Singleton
    @Provides
    fun provideSettingsRepository(settings: SettingsDataStore): SettingsRepository = SettingsRepository(settings)

    @Singleton
    @Provides
    fun provideSyncWorkCanceller(implementation: AndroidSyncWorkCanceller): SyncWorkCanceller = implementation

    @Singleton
    @Provides
    fun provideSyncScheduler(implementation: AndroidSyncScheduler): SyncScheduler = implementation

    @Singleton
    @Provides
    fun provideMasterPasswordVerifier(implementation: VaultMasterPasswordVerifier): MasterPasswordVerifier =
        implementation

    @Singleton
    @Provides
    fun provideBiometricKeyInvalidator(
        implementation: AndroidBiometricKeyInvalidator
    ): BiometricKeyInvalidator = implementation

    @Singleton
    @Provides
    fun provideRemoteDeletionHandler(repository: VaultLifecycleRepository): RemoteDeletionHandler = repository

    @Singleton
    @Provides
    @Suppress("LongParameterList")
    fun provideVaultLifecycleRepository(
        metaStore: VaultMetaStore,
        settings: SettingsDataStore,
        localWiper: LocalVaultDataWiper,
        remote: FirestoreVaultSource,
        auth: FirebaseAuthSource,
        session: VaultSession,
        biometricKeyInvalidator: BiometricKeyInvalidator,
        syncWorkCanceller: SyncWorkCanceller,
        masterPasswordVerifier: MasterPasswordVerifier,
        dispatchers: AppDispatchers,
        syncCoordinator: SyncCoordinator
    ): VaultLifecycleRepository = VaultLifecycleRepository(
        metaStore,
        settings,
        localWiper,
        remote,
        auth,
        session,
        biometricKeyInvalidator,
        syncWorkCanceller,
        masterPasswordVerifier,
        dispatchers,
        syncCoordinator
    )

    @Singleton
    @Provides
    fun provideVaultRepository(
        metaStore: VaultMetaStore,
        itemDao: EncryptedItemDao,
        session: VaultSession,
        dispatchers: AppDispatchers,
        conflictResolver: ConflictResolver,
        syncScheduler: SyncScheduler
    ): VaultRepository = VaultRepository(
        metaStore,
        itemDao,
        session,
        dispatchers,
        conflictResolver,
        syncScheduler
    )

    @Singleton
    @Provides
    fun provideVaultCreationRepository(
        metaStore: VaultMetaStore,
        session: VaultSession,
        dispatchers: AppDispatchers
    ): VaultCreationRepository = VaultCreationRepository(metaStore, session, dispatchers)

    @Singleton
    @Provides
    fun provideBackupRepository(
        database: cl.bovedawilson.data.local.db.VaultDatabase,
        metaDao: VaultMetaDao,
        metaStore: VaultMetaStore,
        itemDao: EncryptedItemDao,
        dispatchers: AppDispatchers,
        session: VaultSession,
        remotePublisher: cl.bovedawilson.data.sync.repo.BackupRemotePublisher,
        publicationAuthorizer: cl.bovedawilson.data.sync.repo.BackupPublicationAuthorizer,
        syncCoordinator: SyncCoordinator,
    ): BackupRepository =
        BackupRepository(
            database,
            metaDao,
            metaStore,
            itemDao,
            dispatchers,
            session,
            remotePublisher,
            publicationAuthorizer,
            syncCoordinator,
        )

    @Singleton
    @Provides
    fun provideBackupPublicationAuthorizer(
        auth: FirebaseAuthSource,
        session: VaultSession,
    ): cl.bovedawilson.data.sync.repo.BackupPublicationAuthorizer =
        cl.bovedawilson.data.sync.repo.InMemoryBackupPublicationAuthorizer(auth, session)

    @Singleton
    @Provides
    fun provideBackupRemotePublisher(
        auth: FirebaseAuthSource,
        remote: FirestoreVaultSource,
        metaStore: VaultMetaStore,
        itemDao: EncryptedItemDao,
        dispatchers: AppDispatchers,
        syncCoordinator: SyncCoordinator,
        publicationAuthorizer: cl.bovedawilson.data.sync.repo.BackupPublicationAuthorizer,
    ): cl.bovedawilson.data.sync.repo.BackupRemotePublisher =
        cl.bovedawilson.data.sync.repo.BackupRemotePublisher(
            auth,
            remote,
            metaStore,
            itemDao,
            dispatchers,
            syncCoordinator,
            publicationAuthorizer,
        )

    @Singleton
    @Provides
    @Suppress("LongParameterList")
    fun provideCloudAccessRepository(
        auth: FirebaseAuthSource,
        remote: FirestoreVaultSource,
        metaStore: VaultMetaStore,
        session: VaultSession,
        dispatchers: AppDispatchers,
        syncWorkCanceller: SyncWorkCanceller,
        syncCoordinator: SyncCoordinator,
        syncScheduler: SyncScheduler,
        remoteDeletionHandler: RemoteDeletionHandler
    ): CloudAccessRepository =
        CloudAccessRepository(
            auth,
            remote,
            metaStore,
            session,
            dispatchers,
            syncWorkCanceller,
            syncCoordinator,
            syncScheduler,
            remoteDeletionHandler
        )

    @Singleton
    @Provides
    fun provideItemRepository(
        localStore: EncryptedItemStore,
        remoteSource: FirestoreVaultSource,
        session: VaultSession,
        syncScheduler: SyncScheduler,
        syncCoordinator: SyncCoordinator,
    ): ItemRepository = ItemRepository(localStore, remoteSource, session, syncScheduler, syncCoordinator)

    @Singleton
    @Provides
    fun provideSyncEngine(
        localStore: EncryptedItemStore,
        remoteSource: FirestoreVaultSource,
        metaStore: VaultMetaStore,
        authSource: FirebaseAuthSource,
        conflictResolver: ConflictResolver,
        syncCoordinator: SyncCoordinator
    ): SyncEngine = SyncEngine(
        localStore = localStore,
        remoteSource = remoteSource,
        vaultMetaStore = metaStore,
        authSource = authSource,
        conflictResolver = conflictResolver,
        coordinator = syncCoordinator
    )

    /** Cablea `AutoLockController` (`docs/architecture.md` §5): un único
     * `CoroutineScope` de proceso, sin `Job` de una `Activity` que muera con ella, porque
     * el temporizador de inactividad debe seguir vivo mientras la app está en segundo
     * plano. */
    @Singleton
    @Provides
    fun provideAutoLockController(
        session: VaultSession,
        settings: SettingsDataStore,
        dispatchers: AppDispatchers
    ): AutoLockController = AutoLockController(
        session = session,
        settings = settings,
        scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    )

    @Singleton
    @Provides
    fun provideConflictResolver(
        localStore: EncryptedItemStore,
        itemDao: EncryptedItemDao
    ): ConflictResolver = ConflictResolver(
        localStore = localStore,
        itemDao = itemDao
    )
}
