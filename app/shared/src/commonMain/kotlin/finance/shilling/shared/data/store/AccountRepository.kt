package finance.shilling.shared.data.store

import finance.shilling.shared.data.Account
import finance.shilling.shared.data.sync.ChangeOp
import finance.shilling.shared.data.sync.EntityType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.StoreWriteRequest

@OptIn(ExperimentalStoreApi::class)
class AccountRepository(
    private val notifier: ChangeNotifier,
    private val store: AccountStore,
    private val sync: StoreSyncDeps? = null
) {
    fun watchAll(): Flow<List<Account>> = store.watchCached(AccountKey.All).flowOn(Dispatchers.Default)

    fun watchById(id: String): Flow<Account?> =
        store.watchCached(AccountKey.ById(id)).map { it.firstOrNull() }.flowOn(Dispatchers.Default)

    suspend fun getAll(): Map<String?, Account> =
        store.readLocalSourceOfTruth(AccountKey.All)
            .associateBy { it.id as String? }

    suspend fun upsert(account: Account) {
        store.writeLocally(StoreWriteRequest.of<AccountKey, List<Account>, Unit>(AccountKey.ById(account.id), listOf(account)))
        notifier.notifyChanged()
    }

    suspend fun delete(accountId: String) {
        store.clear(AccountKey.ById(accountId))
        broadcastChange(sync, EntityType.ACCOUNT, ChangeOp.DELETE, accountId)
        notifier.notifyChanged()
    }

    /**
     * Wipe every account (and its cascades) through Store5's SourceOfTruth delete handler.
     * Local-only: does not emit per-entity DELETE broadcasts.
     */
    suspend fun clearAll() {
        store.clear(AccountKey.All)
        notifier.notifyChanged()
    }
}
