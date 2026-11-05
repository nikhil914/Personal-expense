package app.ledgerly.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY name")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY name")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE type = 'CASH' AND archived = 0 LIMIT 1")
    suspend fun cashAccount(): AccountEntity?

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(account: AccountEntity)
}

@Dao
interface SubBalanceDao {
    @Query("SELECT * FROM sub_balances WHERE accountId = :accountId ORDER BY currency")
    fun observeForAccount(accountId: String): Flow<List<SubBalanceEntity>>

    @Upsert
    suspend fun upsert(subBalance: SubBalanceEntity)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL ORDER BY occurredAt DESC")
    fun observeActive(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL ORDER BY occurredAt DESC")
    suspend fun getAllActive(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observeById(id: String): Flow<TransactionEntity?>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL AND occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt DESC")
    fun observeInRange(from: Instant, to: Instant): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL AND occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt DESC")
    suspend fun getInRange(from: Instant, to: Instant): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL AND reviewState != 'OK' ORDER BY occurredAt DESC")
    fun observeNeedingReview(): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions WHERE deletedAt IS NULL AND reviewState != 'OK'")
    fun observeReviewCount(): Flow<Int>

    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL AND tripId = :tripId ORDER BY occurredAt DESC")
    fun observeForTrip(tripId: String): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions WHERE deletedAt IS NULL AND originalCurrency = :currency AND occurredAt >= :since AND id != :excludeId")
    suspend fun countInCurrencySince(currency: String, since: Instant, excludeId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(txn: TransactionEntity)

    @Update
    suspend fun update(txn: TransactionEntity)

    @Query("UPDATE transactions SET deletedAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Instant)

    @Query("UPDATE transactions SET deletedAt = NULL, updatedAt = :at WHERE id = :id")
    suspend fun restore(id: String, at: Instant)
}

@Dao
abstract class SplitDao {
    @Query("SELECT * FROM transaction_splits WHERE transactionId = :txnId ORDER BY id")
    abstract fun observeForTxn(txnId: String): Flow<List<TransactionSplitEntity>>

    @Query("SELECT * FROM transaction_splits WHERE transactionId = :txnId ORDER BY id")
    abstract suspend fun getForTxn(txnId: String): List<TransactionSplitEntity>

    @Query("DELETE FROM transaction_splits WHERE transactionId = :txnId")
    abstract suspend fun deleteForTxn(txnId: String)

    @Insert
    abstract suspend fun insertAll(splits: List<TransactionSplitEntity>)

    @Transaction
    open suspend fun replaceForTxn(txnId: String, splits: List<TransactionSplitEntity>) {
        deleteForTxn(txnId)
        if (splits.isNotEmpty()) insertAll(splits)
    }
}

@Dao
interface SourceEventDao {
    /** Returns -1 when a message with the same content hash was already captured. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: SourceEventEntity): Long

    @Update
    suspend fun update(event: SourceEventEntity)

    @Query("SELECT * FROM source_events WHERE id = :id")
    suspend fun getById(id: String): SourceEventEntity?

    @Query("SELECT * FROM source_events WHERE processed = 0 ORDER BY receivedAt ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<SourceEventEntity>

    @Query("SELECT * FROM source_events WHERE linkedTxnId = :txnId ORDER BY receivedAt ASC")
    fun observeForTxn(txnId: String): Flow<List<SourceEventEntity>>

    @Query("SELECT * FROM source_events WHERE linkedTxnId = :txnId")
    suspend fun getForTxn(txnId: String): List<SourceEventEntity>

    @Query("SELECT id FROM source_events WHERE linkedTxnId = :txnId")
    suspend fun idsForTxn(txnId: String): List<String>

    @Query(
        "SELECT * FROM source_events WHERE processed = 1 AND body IS NOT NULL AND parseStatus IN " +
            "('IGNORED_NOT_FINANCIAL','IGNORED_OTP','IGNORED_PROMO','FAILED','INFO_ONLY') ORDER BY receivedAt DESC LIMIT 500",
    )
    fun observeIgnored(): Flow<List<SourceEventEntity>>

    @Query("UPDATE source_events SET linkedTxnId = :toTxnId WHERE linkedTxnId = :fromTxnId")
    suspend fun relink(fromTxnId: String, toTxnId: String)

    @Query("UPDATE source_events SET processed = 0, error = NULL WHERE id = :id")
    suspend fun markForReprocess(id: String)

    /** Retention: drop raw text of old messages but keep the row (metadata + link) for the audit trail. */
    @Query("UPDATE source_events SET body = NULL, title = NULL, purgedAt = :now WHERE body IS NOT NULL AND processed = 1 AND receivedAt < :cutoff")
    suspend fun purgeBodiesBefore(cutoff: Instant, now: Instant): Int

    /** Non-financial captures carry no audit value: delete them outright once processed. */
    @Query("DELETE FROM source_events WHERE processed = 1 AND linkedTxnId IS NULL AND parseStatus IN ('IGNORED_NOT_FINANCIAL','IGNORED_OTP','IGNORED_PROMO') AND receivedAt < :cutoff")
    suspend fun deleteNonFinancialBefore(cutoff: Instant): Int

    @Query("DELETE FROM source_events WHERE id = :id")
    suspend fun delete(id: String)
}

/** Append-only: intentionally no @Update / @Delete / UPDATE / DELETE queries. */
@Dao
interface TransactionEventDao {
    @Insert
    suspend fun insert(event: TransactionEventEntity): Long

    @Insert
    suspend fun insertAll(events: List<TransactionEventEntity>)

    @Query("SELECT * FROM transaction_events WHERE transactionId = :txnId ORDER BY at ASC, id ASC")
    fun observeForTxn(txnId: String): Flow<List<TransactionEventEntity>>

    @Query("SELECT * FROM transaction_events ORDER BY id ASC")
    suspend fun getAll(): List<TransactionEventEntity>
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE archived = 0 ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Upsert
    suspend fun upsert(category: CategoryEntity)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules WHERE enabled = 1 ORDER BY priority ASC, id DESC")
    suspend fun enabledByPriority(): List<RuleEntity>

    @Query("SELECT * FROM rules ORDER BY priority ASC, id DESC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Insert
    suspend fun insert(rule: RuleEntity): Long

    @Delete
    suspend fun delete(rule: RuleEntity)
}

@Dao
interface MerchantDao {
    @Query("SELECT * FROM merchants")
    suspend fun getAll(): List<MerchantEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(merchants: List<MerchantEntity>)

    @Upsert
    suspend fun upsert(merchant: MerchantEntity)
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets WHERE active = 1 AND period = 'MONTHLY' AND tripId IS NULL LIMIT 1")
    fun observeActiveMonthly(): Flow<BudgetEntity?>

    @Query("SELECT * FROM budgets WHERE active = 1 AND period = 'MONTHLY' AND tripId IS NULL LIMIT 1")
    suspend fun activeMonthly(): BudgetEntity?

    @Query("SELECT * FROM budget_lines WHERE budgetId = :budgetId")
    fun observeLines(budgetId: String): Flow<List<BudgetLineEntity>>

    @Query("SELECT * FROM budget_lines WHERE budgetId = :budgetId")
    suspend fun lines(budgetId: String): List<BudgetLineEntity>

    @Upsert
    suspend fun upsertBudget(budget: BudgetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLine(line: BudgetLineEntity)

    @Query("DELETE FROM budget_lines WHERE budgetId = :budgetId AND categoryId = :categoryId")
    suspend fun deleteLine(budgetId: String, categoryId: String)
}

@Dao
interface RecurringDao {
    @Query("SELECT * FROM recurring ORDER BY nextDue IS NULL, nextDue ASC")
    fun observeAll(): Flow<List<RecurringEntity>>

    @Query("SELECT * FROM recurring")
    suspend fun getAll(): List<RecurringEntity>

    @Query("SELECT * FROM recurring WHERE active = 1 AND nextDue IS NOT NULL AND nextDue >= :from AND nextDue <= :to ORDER BY nextDue ASC")
    fun observeUpcoming(from: LocalDate, to: LocalDate): Flow<List<RecurringEntity>>

    @Query("SELECT * FROM recurring WHERE active = 1 AND nextDue IS NOT NULL AND nextDue >= :from AND nextDue <= :to")
    suspend fun upcoming(from: LocalDate, to: LocalDate): List<RecurringEntity>

    @Upsert
    suspend fun upsert(recurring: RecurringEntity)
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals ORDER BY targetDate IS NULL, targetDate")
    fun observeAll(): Flow<List<GoalEntity>>

    @Upsert
    suspend fun upsert(goal: GoalEntity)
}

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startDate DESC")
    fun observeAll(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE active = 1 ORDER BY startDate DESC LIMIT 1")
    fun observeActive(): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE active = 1")
    suspend fun active(): List<TripEntity>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getById(id: String): TripEntity?

    @Upsert
    suspend fun upsert(trip: TripEntity)
}

@Dao
interface FxRateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rates: List<FxRateEntity>)

    @Query("SELECT * FROM fx_rates WHERE base = :base AND quote = :quote AND date <= :date ORDER BY date DESC LIMIT 1")
    suspend fun latestOnOrBefore(base: String, quote: String, date: LocalDate): FxRateEntity?

    @Query("SELECT MAX(date) FROM fx_rates")
    suspend fun latestDate(): LocalDate?
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE transactionId = :txnId")
    fun observeForTxn(txnId: String): Flow<List<AttachmentEntity>>

    @Insert
    suspend fun insert(attachment: AttachmentEntity)
}

@Dao
interface ParserTemplateDao {
    @Query("SELECT * FROM parser_templates WHERE enabled = 1")
    suspend fun enabled(): List<ParserTemplateEntity>

    @Upsert
    suspend fun upsert(template: ParserTemplateEntity)
}

@Dao
interface IgnoredSenderDao {
    @Query("SELECT * FROM ignored_senders ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<IgnoredSenderEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM ignored_senders WHERE sender = :sender)")
    suspend fun isIgnored(sender: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: IgnoredSenderEntity)

    @Query("DELETE FROM ignored_senders WHERE sender = :sender")
    suspend fun delete(sender: String)
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(setting: SettingEntity)
}
