package net.ramdos.keyboard_prototype.engine

import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

data class StrokeModelConfig(
    val maxInputPoints: Int = 8192,
    val maxResampledPoints: Int = 1024,
    val maxEvents: Int = 48,
    val nearbyKeyCount: Int = 9,
    val distanceSigma: Double = 0.55,
    val straightEmissionProbability: Double = 0.10,
    val centerDwellMillis: Long = 140,
    val maxNearbyKeyDistance: Double = 1.5,
    val preciseDistanceSigma: Double = 0.43,
) {
    init {
        require(maxInputPoints in 2..65536)
        require(maxResampledPoints in 8..8192)
        require(maxEvents in 1..128)
        require(nearbyKeyCount in 1..9)
        require(distanceSigma.isFinite() && distanceSigma in 0.05..2.0)
        require(straightEmissionProbability.isFinite() && straightEmissionProbability in 0.001..0.99)
        require(centerDwellMillis in 20..5000)
        require(maxNearbyKeyDistance.isFinite() && maxNearbyKeyDistance in 1.25..16.0)
        require(preciseDistanceSigma.isFinite() && preciseDistanceSigma in 0.05..2.0)
    }
}

/** One position in a probability lattice: skip, or emit exactly one character. */
data class StrokeEvent(
    val characterLogProbabilities: Map<Char, Double>,
    val skipLogProbability: Double,
)

/**
 * Distribution A over alignments. An alignment independently selects one option
 * per event. Sum the probabilities of alignments with the same emitted reading
 * to obtain A(reading). There are no word lists, context, or semantic features.
 */
data class StrokeLattice(val events: List<StrokeEvent>)

/**
 * Geometry-only recognizer in units of key width/height, independent of pixels.
 * Straight crossings are optional; endpoints, corners, and center dwell are
 * stronger evidence. Returning to a key can emit it again, including a small
 * loop entirely inside that key. Holding a tap does not invent repeated letters.
 *
 * Voiced and small forms share their base key with fixed priors. These priors and
 * the geometric probabilities are heuristics, not probabilities fitted to data.
 */
class StrokeModel(private val config: StrokeModelConfig = StrokeModelConfig()) {
    private data class Point(val x: Double, val y: Double, val time: Double)
    private data class Key(val kana: Char, val x: Double, val y: Double)
    private data class Run(val key: Int, val start: Int, val end: Int, val repeated: Boolean = false)

    fun lattice(trace: GlideTrace): StrokeLattice {
        checkInterrupted()
        if (!isValid(trace)) return StrokeLattice(emptyList())
        val width = trace.keys.map { (it.right - it.left).toDouble() }.sorted().let { it[it.size / 2] }
        val height = trace.keys.map { (it.bottom - it.top).toDouble() }.sorted().let { it[it.size / 2] }
        val keys = trace.keys.map {
            Key(it.kana[0], (it.left.toDouble() + it.right) / (2 * width), (it.top.toDouble() + it.bottom) / (2 * height))
        }
        val source = trace.points.map { Point(it.x / width, it.y / height, it.elapsedMillis.toDouble()) }
        // Reject a trace entirely outside every key, including traces near a blank cell.
        if (!intersectsKey(trace)) return StrokeLattice(emptyList())
        val points = resample(source)
        val runs = runs(points, keys).flatMap { splitReturns(it, points, keys[it.key]) }
        if (runs.isEmpty()) return StrokeLattice(emptyList())
        // Unusually long scribbles are not silently truncated to a different gesture.
        if (runs.size > config.maxEvents) return StrokeLattice(emptyList())
        return StrokeLattice(runs.mapIndexed { index, run ->
            checkInterrupted()
            val key = keys[run.key]
            val anchor = (run.start..run.end).minByOrNull { squaredDistance(points[it], key) }!!
            val dwellMillis = centerDwell(run, points, key)
            // A tap or deliberate center hold is stronger location evidence than
            // a moving finger. Broaden moving glides without blurring held keys.
            val sigma = if (runs.size == 1 || dwellMillis >= config.centerDwellMillis)
                min(config.distanceSigma, config.preciseDistanceSigma) else config.distanceSigma
            // Keep all immediate neighbours eligible for dictionary/LM scoring.
            // A count alone arbitrarily drops equally close directions, while
            // a radius prevents filling the quota with distant keys at edges/gaps.
            val nearest = keys.filter {
                squaredDistance(points[anchor], it) <= config.maxNearbyKeyDistance * config.maxNearbyKeyDistance
            }.sortedBy { squaredDistance(points[anchor], it) }.take(config.nearbyKeyCount)
            val charWeights = linkedMapOf<Char, Double>()
            nearest.forEach { candidate ->
                val weight = exp(-squaredDistance(points[anchor], candidate) / (2 * sigma * sigma))
                variants(candidate.kana).forEach { (kana, prior) ->
                    charWeights[kana] = (charWeights[kana] ?: 0.0) + weight * prior
                }
            }
            val emission = if (index == 0 || index == runs.lastIndex) 1.0 else {
                val turn = turnAt(anchor, points) / Math.PI
                val dwell = dwellMillis / config.centerDwellMillis
                max(if (run.repeated) 0.94 else 0.0,
                    config.straightEmissionProbability + 0.85 * turn + 0.80 * min(1.0, dwell)).coerceIn(0.001, 0.995)
            }
            val total = charWeights.values.sum()
            StrokeEvent(
                charWeights.mapValues { (_, weight) -> ln(emission * weight / total) },
                if (emission == 1.0) Double.NEGATIVE_INFINITY else ln(1.0 - emission),
            )
        })
    }

    private fun isValid(trace: GlideTrace): Boolean {
        if (trace.points.isEmpty() || trace.points.size > config.maxInputPoints || trace.keys.isEmpty() || trace.keys.size > 128) return false
        if (trace.keys.any { key ->
                key.kana.length != 1 || !key.kana[0].isKanaReadingCharacter() ||
                    !key.left.isFinite() || !key.right.isFinite() || !key.top.isFinite() || !key.bottom.isFinite() ||
                    !(key.right - key.left).isFinite() || !(key.bottom - key.top).isFinite() ||
                    key.right - key.left < 0.00001f || key.bottom - key.top < 0.00001f
            }) return false
        var previousTime = -1L
        for (point in trace.points) {
            if (!point.x.isFinite() || !point.y.isFinite() || point.elapsedMillis < previousTime ||
                point.elapsedMillis < 0 || point.elapsedMillis > 120_000 ||
                point.x !in -2f..3f || point.y !in -2f..3f) return false
            previousTime = point.elapsedMillis
        }
        return true
    }

    private fun intersectsKey(trace: GlideTrace): Boolean {
        if (trace.points.any { point -> trace.keys.any { it.contains(point.x, point.y) } }) return true
        // Test sparse segments as well: touch sampling need not land on a key.
        return trace.points.zipWithNext().any { (a, b) -> trace.keys.any { key ->
            var low = 0.0
            var high = 1.0
            fun clip(start: Double, delta: Double, lower: Double, upper: Double): Boolean {
                if (delta == 0.0) return start >= lower && start < upper
                val first = (lower - start) / delta
                val second = (upper - start) / delta
                low = max(low, min(first, second))
                high = min(high, max(first, second))
                return low < high
            }
            clip(a.x.toDouble(), (b.x - a.x).toDouble(), key.left.toDouble(), key.right.toDouble()) &&
                clip(a.y.toDouble(), (b.y - a.y).toDouble(), key.top.toDouble(), key.bottom.toDouble())
        } }
    }

    private fun resample(source: List<Point>): List<Point> {
        val length = source.zipWithNext().sumOf { (a, b) -> distance(a, b) }
        val step = max(0.18, length / (config.maxResampledPoints / 2.0))
        val stride = max(1, ceil(source.size / (config.maxResampledPoints / 2.0)).toInt())
        val compact = source.filterIndexed { index, _ -> index % stride == 0 || index == source.lastIndex }
        val output = ArrayList<Point>(config.maxResampledPoints)
        output.add(compact.first())
        compact.zipWithNext().forEach { (a, b) ->
            checkInterrupted()
            val count = max(1, ceil(distance(a, b) / step).toInt())
            for (index in 1..count) {
                if (output.size >= config.maxResampledPoints - 1) break
                val ratio = index.toDouble() / count
                output.add(Point(a.x + (b.x - a.x) * ratio, a.y + (b.y - a.y) * ratio, a.time + (b.time - a.time) * ratio))
            }
        }
        if (output.last() != source.last()) output.add(source.last())
        return output
    }

    private fun runs(points: List<Point>, keys: List<Key>): List<Run> {
        val result = mutableListOf<Run>()
        var current = -1
        var start = 0
        points.forEachIndexed { index, point ->
            var nearest = keys.indices.minByOrNull { squaredDistance(point, keys[it]) }!!
            if (squaredDistance(point, keys[nearest]) > 1.25 * 1.25) nearest = -1
            // Hysteresis prevents tiny boundary jitter from creating repeated events.
            if (current >= 0 && nearest >= 0 && squaredDistance(point, keys[current]) < squaredDistance(point, keys[nearest]) + 0.06) nearest = current
            if (nearest != current) {
                if (current >= 0) result.add(Run(current, start, index - 1))
                current = nearest
                start = index
            }
        }
        if (current >= 0) result.add(Run(current, start, points.lastIndex))
        return result
    }

    private fun splitReturns(run: Run, points: List<Point>, key: Key): List<Run> {
        val result = mutableListOf<Run>()
        var start = run.start
        var visitedCenter = false
        var departed = false
        var travel = 0.0
        for (index in run.start..run.end) {
            val radius = kotlin.math.sqrt(squaredDistance(points[index], key))
            if (visitedCenter && index > run.start) travel += distance(points[index - 1], points[index])
            if (radius < 0.24) {
                if (visitedCenter && departed && travel >= 0.55) {
                    result.add(Run(run.key, start, index - 1, result.isNotEmpty()))
                    start = index
                }
                visitedCenter = true
                departed = false
                travel = 0.0
            } else if (visitedCenter && radius > 0.4) departed = true
        }
        result.add(Run(run.key, start, run.end, result.isNotEmpty()))
        return result
    }

    private fun turnAt(index: Int, points: List<Point>): Double {
        var before = index
        var after = index
        while (before > 0 && distance(points[index], points[before]) < 0.6) before--
        while (after < points.lastIndex && distance(points[index], points[after]) < 0.6) after++
        val incomingX = points[index].x - points[before].x
        val incomingY = points[index].y - points[before].y
        val outgoingX = points[after].x - points[index].x
        val outgoingY = points[after].y - points[index].y
        val magnitude = hypot(incomingX, incomingY) * hypot(outgoingX, outgoingY)
        if (magnitude < 0.0001) return 0.0
        return acos(((incomingX * outgoingX + incomingY * outgoingY) / magnitude).coerceIn(-1.0, 1.0))
    }

    private fun centerDwell(run: Run, points: List<Point>, key: Key): Double {
        var duration = 0.0
        for (index in run.start + 1..run.end) {
            if (squaredDistance(points[index - 1], key) < 0.28 * 0.28 && squaredDistance(points[index], key) < 0.28 * 0.28) {
                duration += points[index].time - points[index - 1].time
            }
        }
        return duration
    }

    private fun squaredDistance(point: Point, key: Key): Double = (point.x - key.x) * (point.x - key.x) + (point.y - key.y) * (point.y - key.y)
    private fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)

    private fun variants(kana: Char): Map<Char, Double> {
        val result = linkedMapOf(kana to 1.0)
        val voiced = "かきくけこさしすせそたちつてとはひふへほう"
        val voicedIndex = voiced.indexOf(kana)
        if (voicedIndex >= 0) result["がぎぐげござじずぜぞだぢづでどばびぶべぼゔ"[voicedIndex]] = 0.23
        val semiIndex = "はひふへほ".indexOf(kana)
        if (semiIndex >= 0) result["ぱぴぷぺぽ"[semiIndex]] = 0.10
        val smallIndex = "あいうえおつやゆよわかけ".indexOf(kana)
        if (smallIndex >= 0) result["ぁぃぅぇぉっゃゅょゎゕゖ"[smallIndex]] = 0.18
        val total = result.values.sum()
        return result.mapValues { it.value / total }
    }
}

internal fun checkInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Glide decoding was cancelled")
}
