package org.cnstra.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex

class CNS<TNeuron : CNSNeuron<*, *>>(
    val neurons: List<TNeuron>,
    val options: CNSOptions? = null,
) {
    val network: CNSNetwork<TNeuron, CNSDendrite<*, *>> = CNSNetwork(neurons)

    @OptIn(ExperimentalCoroutinesApi::class)
    internal val orchestrationDispatcher: CoroutineDispatcher =
        options?.orchestrationDispatcher ?: Dispatchers.Default.limitedParallelism(1)

    internal val activationLane = Mutex()

    private val globalResponseListeners =
        java.util.concurrent.CopyOnWriteArrayList<suspend (CNSStimulationResponse) -> Unit>()

    fun addResponseListener(
        listener: suspend (CNSStimulationResponse) -> Unit,
    ): () -> Unit {
        globalResponseListeners.add(listener)
        return { globalResponseListeners.remove(listener) }
    }

    private fun wrapOnResponse(
        local: (suspend (CNSStimulationResponse) -> Unit)?,
    ): (suspend (CNSStimulationResponse) -> Unit)? {
        if (local == null && globalResponseListeners.isEmpty()) return null
        return { r ->
            coroutineScope {
                val calls =
                    buildList {
                        if (local != null) add(async { local.invoke(r) })
                        for (g in globalResponseListeners) {
                            add(async { g.invoke(r) })
                        }
                    }
                calls.awaitAll()
            }
        }
    }

    private fun mergeOptions(
        options: CNSStimulationOptions<CNSStimulationResponse>,
    ): CNSStimulationOptions<CNSStimulationResponse> {
        val wrapped = wrapOnResponse(options.onResponse)
        return options.copy(onResponse = wrapped)
    }

    fun stimulate(
        signal: CNSSignal<*>,
        options: CNSStimulationOptions<CNSStimulationResponse> = CNSStimulationOptions(),
    ): CNSStimulation<TNeuron> = stimulate(listOf(signal), options)

    fun stimulate(
        signals: List<CNSSignal<*>>,
        options: CNSStimulationOptions<CNSStimulationResponse> = CNSStimulationOptions(),
    ): CNSStimulation<TNeuron> {
        val stimulation = CNSStimulation(this, mergeOptions(options))
        stimulation.kickoffWithSignals(signals)
        return stimulation
    }

    fun activate(
        tasks: List<CNSNeuronActivationTask<TNeuron>>,
        options: CNSStimulationOptions<CNSStimulationResponse> = CNSStimulationOptions(),
    ): CNSStimulation<TNeuron> {
        val stimulation = CNSStimulation(this, mergeOptions(options))
        stimulation.kickoffWithTasks(tasks)
        return stimulation
    }
}
