package dev.anclaw.model

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The few GGUF header fields an-claw shows and uses to pick defaults. */
data class GgufInfo(
    val architecture: String? = null,
    val name: String? = null,
    val contextLength: Long? = null,
    val blockCount: Long? = null,
    val expertCount: Long? = null,
    val fileType: Long? = null,
    val sizeLabel: String? = null,
    val hasChatTemplate: Boolean = false,
    /** Whether the embedded chat template renders `tools` (needed for agent tool calls). */
    val templateHasTools: Boolean = false,
    /** Whether the embedded chat template uses ChatML (`<|im_start|>`), the format the bundled Qwen template speaks. */
    val templateIsChatMl: Boolean = false,
) {
    /**
     * Qwen-family ChatML model whose own template cannot call tools: the bundled Qwen template fixes it.
     * Models that only share the architecture but use another prompt format (e.g. DeepSeek-R1-Distill-Qwen,
     * `<｜User｜>`/`<｜Assistant｜>`) must keep their own template, or they produce garbage.
     */
    val needsToolTemplate: Boolean
        get() = !templateHasTools && architecture?.startsWith("qwen") == true &&
            (templateIsChatMl || !hasChatTemplate)

    val quant: String? get() = fileType?.let { FILE_TYPES[it.toInt()] }

    companion object {
        /** llama_ftype names (subset). */
        val FILE_TYPES = mapOf(
            0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1",
            10 to "Q2_K", 11 to "Q3_K_S", 12 to "Q3_K_M", 13 to "Q3_K_L", 14 to "Q4_K_S", 15 to "Q4_K_M",
            16 to "Q5_K_S", 17 to "Q5_K_M", 18 to "Q6_K", 19 to "IQ2_XXS", 20 to "IQ2_XS", 21 to "Q2_K_S",
            22 to "IQ3_XS", 23 to "IQ3_XXS", 24 to "IQ1_S", 25 to "IQ4_NL", 26 to "IQ3_S", 27 to "IQ3_M",
            28 to "IQ2_S", 29 to "IQ2_M", 30 to "IQ4_XS", 31 to "IQ1_M", 32 to "BF16", 36 to "TQ1_0",
            37 to "TQ2_0", 38 to "MXFP4",
        )
    }
}

/**
 * Minimal GGUF v2/v3 metadata reader: walks the key/value section and keeps the
 * keys in [GgufInfo]. Big arrays (the tokenizer vocab) are skipped, not read.
 */
object Gguf {
    private const val MAGIC = 0x46554747 // "GGUF" little-endian

    fun read(file: File): GgufInfo? = runCatching { file.inputStream().buffered(1 shl 16).use { read(it) } }.getOrNull()

    fun read(input: InputStream): GgufInfo? {
        val r = LeReader(DataInputStream(input))
        if (r.u32() != MAGIC) return null
        val version = r.u32()
        if (version < 2) return null
        r.u64() // tensor count
        val kvCount = r.u64()
        val values = HashMap<String, Any>()
        for (i in 0 until minOf(kvCount, 100_000L)) {
            val key = r.str()
            val type = r.u32()
            val value = r.value(type)
            if (value != null) values[key] = value
        }
        val arch = values["general.architecture"] as? String
        fun num(key: String) = (values[key] as? Number)?.toLong()
        return GgufInfo(
            architecture = arch,
            name = values["general.name"] as? String,
            contextLength = arch?.let { num("$it.context_length") },
            blockCount = arch?.let { num("$it.block_count") },
            expertCount = arch?.let { num("$it.expert_count") },
            fileType = num("general.file_type"),
            sizeLabel = values["general.size_label"] as? String,
            hasChatTemplate = values.containsKey("tokenizer.chat_template"),
            templateHasTools = (values["tokenizer.chat_template"] as? String)?.contains("tools") == true,
            templateIsChatMl = (values["tokenizer.chat_template"] as? String)?.contains("<|im_start|>") == true,
        )
    }

    private class LeReader(private val input: DataInputStream) {
        private val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

        private fun fill(n: Int): ByteBuffer {
            buf.clear()
            input.readFully(buf.array(), 0, n)
            buf.limit(n)
            return buf
        }

        fun u8() = input.readUnsignedByte()
        fun u16() = fill(2).short.toInt() and 0xffff
        fun u32() = fill(4).int
        fun u64() = fill(8).long
        fun f32() = fill(4).float
        fun f64() = fill(8).double

        fun str(): String {
            val len = u64()
            require(len in 0..(1L shl 24)) { "bad string length $len" }
            val bytes = ByteArray(len.toInt())
            input.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        private fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) {
                    input.readByte(); left--
                } else left -= skipped
            }
        }

        /** Returns scalars and strings; arrays are skipped (null). */
        fun value(type: Int): Any? = when (type) {
            0 -> u8(); 1 -> u8().toByte(); 2 -> u16(); 3 -> u16().toShort()
            4 -> u32().toLong() and 0xffffffffL; 5 -> u32(); 6 -> f32()
            7 -> u8() != 0; 8 -> str()
            9 -> {
                val elemType = u32()
                val count = u64()
                val fixed = when (elemType) {
                    0, 1, 7 -> 1; 2, 3 -> 2; 4, 5, 6 -> 4; 10, 11, 12 -> 8; else -> -1
                }
                if (fixed > 0) skip(count * fixed) else repeat(count.toInt()) { value(elemType) }
                null
            }
            10 -> u64(); 11 -> u64(); 12 -> f64()
            else -> error("unknown GGUF type $type")
        }
    }
}
