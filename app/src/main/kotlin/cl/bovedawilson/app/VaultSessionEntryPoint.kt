package cl.bovedawilson.app

import cl.bovedawilson.data.sync.session.VaultSession
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Acceso interno al estado volátil para comprobar que un proceso nuevo nace bloqueado. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface VaultSessionEntryPoint {
    fun vaultSession(): VaultSession
}
