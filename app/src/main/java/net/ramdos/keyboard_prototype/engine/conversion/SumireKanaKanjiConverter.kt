package net.ramdos.keyboard_prototype.engine.conversion

import android.content.Context
import com.atilika.kuromoji.ipadic.Tokenizer
import com.kazumaproject.Louds.LOUDS
import com.kazumaproject.Louds.with_term_id.LOUDSWithTermId
import com.kazumaproject.dictionary.TokenArray
import com.kazumaproject.graph.GraphBuilder
import com.kazumaproject.mozc.ConnectionMatrix
import com.kazumaproject.viterbi.FindPath
import net.ramdos.keyboard_prototype.engine.KanaKanjiConverter
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.ObjectInputStream
import java.text.Normalizer
import java.util.zip.ZipInputStream

/**
 * Local Sumire dictionary/Viterbi conversion and Kuromoji IPADIC context readings.
 * Open and use on a worker: loading dictionaries and tokenization can be expensive.
 * No network access or language model is involved in either operation.
 */
class SumireKanaKanjiConverter private constructor(
    private val yomi: LOUDSWithTermId,
    private val tango: LOUDS,
    private val tokens: TokenArray,
    private val connections: ConnectionMatrix,
    private val tokenizer: Tokenizer,
) : KanaKanjiConverter {
    private val graphBuilder = GraphBuilder()
    private val pathFinder = FindPath()
    private var closed = false
    private val conversionCache = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>): Boolean =
            size > 64
    }

    @Synchronized
    override fun convert(reading: String, limit: Int): List<String> {
        check(!closed) { "Kana-kanji converter is closed" }
        if (limit <= 0 || reading.isBlank()) return emptyList()
        val normalized = hiragana(Normalizer.normalize(reading, Normalizer.Form.NFKC))
        require(normalized.length <= MAX_READING_LENGTH) { "Reading is too long" }
        require(normalized.all { it in '\u3041'..'\u3096' || it == 'ー' }) {
            "Kana-kanji conversion expects a kana reading"
        }
        val count = limit.coerceAtMost(MAX_CANDIDATES)
        val cacheKey = "$count:$normalized"
        return conversionCache.getOrPut(cacheKey) {
            val graph = graphBuilder.constructGraph(normalized, yomi, tango, tokens)
            pathFinder.backwardAStar(graph, normalized.length, connections, count)
                .filter { it.isNotBlank() }.distinct().take(count)
        }
    }

    /** Preserve unknown text and punctuation; the LM tokenizer handles unsupported symbols. */
    @Synchronized
    override fun readingOf(text: String): String {
        check(!closed) { "Kana-kanji converter is closed" }
        if (text.isEmpty()) return ""
        return tokenizer.tokenize(Normalizer.normalize(text, Normalizer.Form.NFKC))
            .joinToString("") { token ->
                hiragana(token.reading?.takeUnless { it == "*" || it.isEmpty() } ?: token.surface)
            }
    }

    @Synchronized
    override fun close() {
        closed = true
        conversionCache.clear()
    }

    companion object {
        const val MAX_READING_LENGTH = 64
        private const val MAX_CANDIDATES = 20
        private const val ASSET_ROOT = "conversion/sumire"
        // ZipInputStream may report -1 until its trailing data descriptor is consumed.
        // This is the pinned v1.7.252 matrix's uncompressed byte count (2672² shorts).
        private const val CONNECTION_MATRIX_BYTES = 14_279_168L

        fun open(context: Context): SumireKanaKanjiConverter =
            fromAssets { name -> context.applicationContext.assets.open("$ASSET_ROOT/$name") }

        /** Shared byte-loading route used by Android and real-dictionary JVM integration tests. */
        internal fun fromAssets(openAsset: (String) -> InputStream): SumireKanaKanjiConverter {
            fun <T> zippedObject(name: String, read: (ObjectInputStream) -> T): T =
                openAsset("$name.zip").use { raw ->
                    ZipInputStream(BufferedInputStream(raw)).use { zip ->
                        val entry = checkNotNull(zip.nextEntry) { "Missing Sumire dictionary $name" }
                        check(entry.name == name && !entry.isDirectory) { "Invalid Sumire dictionary archive" }
                        ObjectInputStream(BufferedInputStream(zip)).use(read)
                    }
                }
            val yomi = zippedObject("yomi.dat") { LOUDSWithTermId().readDictionary(it) }
            val tango = zippedObject("tango.dat") { LOUDS().readExternalNotCompress(it) }
            val tokens = TokenArray()
            zippedObject("token.dat") { tokens.readExternalNotCompress(it) }
            openAsset("pos_table.dat").use { raw ->
                ObjectInputStream(BufferedInputStream(raw)).use { tokens.readPOSTable(it) }
            }
            val connections = openAsset("connectionId.dat.zip").use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    val entry = checkNotNull(zip.nextEntry) { "Missing Sumire connection matrix" }
                    check(entry.name == "connectionId.dat" && !entry.isDirectory)
                    check(entry.size == -1L || entry.size == CONNECTION_MATRIX_BYTES)
                    ConnectionMatrix.read(BufferedInputStream(zip), CONNECTION_MATRIX_BYTES)
                }
            }
            return SumireKanaKanjiConverter(yomi, tango, tokens, connections, Tokenizer())
        }

        internal fun hiragana(text: String): String = text.map {
            if (it in '\u30a1'..'\u30f6') it - 0x60 else it
        }.joinToString("")
    }
}
