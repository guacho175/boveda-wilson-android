package cl.bovedawilson.app.ui.cloud

import android.app.Activity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

interface GoogleIdTokenProvider {
    suspend fun request(activity: Activity): String
    suspend fun clearCredentialState(activity: Activity): Boolean
}

/** Adaptador de UI: obtiene el token y lo devuelve una sola vez, sin estado ni logging. */
class CredentialManagerGoogleIdTokenProvider @Inject constructor() : GoogleIdTokenProvider {
    override suspend fun request(activity: Activity): String {
        val clientId = activity.defaultWebClientId()
        val option = GetSignInWithGoogleOption.Builder(clientId).build()
        val response = try {
            CredentialManager.create(activity).getCredential(
                context = activity,
                request = GetCredentialRequest.Builder().addCredentialOption(option).build(),
            )
        } catch (error: NoCredentialException) {
            throw GoogleSignInUnavailableException(error)
        }
        val credential = response.credential as? CustomCredential
            ?: throw GoogleSignInUnavailableException()
        if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw GoogleSignInUnavailableException()
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
            .takeIf(String::isNotBlank) ?: throw GoogleSignInUnavailableException()
    }

    /** Limpia el estado del selector de credenciales después de cerrar Firebase Auth.
     * Un fallo aquí no debe impedir que la bóveda permanezca bloqueada y sin sesión. */
    override suspend fun clearCredentialState(activity: Activity): Boolean = try {
        CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest())
        true
    } catch (error: CancellationException) {
        throw error
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception) {
        false
    }

    private fun Activity.defaultWebClientId(): String {
        val resourceId = resources.getIdentifier("default_web_client_id", "string", packageName)
        if (resourceId == 0) throw GoogleSignInUnavailableException()
        return getString(resourceId).takeIf(String::isNotBlank) ?: throw GoogleSignInUnavailableException()
    }
}

private class GoogleSignInUnavailableException(cause: Throwable? = null) : IllegalStateException(null, cause)
