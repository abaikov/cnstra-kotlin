package org.cnstra.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CnstraSmokeTest {
    @Test
    fun collateralAndSignal() {
        val c = collateral<Map<String, String>>()
        val sig = c.createSignal(mapOf("message" to "hi"))
        assertSame(c, sig.collateral)
        assertEquals(mapOf("message" to "hi"), sig.payload)
    }

    @Test
    fun quickStartStyleFlow() = runBlocking {
        data class UserCreated(val id: String, val name: String)
        data class UserRegistered(val userId: String, val status: String)
        data class SvcAxon(val userRegistered: CNSCollateral<UserRegistered>)

        val userCreated = collateral<UserCreated>()
        val userRegistered = collateral<UserRegistered>()
        val axon = SvcAxon(userRegistered)

        val userService =
            neuron<UserRegistered, SvcAxon>(axon)
                .dendrite(userCreated) { _, payload, ax ->
                    ax.userRegistered.createSignal(
                        UserRegistered(
                            userId = (payload as UserCreated).id,
                            status = "completed",
                        ),
                    )
                }

        val cns = CNS(listOf(userService))
        val stim =
            cns.stimulate(
                userCreated.createSignal(
                    UserCreated(
                        id = "123",
                        name = "John",
                    ),
                ),
            )
        stim.awaitComplete()
    }
}
