package app.ledgerly.export

import android.content.Context
import android.net.Uri
import app.ledgerly.core.model.Transaction
import app.ledgerly.data.db.LedgerlyDatabase
import app.ledgerly.data.mapper.toModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * CSV / JSON export (DPDP / GDPR data portability) and password-encrypted local backup.
 * Files are written to a user-picked location via the Storage Access Framework.
 */
class Exporter(private val context: Context, private val db: LedgerlyDatabase) {

    suspend fun exportCsv(uri: Uri): Int = withContext(Dispatchers.IO) {
        val txns = loadAll()
        val cats = db.categoryDao().getAll().associateBy { it.id }
        open(uri) { out ->
            val w = out.bufferedWriter(Charsets.UTF_8)
            w.write(CsvFormat.header)
            w.newLine()
            for (t in txns) {
                w.write(CsvFormat.row(t, cats[t.categoryId]?.name))
                w.newLine()
            }
            w.flush()
        }
        txns.size
    }

    suspend fun exportJson(uri: Uri): Int = withContext(Dispatchers.IO) {
        val json = buildJson()
        open(uri) { out -> out.write(json.toString(2).toByteArray(Charsets.UTF_8)) }
        json.getJSONArray("transactions").length()
    }

    /**
     * Encrypted backup: JSON export sealed with AES-256-GCM, key derived from [password] with
     * PBKDF2-HMAC-SHA256 (210k iterations). Layout: "LEDGERLY1" | salt(16) | iv(12) | ciphertext.
     * TODO: restore flow (decrypt + re-import) is not implemented yet.
     */
    suspend fun exportEncryptedBackup(uri: Uri, password: CharArray): Int = withContext(Dispatchers.IO) {
        val json = buildJson()
        val plain = json.toString().toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password, salt, 210_000, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain)
        password.fill('\u0000')
        open(uri) { out ->
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            out.write(salt)
            out.write(iv)
            out.write(ct)
        }
        json.getJSONArray("transactions").length()
    }

    private suspend fun loadAll(): List<Transaction> {
        val dao = db.transactionDao()
        return dao.getAllActive().map { e -> e.toModel(db.splitDao().getForTxn(e.id), db.sourceEventDao().idsForTxn(e.id)) }
    }

    private suspend fun buildJson(): JSONObject {
        val root = JSONObject()
        root.put("format", "ledgerly-export")
        root.put("version", 1)
        root.put("exportedAt", java.time.Instant.now().toString())
        root.put("transactions", JSONArray().apply { loadAll().forEach { put(txnJson(it)) } })
        root.put("categories", JSONArray().apply {
            db.categoryDao().getAll().forEach { c ->
                put(JSONObject().put("id", c.id).put("name", c.name).put("parentId", c.parentId ?: JSONObject.NULL).put("isIncome", c.isIncome))
            }
        })
        root.put("accounts", JSONArray().apply {
            db.accountDao().getAll().forEach { a ->
                put(JSONObject().put("id", a.id).put("name", a.name).put("type", a.type.name).put("currency", a.currency).put("institution", a.institution ?: JSONObject.NULL))
            }
        })
        root.put("auditLog", JSONArray().apply {
            db.transactionEventDao().getAll().forEach { e ->
                put(JSONObject().put("txnId", e.transactionId).put("type", e.type.name).put("field", e.field ?: JSONObject.NULL)
                    .put("old", e.oldValue ?: JSONObject.NULL).put("new", e.newValue ?: JSONObject.NULL).put("actor", e.actor.name).put("at", e.at.toString()))
            }
        })
        return root
    }

    private fun txnJson(t: Transaction): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("occurredAt", t.occurredAt.toString())
        put("type", t.type.name)
        put("status", t.status.name)
        put("original", moneyJson(t.original))
        put("account", moneyJson(t.account))
        put("base", moneyJson(t.base))
        put("fxRateApplied", t.fxRateApplied?.toPlainString() ?: JSONObject.NULL)
        put("marketRate", t.marketRate?.toPlainString() ?: JSONObject.NULL)
        put("rateSource", t.rateSource ?: JSONObject.NULL)
        put("rateDate", t.rateDate?.toString() ?: JSONObject.NULL)
        put("fxFee", t.fxFee?.let { moneyJson(it) } ?: JSONObject.NULL)
        put("merchant", t.merchant ?: JSONObject.NULL)
        put("categoryId", t.categoryId ?: JSONObject.NULL)
        put("accountId", t.accountId ?: JSONObject.NULL)
        put("tags", JSONArray(t.tags.toList()))
        put("note", t.note ?: JSONObject.NULL)
        put("reference", t.reference ?: JSONObject.NULL)
        put("tripId", t.tripId ?: JSONObject.NULL)
        put("sources", JSONArray(t.sourceChannels.map { it.name }))
        put("splits", JSONArray().apply { t.splits.forEach { s -> put(JSONObject().put("categoryId", s.categoryId).put("amount", moneyJson(s.amount)).put("note", s.note ?: JSONObject.NULL)) } })
    }

    private fun moneyJson(m: app.ledgerly.core.model.Money) = JSONObject().put("minor", m.minor).put("currency", m.currency.code)

    private inline fun open(uri: Uri, block: (OutputStream) -> Unit) {
        val stream = context.contentResolver.openOutputStream(uri, "w") ?: error("Cannot open $uri")
        stream.use(block)
    }

    companion object {
        private const val MAGIC = "LEDGERLY1"
    }
}

/** Pure CSV formatting (RFC 4180 quoting). */
object CsvFormat {
    const val header = "id,date,type,amount,currency,account_amount,account_currency,base_amount,base_currency,fx_fee,fx_fee_currency,merchant,category,tags,note,sources"

    fun row(t: Transaction, categoryName: String?): String = listOf(
        t.id,
        t.occurredAt.toString(),
        t.type.name,
        t.original.toDecimal().toPlainString(),
        t.original.currency.code,
        t.account.toDecimal().toPlainString(),
        t.account.currency.code,
        t.base.toDecimal().toPlainString(),
        t.base.currency.code,
        t.fxFee?.toDecimal()?.toPlainString().orEmpty(),
        t.fxFee?.currency?.code.orEmpty(),
        t.merchant.orEmpty(),
        categoryName.orEmpty(),
        t.tags.sorted().joinToString(";"),
        t.note.orEmpty(),
        t.sourceChannels.joinToString("+") { it.name },
    ).joinToString(",") { escape(it) }

    fun escape(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
