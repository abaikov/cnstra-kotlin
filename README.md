# CNStra Kotlin

Kotlin/JVM port of CNStra: a deterministic, embeddable workflow/orchestration engine built around a typed neuron graph.

This repository ports the core ideas from `@cnstra/core` into an idiomatic coroutine-based API:

- signals route by collateral **object identity**
- runs start explicitly with `cns.stimulate(...)`
- traversal is deterministic and hop-bounded when `maxNeuronHops` is set
- dendrites are `suspend`, so async workflows use normal Kotlin coroutines
- orchestration runs on one logical lane per `CNS` instance
- context, modality routing, retry via `activate`, drain guards, and persist registries are covered

## Concurrency Model

CNStra Kotlin keeps graph orchestration single-lane on purpose. The runtime chooses the next dendrite, updates context, records responses, and schedules follow-up signals on one logical dispatcher. That keeps ordering deterministic and avoids hidden races in context stores, hop counts, retry, and cleanup.

Heavy work should be explicit inside a dendrite:

```kotlin
.dendrite(input) { _, payload, axon ->
    val result = withContext(Dispatchers.IO) {
        loadFromDatabase(payload)
    }

    axon.output.createSignal(result)
}
```

This mirrors the original JS design: CNStra is the master orchestrator; CPU, IO, workers, and pools belong to the application code.

## Quick Start

```kotlin
import kotlinx.coroutines.runBlocking
import org.cnstra.core.CNS
import org.cnstra.core.CNSCollateral
import org.cnstra.core.CNSStimulationOptions
import org.cnstra.core.collateral
import org.cnstra.core.neuron

data class UserCreated(val id: String, val name: String)
data class UserRegistered(val userId: String, val status: String)
data class UserAxon(val userRegistered: CNSCollateral<UserRegistered>)

fun main() = runBlocking {
    val userCreated = collateral<UserCreated>()
    val userRegistered = collateral<UserRegistered>()

    val userService =
        neuron<Unit, UserAxon>(UserAxon(userRegistered))
            .dendrite(userCreated) { _, payload, axon ->
                val user = payload as UserCreated
                axon.userRegistered.createSignal(
                    UserRegistered(userId = user.id, status = "completed")
                )
            }

    val cns = CNS(listOf(userService))

    cns.stimulate(
        userCreated.createSignal(UserCreated("123", "John")),
        CNSStimulationOptions(
            onResponse = { response ->
                println(response.outputSignal?.payload)
            },
        ),
    ).waitUntilComplete()
}
```

## Context-Aware Neurons

```kotlin
data class CounterContext(val count: Int)
data class CounterAxon(val done: CNSCollateral<Int>)

val input = collateral<Int>()
val done = collateral<Int>()

val counter =
    withCtx<CounterContext>()
        .neuron(CounterAxon(done))
        .dendrite(input) { ctx, payload, axon ->
            val count = (ctx.get()?.count ?: 0) + (payload as Int)
            ctx.set(CounterContext(count))
            axon.done.createSignal(count)
        }
```

## Build

```bash
./gradlew :core:test
```

The current target is JVM 17 bytecode, with tests running through Kotlin/JUnit and `kotlinx-coroutines`.

## Publishing

The core artifact is configured for Maven Central:

```text
io.github.abaikov:cnstra:<version>
```

Local verification:

```bash
./gradlew :core:publishToMavenLocal
```

Release publishing is handled by `.github/workflows/publish-maven-central.yml`.
It runs on `v*` tags or manual dispatch and expects these GitHub Actions secrets:

```text
MAVEN_CENTRAL_USERNAME
MAVEN_CENTRAL_PASSWORD
SIGNING_IN_MEMORY_KEY
SIGNING_IN_MEMORY_KEY_PASSWORD
```

Publish manually from CI/local environment with the same Gradle properties:

```bash
./gradlew :core:publishToMavenCentral
```

After release, Android/JVM users can consume the library with `mavenCentral()`.

Tag releases publish the tag version without the `v` prefix:

```bash
git tag v0.1.0
git push origin v0.1.0
```
