package cl.bovedawilson.data.local.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import cl.bovedawilson.data.local.dao.BiometricUnlockDao
import cl.bovedawilson.data.local.dao.EncryptedItemDao
import cl.bovedawilson.data.local.dao.PendingConflictDao
import cl.bovedawilson.data.local.dao.SyncStateDao
import cl.bovedawilson.data.local.dao.VaultMetaDao
import cl.bovedawilson.data.local.db.VaultDatabase
import cl.bovedawilson.data.local.db.VaultDatabaseFactory
import cl.bovedawilson.data.local.prefs.SettingsDataStore
import cl.bovedawilson.data.local.store.EncryptedItemStore
import cl.bovedawilson.data.local.store.LocalVaultDataWiper
import cl.bovedawilson.data.local.store.RoomEncryptedItemStore
import cl.bovedawilson.data.local.store.RoomLocalVaultDataWiper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private const val SETTINGS_DATASTORE_NAME = "boveda_wilson_settings"

/** Solo preferencias no sensibles (temporizador de bloqueo, si la biometría está
 * activada): nunca contraseñas, frases ni material de clave (`SECURITY.md` §1). */
private val Context.settingsPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SETTINGS_DATASTORE_NAME
)

/**
 * Proveedores de la capa local. Todo lo que sale de aquí maneja exclusivamente ciphertext
 * y metadatos no sensibles: ninguna de estas piezas conoce los modelos de dominio
 * descifrados (`docs/architecture.md` §3).
 */
@Module
@InstallIn(SingletonComponent::class)
object LocalModule {

    @Singleton
    @Provides
    fun provideVaultDatabase(@ApplicationContext context: Context): VaultDatabase =
        VaultDatabaseFactory.create(context)

    @Provides
    fun provideVaultMetaDao(db: VaultDatabase): VaultMetaDao = db.vaultMetaDao()

    @Provides
    fun provideEncryptedItemDao(db: VaultDatabase): EncryptedItemDao = db.encryptedItemDao()

    @Provides
    fun providePendingConflictDao(db: VaultDatabase): PendingConflictDao = db.pendingConflictDao()

    @Provides
    fun provideSyncStateDao(db: VaultDatabase): SyncStateDao = db.syncStateDao()

    @Provides
    fun provideBiometricUnlockDao(db: VaultDatabase): BiometricUnlockDao = db.biometricUnlockDao()

    @Singleton
    @Provides
    fun provideEncryptedItemStore(
        database: VaultDatabase,
        itemDao: EncryptedItemDao,
        syncStateDao: SyncStateDao,
        conflictDao: PendingConflictDao
    ): EncryptedItemStore = RoomEncryptedItemStore(database, itemDao, syncStateDao, conflictDao)

    @Singleton
    @Provides
    fun provideLocalVaultDataWiper(database: VaultDatabase): LocalVaultDataWiper =
        RoomLocalVaultDataWiper(database)

    @Singleton
    @Provides
    fun provideSettingsPreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsPreferencesDataStore

    @Singleton
    @Provides
    fun provideSettingsDataStore(dataStore: DataStore<Preferences>): SettingsDataStore = SettingsDataStore(dataStore)
}
