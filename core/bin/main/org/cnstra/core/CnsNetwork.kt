package org.cnstra.core

data class CNSSubscriber<TNeuron : Any, TDendrite : CNSDendrite<*, *>>(
    val neuron: TNeuron,
    val dendrite: TDendrite,
)

/**
 * Routing indexes + SCC metadata (Tarjan + condensation DAG ancestors), matching `@cnstra/core`.
 */
class CNSNetwork<TNeuron : CNSNeuron<*, *>, TDendrite : CNSDendrite<*, *>>(
    private val neurons: List<TNeuron>,
) {
    val stronglyConnectedComponents = mutableListOf<MutableSet<TNeuron>>()

    private val neuronToScc = idMapNeuronToInt<TNeuron>()
    private val sccDag = mutableMapOf<Int, MutableSet<Int>>()
    private val sccAncestors = mutableMapOf<Int, MutableSet<Int>>()

    private val subIndex = idMapCollateralToMutableList<CNSSubscriber<TNeuron, TDendrite>>()
    private val dendriteIndex = idMapCollateralToDendrite<TDendrite>()
    private val parentNeuronByCollateral = idMapCollateralToNeuron<TNeuron>()

    init {
        buildIndexes()
        buildScc()
    }

    private fun buildNeuronGraph(): java.util.IdentityHashMap<TNeuron, MutableSet<TNeuron>> {
        val graph = idMapNeuronToNeuronSet<TNeuron>()
        for (neuron in neurons) {
            graph.getOrPutRef(neuron) { mutableSetOf() }
        }
        for (neuron in neurons) {
            val reachable = graph.getOrPutRef(neuron) { mutableSetOf() }
            for (collateral in axonCollaterals(neuron.axon)) {
                val subscribers = subIndex.get(collateral) ?: continue
                for (sub in subscribers) {
                    reachable.add(sub.neuron)
                }
            }
        }
        return graph
    }

    private fun buildScc() {
        val graph = buildNeuronGraph()
        val index = idMapNeuronToInt<TNeuron>()
        val lowlink = idMapNeuronToInt<TNeuron>()
        val onStack = idSetNeurons<TNeuron>()
        val stack = ArrayDeque<TNeuron>()
        val components = mutableListOf<MutableSet<TNeuron>>()
        var current = 0

        fun strongConnect(v: TNeuron) {
            index.put(v, current)
            lowlink.put(v, current)
            current++
            stack.addLast(v)
            onStack.addRef(v)
            val neighbors = graph[v] ?: mutableSetOf()
            for (w in neighbors) {
                if (!index.containsKey(w)) {
                    strongConnect(w)
                    lowlink.put(v, minOf(lowlink.get(v)!!, lowlink.get(w)!!))
                } else if (w in onStack) {
                    lowlink.put(v, minOf(lowlink.get(v)!!, index.get(w)!!))
                }
            }
            if (lowlink.get(v) == index.get(v)) {
                val comp = mutableSetOf<TNeuron>()
                while (true) {
                    val w = stack.removeLast()
                    onStack.removeRef(w)
                    comp.add(w)
                    if (w === v) break
                }
                components.add(comp)
            }
        }

        for (n in neurons) {
            if (!index.containsKey(n)) strongConnect(n)
        }
        stronglyConnectedComponents.clear()
        stronglyConnectedComponents.addAll(components)

        neuronToScc.clear()
        for ((i, comp) in stronglyConnectedComponents.withIndex()) {
            for (n in comp) {
                neuronToScc.put(n, i)
            }
        }
        buildSccDag(graph)
        buildSccAncestors()
    }

    private fun buildSccDag(neuronGraph: java.util.IdentityHashMap<TNeuron, MutableSet<TNeuron>>) {
        sccDag.clear()
        for (i in stronglyConnectedComponents.indices) {
            sccDag[i] = mutableSetOf()
        }
        for (i in stronglyConnectedComponents.indices) {
            val scc = stronglyConnectedComponents[i]
            for (n in scc) {
                val neighbors = neuronGraph.get(n) ?: continue
                for (neighbor in neighbors) {
                    val j = neuronToScc.get(neighbor) ?: continue
                    if (j != i) {
                        sccDag.getOrPut(j) { mutableSetOf() }.add(i)
                    }
                }
            }
        }
    }

    private fun buildSccAncestors() {
        sccAncestors.clear()
        for (i in stronglyConnectedComponents.indices) {
            sccAncestors[i] = mutableSetOf()
        }
        val inDegree = mutableMapOf<Int, Int>()
        val queue = ArrayDeque<Int>()
        for (i in stronglyConnectedComponents.indices) {
            val incoming = sccDag[i]?.size ?: 0
            inDegree[i] = incoming
            if (incoming == 0) queue.addLast(i)
        }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (neighbor in outgoingEdges(cur)) {
                val na = sccAncestors.getOrPut(neighbor) { mutableSetOf() }
                na.add(cur)
                sccAncestors[cur]?.let { ca -> na.addAll(ca) }
                val nd = (inDegree[neighbor] ?: 0) - 1
                inDegree[neighbor] = nd
                if (nd == 0) queue.addLast(neighbor)
            }
        }
    }

    private fun outgoingEdges(sccIndex: Int): Set<Int> {
        val out = mutableSetOf<Int>()
        for ((target, incoming) in sccDag) {
            if (incoming.contains(sccIndex)) out.add(target)
        }
        return out
    }

    fun getSccIndexByNeuron(neuron: TNeuron): Int? = neuronToScc.get(neuron)

    fun getSccSetByNeuron(neuron: TNeuron): Set<TNeuron>? =
        getSccIndexByNeuron(neuron)?.let { stronglyConnectedComponents[it] }

    fun canNeuronBeGuaranteedDone(neuron: TNeuron, activeSccCounts: Map<Int, Int>): Boolean {
        val sccIndex = neuronToScc.get(neuron) ?: return true
        if ((activeSccCounts[sccIndex] ?: 0) > 0) return false
        val ancestors = sccAncestors[sccIndex] ?: return true
        for (a in ancestors) {
            if ((activeSccCounts[a] ?: 0) > 0) return false
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildIndexes() {
        subIndex.clear()
        parentNeuronByCollateral.clear()
        dendriteIndex.clear()
        for (neuron in neurons) {
            for (d in neuron.dendrites) {
                val key = d.collateral
                subIndex.getOrPutList(key).add(CNSSubscriber(neuron, d as TDendrite))
                dendriteIndex.put(key, d as TDendrite)
            }
            for (collateral in axonCollaterals(neuron.axon)) {
                parentNeuronByCollateral.put(collateral, neuron)
            }
        }
    }

    fun getParentNeuronByCollateral(collateral: CNSCollateral<*>): TNeuron? =
        parentNeuronByCollateral.get(collateral)

    fun getSubscribers(collateral: CNSCollateral<*>): List<CNSSubscriber<TNeuron, TDendrite>> =
        subIndex[collateral].orEmpty()

    private companion object {
        private val collateralGetterCache =
            java.util.concurrent.ConcurrentHashMap<Class<*>, List<java.lang.reflect.Method>>()

        private fun axonCollaterals(axon: Any): List<CNSCollateral<*>> {
            if (axon is Map<*, *>) {
                return axon.values.mapNotNull { it as? CNSCollateral<*> }
            }
            val getters =
                collateralGetterCache.computeIfAbsent(axon.javaClass) { klass ->
                    klass.methods
                        .asSequence()
                        .filter { m ->
                            m.parameterCount == 0 &&
                                CNSCollateral::class.java.isAssignableFrom(m.returnType)
                        }
                        .toList()
                }
            return getters
                .asSequence()
                .mapNotNull { m ->
                    runCatching { m.invoke(axon) as? CNSCollateral<*> }.getOrNull()
                }
                .toList()
        }
    }
}

private typealias IdNeuronMap<T> = java.util.IdentityHashMap<T, Int>
private typealias IdNeuronToSet<T> = java.util.IdentityHashMap<T, MutableSet<T>>

@Suppress("NOTHING_TO_INLINE")
private inline fun <T : Any> idMapNeuronToInt(): IdNeuronMap<T> =
    java.util.IdentityHashMap()

@Suppress("NOTHING_TO_INLINE")
private inline fun <T : Any> idMapNeuronToNeuronSet(): IdNeuronToSet<T> =
    java.util.IdentityHashMap()

private fun <T : Any> IdNeuronToSet<T>.getOrPutRef(
    k: T,
    default: () -> MutableSet<T>,
): MutableSet<T> {
    val existing = get(k)
    if (existing != null) return existing
    val created = default()
    put(k, created)
    return created
}

private class IdNeuronSet<T : Any> {
    private val inner = java.util.IdentityHashMap<T, Boolean>()
    fun addRef(e: T) {
        inner[e] = true
    }

    fun removeRef(e: T) {
        inner.remove(e)
    }

    operator fun contains(e: T): Boolean = inner.containsKey(e)
}

@Suppress("NOTHING_TO_INLINE")
private inline fun <T : Any> idSetNeurons(): IdNeuronSet<T> = IdNeuronSet()

private typealias IdCollateralList<V> = java.util.IdentityHashMap<CNSCollateral<*>, MutableList<V>>

@Suppress("NOTHING_TO_INLINE")
private inline fun <V> idMapCollateralToMutableList(): IdCollateralList<V> =
    java.util.IdentityHashMap()

private fun <V> IdCollateralList<V>.getOrPutList(k: CNSCollateral<*>): MutableList<V> {
    val ex = get(k)
    if (ex != null) return ex
    val nl = mutableListOf<V>()
    put(k, nl)
    return nl
}

private typealias IdCollateralDendrite<D> = java.util.IdentityHashMap<CNSCollateral<*>, D>
@Suppress("NOTHING_TO_INLINE")
private inline fun <D> idMapCollateralToDendrite(): IdCollateralDendrite<D> =
    java.util.IdentityHashMap()

private typealias IdCollateralNeuron<N> = java.util.IdentityHashMap<CNSCollateral<*>, N>
@Suppress("NOTHING_TO_INLINE")
private inline fun <N> idMapCollateralToNeuron(): IdCollateralNeuron<N> =
    java.util.IdentityHashMap()
