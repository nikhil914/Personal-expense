package app.ledgerly.core.audit

import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Transaction

/** Persistence port for canonical transactions (Room-backed in the app). */
interface TransactionStore {
    fun get(id: Id): Transaction?
    fun put(transaction: Transaction)
    fun remove(id: Id): Transaction?
    /** All stored transactions, including soft-deleted ones. */
    fun all(): List<Transaction>
}

class InMemoryTransactionStore(initial: Collection<Transaction> = emptyList()) : TransactionStore {
    private val map = LinkedHashMap<Id, Transaction>()

    init { initial.forEach { put(it) } }

    @Synchronized override fun get(id: Id): Transaction? = map[id]
    @Synchronized override fun put(transaction: Transaction) { map[transaction.id] = transaction }
    @Synchronized override fun remove(id: Id): Transaction? = map.remove(id)
    @Synchronized override fun all(): List<Transaction> = map.values.toList()
}
