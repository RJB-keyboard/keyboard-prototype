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
import net.ramdos.keyboard_prototype.engine.ConversionCandidate
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.ObjectInputStream
import java.text.Normalizer
import java.util.zip.ZipInputStream

/**
 * Local Sumire dictionary/Viterbi conversion and Kuromoji IPADIC context readings.
 * Open and use on a worker: loading dictionaries and tokenization can be expensive.
 * Surface ranking uses a small character n-gram model; neither operation uses network or neural inference.
 */
class SumireKanaKanjiConverter private constructor(
    private val yomi: LOUDSWithTermId,
    private val tango: LOUDS,
    private val tokens: TokenArray,
    private val connections: ConnectionMatrix,
    private val tokenizer: Tokenizer,
    lexiconConfig: SumireLexiconConfig,
    private val surfaceModel: SurfaceLanguageModel?,
    private val surfaceConfig: SurfaceRerankingConfig,
) : KanaKanjiConverter {
    private val graphBuilder = GraphBuilder()
    private val pathFinder = FindPath()
    @Volatile private var closed = false
    override val readingLexicon = SumireReadingLexicon(yomi, tokens, connections, lexiconConfig) {
        check(!closed) { "Kana-kanji converter is closed" }
    }
    private val conversionCache = object : LinkedHashMap<String, List<ConversionCandidate>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ConversionCandidate>>): Boolean =
            size > 64
    }

    @Synchronized
    override fun convertCandidates(reading: String, limit: Int): List<ConversionCandidate> {
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
            val poolSize = if (surfaceModel == null) count else MAX_CANDIDATES
            val candidates = pathFinder.backwardAStarCandidates(graph, normalized.length, connections, poolSize)
                .filter { it.text.isNotBlank() }
                .map {
                    val scale = readingLexicon.config.costScale
                    ConversionCandidate(it.text, it.cost * scale,
                        wordCost = it.wordCost * scale, connectionCost = it.connectionCost * scale)
                }
            rerank(candidates).take(count)
        }
    }

    private fun rerank(candidates: List<ConversionCandidate>): List<ConversionCandidate> {
        val model = surfaceModel ?: return candidates
        val first = candidates.firstOrNull() ?: return candidates
        val scored = candidates.map { it.copy(surfaceCost = model.cost(it.text)) }
        val baseline = requireNotNull(scored.first().surfaceCost)
        val differences = scored.map { candidate ->
            // Do not rescue arbitrarily implausible dictionary paths with corpus frequency alone.
            if (candidate.dictionaryCost - first.dictionaryCost > surfaceConfig.maxDictionaryGap) 0.0 else
                surfaceConfig.weight * (requireNotNull(candidate.surfaceCost) - baseline)
        }
        return scored.zip(surfaceConfig.boundAdjustments(differences)).map { (candidate, adjustment) ->
            candidate.copy(cost = candidate.dictionaryCost + adjustment, contextAdjustment = adjustment)
        }.sortedWith(compareBy<ConversionCandidate> { it.cost }.thenBy { it.dictionaryCost })
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
        private const val ASSET_ROOT = "conversion"
        // ZipInputStream may report -1 until its trailing data descriptor is consumed.
        // This is the pinned v1.7.252 matrix's uncompressed byte count (2672² shorts).
        private const val CONNECTION_MATRIX_BYTES = 14_279_168L

        fun open(
            context: Context,
            lexiconConfig: SumireLexiconConfig = SumireLexiconConfig(),
            surfaceConfig: SurfaceRerankingConfig = SurfaceRerankingConfig(),
        ): SumireKanaKanjiConverter =
            fromAssets(lexiconConfig, surfaceConfig) { name -> context.applicationContext.assets.open("$ASSET_ROOT/$name") }

        /** Shared byte-loading route used by Android and real-dictionary JVM integration tests. */
        internal fun fromAssets(
            lexiconConfig: SumireLexiconConfig = SumireLexiconConfig(),
            surfaceConfig: SurfaceRerankingConfig = SurfaceRerankingConfig(),
            openAsset: (String) -> InputStream,
        ): SumireKanaKanjiConverter {
            fun <T> zippedObject(name: String, read: (ObjectInputStream) -> T): T =
                openAsset("sumire/$name.zip").use { raw ->
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
            openAsset("sumire/pos_table.dat").use { raw ->
                ObjectInputStream(BufferedInputStream(raw)).use { tokens.readPOSTable(it) }
            }
            val connections = openAsset("sumire/connectionId.dat.zip").use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    val entry = checkNotNull(zip.nextEntry) { "Missing Sumire connection matrix" }
                    check(entry.name == "connectionId.dat" && !entry.isDirectory)
                    check(entry.size == -1L || entry.size == CONNECTION_MATRIX_BYTES)
                    ConnectionMatrix.read(BufferedInputStream(zip), CONNECTION_MATRIX_BYTES)
                }
            }
            val surfaceModel = if (surfaceConfig.weight == 0.0) null else
                // Gzip payload uses .bin: AAPT transparently expands assets ending in .gz.
                openAsset("surface/model.bin").use(SurfaceLanguageModel::read)
            return SumireKanaKanjiConverter(yomi, tango, tokens, connections, Tokenizer(), lexiconConfig,
                surfaceModel, surfaceConfig)
        }

        internal fun hiragana(text: String): String = text.map {
            if (it in '\u30a1'..'\u30f6') it - 0x60 else it
        }.joinToString("")
    }
}
