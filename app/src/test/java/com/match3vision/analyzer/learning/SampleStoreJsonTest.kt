package com.match3vision.analyzer.learning

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.hud.Json
import org.junit.Test

class SampleStoreJsonTest {
    @Test
    fun roundTrip_keepsQuotesBackslashesNewlinesAndTheFullHash() {
        val store = SampleStore()
        store.add(
            Sample(
                id = "id \"q\" \\ \n",
                features = linkedMapOf(
                    "unknownCount" to 3f,
                    "confidence" to 0.25f,
                    "extra" to 7f,
                ),
                label = "lab\"el\n\\",
                boardHash = Long.MIN_VALUE,
            ),
        )
        store.add(
            Sample(
                id = "plain",
                features = mapOf("unknownCount" to 1f, "confidence" to 1f),
                label = null,
                boardHash = Long.MAX_VALUE,
            ),
        )
        val json = store.exportJson()
        assertThat(json).contains(""""boardHash":"-9223372036854775808"""")
        assertThat(json).contains(""""boardHash":"9223372036854775807"""")

        val rows = Json.parse(json) as Json.Arr
        val first = rows.items[0] as Json.Obj
        assertThat(first.string("id")).isEqualTo("id \"q\" \\ \n")
        assertThat(first.string("label")).isEqualTo("lab\"el\n\\")
        assertThat(first.string("boardHash").toLong()).isEqualTo(Long.MIN_VALUE)
        val features = first.fields["features"] as Json.Obj
        assertThat(features.fields.keys).containsAtLeast("unknownCount", "confidence", "extra")
        assertThat((features.fields["confidence"] as Json.Num).raw).isEqualTo("0.25")

        val second = rows.items[1] as Json.Obj
        assertThat(second.fields["label"]).isEqualTo(Json.Null)
        assertThat(second.string("boardHash").toLong()).isEqualTo(Long.MAX_VALUE)
    }
}
