package com.chaiandtapri.counter

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Serializes sync and logout so an in-flight request cannot race local credential revocation. */
internal object SyncOperationLock {
    private val lock = ReentrantLock(true)
    fun <T> withLock(action: () -> T): T = lock.withLock(action)
}

/** Durable network-constrained jobs. WorkManager input/output never contains credentials or business rows. */
object SyncBackgroundScheduler {
    const val ONE_TIME_NAME = "chai_tapri_sync_one_time_v1"
    const val PERIODIC_NAME = "chai_tapri_sync_periodic_v1"
    private const val CONTINUATION_INPUT = "continue_pending_batch"

    private fun connectedConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueueOneTime(context: Context): Operation =
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ONE_TIME_NAME,
            ExistingWorkPolicy.KEEP,
            oneTimeRequest()
        )

    fun enablePeriodic(context: Context): Operation =
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest()
        )

    fun pause(context: Context): List<Operation> {
        val manager = WorkManager.getInstance(context.applicationContext)
        return listOf(manager.cancelUniqueWork(ONE_TIME_NAME), manager.cancelUniqueWork(PERIODIC_NAME))
    }

    internal fun enqueueContinuation(context: Context): Operation {
        val request = oneTimeRequest(Data.Builder().putBoolean(CONTINUATION_INPUT, true).build())
        return WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ONE_TIME_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    private fun oneTimeRequest(input: Data = Data.EMPTY): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SyncOutboxWorker>()
            .setInputData(input)
            .setConstraints(connectedConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(ONE_TIME_NAME)
            .build()

    private fun periodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<SyncOutboxWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
            .setConstraints(connectedConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(PERIODIC_NAME)
            .build()
}

/** Non-sensitive status only. No tokens, endpoints, event payloads or customer data are stored here. */
internal object SyncBackgroundStatus {
    private const val PREFS = "chai_tapri_sync_work_status_v1"
    private const val STATE = "state"
    private const val ACKNOWLEDGED = "acknowledged"
    private const val REVISION = "server_revision"
    private const val UPDATED_AT = "updated_at"

    fun record(context: Context, state: String, acknowledged: Int = 0, revision: Long = 0L) {
        require(state in setOf("synced", "idle", "conflict", "session_required", "session_unavailable", "server_rejected", "retrying", "attention_required", "actor_binding_required", "actor_mismatch", "owner_identity_required", "operator_session_required", "actor_boundary"))
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(STATE, state)
            .putInt(ACKNOWLEDGED, acknowledged.coerceAtLeast(0))
            .putLong(REVISION, revision.coerceAtLeast(0L))
            .putLong(UPDATED_AT, System.currentTimeMillis())
            .commit()
    }

    fun read(context: Context): String? {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = preferences.getString(STATE, null) ?: return null
        val acknowledged = preferences.getInt(ACKNOWLEDGED, 0)
        return when (state) {
            "synced" -> "Last background sync acknowledged $acknowledged event(s)."
            "idle" -> "Last background sync found no sendable events."
            "conflict" -> "Background sync paused at a server revision conflict. Open Cloud Sync to review."
            "session_required" -> "Background sync needs an owner session. Sign in to resume."
            "session_unavailable" -> "Saved sync session is unavailable. Encrypted session data was preserved."
            "actor_binding_required" -> "Background sync paused because the session needs a trusted local-operator binding. Sign in again as the owner."
            "actor_mismatch" -> "Background sync paused. The next queued action belongs to another or unlinked local operator; it was not uploaded under this account."
            "owner_identity_required" -> "Background sync paused because this saved cloud account is not verified as the local shop owner. Sign in with the owner account."
            "operator_session_required" -> "Background sync paused: the next queued operator has no linked cloud session. Link that local staff account, then retry."
            "actor_boundary" -> "Background sync preserved operator order and will continue with the next linked account."
            "server_rejected" -> "Server rejected the sync session or request. Review account access."
            "retrying" -> "Background sync will retry when its network and backoff conditions allow."
            else -> "Background sync needs attention. Local records remain on this device."
        }
    }

    fun enabled(context: Context): Boolean = WorkManager.getInstance(context.applicationContext)
        .getWorkInfosForUniqueWork(PERIODIC_STATUS_NAME).get().any { !it.state.isFinished }

    private const val PERIODIC_STATUS_NAME = SyncBackgroundScheduler.PERIODIC_NAME
}

class SyncOutboxWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val businessOperationPermit = BusinessWriteBarrier.tryBeginBusinessOperation()
        if (businessOperationPermit == null) {
            SyncBackgroundStatus.record(applicationContext, "retrying")
            return Result.retry()
        }
        return withContext(Dispatchers.IO + BusinessWriteBarrier.operationContext(businessOperationPermit)) {
            try { doWorkWithBusinessPermit() }
            finally { businessOperationPermit.close() }
        }
    }

    private data class WorkerBatch(val run: SyncRunResult?, val more: Boolean = false, val actorBoundary: Boolean = false)

    private suspend fun doWorkWithBusinessPermit(): Result {
        return try {
            val batch = withContext(Dispatchers.IO) {
                SyncOperationLock.withLock {
                    if (isStopped || BusinessWriteBarrier.currentMaintenance() != null) throw BusinessMaintenanceException()
                    SqliteCounterPersistence(applicationContext).use { persistence ->
                        val head = persistence.loadPendingSyncEvents(1).firstOrNull()
                            ?: return@withLock WorkerBatch(run = null)
                        val actorId = head.actorId ?: throw SyncActorMismatchException("Outbox head has no trusted local operator attribution.")
                        val vault = if (actorId == SyncOwnerPolicy.LOCAL_OWNER_ACTOR) SyncSessionVault(applicationContext)
                            else SyncSessionVault(applicationContext, actorId)
                        val session = try { vault.load() } catch (error: Exception) {
                            throw SyncSessionUnavailableException("The next operator's encrypted session is unavailable.", error)
                        } ?: throw SyncActorSessionRequiredException()
                        if (session.localActorId != actorId) throw SyncActorMismatchException("Stored session belongs to a different local operator.")
                        val authorized = if (actorId == SyncOwnerPolicy.LOCAL_OWNER_ACTOR) SyncOwnerPolicy.mayUpload(session)
                            else SyncStaffPolicy.mayUpload(session, actorId)
                        if (!authorized && actorId == SyncOwnerPolicy.LOCAL_OWNER_ACTOR) throw SyncOwnerIdentityRequiredException()
                        if (!authorized) throw SyncActorMismatchException("Stored cloud role is not authorized for this local operator.")
                        val journal = ActorScopedSyncJournal(PersistenceSyncJournal(persistence), actorId)
                        val run = SyncRunner(journal, HttpsSyncHttpTransport(connectTimeoutMs = 5_000, readTimeoutMs = 7_000))
                            .push(session.endpointBase, session.identity, session.bearerToken, maxEvents = MAX_EVENTS_PER_RUN)
                        val more = journal.pending(1).isNotEmpty()
                        WorkerBatch(run, more, journal.blockedByActor)
                    }
                }
            }
            val run = batch.run
            if (run == null) {
                SyncBackgroundStatus.record(applicationContext, "idle")
                return Result.success(output("idle"))
            }
            when {
                run.conflictEventId != null -> {
                    SyncBackgroundStatus.record(applicationContext, "conflict", run.acknowledged, run.serverRevision)
                    Result.success(output("conflict", run.acknowledged, run.serverRevision))
                }
                run.blockedByActor || batch.actorBoundary -> {
                    if (run.acknowledged > 0 && !isStopped) SyncBackgroundScheduler.enqueueContinuation(applicationContext)
                    SyncBackgroundStatus.record(applicationContext, "actor_boundary", run.acknowledged, run.serverRevision)
                    Result.success(output("actor_boundary", run.acknowledged, run.serverRevision))
                }
                run.acknowledged == 0 -> {
                    SyncBackgroundStatus.record(applicationContext, "idle", 0, run.serverRevision)
                    Result.success(output("idle", 0, run.serverRevision))
                }
                else -> {
                    SyncBackgroundStatus.record(applicationContext, "synced", run.acknowledged, run.serverRevision)
                    if (batch.more && !isStopped) SyncBackgroundScheduler.enqueueContinuation(applicationContext)
                    Result.success(output("synced", run.acknowledged, run.serverRevision))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: BusinessMaintenanceException) {
            SyncBackgroundStatus.record(applicationContext, "retrying")
            Result.retry()
        } catch (_: IOException) {
            SyncBackgroundStatus.record(applicationContext, "retrying")
            Result.retry()
        } catch (error: SyncRequestException) {
            if (error.statusCode in setOf(408, 425, 429) || error.statusCode >= 500) {
                SyncBackgroundStatus.record(applicationContext, "retrying")
                Result.retry()
            } else {
                SyncBackgroundStatus.record(applicationContext, "server_rejected")
                Result.failure(output("server_rejected"))
            }
        } catch (_: SyncSessionUnavailableException) {
            SyncBackgroundStatus.record(applicationContext, "session_unavailable")
            Result.failure(output("session_unavailable"))
        } catch (_: SyncActorSessionRequiredException) {
            SyncBackgroundStatus.record(applicationContext, "operator_session_required")
            Result.failure(output("operator_session_required"))
        } catch (_: SyncActorMismatchException) {
            SyncBackgroundStatus.record(applicationContext, "actor_mismatch")
            Result.failure(output("actor_mismatch"))
        } catch (_: SyncOwnerIdentityRequiredException) {
            SyncBackgroundStatus.record(applicationContext, "owner_identity_required")
            Result.failure(output("owner_identity_required"))
        } catch (_: Exception) {
            SyncBackgroundStatus.record(applicationContext, "attention_required")
            Result.failure(output("attention_required"))
        }
    }

    private fun output(state: String, acknowledged: Int = 0, revision: Long = 0L) =
        Data.Builder().putString(OUTPUT_STATE, state).putInt(OUTPUT_ACKNOWLEDGED, acknowledged).putLong(OUTPUT_REVISION, revision).build()

    companion object {
        const val OUTPUT_STATE = "sync_state"
        const val OUTPUT_ACKNOWLEDGED = "acknowledged_count"
        const val OUTPUT_REVISION = "server_revision"
        // Bound cancellation/logout handoff time while keeping the worker comfortably below WorkManager's execution limit.
        private const val MAX_EVENTS_PER_RUN = 5
    }
}

private class SyncActorSessionRequiredException : IllegalStateException("No cloud session is linked to the next local operator.")
private class SyncActorMismatchException(message: String) : IllegalStateException(message)
private class SyncOwnerIdentityRequiredException : IllegalStateException("Saved cloud identity is not a verified owner account.")
