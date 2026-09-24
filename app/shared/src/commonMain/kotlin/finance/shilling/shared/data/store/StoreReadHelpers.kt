package finance.shilling.shared.data.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin

@OptIn(ExperimentalStoreApi::class)
internal fun <Key : Any, Output : Any> MutableStore<Key, Output>.watchCached(key: Key): Flow<Output> =
    stream<Output>(StoreReadRequest.cached(key = key, refresh = false))
        .mapNotNull { response ->
            when (response) {
                is StoreReadResponse.Data -> response.value
                else -> null
            }
        }

@OptIn(ExperimentalStoreApi::class)
internal suspend fun <Key : Any, Output : Any> MutableStore<Key, Output>.readCached(key: Key): Output =
    watchCached(key).first()

@OptIn(ExperimentalStoreApi::class)
internal fun <Key : Any, Output : Any> MutableStore<Key, Output>.watchLocalSourceOfTruth(key: Key): Flow<Output> =
    stream<Output>(StoreReadRequest.localOnly(key))
        .mapNotNull { response ->
            when (response) {
                is StoreReadResponse.Data ->
                    if (response.origin is StoreReadResponseOrigin.SourceOfTruth) response.value else null
                else -> null
            }
        }

@OptIn(ExperimentalStoreApi::class)
internal suspend fun <Key : Any, Output : Any> MutableStore<Key, Output>.readLocalSourceOfTruth(key: Key): Output =
    watchLocalSourceOfTruth(key).first()
