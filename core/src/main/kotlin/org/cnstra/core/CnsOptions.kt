package org.cnstra.core

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Per-stimulation options aligned with [@cnstra/core](https://cnstra.org/docs/core/api).
 */
data class CNSOptions(
    val autoCleanupContexts: Boolean = false,
    /**
     * Runs CNS orchestration. Keep this single-lane for deterministic graph traversal;
     * offload heavy dendrite work explicitly with `withContext(Dispatchers.IO/Default)`.
     */
    val orchestrationDispatcher: CoroutineDispatcher? = null,
)

data class CNSStimulationOptions<TResponse : Any>(
    val maxNeuronHops: Int? = null,
    val onResponse: (suspend (TResponse) -> Unit)? = null,
    /** When this job is cancelled, active work respects cooperative cancellation and pending work stops scheduling. */
    val superviseWith: kotlinx.coroutines.Job? = null,
    val ctx: ICNSStimulationContextStore? = null,
    /** Kept for source compatibility; orchestration itself is single-lane by design. */
    @Deprecated("CNStra Kotlin runs orchestration on one logical lane. Offload heavy work explicitly inside dendrites.")
    val concurrency: Int? = null,
    val modality: CNSModality? = null,
    val afferentPath: CNSAfferentPath? = null,
    val stimulationContext: Any? = null,
)

data class CNSStimulationResponse(
    val inputSignal: CNSSignal<*>?,
    val outputSignal: CNSSignal<*>?,
    val modality: CNSModality?,
    val afferentPath: CNSAfferentPath?,
    val contextValue: Map<Any, Any?>,
    val queueLength: Int,
    val stimulation: CNSStimulation<*>,
    val error: Throwable?,
    val hops: Int?,
)

data class CNSNeuronActivationTask<TNeuron : Any>(
    val neuron: TNeuron,
    val dendriteCollateral: CNSCollateral<*>,
    val input: CNSSignal<*>?,
)

data class CNSNeuronActivationTaskFailure<TNeuron : Any>(
    val task: CNSNeuronActivationTask<TNeuron>,
    val error: Throwable,
    val aborted: Boolean,
)
