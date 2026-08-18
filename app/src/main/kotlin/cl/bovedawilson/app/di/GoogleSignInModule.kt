package cl.bovedawilson.app.di

import cl.bovedawilson.app.ui.cloud.CredentialManagerGoogleIdTokenProvider
import cl.bovedawilson.app.ui.cloud.GoogleIdTokenProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface GoogleSignInModule {
    @Binds
    fun bindGoogleIdTokenProvider(
        implementation: CredentialManagerGoogleIdTokenProvider,
    ): GoogleIdTokenProvider
}
