package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.Frequency
import finance.shilling.shared.data.Schedule
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.sync.ChangeMessage
import finance.shilling.shared.data.sync.ChangeOp
import finance.shilling.shared.data.sync.ChangePayload
import finance.shilling.shared.data.sync.EntityType
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntityVersionsTest {
    private fun account(name: String = "Checking", balance: Double = 1.0) =
        ChangePayload.AccountPayload(Account("a", name, balance)).toFields()

    private fun state(values: kotlinx.serialization.json.JsonObject?, versions: Map<String, String>, deletedAt: String? = null) =
        EntityState(values, versions, deletedAt)

    private fun v(wall: Long, node: String = "n") = Hlc.encode(wall, 0, node)

    @Test
    fun versionsCompareByWallThenCounterThenNode() {
        val ordered = listOf(Hlc.ZERO, Hlc.encode(5, 0, "z"), Hlc.encode(5, 1, "a"), Hlc.encode(5, 1, "b"), Hlc.encode(40, 0, "a"))
        assertEquals(ordered, ordered.shuffled().sorted())
        assertTrue(Hlc.isValid(Hlc.encode(1_700_000_000_000, 12, "device")))
        assertFalse(Hlc.isValid("1700000000000"))
        assertEquals(1_700_000_000_000, Hlc.wall(Hlc.encode(1_700_000_000_000, 12, "device")))
    }

    @Test
    fun clockIssuesVersionsAfterAnythingItHasSeenEvenWhenBehind() = runBlocking {
        var now = 1_000L
        val clock = HybridLogicalClock("slow") { now }
        val first = clock.next()
        assertTrue(clock.next() > first, "Same millisecond still moves forward")
        val fromFastPeer = Hlc.encode(9_000_000, 3, "fast")
        clock.observe(fromFastPeer)
        assertTrue(clock.next() > fromFastPeer)
        now = 500L
        val afterLocalRegression = clock.next(after = Hlc.encode(9_000_000, 99, "zzz"))
        assertTrue(afterLocalRegression > Hlc.encode(9_000_000, 99, "zzz"))
    }

    @Test
    fun mergeTakesTheNewerVersionOfEachFieldIndependently() {
        val local = state(account(name = "Joint"), mapOf("name" to v(20), "balance" to v(10)))
        val remote = state(account(balance = 99.0), mapOf("name" to v(10), "balance" to v(30)))
        val merged = local.mergedWith(EntityType.ACCOUNT, remote)
        val decoded = (EntityType.ACCOUNT.payloadFrom(merged.values!!) as ChangePayload.AccountPayload).account
        assertEquals(Account("a", "Joint", 99.0), decoded)
        assertEquals(mapOf("name" to v(20), "balance" to v(30)), merged.versions)
    }

    @Test
    fun mergeIsOrderIndependentAndIdempotent() {
        val a = state(account(name = "A", balance = 1.0), mapOf("name" to v(20, "a"), "balance" to v(10, "a")))
        val b = state(account(name = "B", balance = 2.0), mapOf("name" to v(20, "b"), "balance" to v(5, "b")))
        val c = state(account(name = "C", balance = 3.0), mapOf("name" to v(15, "c"), "balance" to v(40, "c")), deletedAt = v(30, "c"))
        val abc = a.mergedWith(EntityType.ACCOUNT, b).mergedWith(EntityType.ACCOUNT, c)
        val cba = c.mergedWith(EntityType.ACCOUNT, b).mergedWith(EntityType.ACCOUNT, a)
        val bac = b.mergedWith(EntityType.ACCOUNT, a).mergedWith(EntityType.ACCOUNT, c)
        assertEquals(abc, cba)
        assertEquals(abc, bac)
        assertEquals(abc, abc.mergedWith(EntityType.ACCOUNT, abc))
        assertEquals(abc, abc.mergedWith(EntityType.ACCOUNT, a))
    }

    @Test
    fun deleteWinsOverOlderEditsAndLosesToNewerOnes() {
        val edited = state(account(name = "Edited"), mapOf("name" to v(20), "balance" to v(10)))
        val deletedLater = state(account(), mapOf("name" to v(10), "balance" to v(10)), deletedAt = v(25))
        assertFalse(edited.mergedWith(EntityType.ACCOUNT, deletedLater).alive)

        val deletedEarlier = state(account(), mapOf("name" to v(10), "balance" to v(10)), deletedAt = v(15))
        val revived = edited.mergedWith(EntityType.ACCOUNT, deletedEarlier)
        assertTrue(revived.alive)
        assertEquals(v(15), revived.deletedAt)
    }

    @Test
    fun relatedFieldsMergeAsOneGroup() {
        fun schedule(freq: Frequency, interval: Int, byMonthDay: Int?) = ChangePayload.SchedulePayload(
            Schedule("s", "Rent", 1000.0, ScheduleType.EXPENSE, "acct", startDate = LocalDate(2026, 1, 1),
                freq = freq, interval = interval, byMonthDay = byMonthDay)
        ).toFields()
        val local = schedule(Frequency.MONTHLY_BY_DAY, 1, 15)
        val groups = EntityFields.groups(EntityType.SCHEDULE, local)
        assertTrue("recurrence" in groups && "freq" !in groups && "title" in groups)
        val weekly = state(schedule(Frequency.WEEKLY, 2, null), groups.associateWith { if (it == "recurrence") v(20) else v(1) })
        val merged = state(local, groups.associateWith { v(10) }).mergedWith(EntityType.SCHEDULE, weekly)
        val decoded = (EntityType.SCHEDULE.payloadFrom(merged.values!!) as ChangePayload.SchedulePayload).schedule
        assertEquals(Frequency.WEEKLY, decoded.freq)
        assertEquals(2, decoded.interval)
        assertNull(decoded.byMonthDay, "The whole recurrence rule comes from one side")
    }

    @Test
    fun equalVersionsPreferClearedReferencesSoCascadesConverge() {
        fun posting(categoryId: String?) = ChangePayload.PostingPayload(
            finance.shilling.shared.data.Posting("p", null, ScheduleType.EXPENSE, "acct", LocalDate(2026, 1, 1), 5.0, categoryId = categoryId)
        ).toFields()
        val groups = EntityFields.groups(EntityType.POSTING, posting(null)).associateWith { v(10) }
        val cleared = state(posting(null), groups)
        val stale = state(posting("deleted-category"), groups)
        assertEquals(cleared.values, stale.mergedWith(EntityType.POSTING, cleared).values)
        assertEquals(cleared.values, cleared.mergedWith(EntityType.POSTING, stale).values)
    }

    @Test
    fun changesFromPeersWithoutVersionsUseTheirTimestamp() {
        val upsert = ChangeMessage("1", EntityType.ACCOUNT, ChangeOp.UPSERT, "a", 1234L, "old-peer",
            ChangePayload.AccountPayload(Account("a", "Checking", 1.0)))
        assertEquals(setOf(Hlc.fromTimestamp(1234L, "old-peer")), upsert.toEntityState().versions.values.toSet())
        val delete = ChangeMessage("2", EntityType.ACCOUNT, ChangeOp.DELETE, "a", 2000L, "old-peer")
        assertEquals(Hlc.fromTimestamp(2000L, "old-peer"), delete.toEntityState().deletedAt)
        assertFalse(delete.toEntityState().alive)
    }

    @Test
    fun mismatchedPayloadsAndVersionsAreRejected() {
        val wrongId = ChangeMessage("1", EntityType.ACCOUNT, ChangeOp.UPSERT, "other", 1L, "peer",
            ChangePayload.AccountPayload(Account("a", "Checking", 1.0)))
        assertFailsWith<IllegalArgumentException> { wrongId.toEntityState() }
        val badVersion = ChangeMessage("1", EntityType.ACCOUNT, ChangeOp.UPSERT, "a", 1L, "peer",
            ChangePayload.AccountPayload(Account("a", "Checking", 1.0)), versions = mapOf("name" to "soon"))
        assertFailsWith<IllegalArgumentException> { badVersion.toEntityState() }
    }

    @Test
    fun exceptionIdsSurviveUnderscoresInScheduleIds() {
        val date = LocalDate(2026, 3, 15)
        assertEquals("rent_2026_${date.toEpochDays()}", exceptionEntityId("rent_2026", date))
        assertEquals("rent_2026" to date, parseExceptionEntityId(exceptionEntityId("rent_2026", date)))
        assertNull(parseExceptionEntityId("no-date"))
    }

    @Test
    fun localEditVersionsOnlyTheFieldsItChanged() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ShillingDatabase.Schema.create(driver).await()
        val versions = SyncVersions(ShillingDatabase(driver), LOCAL_SPACE_ID, "device")
        versions.recordLocalUpsert(EntityType.ACCOUNT, "a", account(), null)
        val created = versions.load(EntityType.ACCOUNT, "a")!!
        versions.recordLocalUpsert(EntityType.ACCOUNT, "a", account(balance = 5.0), account())
        val edited = versions.load(EntityType.ACCOUNT, "a")!!
        assertEquals(created.versions["name"], edited.versions["name"])
        assertTrue(edited.versions.getValue("balance") > created.versions.getValue("balance"))
        versions.recordLocalUpsert(EntityType.ACCOUNT, "a", account(balance = 5.0), account(balance = 5.0))
        assertEquals(edited, versions.load(EntityType.ACCOUNT, "a"), "Rewriting the same values is not an edit")
        versions.recordLocalDelete(EntityType.ACCOUNT, "a", account(balance = 5.0))
        val deleted = versions.load(EntityType.ACCOUNT, "a")!!
        assertFalse(deleted.alive)
        versions.recordLocalUpsert(EntityType.ACCOUNT, "a", account(balance = 5.0), null)
        assertTrue(versions.load(EntityType.ACCOUNT, "a")!!.alive, "Recreating with the same values revives it")
        driver.close()
    }

    @Test
    fun anEditMadeAfterSeeingAPeersChangeWinsEvenWithASlowClock() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ShillingDatabase.Schema.create(driver).await()
        val versions = SyncVersions(ShillingDatabase(driver), LOCAL_SPACE_ID, "slow")
        // A peer whose clock runs a year ahead renamed the account.
        val farFuture = Hlc.encode(System.currentTimeMillis() + 365L * 24 * 3600 * 1000, 0, "fast")
        val remote = EntityState(account(name = "Theirs"), mapOf("name" to farFuture, "balance" to farFuture), null)
        val merged = versions.merge(EntityType.ACCOUNT, "a", remote, null)!!
        versions.save(EntityType.ACCOUNT, "a", merged)
        versions.recordLocalUpsert(EntityType.ACCOUNT, "a", account(name = "Mine"), account(name = "Theirs"))
        val mine = versions.load(EntityType.ACCOUNT, "a")!!
        assertTrue(mine.versions.getValue("name") > farFuture)
        assertEquals(remote.mergedWith(EntityType.ACCOUNT, mine).values, mine.values, "The peer adopts the later edit too")
        driver.close()
    }
}
