package ru.spgsroot.peachy.translate

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class ModelVision { Yes, No, Unknown }
enum class ModelSort { Price, Speed, Name }

/** Prices are USD per million tokens, including the multiplier at catalog fetch time. */
data class ProviderModel(
    val id: String,
    val name: String = id,
    val vision: ModelVision = ModelVision.Unknown,
    val inputPrice: Double? = null,
    val outputPrice: Double? = null,
    val hasPeakPricing: Boolean = false,
    val multiplier: Double = 1.0
)

data class ModelSpeed(val firstTokenMs: Long, val totalMs: Long, val measuredAt: Long)

object ProviderModels {
    fun parseOpenCode(raw: String, at: Instant): List<ProviderModel> = parse(raw) { item ->
        val cost = item.optJSONObject("cost")
        val rules = cost?.optJSONArray("multipliers")
        val factor = try {
            var value = 1.0
            if (rules != null) for (i in 0 until rules.length()) {
                val rule = rules.getJSONObject(i)
                val multiplier = price(rule.opt("factor"))?.takeIf { it > 0 } ?: throw JSONException("factor")
                if (matches(rule.getJSONObject("when"), at)) value *= multiplier
            }
            value.takeIf { it.isFinite() }
        } catch (_: JSONException) { null }
        val text = cost?.optJSONObject("text")
        ProviderModel(
            item.getString("id"), item.optString("name", item.getString("id")),
            vision(item.optJSONObject("modalities")?.optJSONArray("input")),
            factor?.let { price(text?.optJSONObject("input")?.opt("price"))?.times(it) },
            factor?.let { price(text?.optJSONObject("output")?.opt("price"))?.times(it) },
            rules != null && rules.length() > 0, factor ?: 1.0
        )
    }

    fun parseOpenRouter(raw: String): List<ProviderModel> = parse(raw) { item ->
        val pricing = item.optJSONObject("pricing")
        ProviderModel(
            item.getString("id"), item.optString("name", item.getString("id")),
            vision(item.optJSONObject("architecture")?.optJSONArray("input_modalities")),
            price(pricing?.opt("prompt"))?.times(1_000_000),
            price(pricing?.opt("completion"))?.times(1_000_000)
        )
    }

    fun parseGeneric(raw: String): List<ProviderModel> = parse(raw) { item ->
        ProviderModel(item.getString("id"), item.optString("name", item.getString("id")),
            vision(item.optJSONObject("modalities")?.optJSONArray("input")))
    }

    fun visible(models: List<ProviderModel>, imagesOnly: Boolean, sort: ModelSort, speeds: Map<String, ModelSpeed>): List<ProviderModel> {
        val byName = compareBy<ProviderModel> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id }
        val comparator = when (sort) {
            ModelSort.Name -> byName
            ModelSort.Speed -> compareBy<ProviderModel> { speeds[it.id]?.firstTokenMs ?: Long.MAX_VALUE }.then(byName)
            ModelSort.Price -> compareBy<ProviderModel> {
                if (it.inputPrice != null && it.outputPrice != null) it.inputPrice + it.outputPrice else Double.POSITIVE_INFINITY
            }.thenBy { if (it.outputPrice != null) it.inputPrice ?: Double.POSITIVE_INFINITY else Double.POSITIVE_INFINITY }
                .thenBy { it.outputPrice ?: Double.POSITIVE_INFINITY }.then(byName)
        }
        return models.filter { !imagesOnly || it.vision != ModelVision.No }.sortedWith(comparator)
    }

    private fun price(value: Any?): Double? = value?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

    private fun vision(inputs: JSONArray?): ModelVision {
        if (inputs == null || inputs.length() == 0) return ModelVision.Unknown
        var known = false
        for (i in 0 until inputs.length()) {
            val input = inputs.opt(i) as? String ?: continue
            if (input == "image" || input.startsWith("image/")) return ModelVision.Yes
            known = true
        }
        return if (known) ModelVision.No else ModelVision.Unknown
    }

    private fun parse(raw: String, model: (JSONObject) -> ProviderModel): List<ProviderModel> {
        val data = JSONObject(raw).getJSONArray("data")
        return buildList {
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                if (item.opt("id") !is String || item.getString("id").isBlank()) throw JSONException("model id")
                add(model(item))
            }
        }.distinctBy { it.id }
    }

    /** Unknown conditions are not guessed: the caller marks the price unknown. */
    private fun matches(condition: JSONObject, at: Instant): Boolean {
        if (condition.length() == 0) throw JSONException("empty condition")
        val utc = at.atOffset(ZoneOffset.UTC)
        var result = true
        for (key in condition.keys()) {
            val match = when (key) {
                "utc_days" -> {
                    val days = condition.getJSONObject(key)
                    if (days.length() != 1) throw JSONException("days")
                    val allowed = days.getJSONArray("in")
                    val today = utc.dayOfWeek.name.take(3).lowercase(Locale.ROOT)
                    (0 until allowed.length()).map { allowed.getString(it).also { day ->
                        if (day !in listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")) throw JSONException("day")
                    } }.contains(today)
                }
                "utc_hours" -> {
                    val hours = condition.getJSONObject(key)
                    if (hours.length() == 0) throw JSONException("hours")
                    var inRange = true
                    for (operator in hours.keys()) {
                        val time = try { LocalTime.parse(hours.getString(operator)) } catch (_: RuntimeException) { throw JSONException("time") }
                        val yes = when (operator) {
                            "gte" -> utc.toLocalTime() >= time
                            "lt" -> utc.toLocalTime() < time
                            else -> throw JSONException("hour operator")
                        }
                        inRange = inRange && yes
                    }
                    inRange
                }
                "any", "all" -> {
                    val conditions = condition.getJSONArray(key)
                    if (conditions.length() == 0) throw JSONException("conditions")
                    // Evaluate every branch to detect unsupported conditions even outside their window.
                    val values = (0 until conditions.length()).map { matches(conditions.getJSONObject(it), at) }
                    if (key == "any") values.any { it } else values.all { it }
                }
                else -> throw JSONException("condition")
            }
            result = result && match
        }
        return result
    }
}
