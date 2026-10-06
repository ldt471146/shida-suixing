package cn.gxnu.campus.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class ManualReadiness { READY, NEED_PERMISSION, UNAVAILABLE }
internal enum class ManualIntentStage { NONE, PREPARING, WAITING_PERMISSION, FAILED }

/** Owns one manual click independently of the automatic connection setting. */
internal class ManualConnectionIntent(
    private val scope: CoroutineScope,
    private val refresh: suspend () -> ManualReadiness,
    private val onPreparing: () -> Unit,
    private val onPermissionRequired: (requestDialog: Boolean) -> Unit,
    private val onContinue: () -> Unit,
    private val onUnavailable: () -> Unit = {}
) {
    var pending: Boolean = false
        private set
    var stage: ManualIntentStage = ManualIntentStage.NONE
        private set
    private var refreshJob: Job? = null
    private var generation = 0L

    fun request() {
        if (stage == ManualIntentStage.PREPARING && refreshJob?.isActive == true) return
        pending = true
        prepare(requestDialog = true)
    }

    fun resumeAfterPermissionChange() {
        if (!pending || (stage == ManualIntentStage.PREPARING && refreshJob?.isActive == true)) return
        prepare(requestDialog = false)
    }

    private fun prepare(requestDialog: Boolean) {
        val ticket = ++generation
        refreshJob?.cancel()
        stage = ManualIntentStage.PREPARING
        onPreparing()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val readiness = refresh()
            if (!pending || ticket != generation) return@launch
            refreshJob = null
            when (readiness) {
                ManualReadiness.NEED_PERMISSION -> {
                    stage = ManualIntentStage.WAITING_PERMISSION
                    onPermissionRequired(requestDialog)
                }
                ManualReadiness.READY -> {
                    pending = false
                    stage = ManualIntentStage.NONE
                    onContinue()
                }
                ManualReadiness.UNAVAILABLE -> {
                    pending = false
                    stage = ManualIntentStage.FAILED
                    onUnavailable()
                }
            }
        }
        refreshJob = job
        job.start()
    }

    fun cancel() {
        generation++
        pending = false
        stage = ManualIntentStage.NONE
        refreshJob?.cancel()
        refreshJob = null
    }
}
