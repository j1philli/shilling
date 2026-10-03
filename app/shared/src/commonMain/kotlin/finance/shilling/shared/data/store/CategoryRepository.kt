package finance.shilling.shared.data.store

import finance.shilling.shared.data.Category
import finance.shilling.shared.data.sync.ChangeOp
import finance.shilling.shared.data.sync.EntityType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.StoreWriteRequest

@OptIn(ExperimentalStoreApi::class)
class CategoryRepository(
    private val notifier: ChangeNotifier,
    private val store: CategoryStore,
    private val sync: StoreSyncDeps? = null
) {
    fun watchAll(): Flow<List<Category>> = store.watchCached(CategoryKey.All).flowOn(Dispatchers.Default)

    fun watchById(id: String): Flow<Category?> =
        store.watchCached(CategoryKey.ById(id)).map { it.firstOrNull() }.flowOn(Dispatchers.Default)

    suspend fun getAll(): List<Category> = store.readLocalSourceOfTruth(CategoryKey.All)

    suspend fun upsert(category: Category) {
        store.writeLocally(StoreWriteRequest.of<CategoryKey, List<Category>, Unit>(CategoryKey.ById(category.id), listOf(category)))
        notifier.notifyChanged()
    }

    suspend fun delete(categoryId: String) {
        store.clear(CategoryKey.ById(categoryId))
        broadcastChange(sync, EntityType.CATEGORY, ChangeOp.DELETE, categoryId)
        notifier.notifyChanged()
    }

    /**
     * Wipe every category through Store5's SourceOfTruth delete handler.
     * Local-only: does not emit per-entity DELETE broadcasts.
     */
    suspend fun clearAll() {
        store.clear(CategoryKey.All)
        notifier.notifyChanged()
    }
}
