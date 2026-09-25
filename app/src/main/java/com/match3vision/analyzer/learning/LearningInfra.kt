package com.match3vision.analyzer.learning

/**
 * Collect/export only — NO online self-modifying model.
 */
data class Sample(val id: String, val features: Map<String, Float>, val label: String?)
data class FeatureVector(val values: Map<String, Float>)

class SampleStore {
    private val samples = mutableListOf<Sample>()
    fun add(sample: Sample) { samples += sample }
    fun all(): List<Sample> = samples.toList()
    fun exportJson(): String = samples.joinToString(prefix = "[", postfix = "]") {
        """{"id":"${it.id}","label":${it.label?.let { l -> "\"$l\"" } ?: "null"}}"""
    }
}

class LabelStore {
    private val labels = mutableMapOf<String, String>()
    fun put(id: String, label: String) { labels[id] = label }
    fun get(id: String): String? = labels[id]
    fun exportJson(): String = labels.entries.joinToString(prefix = "{", postfix = "}") {
        """"${it.key}":"${it.value}""""
    }
}

class FeatureExtractor {
    fun extract(boardHash: Long, unknownCount: Int, confidence: Float): FeatureVector =
        FeatureVector(
            mapOf(
                "boardHash" to boardHash.toFloat(),
                "unknownCount" to unknownCount.toFloat(),
                "confidence" to confidence,
            ),
        )
}

class DatasetManager(
    private val samples: SampleStore = SampleStore(),
    private val labels: LabelStore = LabelStore(),
    private val features: FeatureExtractor = FeatureExtractor(),
) {
    fun collect(id: String, boardHash: Long, unknownCount: Int, confidence: Float, label: String? = null) {
        val fv = features.extract(boardHash, unknownCount, confidence)
        samples.add(Sample(id, fv.values, label))
        if (label != null) labels.put(id, label)
    }
    fun sampleStore() = samples
    fun labelStore() = labels
}

class EvaluationDataset(private val store: SampleStore) {
    fun size() = store.all().size
    fun export() = store.exportJson()
}

class TrainingDataset(private val store: SampleStore, private val labels: LabelStore) {
    fun labeledCount() = store.all().count { it.label != null || labels.get(it.id) != null }
    fun export() = """{"samples":${store.exportJson()},"labels":${labels.exportJson()}}"""
}
