package finance.shilling.shared.data.store

import finance.shilling.shared.data.Schedule
import finance.shilling.shared.data.ScheduleException
import finance.shilling.shared.data.sync.ChangeOp
import finance.shilling.shared.data.sync.EntityType
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.StoreWriteRequest

@OptIn(ExperimentalStoreApi::class)
class ScheduleRepository(
    private val notifier: ChangeNotifier,
    private val store: ScheduleStore,
    private val exceptionStore: ScheduleExceptionStore,
    private val sync: StoreSyncDeps? = null
) {
    fun watchAll(): Flow<List<Schedule>> = store.watchCached(ScheduleKey.All)

    suspend fun getAll(): List<Schedule> = store.readLocalSourceOfTruth(ScheduleKey.All)

    suspend fun getIntersecting(start: LocalDate, end: LocalDate): List<Schedule> =
        store.readLocalSourceOfTruth(ScheduleKey.Intersecting(start, end))

    suspend fun getExceptions(scheduleIds: List<String>): Map<String, List<ScheduleException>> {
        if (scheduleIds.isEmpty()) return emptyMap()
        return buildMap {
            scheduleIds.forEach { scheduleId ->
                val exceptions = exceptionStore.readLocalSourceOfTruth(ScheduleExceptionKey.ByScheduleId(scheduleId))
                if (exceptions.isNotEmpty()) {
                    put(scheduleId, exceptions)
                }
            }
        }
    }

    suspend fun upsert(schedule: Schedule) {
        store.write(StoreWriteRequest.of<ScheduleKey, List<Schedule>, Unit>(ScheduleKey.ById(schedule.id), listOf(schedule)))
        notifier.notifyChanged()
    }

    suspend fun delete(scheduleId: String) {
        store.clear(ScheduleKey.ById(scheduleId))
        broadcastChange(sync, EntityType.SCHEDULE, ChangeOp.DELETE, scheduleId)
        notifier.notifyChanged()
    }

    suspend fun upsertException(exception: ScheduleException) {
        exceptionStore.write(
            StoreWriteRequest.of<ScheduleExceptionKey, List<ScheduleException>, Unit>(
                ScheduleExceptionKey.ByKey(exception.scheduleId, exception.date),
                listOf(exception)
            )
        )
        notifier.notifyChanged()
    }

    suspend fun removeException(scheduleId: String, date: LocalDate) {
        exceptionStore.clear(ScheduleExceptionKey.ByKey(scheduleId, date))
        broadcastChange(sync, EntityType.SCHEDULE_EXCEPTION, ChangeOp.DELETE, "${scheduleId}_${date.toEpochDays()}")
        notifier.notifyChanged()
    }

    /**
     * Wipe every schedule (cascading exceptions + postings) through Store5's SourceOfTruth
     * delete handler. Also clears any orphaned exceptions via the exception store.
     * Local-only: does not emit per-entity DELETE broadcasts.
     */
    suspend fun clearAll() {
        store.clear(ScheduleKey.All)
        exceptionStore.clear(ScheduleExceptionKey.All)
        notifier.notifyChanged()
    }
}
