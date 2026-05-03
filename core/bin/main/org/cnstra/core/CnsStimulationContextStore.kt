package org.cnstra.core

interface ICNSStimulationContextStore {
    operator fun get(key: Any): Any?
    operator fun set(key: Any, value: Any?)
    fun delete(key: Any)
    fun getAll(): Map<Any, Any?>
    fun setAll(values: Map<Any, Any?>)
}

class CNSStimulationContextStore(
    private val ctx: MutableMap<Any, Any?> = java.util.IdentityHashMap(),
) : ICNSStimulationContextStore {
    override fun get(key: Any): Any? = ctx[key]

    override fun set(key: Any, value: Any?) {
        ctx[key] = value
    }

    override fun delete(key: Any) {
        ctx.remove(key)
    }

    override fun getAll(): Map<Any, Any?> = LinkedHashMap(ctx)

    override fun setAll(values: Map<Any, Any?>) {
        ctx.clear()
        ctx.putAll(values)
    }
}
