package com.match3vision.analyzer.learning

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LearningInfraTest {
    @Test fun collect_export_noOnlineLearning() {
        val dm = DatasetManager()
        dm.collect("s1", 1L, 0, 0.9f, "B")
        assertThat(dm.sampleStore().all()).hasSize(1)
        assertThat(EvaluationDataset(dm.sampleStore()).size()).isEqualTo(1)
        assertThat(TrainingDataset(dm.sampleStore(), dm.labelStore()).labeledCount()).isEqualTo(1)
        assertThat(TrainingDataset(dm.sampleStore(), dm.labelStore()).export()).contains("samples")
    }
}
