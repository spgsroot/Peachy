package ru.spgsroot.peachy.translate

import java.util.concurrent.TimeUnit
import okhttp3.Response
import org.json.JSONObject

/** SSE probe parser: only visible content counts, never role or reasoning deltas. */
internal object ModelSpeedStream {
    fun read(response: Response, started: Long): ModelSpeed {
        val source = response.body?.source() ?: throw TranslationException(TranslationError.BadResponse)
        var first: Long? = null
        val data = StringBuilder()
        fun result(): ModelSpeed {
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            return ModelSpeed(first ?: throw TranslationException(TranslationError.Refused), elapsed, System.currentTimeMillis())
        }
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty()) {
                if (data.isEmpty()) continue
                val event = data.toString()
                data.setLength(0)
                if (event.trim() == "[DONE]") return result()
                val root = JSONObject(event)
                if (root.has("error")) throw TranslationException(TranslationError.BadResponse)
                val choices = root.optJSONArray("choices") ?: continue // usage-only event
                var finished = false
                for (i in 0 until choices.length()) {
                    val choice = choices.getJSONObject(i)
                    if (choice.optString("finish_reason") == "content_filter") throw TranslationException(TranslationError.Refused)
                    val content = choice.optJSONObject("delta")?.opt("content") as? String
                    if (first == null && !content.isNullOrEmpty()) first = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    if (!choice.isNull("finish_reason") && choice.optString("finish_reason").isNotEmpty()) finished = true
                }
                if (finished) return result()
            } else if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).removePrefix(" "))
            }
        }
        throw TranslationException(TranslationError.BadResponse)
    }
}
