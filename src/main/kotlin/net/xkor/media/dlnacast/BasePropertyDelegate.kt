package net.xkor.media.dlnacast

import kotlin.reflect.KClass
import kotlin.reflect.KProperty

/**
 * Base delegated property implementation.
 */
abstract class BasePropertyDelegate(protected var keyPrefix: String = "") {

    abstract fun <T : Any> getValue(key: String, type: KClass<T>): T?

    abstract fun <T : Any> setValue(key: String, value: T?)

    fun key(property: KProperty<*>) = keyPrefix + property.name

    inline operator fun <reified T : Any> getValue(thisRef: Any, property: KProperty<*>): T? =
        getValue(key(property), T::class)

    operator fun <T : Any> setValue(thisRef: Any, property: KProperty<*>, value: T?) = setValue(key(property), value)

    fun <T : Any> default(value: T) = WithDefault(this, value)

    open class WithDefault<T : Any> internal constructor(
        val base: BasePropertyDelegate,
        val default: T
    ) {
        inline operator fun <reified R : T> getValue(thisRef: Any, property: KProperty<*>): R {
            return base.getValue<R>(thisRef, property) ?: default as R
        }

        open operator fun setValue(thisRef: Any, property: KProperty<*>, value: T) {
            base.setValue(thisRef, property, value)
        }
    }

}
