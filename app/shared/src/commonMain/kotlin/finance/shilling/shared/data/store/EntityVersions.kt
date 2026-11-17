package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.Category
import finance.shilling.shared.data.Posting
import finance.shilling.shared.data.Receipt
import finance.shilling.shared.data.Schedule
import finance.shilling.shared.data.ScheduleException
import finance.shilling.shared.data.sync.ChangeMessage
import finance.shilling.shared.data.sync.ChangeOp
import finance.shilling.shared.data.sync.ChangePayload
import finance.shilling.shared.data.sync.EntityType
import finance.shilling.shared.db.Entity_state
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/**
 * Hybrid logical clock versions. Fixed-width wall milliseconds and counter, then the
 * writing device's id, so plain string comparison orders versions causally.
 */
internal object Hlc {
    private const val WALL_DIGITS = 15
    private const val COUNTER_DIGITS = 6
    const val MAX_COUNTER = 999_999
    /** Version of data written before versions existed and never changed since. */
    val ZERO = encode(0, 0, "")

    fun encode(wall: Long, counter: Int, node: String): String =
        "${wall.toString().padStart(WALL_DIGITS, '0')}-${counter.toString().padStart(COUNTER_DIGITS, '0')}-$node"

    fun wall(version: String): Long = version.substring(0, WALL_DIGITS).toLong()
    fun counter(version: String): Int = version.substring(WALL_DIGITS + 1, WALL_DIGITS + 1 + COUNTER_DIGITS).toInt()

    fun isValid(version: String): Boolean {
        val counterEnd = WALL_DIGITS + 1 + COUNTER_DIGITS
        return version.length > counterEnd && version[WALL_DIGITS] == '-' && version[counterEnd] == '-' &&
            (0 until WALL_DIGITS).all { version[it].isDigit() } &&
            (WALL_DIGITS + 1 until counterEnd).all { version[it].isDigit() }
    }

    /** Peers that predate field versions only send a wall-clock timestamp. */
    fun fromTimestamp(timestampMillis: Long, node: String): String =
        encode(timestampMillis.coerceIn(0, 999_999_999_999_999), 0, node)
}

/**
 * Issues versions that are later than every version this device has issued or seen, so an
 * edit always wins over the state it was made on, even when this device's clock is behind.
 */
internal class HybridLogicalClock(
    private val node: String,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) {
    private val lock = Mutex()
    private var wall = 0L
    private var counter = 0

    suspend fun next(after: String? = null): String = lock.withLock {
        after?.let(::advanceTo)
        val physical = now()
        when {
            physical > wall -> { wall = physical; counter = 0 }
            counter < Hlc.MAX_COUNTER -> counter++
            else -> { wall++; counter = 0 }
        }
        Hlc.encode(wall, counter, node)
    }

    suspend fun observe(version: String?) {
        if (version != null) lock.withLock { advanceTo(version) }
    }

    private fun advanceTo(version: String) {
        val w = Hlc.wall(version)
        val c = Hlc.counter(version)
        if (w > wall || (w == wall && c > counter)) { wall = w; counter = c }
    }
}

/**
 * Field groups merged as one unit. Fields that only make sense together (a recurrence rule,
 * a transfer's accounts) share a version so a merge can't mix halves from two devices.
 * Unlisted fields are their own group.
 */
internal object EntityFields {
    private fun group(name: String, vararg fields: String) = fields.associateWith { name }

    private val groups: Map<EntityType, Map<String, String>> = mapOf(
        EntityType.SCHEDULE to group("recurrence", "startDate", "endDate", "freq", "interval", "byDayMask", "byMonthDay", "nthWeekday", "lastDayFlag") +
            group("accounts", "type", "accountId", "counterAccountId"),
        EntityType.SCHEDULE_EXCEPTION to group("exception", "skip", "overrideAmount", "overrideAccountId", "overrideCounterAccountId"),
        EntityType.POSTING to group("kind", "type", "accountId", "scheduleId", "pairId"),
        EntityType.RECEIPT to group("file", "filePath", "originalName", "addedAt")
    )

    fun identity(type: EntityType): Set<String> =
        if (type == EntityType.SCHEDULE_EXCEPTION) setOf("scheduleId", "date") else setOf("id")

    fun groupOf(type: EntityType, field: String): String = groups[type]?.get(field) ?: field

    fun groups(type: EntityType, values: JsonObject): Set<String> {
        val identity = identity(type)
        return values.keys.filterNot { it in identity }.mapTo(LinkedHashSet()) { groupOf(type, it) }
    }

    fun slice(type: EntityType, values: JsonObject?, group: String): Map<String, JsonElement> {
        val identity = identity(type)
        return values?.filterKeys { it !in identity && groupOf(type, it) == group }.orEmpty()
    }
}

/**
 * Mergeable sync state of one entity: its field values, a version per field group, and the
 * latest delete. The entity exists while some field version is newer than the delete.
 * Merging takes the newer version of each group and the later delete, so every device that
 * has seen the same changes ends up with the same state, whatever order they arrived in.
 */
internal data class EntityState(
    val values: JsonObject?,
    val versions: Map<String, String>,
    val deletedAt: String?
) {
    private val latestEdit: String? get() = versions.values.maxOrNull()
    val latest: String? get() = listOfNotNull(latestEdit, deletedAt).maxOrNull()
    val alive: Boolean get() {
        val edit = latestEdit ?: return false
        return values != null && (deletedAt == null || edit > deletedAt)
    }

    fun mergedWith(type: EntityType, remote: EntityState): EntityState {
        val deletedAt = listOfNotNull(deletedAt, remote.deletedAt).maxOrNull()
        val remoteValues = remote.values ?: return copy(deletedAt = deletedAt)
        val localValues = values ?: return remote.copy(deletedAt = deletedAt)
        val merged = localValues.toMutableMap()
        val versions = versions.toMutableMap()
        for ((group, remoteVersion) in remote.versions) {
            val localVersion = versions[group]
            val remoteSlice = EntityFields.slice(type, remoteValues, group)
            val localSlice = EntityFields.slice(type, localValues, group)
            val takeRemote = when {
                localVersion == null || remoteVersion > localVersion -> true
                remoteVersion < localVersion -> false
                else -> prefersOnTie(remoteSlice, localSlice)
            }
            if (takeRemote) {
                versions[group] = remoteVersion
                localSlice.keys.forEach(merged::remove)
                merged.putAll(remoteSlice)
            }
        }
        // Fields this device doesn't know about yet (a newer app version) ride along.
        remoteValues.forEach { (field, value) -> if (field !in merged) merged[field] = value }
        return EntityState(JsonObject(merged), versions, deletedAt)
    }
}

/**
 * Equal versions with different values only come from data written before versions existed
 * or from local cascades (deleting an account clears references to it). Prefer the cleared
 * reference, then the larger encoding, so both sides pick the same winner.
 */
private fun prefersOnTie(candidate: Map<String, JsonElement>, current: Map<String, JsonElement>): Boolean {
    if (candidate == current) return false
    val nulls = candidate.values.count { it is JsonNull } - current.values.count { it is JsonNull }
    if (nulls != 0) return nulls > 0
    return canonical(candidate) > canonical(current)
}

private fun canonical(fields: Map<String, JsonElement>): String =
    fields.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

// --- Entity <-> field values ---

/** Includes defaults and nulls so every field has a value to version. */
private val stateJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}

internal fun exceptionEntityId(scheduleId: String, date: LocalDate) = "${scheduleId}_${date.toEpochDays()}"

/** Schedule ids may themselves contain underscores; the epoch day is always last. */
internal fun parseExceptionEntityId(entityId: String): Pair<String, LocalDate>? {
    val scheduleId = entityId.substringBeforeLast('_', missingDelimiterValue = "")
    val epochDay = entityId.substringAfterLast('_').toLongOrNull() ?: return null
    if (scheduleId.isEmpty()) return null
    return scheduleId to LocalDate.fromEpochDays(epochDay)
}

internal val ChangePayload.entityType: EntityType get() = when (this) {
    is ChangePayload.AccountPayload -> EntityType.ACCOUNT
    is ChangePayload.CategoryPayload -> EntityType.CATEGORY
    is ChangePayload.SchedulePayload -> EntityType.SCHEDULE
    is ChangePayload.ScheduleExceptionPayload -> EntityType.SCHEDULE_EXCEPTION
    is ChangePayload.PostingPayload -> EntityType.POSTING
    is ChangePayload.ReceiptPayload -> EntityType.RECEIPT
}

internal val ChangePayload.entityId: String get() = when (this) {
    is ChangePayload.AccountPayload -> account.id
    is ChangePayload.CategoryPayload -> category.id
    is ChangePayload.SchedulePayload -> schedule.id
    is ChangePayload.ScheduleExceptionPayload -> exceptionEntityId(exception.scheduleId, exception.date)
    is ChangePayload.PostingPayload -> posting.id
    is ChangePayload.ReceiptPayload -> receipt.id
}

internal fun ChangePayload.toFields(): JsonObject = when (this) {
    is ChangePayload.AccountPayload -> stateJson.encodeToJsonElement(Account.serializer(), account)
    is ChangePayload.CategoryPayload -> stateJson.encodeToJsonElement(Category.serializer(), category)
    is ChangePayload.SchedulePayload -> stateJson.encodeToJsonElement(Schedule.serializer(), schedule)
    is ChangePayload.ScheduleExceptionPayload -> stateJson.encodeToJsonElement(ScheduleException.serializer(), exception)
    is ChangePayload.PostingPayload -> stateJson.encodeToJsonElement(Posting.serializer(), posting)
    is ChangePayload.ReceiptPayload -> stateJson.encodeToJsonElement(Receipt.serializer(), receipt)
}.jsonObject

internal fun EntityType.payloadFrom(values: JsonObject): ChangePayload = when (this) {
    EntityType.ACCOUNT -> ChangePayload.AccountPayload(stateJson.decodeFromJsonElement(Account.serializer(), values))
    EntityType.CATEGORY -> ChangePayload.CategoryPayload(stateJson.decodeFromJsonElement(Category.serializer(), values))
    EntityType.SCHEDULE -> ChangePayload.SchedulePayload(stateJson.decodeFromJsonElement(Schedule.serializer(), values))
    EntityType.SCHEDULE_EXCEPTION -> ChangePayload.ScheduleExceptionPayload(stateJson.decodeFromJsonElement(ScheduleException.serializer(), values))
    EntityType.POSTING -> ChangePayload.PostingPayload(stateJson.decodeFromJsonElement(Posting.serializer(), values))
    EntityType.RECEIPT -> ChangePayload.ReceiptPayload(stateJson.decodeFromJsonElement(Receipt.serializer(), values))
}

// --- Wire format ---

/** Reads a peer's change, including changes from peers that predate field versions. */
internal fun ChangeMessage.toEntityState(): EntityState {
    val values = payload?.let {
        require(it.entityType == entityType && it.entityId == entityId) { "Payload does not match $entityType/$entityId" }
        it.toFields()
    }
    versions?.values?.forEach { require(Hlc.isValid(it)) { "Invalid version" } }
    deletedAt?.let { require(Hlc.isValid(it)) { "Invalid delete version" } }
    if (versions != null) return EntityState(values, versions, deletedAt)
    val legacy = Hlc.fromTimestamp(timestamp, deviceId)
    return when (op) {
        ChangeOp.UPSERT -> EntityState(values, values?.let { v -> EntityFields.groups(entityType, v).associateWith { legacy } }.orEmpty(), null)
        ChangeOp.DELETE -> EntityState(values, emptyMap(), legacy)
    }
}

/**
 * A full-state change. Deletes carry the last known values too: if another device revives
 * the entity with a newer edit, both sides need the same values for the older groups.
 */
internal fun EntityState.toChangeMessage(id: String, type: EntityType, entityId: String, deviceId: String) = ChangeMessage(
    id = id,
    entityType = type,
    op = if (alive) ChangeOp.UPSERT else ChangeOp.DELETE,
    entityId = entityId,
    timestamp = Hlc.wall(latest ?: Hlc.ZERO),
    deviceId = deviceId,
    payload = values?.let(type::payloadFrom),
    versions = versions,
    deletedAt = deletedAt
)

/** Same id for the same state, so peers can skip a snapshot entry they already applied. */
internal fun EntityState.snapshotId(type: EntityType, entityId: String): String {
    val digest = versions.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" } + "|$deletedAt|$values"
    return "state:$type:$entityId:${latest ?: ""}:${digest.hashCode().toUInt().toString(16)}"
}

// --- Storage ---

/**
 * Sync versions for one finance space. Local edits get a new version for the field groups
 * they change; peers' changes are merged group by group.
 */
class SyncVersions(
    private val db: ShillingDatabase,
    private val spaceId: String,
    node: String
) {
    internal val clock = HybridLogicalClock(node)

    internal suspend fun load(type: EntityType, entityId: String): EntityState? =
        db.entityStateQueries.selectOne(spaceId, type.name, entityId).awaitAsOneOrNull()?.toState()

    internal suspend fun loadAll(): Map<Pair<EntityType, String>, EntityState> =
        db.entityStateQueries.selectForSpace(spaceId).awaitAsList().mapNotNull { row ->
            val type = EntityType.entries.firstOrNull { it.name == row.entity_type } ?: return@mapNotNull null
            (type to row.entity_id) to row.toState()
        }.toMap()

    internal suspend fun save(type: EntityType, entityId: String, state: EntityState) {
        db.entityStateQueries.upsert(
            space_id = spaceId,
            entity_type = type.name,
            entity_id = entityId,
            values_json = state.values?.toString(),
            versions_json = JsonObject(state.versions.mapValues { JsonPrimitive(it.value) }).toString(),
            deleted_at = state.deletedAt
        )
    }

    /** An entity written before versions existed: every group at its last change-log time. */
    internal suspend fun legacyState(type: EntityType, entityId: String, values: JsonObject): EntityState {
        val base = db.latestEntityVersion(type, entityId, spaceId)?.let { Hlc.fromTimestamp(it.timestamp, "") } ?: Hlc.ZERO
        return EntityState(values, EntityFields.groups(type, values).associateWith { base }, null)
    }

    /**
     * Local state of an entity. The stored row's values win over the recorded ones: local
     * cascades (clearing references to a deleted account) change rows without new versions.
     */
    internal suspend fun current(type: EntityType, entityId: String, row: JsonObject?): EntityState? {
        val stored = load(type, entityId)
        return when {
            stored != null -> if (row != null) stored.copy(values = row) else stored
            row != null -> legacyState(type, entityId, row)
            else -> null
        }
    }

    /** Versions the field groups a local write changed. [previousRow] is the row before the write. */
    internal suspend fun recordLocalUpsert(type: EntityType, entityId: String, values: JsonObject, previousRow: JsonObject?) {
        val current = current(type, entityId, previousRow)
        val groups = EntityFields.groups(type, values)
        val changed = if (current == null || previousRow == null || !current.alive) groups else groups.filter {
            it !in current.versions || EntityFields.slice(type, values, it) != EntityFields.slice(type, current.values, it)
        }
        if (current != null && changed.isEmpty()) return
        val version = clock.next(after = current?.latest)
        save(type, entityId, EntityState(values, current?.versions.orEmpty() + changed.associateWith { version }, current?.deletedAt))
    }

    /** Records a tombstone newer than every edit this device has seen of the entity. */
    internal suspend fun recordLocalDelete(type: EntityType, entityId: String, previousRow: JsonObject?) {
        val current = current(type, entityId, previousRow)
        val deletedAt = clock.next(after = current?.latest)
        save(type, entityId, EntityState(current?.values, current?.versions.orEmpty(), deletedAt))
    }

    /**
     * Merges a peer's state into the local one. Returns the merged state, or null when it
     * changes nothing here. The caller saves it once the merged entity is written.
     */
    internal suspend fun merge(type: EntityType, entityId: String, remote: EntityState, row: JsonObject?): EntityState? {
        clock.observe(remote.latest)
        val local = current(type, entityId, row)
        val merged = local?.mergedWith(type, remote) ?: remote
        return merged.takeIf { it != local }
    }
}

private fun Entity_state.toState() = EntityState(
    values = values_json?.let { Json.parseToJsonElement(it).jsonObject },
    versions = Json.parseToJsonElement(versions_json).jsonObject.mapValues { it.value.jsonPrimitive.content },
    deletedAt = deleted_at
)
