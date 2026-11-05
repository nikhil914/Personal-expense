package app.ledgerly.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        AccountEntity::class,
        SubBalanceEntity::class,
        TransactionEntity::class,
        TransactionSplitEntity::class,
        SourceEventEntity::class,
        TransactionEventEntity::class,
        CategoryEntity::class,
        RuleEntity::class,
        MerchantEntity::class,
        BudgetEntity::class,
        BudgetLineEntity::class,
        RecurringEntity::class,
        GoalEntity::class,
        TripEntity::class,
        FxRateEntity::class,
        AttachmentEntity::class,
        ParserTemplateEntity::class,
        IgnoredSenderEntity::class,
        SettingEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LedgerlyDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun subBalanceDao(): SubBalanceDao
    abstract fun transactionDao(): TransactionDao
    abstract fun splitDao(): SplitDao
    abstract fun sourceEventDao(): SourceEventDao
    abstract fun transactionEventDao(): TransactionEventDao
    abstract fun categoryDao(): CategoryDao
    abstract fun ruleDao(): RuleDao
    abstract fun merchantDao(): MerchantDao
    abstract fun budgetDao(): BudgetDao
    abstract fun recurringDao(): RecurringDao
    abstract fun goalDao(): GoalDao
    abstract fun tripDao(): TripDao
    abstract fun fxRateDao(): FxRateDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun parserTemplateDao(): ParserTemplateDao
    abstract fun ignoredSenderDao(): IgnoredSenderDao
    abstract fun settingDao(): SettingDao

    companion object {
        const val FILE_NAME = "ledgerly.db"

        /**
         * Opens the SQLCipher-encrypted database. [passphrase] comes from
         * [app.ledgerly.security.DbKeyManager] (random 32-byte key wrapped by an Android Keystore AES key).
         */
        fun build(context: Context, passphrase: ByteArray): LedgerlyDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(context.applicationContext, LedgerlyDatabase::class.java, FILE_NAME)
                .openHelperFactory(factory)
                .addCallback(AppendOnlyAuditCallback)
                .build()
        }
    }
}

/** DB-level guarantee that the audit log is append-only, on top of the DAO exposing no mutations. */
private object AppendOnlyAuditCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) = installTriggers(db)
    override fun onOpen(db: SupportSQLiteDatabase) = installTriggers(db)

    private fun installTriggers(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS transaction_events_no_update BEFORE UPDATE ON transaction_events " +
                "BEGIN SELECT RAISE(ABORT, 'transaction_events is append-only'); END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS transaction_events_no_delete BEFORE DELETE ON transaction_events " +
                "BEGIN SELECT RAISE(ABORT, 'transaction_events is append-only'); END",
        )
    }
}
