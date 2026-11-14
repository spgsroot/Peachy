package ru.spgsroot.peachy.translate

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ProviderModelsTest {
    private val schedule = """[{"factor":2,"when":{"utc_days":{"in":["mon","tue","wed","thu","fri"]},"any":[{"all":[{"utc_hours":{"gte":"01:00"}},{"utc_hours":{"lt":"04:00"}}]},{"all":[{"utc_hours":{"gte":"06:00"}},{"utc_hours":{"lt":"10:00"}}]}]}}]"""
    private fun go(time: String) = ProviderModels.parseOpenCode("""{"data":[{"id":"flash","name":"Flash","modalities":{"input":["text","image/jpeg"]},"cost":{"text":{"input":{"price":0.15},"output":{"price":0.6}},"multipliers":$schedule}}]}""", Instant.parse(time)).single()

    @Test fun openCodeUsesMillionTokenPricesAndUtcBoundaries() {
        listOf(
            "2026-11-16T00:59:59Z" to 0.15,
            "2026-11-16T01:00:00Z" to 0.30,
            "2026-11-16T03:59:59Z" to 0.30,
            "2026-11-16T04:00:00Z" to 0.15,
            "2026-11-16T06:00:00Z" to 0.30,
            "2026-11-16T10:00:00Z" to 0.15,
            "2026-11-15T02:00:00Z" to 0.15
        ).forEach { (time, price) ->
            val model = go(time)
            assertEquals(price, model.inputPrice!!, 0.000001)
            assertEquals(price * 4, model.outputPrice!!, 0.000001)
            assertEquals(ModelVision.Yes, model.vision)
            assertTrue(model.hasPeakPricing)
        }
        assertEquals(2.0, go("2026-11-16T01:00:00Z").multiplier, 0.0)
    }

    @Test fun openRouterNormalizesStringPricesAndKeepsUnknownModalities() {
        val models = ProviderModels.parseOpenRouter("""{"data":[
            {"id":"a/vision","name":"Vision","architecture":{"input_modalities":["text","image"]},"pricing":{"prompt":"0.0000015","completion":"0.000006"}},
            {"id":"a/text","architecture":{"input_modalities":["text"]},"pricing":{"prompt":"0","completion":"0"}},
            {"id":"a/unknown","pricing":{"prompt":"-1","completion":"garbage"}}
        ]}""")
        assertEquals(1.5, models[0].inputPrice!!, 0.0)
        assertEquals(6.0, models[0].outputPrice!!, 0.0)
        assertEquals(ModelVision.No, models[1].vision)
        assertEquals(ModelVision.Unknown, models[2].vision)
        assertNull(models[2].inputPrice)
        assertNull(models[2].outputPrice)
        assertEquals(listOf("a/unknown", "a/vision"), ProviderModels.visible(models, true, ModelSort.Name, emptyMap()).map { it.id })
        assertEquals(3, ProviderModels.visible(models, false, ModelSort.Name, emptyMap()).size)
    }

    @Test fun missingPricesAndIncompletePricePairsSortLast() {
        val models = listOf(
            ProviderModel("unknown"), ProviderModel("partial", inputPrice = 0.0),
            ProviderModel("output-heavy", inputPrice = 0.1, outputPrice = 8.0),
            ProviderModel("cheap", inputPrice = 1.0, outputPrice = 2.0),
            ProviderModel("free", inputPrice = 0.0, outputPrice = 0.0)
        )
        assertEquals(listOf("free", "cheap", "output-heavy", "partial", "unknown"), ProviderModels.visible(models, false, ModelSort.Price, emptyMap()).map { it.id })
        val speeds = mapOf("cheap" to ModelSpeed(120, 300, 1), "unknown" to ModelSpeed(60, 200, 1))
        assertEquals(listOf("unknown", "cheap", "free", "output-heavy", "partial"), ProviderModels.visible(models, false, ModelSort.Speed, speeds).map { it.id })
    }

    @Test fun unsupportedMultiplierDoesNotClaimKnownPrice() {
        val model = ProviderModels.parseOpenCode("""{"data":[{"id":"a","cost":{"text":{"input":{"price":1},"output":{"price":2}},"multipliers":[{"factor":2,"when":{"future_condition":true}}]}}]}""", Instant.EPOCH).single()
        assertNull(model.inputPrice)
        assertTrue(model.hasPeakPricing)
    }

    @Test fun genericCatalogDoesNotInventPriceOrVision() {
        val model = ProviderModels.parseGeneric("""{"data":[{"id":"gpt-vision"}]}""").single()
        assertEquals(ModelVision.Unknown, model.vision)
        assertNull(model.inputPrice)
        assertNull(model.outputPrice)
    }

    @Test fun malformedCatalogIsRejectedInsteadOfAnEmptySuccess() {
        listOf("{}", "[]", "{bad}", """{"data":[{}]}""").forEach { body ->
            try { ProviderModels.parseGeneric(body); fail(body) } catch (_: org.json.JSONException) { }
        }
        assertTrue(ProviderModels.parseGeneric("""{"data":[]}""").isEmpty())
    }
}
