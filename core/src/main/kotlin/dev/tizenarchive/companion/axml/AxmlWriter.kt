package dev.tizenarchive.companion.axml

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

public data class AxmlNamespace(val prefix: String, val uri: String)

public sealed interface AxmlValue {
    public data class Text(val value: String) : AxmlValue
    public data class IntDec(val value: Int) : AxmlValue
    public data class Bool(val value: Boolean) : AxmlValue
}

/** An attribute. Attributes with a namespace must carry the framework resource id of their name. */
public data class AxmlAttribute(
    val name: String,
    val value: AxmlValue,
    val namespace: AxmlNamespace? = null,
    val resourceId: Int? = null,
) {
    init {
        require(namespace == null || resourceId != null) { "Namespaced attribute '$name' needs a resource id" }
    }
}

public class AxmlElement(
    public val name: String,
    public val attributes: List<AxmlAttribute> = emptyList(),
    public val children: List<AxmlElement> = emptyList(),
)

/**
 * Serializes an element tree into Android binary XML (the format of a compiled AndroidManifest.xml):
 * string pool (UTF-16), resource map, namespace and element chunks.
 */
public object AxmlWriter {
    private const val TYPE_XML = 0x0003
    private const val TYPE_STRING_POOL = 0x0001
    private const val TYPE_RESOURCE_MAP = 0x0180
    private const val TYPE_START_NAMESPACE = 0x0100
    private const val TYPE_END_NAMESPACE = 0x0101
    private const val TYPE_START_ELEMENT = 0x0102
    private const val TYPE_END_ELEMENT = 0x0103

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_BOOLEAN = 0x12

    private const val NONE = -1

    public fun write(root: AxmlElement, namespaces: List<AxmlNamespace> = emptyList()): ByteArray {
        val pool = StringPool.collect(root, namespaces)
        val body = Chunks()

        var line = 1
        for (ns in namespaces) {
            body.namespace(TYPE_START_NAMESPACE, line++, pool.index(ns.prefix), pool.index(ns.uri))
        }
        line = writeElement(body, pool, root, line)
        for (ns in namespaces.asReversed()) {
            body.namespace(TYPE_END_NAMESPACE, line++, pool.index(ns.prefix), pool.index(ns.uri))
        }

        val poolChunk = pool.toChunk()
        val resourceMap = pool.resourceMapChunk()
        val total = 8 + poolChunk.size + resourceMap.size + body.size()
        val out = Chunks()
        out.u16(TYPE_XML).u16(8).u32(total)
        out.bytes(poolChunk).bytes(resourceMap).bytes(body.toByteArray())
        return out.toByteArray()
    }

    private fun writeElement(out: Chunks, pool: StringPool, element: AxmlElement, startLine: Int): Int {
        var line = startLine
        val attributes = sortAttributes(element.attributes)
        out.u16(TYPE_START_ELEMENT).u16(16).u32(16 + 20 + 20 * attributes.size)
        out.u32(line++).u32(NONE)
        out.u32(NONE).u32(pool.index(element.name))
        out.u16(20).u16(20).u16(attributes.size).u16(0).u16(0).u16(0)
        for (attribute in attributes) {
            out.u32(attribute.namespace?.let { pool.index(it.uri) } ?: NONE)
            out.u32(pool.index(attribute.name))
            when (val value = attribute.value) {
                is AxmlValue.Text -> {
                    val index = pool.index(value.value)
                    out.u32(index).u16(8).u8(0).u8(TYPE_STRING).u32(index)
                }
                is AxmlValue.IntDec -> out.u32(NONE).u16(8).u8(0).u8(TYPE_INT_DEC).u32(value.value)
                is AxmlValue.Bool -> out.u32(NONE).u16(8).u8(0).u8(TYPE_INT_BOOLEAN).u32(if (value.value) -1 else 0)
            }
        }
        for (child in element.children) {
            line = writeElement(out, pool, child, line)
        }
        out.u16(TYPE_END_ELEMENT).u16(16).u32(24)
        out.u32(line++).u32(NONE)
        out.u32(NONE).u32(pool.index(element.name))
        return line
    }

    /** Android looks framework attributes up by resource id, so those come first in ascending id order. */
    private fun sortAttributes(attributes: List<AxmlAttribute>): List<AxmlAttribute> {
        val (withId, withoutId) = attributes.partition { it.resourceId != null }
        return withId.sortedBy { it.resourceId } + withoutId
    }

    private class StringPool(
        private val strings: List<String>,
        private val resourceIds: List<Int>,
    ) {
        private val indexes: Map<String, Int> = strings.withIndex().associate { it.value to it.index }

        fun index(value: String): Int = indexes.getValue(value)

        fun toChunk(): ByteArray {
            val encoded = strings.map(::encode)
            val offsets = IntArray(encoded.size)
            var cursor = 0
            encoded.forEachIndexed { i, bytes ->
                offsets[i] = cursor
                cursor += bytes.size
            }
            val stringsStart = 28 + 4 * strings.size
            val dataSize = (cursor + 3) and 3.inv()
            val out = Chunks()
            out.u16(TYPE_STRING_POOL).u16(28).u32(stringsStart + dataSize)
            out.u32(strings.size).u32(0).u32(0).u32(stringsStart).u32(0)
            offsets.forEach { out.u32(it) }
            encoded.forEach { out.bytes(it) }
            repeat(dataSize - cursor) { out.u8(0) }
            return out.toByteArray()
        }

        fun resourceMapChunk(): ByteArray {
            val out = Chunks()
            out.u16(TYPE_RESOURCE_MAP).u16(8).u32(8 + 4 * resourceIds.size)
            resourceIds.forEach { out.u32(it) }
            return out.toByteArray()
        }

        private fun encode(value: String): ByteArray {
            val length = value.length
            require(length < 0x7fff) { "String too long" }
            val out = Chunks()
            out.u16(length)
            value.forEach { out.u16(it.code) }
            out.u16(0)
            return out.toByteArray()
        }

        companion object {
            fun collect(root: AxmlElement, namespaces: List<AxmlNamespace>): StringPool {
                val withId = sortedMapOf<Int, String>()
                val others = LinkedHashSet<String>()

                fun visit(element: AxmlElement) {
                    others += element.name
                    for (attribute in element.attributes) {
                        val id = attribute.resourceId
                        if (id != null) {
                            val previous = withId.put(id, attribute.name)
                            require(previous == null || previous == attribute.name) {
                                "Resource id 0x${id.toString(16)} used for '$previous' and '${attribute.name}'"
                            }
                        } else {
                            others += attribute.name
                        }
                        attribute.namespace?.let { others += it.uri }
                        (attribute.value as? AxmlValue.Text)?.let { others += it.value }
                    }
                    element.children.forEach(::visit)
                }
                visit(root)
                for (ns in namespaces) {
                    others += ns.prefix
                    others += ns.uri
                }

                val named = withId.values.toList()
                val ordered = named + others.filterNot { it in named }
                require(named.size == named.toSet().size) { "One attribute name used with several resource ids" }
                return StringPool(ordered, withId.keys.toList())
            }
        }
    }

    private class Chunks {
        private val buffer = ByteArrayOutputStream()

        fun size(): Int = buffer.size()

        fun u8(value: Int): Chunks = apply { buffer.write(value) }

        fun u16(value: Int): Chunks = apply {
            buffer.write(value and 0xff)
            buffer.write((value ushr 8) and 0xff)
        }

        fun u32(value: Int): Chunks = apply {
            buffer.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        }

        fun bytes(value: ByteArray): Chunks = apply { buffer.write(value) }

        fun namespace(type: Int, line: Int, prefix: Int, uri: Int): Chunks =
            u16(type).u16(16).u32(24).u32(line).u32(NONE).u32(prefix).u32(uri)

        fun toByteArray(): ByteArray = buffer.toByteArray()
    }
}
