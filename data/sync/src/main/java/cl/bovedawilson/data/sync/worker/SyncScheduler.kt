package cl.bovedawilson.data.sync.worker

import android.content.Context
import cl.bovedawilson.data.local.store.VaultMetaStore
import cl.bovedawilson.data.remote.auth.FirebaseAuthSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

interface SyncScheduler {
    suspend fun scheduleIfAuthorized()
    suspend fun syncNowIfAuthorized()
}

internal object NoOpSyncScheduler : SyncScheduler {
    override suspend fun scheduleIfAuthorized() = Unit
    override suspend fun syncNowIfAuthorized() = Unit
}

class AndroidSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val metaStore: VaultMetaStore,
    private val auth: FirebaseAuthSource
) : SyncScheduler {
    override suspend fun scheduleIfAuthorized() {
        if (isAuthorized()) SyncWorker.schedule(context)
    }

    override suspend fun syncNowIfAuthorized() {
        if (isAuthorized()) SyncWorker.syncNow(context)
    }

    private suspend fun isAuthorized(): Boolean {
        val meta = metaStore.getMeta() ?: return false
        return meta.ownerUid.isNotBlank() && meta.ownerUid == auth.currentUserId
    }
}
