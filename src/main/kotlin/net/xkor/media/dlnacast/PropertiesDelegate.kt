package net.xkor.media.dlnacast

import java.util.*
import kotlin.reflect.KClass

class PropertiesDelegate(
    private val properties: Properties,
    prefix: String = ""
) : BasePropertyDelegate(prefix) {

    override fun <T : Any> getValue(key: String, type: KClass<T>): T? {
        return properties[key]?.toString()?.let { value ->
            @Suppress("UNCHECKED_CAST")
            when (type) {
                Boolean::class -> java.lang.Boolean.parseBoolean(value) as T
                Byte::class -> java.lang.Byte.parseByte(value) as T
                Short::class -> java.lang.Short.parseShort(value) as T
                Int::class -> Integer.parseInt(value) as T
                Long::class -> java.lang.Long.parseLong(value) as T
                Float::class -> java.lang.Float.parseFloat(value) as T
                Double::class -> java.lang.Double.parseDouble(value) as T
                String::class -> value as T
                else -> throw UnsupportedOperationException(type.java.name + " unsupported to parse from String")
            }
        }
    }

    override fun <T : Any> setValue(key: String, value: T?) {
        properties[key] = value?.toString()
    }

}
