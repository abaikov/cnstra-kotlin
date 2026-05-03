package org.cnstra.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CnstraCoreBehaviorTest {
    @Test
    fun chainFanOutAndArraySignals() = runBlocking {
        data class FirstAxon(
            val a: CNSCollateral<String>,
            val b: CNSCollateral<String>,
        )
        data class TerminalAxon(
            val done: CNSCollateral<String>,
        )

        val start = collateral<String>()
        val a = collateral<String>()
        val b = collateral<String>()
        val done = collateral<String>()

        val first =
            neuron<Unit, FirstAxon>(FirstAxon(a, b))
                .dendrite(start) { _, payload, ax ->
                    listOf(
                        ax.a.createSignal("a:${payload as String}"),
                        ax.b.createSignal("b:$payload"),
                    )
                }
        val terminal =
            neuron<Unit, TerminalAxon>(TerminalAxon(done))
                .dendrite(listOf(a, b)) { _, payload, ax ->
                    ax.done.createSignal(payload as String)
                }

        val outputs = mutableListOf<String>()
        CNS(listOf(first, terminal))
            .stimulate(
                start.createSignal("root"),
                CNSStimulationOptions(
                    onResponse = { r ->
                        if (r.outputSignal?.collateral === done) {
                            outputs.add(r.outputSignal.payload as String)
                        }
                    },
                ),
            ).awaitComplete()

        assertEquals(setOf("a:root", "b:root"), outputs.toSet())
    }

    @Test
    fun publicApiAliasesAndWithCtxAreUsable() = runBlocking {
        data class Ctx(val seen: Int)
        data class Axon(val out: CNSCollateral<Int>)

        val input = collateral<Int>()
        val out = collateral<Int>()
        val n =
            withCtx<Ctx>()
                .neuron(Axon(out))
                .dendrite(input) { ctx, payload, ax ->
                    val next = ((ctx.get()?.seen ?: 0) + (payload as Int))
                    ctx.set(Ctx(next))
                    ax.out.createSignal(next)
                }

        val cns: CNS<CNSNeuron<Ctx, Axon>> = CNS(listOf(n))
        var observed = 0
        cns.stimulate(
            input.createSignal(7),
            CNSStimulationOptions(
                onResponse = { r ->
                    if (r.outputSignal?.collateral === out) {
                        observed = r.outputSignal.payload as Int
                    }
                },
            ),
        ).waitUntilComplete()

        assertEquals(7, observed)
    }

    @Test
    fun contextIsScopedToNeuronDuringStimulation() = runBlocking {
        data class Axon(val again: CNSCollateral<Int>, val done: CNSCollateral<Int>)

        val start = collateral<Int>()
        val again = collateral<Int>()
        val done = collateral<Int>()

        val counter =
            neuron<Int, Axon>(Axon(again, done))
                .dendrite(listOf(start, again)) { ctx, payload, ax ->
                    val next = (ctx.get() ?: 0) + 1
                    ctx.set(next)
                    if ((payload as Int) < 2) {
                        ax.again.createSignal(payload + 1)
                    } else {
                        ax.done.createSignal(next)
                    }
                }

        var finalCount = 0
        CNS(listOf(counter))
            .stimulate(
                start.createSignal(0),
                CNSStimulationOptions(
                    maxNeuronHops = 5,
                    onResponse = { r ->
                        if (r.outputSignal?.collateral === done) {
                            finalCount = r.outputSignal.payload as Int
                        }
                    },
                ),
            ).awaitComplete()

        assertEquals(3, finalCount)
    }

    @Test
    fun modalityDendriteRoutesByModalityAndAfferentPathIdentity() = runBlocking {
        data class Axon(val output: CNSCollateral<String>)

        val input = collateral<String>()
        val output = collateral<String>()
        val ui = afferentPath()
        val onboarding = afferentPath()
        val cardModality = modality(mapOf("ui" to ui, "onboarding" to onboarding))
        val uiHandler: suspend (DendriteContext<Unit>, Any?, Axon) -> String =
            { _, payload, _ -> "real-${payload as String}" }
        val handlers: Map<CNSAfferentPath, suspend (DendriteContext<Unit>, Any?, Axon) -> String> =
            mapOf(ui to uiHandler)

        val createCard =
            neuron<Unit, Axon>(Axon(output))
                .modalityDendrite(
                    collateral = input,
                    modality = cardModality,
                    afferentPaths = handlers,
                    default = { _, payload, _ -> "default-${payload as String}" },
                    output = { _, result, ax -> ax.output.createSignal(result) },
                )

        val responses = mutableListOf<String>()
        val cns = CNS(listOf(createCard))
        cns.stimulate(
            input.createSignal("button"),
            CNSStimulationOptions(
                modality = cardModality,
                afferentPath = ui,
                onResponse = { r ->
                    if (r.outputSignal?.collateral === output) {
                        responses.add(r.outputSignal.payload as String)
                    }
                },
            ),
        ).awaitComplete()
        cns.stimulate(
            input.createSignal("fallback"),
            CNSStimulationOptions(
                modality = cardModality,
                afferentPath = onboarding,
                onResponse = { r ->
                    if (r.outputSignal?.collateral === output) {
                        responses.add(r.outputSignal.payload as String)
                    }
                },
            ),
        ).awaitComplete()

        assertEquals(listOf("real-button", "default-fallback"), responses)
    }

    @Test
    fun modalityDendriteReportsMissingHandlerAsFailedTask() = runBlocking {
        data class Axon(val output: CNSCollateral<String>)

        val input = collateral<String>()
        val output = collateral<String>()
        val handled = afferentPath()
        val missing = afferentPath()
        val mode = modality(mapOf("handled" to handled, "missing" to missing))
        val handledHandler: suspend (DendriteContext<Unit>, Any?, Axon) -> String =
            { _, payload, _ -> payload as String }
        val handlers: Map<CNSAfferentPath, suspend (DendriteContext<Unit>, Any?, Axon) -> String> =
            mapOf(handled to handledHandler)

        val n =
            neuron<Unit, Axon>(Axon(output))
                .modalityDendrite(
                    collateral = input,
                    modality = mode,
                    afferentPaths = handlers,
                    output = { _, result, ax -> ax.output.createSignal(result) },
                )

        val stimulation =
            CNS(listOf(n)).stimulate(
                input.createSignal("x"),
                CNSStimulationOptions(modality = mode, afferentPath = missing),
            )

        assertFailsWith<Throwable> { stimulation.awaitComplete() }
        assertEquals(1, stimulation.getFailedTasks().size)
        assertEquals(
            "modalityDendrite: No handler found for afferent path in modality and no default handler provided",
            stimulation.getFailedTasks().single().error.message,
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun separateStimulationsShareOneMasterLane() = runBlocking {
        data class Axon(val out: CNSCollateral<Unit>)

        val input = collateral<Unit>()
        val out = collateral<Unit>()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        val worker =
            neuron<Unit, Axon>(Axon(out))
                .setConcurrency(1)
                .dendrite(input) { _, _, ax ->
                    val now = active.incrementAndGet()
                    maxActive.updateAndGet { old -> maxOf(old, now) }
                    delay(40)
                    active.decrementAndGet()
                    ax.out.createSignal(Unit)
                }

        val cns = CNS(listOf(worker))
        coroutineScope {
            val a = async { cns.stimulate(input.createSignal(Unit)).awaitComplete() }
            val b = async { cns.stimulate(input.createSignal(Unit)).awaitComplete() }
            a.await()
            b.await()
        }

        assertEquals(1, maxActive.get())
    }

    @Test
    @Suppress("DEPRECATION")
    fun orchestrationStaysSingleLaneWithinStimulation() = runBlocking {
        data class EmitterAxon(val work: CNSCollateral<Unit>)
        data class WorkerAxon(val out: CNSCollateral<Unit>)

        val start = collateral<Unit>()
        val work = collateral<Unit>()
        val out = collateral<Unit>()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        val emitters =
            (1..3).map {
                neuron<Unit, EmitterAxon>(EmitterAxon(work))
                    .dendrite(start) { _, _, ax -> ax.work.createSignal(Unit) }
            }
        val worker =
            neuron<Unit, WorkerAxon>(WorkerAxon(out))
                .dendrite(work) { _, _, ax ->
                    val now = active.incrementAndGet()
                    maxActive.updateAndGet { old -> maxOf(old, now) }
                    delay(40)
                    active.decrementAndGet()
                    ax.out.createSignal(Unit)
                }

        CNS(emitters + worker)
            .stimulate(start.createSignal(Unit), CNSStimulationOptions(concurrency = 2))
            .awaitComplete()

        assertEquals(1, maxActive.get())
    }

    @Test
    fun heavyWorkCanBeExplicitlyOffloadedFromTheMasterLane() = runBlocking {
        data class EmitterAxon(val work: CNSCollateral<Unit>)
        data class WorkerAxon(val out: CNSCollateral<Unit>)

        val start = collateral<Unit>()
        val work = collateral<Unit>()
        val out = collateral<Unit>()

        val emitters =
            (1..2).map {
                neuron<Unit, EmitterAxon>(EmitterAxon(work))
                    .dendrite(start) { _, _, ax -> ax.work.createSignal(Unit) }
            }
        val worker =
            neuron<Unit, WorkerAxon>(WorkerAxon(out))
                .dendrite(work) { _, _, ax ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        // User-owned heavy work lives outside the orchestration lane.
                        (1..10_000).sum()
                    }
                    ax.out.createSignal(Unit)
                }

        var done = 0
        CNS(emitters + worker)
            .stimulate(
                start.createSignal(Unit),
                CNSStimulationOptions(
                    onResponse = { r ->
                        if (r.outputSignal?.collateral === out) done++
                    },
                ),
            ).awaitComplete()

        assertEquals(2, done)
    }

    @Test
    fun responseListenersRunInParallelAndBlockSubscriberEnqueue() = runBlocking {
        data class Axon(val next: CNSCollateral<Unit>)

        val start = collateral<Unit>()
        val next = collateral<Unit>()
        val first =
            neuron<Unit, Axon>(Axon(next))
                .dendrite(start) { _, _, ax -> ax.next.createSignal(Unit) }
        val second =
            neuron<Unit, Map<String, CNSCollateral<*>>>(emptyMap())
                .dendrite(next) { _, _, _ -> Unit }
        val cns = CNS(listOf(first, second))
        cns.addResponseListener { delay(80) }

        var terminalSeenAt = 0L
        val elapsed =
            measureTimeMillis {
                cns.stimulate(
                    start.createSignal(Unit),
                    CNSStimulationOptions(
                        onResponse = { r ->
                            delay(80)
                            if (r.inputSignal?.collateral === next) {
                                terminalSeenAt = System.currentTimeMillis()
                            }
                        },
                    ),
                ).awaitComplete()
            }

        assertTrue(elapsed in 220..380, "listeners should run in parallel per hop, elapsed=$elapsed")
        assertTrue(terminalSeenAt > 0)
    }

    @Test
    fun drainGuardReusesActiveDrain() = runBlocking {
        data class Axon(val out: CNSCollateral<Unit>)

        val input = collateral<Unit>()
        val out = collateral<Unit>()
        val worker =
            neuron<Unit, Axon>(Axon(out))
                .dendrite(input) { _, _, ax ->
                    delay(50)
                    ax.out.createSignal(Unit)
                }

        val guard = CNSDrainGuard(CNS(listOf(worker)), input.createSignal(Unit))
        coroutineScope {
            val a = async { guard.drain() }
            delay(5)
            val current = guard.getCurrentStimulation()
            val b = async { guard.drain() }
            assertTrue(guard.isDraining())
            assertSame(current, guard.getCurrentStimulation())
            a.await()
            b.await()
        }

        assertFalse(guard.isDraining())
    }

    @Test
    fun maxHopsMaxDurationActivateRetryAndPersistRegistry() = runBlocking {
        data class Axon(val loop: CNSCollateral<Int>, val done: CNSCollateral<Int>)

        val input = collateral<Int>()
        val loop = collateral<Int>()
        val done = collateral<Int>()
        val worker =
            neuron<Unit, Axon>(Axon(loop, done))
                .setMaxDurationMs(500)
                .dendrite(listOf(input, loop)) { _, payload, ax ->
                    val value = payload as Int
                    if (value < 2) ax.loop.createSignal(value + 1) else ax.done.createSignal(value)
                }

        val cns = CNS(listOf(worker))
        val stimulation =
            cns.stimulate(
                input.createSignal(0),
                CNSStimulationOptions(maxNeuronHops = 5),
            )
        stimulation.waitUntilComplete()

        val registry = CNSPersistOptionsRegistry()
        registry.addNeuron(worker, "worker")
        registry.addCollateral(input, "input")
        registry.addStimulation(stimulation, CNSStimulationPersistOptions("stim-1"))
        assertSame(worker, registry.getNeuron("worker"))
        assertEquals("worker", registry.getNeuronName(worker))
        assertSame(input, registry.getCollateral("input"))
        assertSame(stimulation, registry.getStimulation("stim-1"))

        val failedTask =
            CNSNeuronActivationTask(
                neuron = worker,
                dendriteCollateral = input,
                input = input.createSignal(2),
            )
        cns.activate(listOf(failedTask)).waitUntilComplete()
    }

    @Test
    fun longSynchronousChainsStayStackSafeAndFastEnough() = runBlocking {
        data class Axon(val out: CNSCollateral<Int>)

        val size = 1_000
        val collaterals = List(size + 1) { collateral<Int>() }
        val neurons =
            (0 until size).map { i ->
                neuron<Unit, Axon>(Axon(collaterals[i + 1]))
                    .dendrite(collaterals[i]) { _, payload, ax ->
                        ax.out.createSignal((payload as Int) + 1)
                    }
            }

        var result = 0
        val queueLengths = mutableListOf<Int>()
        val taskLengths = mutableListOf<Int>()
        val elapsed =
            measureTimeMillis {
                CNS(neurons)
                    .stimulate(
                        collaterals.first().createSignal(0),
                        CNSStimulationOptions(
                            onResponse = { r ->
                                queueLengths.add(r.queueLength)
                                taskLengths.add(r.stimulation.getAllActivationTasks().size)
                                if (r.outputSignal?.collateral === collaterals.last()) {
                                    result = r.outputSignal.payload as Int
                                }
                            },
                        ),
                    ).waitUntilComplete()
            }

        assertEquals(size, result)
        assertEquals(size + 1, queueLengths.size)
        assertEquals(queueLengths, taskLengths)
        assertEquals(0, queueLengths.last())
        assertTrue(elapsed < 5_000, "1000-hop chain took ${elapsed}ms")
    }

    @Test
    fun tenThousandHopSelfLoopHotPathStaysFast() = runBlocking {
        data class Axon(val loop: CNSCollateral<Int>, val done: CNSCollateral<Int>)

        val size = 10_000
        val loop = collateral<Int>()
        val done = collateral<Int>()
        val worker =
            neuron<Unit, Axon>(Axon(loop, done))
                .dendrite(loop) { _, payload, ax ->
                    val next = (payload as Int) + 1
                    if (next < size) ax.loop.createSignal(next) else ax.done.createSignal(next)
                }

        var result = 0
        val elapsed =
            measureTimeMillis {
                CNS(listOf(worker))
                    .stimulate(
                        loop.createSignal(0),
                        CNSStimulationOptions(
                            maxNeuronHops = size + 1,
                            onResponse = { r ->
                                if (r.outputSignal?.collateral === done) {
                                    result = r.outputSignal.payload as Int
                                }
                            },
                        ),
                    ).waitUntilComplete()
            }

        assertEquals(size, result)
        assertTrue(elapsed < 5_000, "10000-hop self-loop took ${elapsed}ms")
    }
}
