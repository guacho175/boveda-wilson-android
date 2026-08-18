package cl.bovedawilson.data.remote.di

import android.content.Context
import cl.bovedawilson.data.remote.auth.FirebaseAuthSource
import cl.bovedawilson.data.remote.auth.FirebaseAuthSourceImpl
import cl.bovedawilson.data.remote.auth.OfflineFirebaseAuthSource
import cl.bovedawilson.data.remote.firestore.FirestoreVaultSource
import cl.bovedawilson.data.remote.firestore.FirestoreVaultSourceImpl
import cl.bovedawilson.data.remote.firestore.OfflineFirestoreVaultSource
import com.google.firebase.FirebaseApp
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Proveedores de la capa remota. Solo manejan ciphertext y metadatos mínimos; el `uid` se
 * resuelve dentro de la implementación desde `FirebaseAuth`, nunca como parámetro
 * (ADR-009).
 */
@Module
@InstallIn(SingletonComponent::class)
object RemoteModule {

    @Singleton
    @Provides
    fun provideFirebaseAuthSource(@ApplicationContext context: Context): FirebaseAuthSource =
        if (FirebaseApp.getApps(context).isEmpty()) OfflineFirebaseAuthSource() else FirebaseAuthSourceImpl()

    /**
     * Sin `google-services.json` no hay `FirebaseApp` por defecto y construir el cliente
     * real lanzaría al inyectar. Se comprueba antes de instanciar y se entrega la variante
     * sin red, que falla de forma explícita si alguien intenta sincronizar (B-01).
     */
    @Singleton
    @Provides
    fun provideFirestoreVaultSource(@ApplicationContext context: Context): FirestoreVaultSource =
        if (FirebaseApp.getApps(context).isEmpty()) {
            OfflineFirestoreVaultSource()
        } else {
            FirestoreVaultSourceImpl()
        }
}
