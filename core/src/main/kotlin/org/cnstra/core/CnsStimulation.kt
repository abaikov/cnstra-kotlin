package org.cnstra.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class CNSStimulation<TNeuron : CNSNeuron<*, *>>(
    val cns: CNS<TNeuron>,
    val options: CNSStimulationOptions<CNSStimulationResponse> = CNSStimulationOptions(),
) {
    private val ctx: ICNSStimulationContextStore = options.ctx ?: CNSStimulationContextStore()

    private val completed = CompletableDeferred<Unit>()
    private val failedTasks = mutableListOf<CNSNeuronActivationTaskFailure<TNeuron>>()
    private val pendingTasks = mutableSetOf<CNSNeuronActivationTask<TNeuron>>()

    private val queue = ArrayDeque<CNSNeuronActivationTask<TNeuron>>()

    private var scheduledCount = 0
    private var draining = false
    private var isCompleted = false
    private var onResponseError: Throwable? = null

    private val visitMap: java.util.IdentityHashMap<TNeuron, Int>? =
        options.maxNeuronHops?.let { java.util.IdentityHashMap() }

    private val activeSccCounts = mutableMapOf<Int, Int>()

    private val supervisorJob: Job = SupervisorJob(options.superviseWith)
    private val scope = CoroutineScope(supervisorJob + cns.orchestrationDispatcher)

    fun getContext(): ICNSStimulationContextStore = ctx

    fun getFailedTasks(): List<CNSNeuronActivationTaskFailure<TNeuron>> =
        failedTasks.toList()

    suspend fun getAllActivationTasks(): List<CNSNeuronActivationTask<TNeuron>> {
        return withContext(cns.orchestrationDispatcher) {
            queue.toList() + pendingTasks.toList()
        }
    }

    suspend fun awaitComplete() {
        completed.await()
    }

    suspend fun waitUntilComplete() {
        awaitComplete()
    }

    internal fun kickoffWithSignals(signals: List<CNSSignal<*>>) {
        scope.launch {
            try {
                when {
                    signals.isEmpty() -> tryResolveCompleted()
                    signals.size == 1 -> responseToSignal(signals.single())
                    else -> {
                        processResponseOrResponses(null, null, signals, null)
                        drainQueue()
                        tryResolveCompleted()
                    }
                }
            } catch (t: Throwable) {
                if (!isCompleted) {
                    isCompleted = true
                    completed.completeExceptionally(t)
                }
            }
        }
    }

    internal fun kickoffWithTasks(tasks: List<CNSNeuronActivationTask<TNeuron>>) {
        scope.launch {
            try {
                enqueueTasks(tasks)
            } catch (t: Throwable) {
                if (!isCompleted) {
                    isCompleted = true
                    completed.completeExceptionally(t)
                }
            }
        }
    }

    suspend fun enqueueTasks(tasks: List<CNSNeuronActivationTask<TNeuron>>) {
        for (task in tasks) {
            queue.addLast(task)
        }
        drainQueue()
        tryResolveCompleted()
    }

    private suspend fun responseToSignal(signal: CNSSignal<*>) {
        processResponse(null, null, signal, null)
        drainQueue()
        tryResolveCompleted()
    }

    private suspend fun drainQueue() {
        if (draining) return
        draining = true
        while (true) {
            val task = queue.removeFirstOrNull() ?: break
            executeActivationTask(task)
        }
        draining = false
    }

    private suspend fun tryResolveCompleted() {
        if (isCompleted) return
        if (completed.isCompleted) return
        val empty = queue.isEmpty() && scheduledCount == 0 && pendingTasks.isEmpty() && !draining
        if (!empty) return

        isCompleted = true
        if (failedTasks.isNotEmpty() || onResponseError != null) {
            val err =
                onResponseError
                    ?: Exception("Stimulation completed with ${failedTasks.size} failed task(s)")
            completed.completeExceptionally(err)
        } else {
            completed.complete(Unit)
        }
    }

    private fun autoCleanupContextsEnabled(): Boolean = cns.options?.autoCleanupContexts == true

    private fun markNeuronActive(neuron: TNeuron) {
        if (!autoCleanupContextsEnabled()) return
        val idx = cns.network.getSccIndexByNeuron(neuron) ?: return
        activeSccCounts[idx] = (activeSccCounts[idx] ?: 0) + 1
    }

    private fun markNeuronInactive(neuron: TNeuron) {
        if (!autoCleanupContextsEnabled()) return
        val idx = cns.network.getSccIndexByNeuron(neuron) ?: return
        val next = (activeSccCounts[idx] ?: 0) - 1
        if (next <= 0) activeSccCounts.remove(idx) else activeSccCounts[idx] = next
    }

    private fun canNeuronBeGuaranteedDone(neuron: TNeuron): Boolean {
        if (!autoCleanupContextsEnabled()) return false
        return cns.network.canNeuronBeGuaranteedDone(neuron, activeSccCounts)
    }

    private suspend fun cleanupCtxIfNeeded(neuron: TNeuron) {
        if (autoCleanupContextsEnabled() && canNeuronBeGuaranteedDone(neuron)) {
            ctx.delete(neuron)
        }
    }

    private fun createSubscriberTask(
        subscriber: CNSSubscriber<TNeuron, CNSDendrite<*, *>>,
        inputSignal: CNSSignal<*>?,
    ): CNSNeuronActivationTask<TNeuron> {
        val hops = options.maxNeuronHops
        if (hops != null) {
            val map = visitMap!!
            val cur = map[subscriber.neuron] ?: 0
            if (cur >= hops) {
                error("Max neuron hops reached when trying to enqueue subscriber")
            }
            map[subscriber.neuron] = cur + 1
        }
        return CNSNeuronActivationTask(
            neuron = subscriber.neuron,
            dendriteCollateral = subscriber.dendrite.collateral,
            input = inputSignal,
        )
    }

    private suspend fun executeActivationTask(task: CNSNeuronActivationTask<TNeuron>) {
        val subscribers = cns.network.getSubscribers(task.dendriteCollateral)
        val subscriber =
            subscribers.find { it.neuron === task.neuron } ?: run {
                failedTasks.add(
                    CNSNeuronActivationTaskFailure(
                        task,
                        IllegalStateException("Subscriber not found for activation task"),
                        aborted = false,
                    ),
                )
                return
            }

        val neuron = subscriber.neuron
        @Suppress("UNCHECKED_CAST")
        val dendrite = subscriber.dendrite as CNSDendrite<Any?, Any>

        cns.activationLane.withLock {
            markNeuronActive(neuron)
            try {
                val dCtx =
                    DendriteContext<Any?>(
                        store = ctx,
                        neuronKey = neuron,
                        cns = cns,
                        stimulation = this,
                        superviseJob = options.superviseWith,
                    )
                val raw =
                    try {
                        val maxMs = neuron.maxDurationMs
                        val run: suspend () -> Any? = {
                            dendrite.response(
                                dCtx,
                                task.input?.payload,
                                neuron.axon,
                            )
                        }
                        if (maxMs != null && maxMs > 0) withTimeout(maxMs) { run() } else run()
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        throw Exception("Neuron exceeded maxDuration ${neuron.maxDurationMs}ms", e)
                    }

                markNeuronInactive(neuron)
                val normalized = normalizeSignals(raw)
                when {
                    normalized == null || normalized.isEmpty() -> {
                        processResponse(neuron, task.input, null, null)
                    }
                    else -> {
                        for (s in normalized) {
                            processResponse(neuron, task.input, s, null)
                        }
                    }
                }
            } catch (e: Throwable) {
                markNeuronInactive(neuron)
                val aborted = options.superviseWith?.let { !it.isActive } == true
                failedTasks.add(CNSNeuronActivationTaskFailure(task, e, aborted))
                processResponse(neuron, task.input, null, e)
            }
        }
    }

    private fun normalizeSignals(raw: Any?): List<CNSSignal<*>>? {
        if (raw == null || raw is Unit) return null
        if (raw is CNSSignal<*>) return listOf(raw)
        if (raw is List<*>) {
            if (raw.isEmpty()) return emptyList()
            @Suppress("UNCHECKED_CAST")
            return raw as List<CNSSignal<*>>
        }
        error("Invalid dendrite response type: ${raw::class.java.name}")
    }

    private suspend fun processResponseOrResponses(
        emitter: TNeuron?,
        inputSignal: CNSSignal<*>?,
        outputSignalOrSignals: List<CNSSignal<*>>?,
        error: Throwable?,
    ) {
        val list = outputSignalOrSignals ?: emptyList()
        if (list.isEmpty()) {
            processResponse(emitter, inputSignal, null, error)
            return
        }
        for (s in list) {
            processResponse(emitter, inputSignal, s, error)
        }
    }

    private suspend fun processResponseOrResponses(
        emitter: TNeuron?,
        inputSignal: CNSSignal<*>?,
        outputSignalOrSignals: CNSSignal<*>?,
        error: Throwable?,
    ) {
        val single = outputSignalOrSignals
        if (single == null) {
            processResponse(emitter, inputSignal, null, error)
        } else {
            processResponse(emitter, inputSignal, single, error)
        }
    }

    private suspend fun processResponse(
        emitter: TNeuron?,
        inputSignal: CNSSignal<*>?,
        outputSignal: CNSSignal<*>?,
        error: Throwable?,
    ) {
        val collateral = outputSignal?.collateral
        val subscribers =
            if (collateral != null && error == null) {
                cns.network.getSubscribers(collateral)
            } else {
                emptyList()
            }

        val subscriberTasks = ArrayList<CNSNeuronActivationTask<TNeuron>>()

        if (collateral != null && error == null) {
            if (emitter != null) {
                cleanupCtxIfNeeded(emitter)
            }
            for (sub in subscribers) {
                val t = createSubscriberTask(sub, outputSignal)
                subscriberTasks.add(t)
                pendingTasks.add(t)
            }
        }

        scheduledCount += subscriberTasks.size

        val queueLenAt = queue.size + scheduledCount

        var responseError: Throwable? = null
        try {
            options.onResponse?.invoke(
                CNSStimulationResponse(
                    inputSignal = inputSignal,
                    outputSignal = outputSignal,
                    modality = options.modality,
                    afferentPath = options.afferentPath,
                    contextValue = ctx.getAll(),
                    queueLength = queueLenAt,
                    stimulation = this,
                    error = error,
                    hops =
                        if (options.maxNeuronHops != null && emitter != null) {
                            visitMap?.get(emitter)
                        } else {
                            null
                        },
                ),
            )
        } catch (e: Throwable) {
            responseError = e
        }

        if (responseError != null) {
            onResponseError = onResponseError ?: responseError
        }

        for (t in subscriberTasks) {
            scheduledCount--
            pendingTasks.remove(t)
            queue.addLast(t)
        }
        tryResolveCompleted()
    }
}
