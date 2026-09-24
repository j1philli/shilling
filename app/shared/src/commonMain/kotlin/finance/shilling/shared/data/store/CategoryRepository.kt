package finance.shilling.shared.data.store

import finance.shilling.shared.data.Category
import finance.shilling.shared.data.sync.ChangeOp
import finance.shilling.shared.data.sync.EntityType
import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.StoreWriteRequest

@OptIn(ExperimentalStoreApi::class)
class CategoryRepository(
    private val notifier: ChangeNotifier,
    private val store: CategoryStore,
    private val sync: StoreSyncDeps? = null
) {
    fun watchAll(): Flow<List<Category>> = store.watchCached(CategoryKey.All)

    suspend fun getAll(): List<Category> = store.readLocalSourceOfTruth(CategoryKey.All)

    suspend fun upsert(category: Category) {
        store.write(StoreWriteRequest.of<CategoryKey, List<Category>, Unit>(CategoryKey.ById(category.id), listOf(category)))
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
