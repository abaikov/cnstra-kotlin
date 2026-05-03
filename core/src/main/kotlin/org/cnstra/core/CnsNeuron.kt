package org.cnstra.core

typealias DendriteResponse<TContext, TAxon> =
    suspend (DendriteContext<TContext>, Any?, TAxon) -> Any?

class CNSDendrite<TContext : Any?, TAxon : Any>(
    val collateral: CNSCollateral<*>,
    val response: DendriteResponse<TContext, TAxon>,
)

class CNSModalityHandler<TContext : Any?, TAxon : Any, TResult : Any?>(
    val modality: CNSModality,
    val afferentPaths: Map<CNSAfferentPath, suspend (DendriteContext<TContext>, Any?, TAxon) -> TResult> = emptyMap(),
    val default: (suspend (DendriteContext<TContext>, Any?, TAxon) -> TResult)? = null,
)

/**
 * Neuron node: **reference identity** matters for context keys and graph analysis (Tarjan SCC),
 * mirroring object identity semantics in the JS engine.
 */
class CNSNeuron<TContext : Any?, TAxon : Any>(
    val axon: TAxon,
) {
    val dendrites = mutableListOf<CNSDendrite<TContext, TAxon>>()
    @Deprecated("CNStra Kotlin orchestration is single-lane; offload heavy work explicitly inside dendrites.")
    var concurrency: Int? = null
        private set
    var maxDurationMs: Long? = null
        private set

    @Deprecated("CNStra Kotlin orchestration is single-lane; offload heavy work explicitly inside dendrites.")
    @Suppress("DEPRECATION")
    fun setConcurrency(n: Int?): CNSNeuron<TContext, TAxon> {
        concurrency = n
        return this
    }

    fun setMaxDurationMs(ms: Long?): CNSNeuron<TContext, TAxon> {
        maxDurationMs = ms
        return this
    }

    fun dendrite(
        collateral: CNSCollateral<*>,
        response: DendriteResponse<TContext, TAxon>,
    ): CNSNeuron<TContext, TAxon> {
        dendrites.add(CNSDendrite(collateral, response))
        return this
    }

    fun dendrite(
        collateral: List<CNSCollateral<*>>,
        response: DendriteResponse<TContext, TAxon>,
    ): CNSNeuron<TContext, TAxon> {
        for (c in collateral) {
            dendrites.add(CNSDendrite(c, response))
        }
        return this
    }

    fun <TResult : Any?> modalityDendrite(
        collateral: CNSCollateral<*>,
        modality: CNSModality,
        afferentPaths: Map<CNSAfferentPath, suspend (DendriteContext<TContext>, Any?, TAxon) -> TResult> = emptyMap(),
        default: (suspend (DendriteContext<TContext>, Any?, TAxon) -> TResult)? = null,
        output: suspend (DendriteContext<TContext>, TResult, TAxon) -> Any?,
    ): CNSNeuron<TContext, TAxon> =
        modalityDendrite(
            collateral = collateral,
            modalities =
                listOf(
                    CNSModalityHandler(
                        modality = modality,
                        afferentPaths = afferentPaths,
                        default = default,
                    ),
                ),
            default = default,
            output = output,
        )

    fun <TResult : Any?> modalityDendrite(
        collateral: CNSCollateral<*>,
        modalities: List<CNSModalityHandler<TContext, TAxon, TResult>>,
        default: (suspend (DendriteContext<TContext>, Any?, TAxon) -> TResult)? = null,
        output: suspend (DendriteContext<TContext>, TResult, TAxon) -> Any?,
    ): CNSNeuron<TContext, TAxon> {
        dendrites.add(
            CNSDendrite(collateral) { ctx, payload, axon ->
                val stimOptions = ctx.stimulation.options
                val matching = modalities.firstOrNull { it.modality === stimOptions.modality }

                val handler =
                    when {
                        matching == null -> {
                            default
                                ?: throw IllegalStateException(
                                    "modalityDendrite: No handler found for modality and no default handler provided",
                                )
                        }
                        else -> {
                            val pathHandler = stimOptions.afferentPath?.let { path ->
                                matching.afferentPaths.entries.firstOrNull { it.key === path }?.value
                            }
                            pathHandler
                                ?: matching.default
                                ?: default
                                ?: throw IllegalStateException(
                                    "modalityDendrite: No handler found for afferent path in modality and no default handler provided",
                                )
                        }
                    }

                output(ctx, handler(ctx, payload, axon), axon)
            },
        )
        return this
    }
}

class DendriteContext<TContext : Any?>(
    private val store: ICNSStimulationContextStore,
    private val neuronKey: Any,
    val cns: CNS<*>,
    val stimulation: CNSStimulation<*>,
    val superviseJob: kotlinx.coroutines.Job?,
) {
    @Suppress("UNCHECKED_CAST")
    fun get(): TContext? = store[neuronKey] as TContext?

    fun set(value: TContext?) {
        if (value == null) {
            store.delete(neuronKey)
        } else {
            store[neuronKey] = value
        }
    }

    fun delete() = store.delete(neuronKey)

    val isCancelled: Boolean get() = superviseJob?.let { !it.isActive } == true
}

fun <TContext : Any?, TAxon : Any> neuron(axon: TAxon): CNSNeuron<TContext, TAxon> =
    CNSNeuron(axon)

fun <TPayload : Any?> collateral(): CNSCollateral<TPayload> = CNSCollateral()

fun afferentPath(parent: CNSAfferentPath? = null): CNSAfferentPath = CNSAfferentPath(parent)

fun modality(afferentPaths: Map<Any, CNSAfferentPath>): CNSModality = CNSModality(afferentPaths)

class CNSContextFactory<TContext : Any?> {
    fun <TAxon : Any> neuron(axon: TAxon): CNSNeuron<TContext, TAxon> =
        CNSNeuron(axon)
}

fun <TContext : Any?> withCtx(): CNSContextFactory<TContext> = CNSContextFactory()
