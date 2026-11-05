package app.ledgerly.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * On-device LLM backend (Gemini Nano). Used for (a) parser fallback on messages the template and
 * heuristic engines cannot read, and (b) phrasing Ask answers. Nothing ever leaves the device.
 *
 * Integration path (TODO(llm)):
 *  - Gemini Nano is served by the AICore system service (package "com.google.android.aicore") on
 *    supported devices (Pixel 8+/9, Galaxy S24+ and others; Android 14+).
 *  - The supported client is the ML Kit GenAI APIs (Prompt API: com.google.mlkit:genai-prompt, still
 *    beta). Add the dependency, then in [isAvailable] call the client's checkStatus()/feature-status
 *    check (AVAILABLE / DOWNLOADABLE / DOWNLOADING / UNAVAILABLE) and in [generate] call
 *    generateContent(prompt) on Dispatchers.Default with a short timeout.
 *  - Until then this backend reports availability conservatively and always returns null, so callers
 *    fall back to the heuristic engines.
 */
class GeminiNanoLlmBackend(private val context: Context) : LlmBackend {

    @Volatile private var cachedAvailability: Boolean? = null

    override suspend fun isAvailable(): Boolean {
        cachedAvailability?.let { return it }
        val available = Build.VERSION.SDK_INT >= 34 && isAiCoreInstalled() && ML_KIT_GENAI_LINKED
        cachedAvailability = available
        return available
    }

    override suspend fun generate(prompt: String): String? {
        if (!isAvailable()) return null
        // TODO(llm): GenerativeModel.generateContent(prompt).text via ML Kit GenAI Prompt API.
        return null
    }

    private fun isAiCoreInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(AICORE_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        const val AICORE_PACKAGE = "com.google.android.aicore"
        /** Flip to true once the ML Kit GenAI dependency and calls above are added. */
        private const val ML_KIT_GENAI_LINKED = false
    }
}

/** Uses the LLM to extract a transaction as JSON when the regular parser fails. */
class LlmParserFallback(private val llm: LlmBackend) {

    suspend fun extract(event: app.ledgerly.core.model.SourceEvent): app.ledgerly.core.model.ParsedTransaction? {
        if (!llm.isAvailable()) return null
        val prompt = """
            Extract the financial transaction from this message. Reply with JSON only:
            {"isTransaction":bool,"direction":"DEBIT"|"CREDIT","amount":"123.45","currency":"ISO4217","merchant":string|null,"last4":string|null}
            Message: ${event.title.orEmpty()} ${event.body}
        """.trimIndent()
        val raw = llm.generate(prompt) ?: return null
        return try {
            val json = org.json.JSONObject(raw.substringAfter('{', "").let { "{$it" }.substringBeforeLast('}') + "}")
            if (!json.optBoolean("isTransaction", false)) return null
            val currency = app.ledgerly.core.model.CurrencyCode.of(json.getString("currency"))
            val amount = app.ledgerly.core.model.Money.of(json.getString("amount"), currency)
            val direction = if (json.optString("direction") == "CREDIT") app.ledgerly.core.model.TxnDirection.CREDIT else app.ledgerly.core.model.TxnDirection.DEBIT
            app.ledgerly.core.model.ParsedTransaction(
                sourceEventId = event.id,
                channel = event.channel,
                kind = if (direction == app.ledgerly.core.model.TxnDirection.CREDIT) app.ledgerly.core.model.MessageKind.CREDIT else app.ledgerly.core.model.MessageKind.DEBIT,
                direction = direction,
                amount = amount,
                accountLast4 = json.optString("last4").takeIf { it.length == 4 },
                merchantRaw = json.optString("merchant").takeIf { it.isNotBlank() && it != "null" },
                occurredAt = event.receivedAt,
                confidence = 0.6,
                parserId = "llm-gemini-nano",
                // LLM output always goes through the review inbox.
                status = app.ledgerly.core.model.ParseStatus.NEEDS_REVIEW,
            )
        } catch (e: Exception) {
            null
        }
    }
}
