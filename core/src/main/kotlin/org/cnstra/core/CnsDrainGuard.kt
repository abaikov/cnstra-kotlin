package org.cnstra.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CNSDrainGuard<TNeuron : CNSNeuron<*, *>>(
    private val cns: CNS<TNeuron>,
    private val signals: List<CNSSignal<*>>,
    private val options: CNSStimulationOptions<CNSStimulationResponse> = CNSStimulationOptions(),
) {
    constructor(
        cns: CNS<TNeuron>,
        signal: CNSSignal<*>,
        options: CNSStimulationOptions<CNSStimulationResponse> = CNSStimulationOptions(),
    ) : this(cns, listOf(signal), options)

    private var currentStimulation: CNSStimulation<TNeuron>? = null
    private var currentDrain: CompletableDeferred<Unit>? = null
    private var ownedJob: Job? = null

    fun isDraining(): Boolean = currentDrain != null

    fun getCurrentStimulation(): CNSStimulation<TNeuron>? = currentStimulation

    suspend fun drain() {
        currentDrain?.await()
            ?: startDrain().await()
    }

    fun abort(reason: Throwable? = null): Boolean {
        val job = ownedJob ?: return false
        if (!job.isActive) return false
        job.cancel(reason?.let { kotlinx.coroutines.CancellationException(it.message, it) })
        return true
    }

    private fun startDrain(): CompletableDeferred<Unit> {
        val drain = CompletableDeferred<Unit>()
        val job = options.superviseWith ?: SupervisorJob()
        if (options.superviseWith == null) {
            ownedJob = job
        }
        val stimulation = cns.stimulate(signals, options.copy(superviseWith = job))

        currentStimulation = stimulation
        currentDrain = drain

        CoroutineScope(Dispatchers.Default).launch {
            try {
                stimulation.awaitComplete()
                drain.complete(Unit)
            } catch (t: Throwable) {
                drain.completeExceptionally(t)
            } finally {
                if (currentDrain === drain) {
                    currentDrain = null
                    currentStimulation = null
                    ownedJob = null
                }
            }
        }

        return drain
    }
}
