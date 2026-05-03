package org.cnstra.core

/**
 * Typed collateral channel — signals route by **instance identity** (like the TS core).
 */
open class CNSCollateral<TPayload : Any?> {
    fun createSignal(payload: TPayload? = null): CNSSignal<TPayload> =
        CNSSignal(this, payload)
}

data class CNSSignal<TPayload : Any?>(
    val collateral: CNSCollateral<TPayload>,
    val payload: TPayload?,
)

class CNSAfferentPath(
    val parent: CNSAfferentPath? = null,
)

class CNSModality(
    val afferentPaths: Map<Any, CNSAfferentPath>,
)
