package org.cnstra.core

data class CNSNeuronPersistOptions<TNeuron : CNSNeuron<*, *>>(
    val name: String,
    val neuron: TNeuron,
)

data class CNSCollateralPersistOptions<TCollateral : CNSCollateral<*>>(
    val name: String,
    val collateral: TCollateral,
)

data class CNSStimulationPersistOptions(
    val stimulationId: String,
)

class CNSPersistOptionsRegistry {
    private val neurons = linkedMapOf<String, CNSNeuron<*, *>>()
    private val neuronNames = java.util.IdentityHashMap<CNSNeuron<*, *>, String>()
    private val collaterals = linkedMapOf<String, CNSCollateral<*>>()
    private val stimulations = linkedMapOf<String, CNSStimulation<*>>()

    fun <TNeuron : CNSNeuron<*, *>> addNeuron(options: CNSNeuronPersistOptions<TNeuron>) {
        neurons[options.name] = options.neuron
        neuronNames[options.neuron] = options.name
    }

    fun addNeuron(neuron: CNSNeuron<*, *>, name: String) {
        addNeuron(CNSNeuronPersistOptions(name, neuron))
    }

    fun getNeuron(name: String): CNSNeuron<*, *>? = neurons[name]

    fun getNeuronName(neuron: CNSNeuron<*, *>): String? = neuronNames[neuron]

    fun removeNeuron(name: String) {
        neurons.remove(name)?.let { neuronNames.remove(it) }
    }

    fun <TCollateral : CNSCollateral<*>> addCollateral(
        options: CNSCollateralPersistOptions<TCollateral>,
    ) {
        collaterals[options.name] = options.collateral
    }

    fun addCollateral(collateral: CNSCollateral<*>, name: String) {
        addCollateral(CNSCollateralPersistOptions(name, collateral))
    }

    fun getCollateral(name: String): CNSCollateral<*>? = collaterals[name]

    fun removeCollateral(name: String) {
        collaterals.remove(name)
    }

    fun addStimulation(
        stimulation: CNSStimulation<*>,
        options: CNSStimulationPersistOptions,
    ) {
        stimulations[options.stimulationId] = stimulation
    }

    fun getStimulation(stimulationId: String): CNSStimulation<*>? = stimulations[stimulationId]

    fun removeStimulation(stimulationId: String) {
        stimulations.remove(stimulationId)
    }
}
