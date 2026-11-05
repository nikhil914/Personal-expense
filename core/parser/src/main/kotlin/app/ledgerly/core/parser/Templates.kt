package app.ledgerly.core.parser

import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.TxnDirection

/**
 * A data-driven parsing template.
 *
 * [pattern] is a Java regex (matched case-insensitively with `find()` against whitespace-normalised
 * text; notification title and body are joined as "title | body"). Supported named groups:
 * `amount`, `currency`, `last4`, `merchant`, `ref`, `balance`, `balcur`, `date`, `accountAmount`,
 * `acur`, `dueDate`, `minDue`, `mincur`.
 *
 * Macros expanded before compilation (see [TemplateMacros]): `{AMT} {CUR} {L4} {DATE} {DUE}
 * {MERCHANT} {REF} {BAL} {ACUR} {AAMT} {MCUR} {MIN} {END}`.
 *
 * [senders] are case-insensitive regexes tested against the normalised sender key (DLT header,
 * package name, email domain) and the resolved institution name. Empty = any sender.
 */
data class ParserTemplate(
    val id: String,
    val institution: String?,
    val pattern: String,
    val senders: List<String> = emptyList(),
    /** Fixed kind; null lets the classifier decide (e.g. deposit -> SALARY vs CREDIT). */
    val kind: MessageKind? = null,
    val direction: TxnDirection? = null,
    /** ISO code used when the template has no currency group. Null = parser default currency. */
    val currency: String? = null,
    val confidence: Double = 0.95,
    /** Restrict to channels; empty = all. */
    val channels: Set<Channel> = emptySet(),
    val learned: Boolean = false,
    /** Example message, kept for regression tests / remote validation. */
    val sample: String? = null,
)

/** Versioned set of templates, serialisable for remote updates. */
data class TemplateLibrary(
    val version: Int,
    val templates: List<ParserTemplate>,
    val updatedAt: String? = null,
) {
    companion object {
        const val FORMAT = "ledgerly.parser.templates"
        const val FORMAT_VERSION = 1
    }
}

/** Regex macros shared by built-in, remote and learned templates. */
object TemplateMacros {
    private const val TIME = "(?:,?\\s*(?:at\\s*)?\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[AaPp]\\.?[Mm]\\.?)?)?"
    private const val DATE_BODY =
        "(?:\\d{4}-\\d{1,2}-\\d{1,2}(?::\\d{2}:\\d{2}(?::\\d{2})?)?" +
            "|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}" +
            "|\\d{1,2}(?:st|nd|rd|th)?[- /]?[A-Za-z]{3,9}\\.?(?:[- /,]*(?:\\d{4}|\\d{2}(?![\\d:])))?" +
            "|[A-Za-z]{3,9}\\.? \\d{1,2}(?:st|nd|rd|th)?,? \\d{4})" + TIME

    private val CUR_TOKEN = Currencies.PREFIX_TOKEN

    val MACROS: Map<String, String> = linkedMapOf(
        "{AMT}" to "(?<amount>${AmountParser.NUMBER})",
        "{CUR}" to "(?<currency>$CUR_TOKEN)",
        "{L4}" to "[xX*•.]*\\s?\\d*?(?<last4>\\d{3,4})(?!\\d)",
        "{DATE}" to "(?<date>$DATE_BODY)",
        "{DUE}" to "(?<dueDate>$DATE_BODY)",
        "{MERCHANT}" to "(?<merchant>.+?)",
        "{REF}" to "(?<ref>[A-Za-z0-9]*\\d[A-Za-z0-9]*)",
        "{BAL}" to "(?<balcur>$CUR_TOKEN)?\\s?(?<balance>${AmountParser.NUMBER})",
        "{ACUR}" to "(?<acur>$CUR_TOKEN)",
        "{AAMT}" to "(?<accountAmount>${AmountParser.NUMBER})",
        "{MCUR}" to "(?<mincur>$CUR_TOKEN)",
        "{MIN}" to "(?<minDue>${AmountParser.NUMBER})",
        "{END}" to "(?=\\s*(?:[.!|,;](?:\\s|$)|$|\\s(?:on|using|with|via|ref)\\b))",
    )

    fun expand(pattern: String): String {
        var p = pattern
        for ((k, v) in MACROS) p = p.replace(k, v)
        return p
    }
}

/** Hand-written JSON codec for [TemplateLibrary] (no external dependencies). */
object TemplateCodec {

    fun encode(library: TemplateLibrary, pretty: Boolean = true): String {
        val root = linkedMapOf<String, Any?>(
            "format" to TemplateLibrary.FORMAT,
            "formatVersion" to TemplateLibrary.FORMAT_VERSION,
            "version" to library.version,
            "updatedAt" to library.updatedAt,
            "templates" to library.templates.map { encodeTemplate(it) },
        )
        return MiniJson.stringify(root, if (pretty) "  " else null)
    }

    fun encodeTemplate(t: ParserTemplate): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        put("id", t.id)
        put("institution", t.institution)
        put("pattern", t.pattern)
        if (t.senders.isNotEmpty()) put("senders", t.senders)
        t.kind?.let { put("kind", it.name) }
        t.direction?.let { put("direction", it.name) }
        t.currency?.let { put("currency", it) }
        put("confidence", t.confidence)
        if (t.channels.isNotEmpty()) put("channels", t.channels.map { it.name })
        if (t.learned) put("learned", true)
        t.sample?.let { put("sample", it) }
    }

    /**
     * Decodes a library. Invalid templates (bad regex, unknown enum, missing fields) are skipped
     * rather than failing the whole update; use [decodeStrict] to fail instead.
     */
    fun decode(json: String): TemplateLibrary = decodeInternal(json, strict = false)

    fun decodeStrict(json: String): TemplateLibrary = decodeInternal(json, strict = true)

    private fun decodeInternal(json: String, strict: Boolean): TemplateLibrary {
        val root = MiniJson.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("Root must be an object")
        val fv = (root["formatVersion"] as? Number)?.toInt() ?: TemplateLibrary.FORMAT_VERSION
        require(fv <= TemplateLibrary.FORMAT_VERSION) { "Unsupported template format version $fv" }
        val version = (root["version"] as? Number)?.toInt() ?: throw IllegalArgumentException("Missing version")
        val list = root["templates"] as? List<*> ?: throw IllegalArgumentException("Missing templates")
        val templates = list.mapNotNull { item ->
            try {
                decodeTemplate(item as? Map<*, *> ?: throw IllegalArgumentException("Template must be an object"))
            } catch (e: Exception) {
                if (strict) throw IllegalArgumentException("Invalid template: ${e.message}", e) else null
            }
        }
        return TemplateLibrary(version, templates, root["updatedAt"] as? String)
    }

    fun decodeTemplate(m: Map<*, *>): ParserTemplate {
        val id = m["id"] as? String ?: throw IllegalArgumentException("Missing id")
        val pattern = m["pattern"] as? String ?: throw IllegalArgumentException("Missing pattern for $id")
        Regex(TemplateMacros.expand(pattern)) // validate
        return ParserTemplate(
            id = id,
            institution = m["institution"] as? String,
            pattern = pattern,
            senders = (m["senders"] as? List<*>)?.map { it.toString() } ?: emptyList(),
            kind = (m["kind"] as? String)?.let { MessageKind.valueOf(it) },
            direction = (m["direction"] as? String)?.let { TxnDirection.valueOf(it) },
            currency = m["currency"] as? String,
            confidence = (m["confidence"] as? Number)?.toDouble()?.coerceIn(0.0, 1.0) ?: 0.9,
            channels = (m["channels"] as? List<*>)?.map { Channel.valueOf(it.toString()) }?.toSet() ?: emptySet(),
            learned = m["learned"] as? Boolean ?: false,
            sample = m["sample"] as? String,
        )
    }
}
