package finance.shilling.shared.mcp

import finance.shilling.shared.data.Account
import finance.shilling.shared.data.Category
import finance.shilling.shared.data.Frequency
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.Posting
import finance.shilling.shared.data.Schedule
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.ScheduledTxWithAccount
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ScheduleRepository
import finance.shilling.shared.data.usecase.ComputeBudgetUseCase
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import finance.shilling.shared.presentation.OccurrenceActions
import finance.shilling.shared.presentation.describeRecurrence
import finance.shilling.shared.presentation.toDayMask
import finance.shilling.shared.presentation.today
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToLong

/** One MCP tool: its listing metadata and a handler that returns the JSON result. */
class McpTool(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean,
    val destructive: Boolean = false,
    val idempotent: Boolean = false,
    val handler: suspend (JsonObject) -> JsonElement
)

private const val DEFAULT_TRANSACTION_DAYS = 30
private const val DEFAULT_UPCOMING_DAYS = 14
private const val MAX_RANGE_DAYS = 366 * 2
private const val DEFAULT_TRANSACTION_LIMIT = 200

/**
 * The budget tools assistants can call. Reads go through the repositories and use cases the UI
 * uses; writes go through the same Store5-backed repositories, so they sync to other devices.
 */
class McpToolbox(
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val schedules: ScheduleRepository,
    private val postings: PostingRepository,
    private val window: ComputeWindowUseCase,
    private val budget: ComputeBudgetUseCase,
    private val occurrences: OccurrenceActions,
    private val idGenerator: IdGenerator
) {
    val tools: List<McpTool> = listOf(
        McpTool(
            name = "list_accounts",
            title = "List accounts",
            description = "All accounts with their current balances and the combined total.",
            inputSchema = schema(),
            readOnly = true
        ) { listAccounts() },
        McpTool(
            name = "list_categories",
            title = "List categories",
            description = "All categories (labels for schedules and transactions).",
            inputSchema = schema(),
            readOnly = true
        ) { listCategories() },
        McpTool(
            name = "list_schedules",
            title = "List schedules",
            description = "Planned income, expenses and transfers with their recurrence, amount, accounts and category.",
            inputSchema = schema {
                enumProperty("type", "Only schedules of this type.", ScheduleType.entries)
            },
            readOnly = true
        ) { args -> listSchedules(args) },
        McpTool(
            name = "list_transactions",
            title = "List transactions",
            description = "Recorded transactions between two dates (inclusive), newest first. Defaults to the last " +
                "$DEFAULT_TRANSACTION_DAYS days. Transfers appear as two legs (an EXPENSE from the source account and " +
                "an INCOME to the destination) sharing a pairId.",
            inputSchema = schema {
                dateProperty("startDate", "First day to include.")
                dateProperty("endDate", "Last day to include. Defaults to today.")
                stringProperty("search", "Case-insensitive match on title, account or category.")
                stringProperty("accountId", "Only transactions in this account.")
                integerProperty("limit", "Maximum rows to return (default $DEFAULT_TRANSACTION_LIMIT).")
            },
            readOnly = true
        ) { args -> listTransactions(args) },
        McpTool(
            name = "list_upcoming",
            title = "List upcoming occurrences",
            description = "Scheduled occurrences between two dates (inclusive), each marked paid (recorded) or due. " +
                "Defaults to the next $DEFAULT_UPCOMING_DAYS days. Use scheduleId + date with the occurrence tools.",
            inputSchema = schema {
                dateProperty("startDate", "First day to include. Defaults to today.")
                dateProperty("endDate", "Last day to include.")
                stringProperty("accountId", "Only occurrences that move money in or out of this account.")
            },
            readOnly = true
        ) { args -> listUpcoming(args) },
        McpTool(
            name = "get_monthly_budget",
            title = "Get monthly budget",
            description = "Planned totals for a calendar month: each schedule's occurrences and total, totals per " +
                "category (income positive, expenses negative) and the net change.",
            inputSchema = schema {
                stringProperty("month", "Month as YYYY-MM. Defaults to the current month.", pattern = "^\\d{4}-\\d{2}$")
            },
            readOnly = true
        ) { args -> monthlyBudget(args) },
        McpTool(
            name = "project_balance",
            title = "Project balance",
            description = "Projected balance on a future date: current balances plus the scheduled occurrences " +
                "still due before then. For one account, or all accounts combined.",
            inputSchema = schema(required = listOf("date")) {
                dateProperty("date", "Date to project to.")
                stringProperty("accountId", "Account to project. Omit for all accounts combined.")
            },
            readOnly = true
        ) { args -> projectBalance(args) },

        McpTool(
            name = "record_transaction",
            title = "Record transaction",
            description = "Records a transaction that isn't from a schedule. For a TRANSFER, accountId is the source " +
                "and toAccountId the destination. Does not change stored account balances.",
            inputSchema = schema(required = listOf("title", "amount", "type", "accountId")) {
                stringProperty("title", "What the transaction was.")
                amountProperty("amount", "Positive amount.")
                enumProperty("type", "Direction of the money.", ScheduleType.entries)
                stringProperty("accountId", "Account the money leaves (EXPENSE, TRANSFER) or enters (INCOME).")
                stringProperty("toAccountId", "Destination account. Required for TRANSFER.")
                stringProperty("categoryId", "Category for the transaction.")
                dateProperty("date", "When it happened. Defaults to today.")
            },
            readOnly = false
        ) { args -> recordTransaction(args) },
        McpTool(
            name = "update_transaction",
            title = "Update transaction",
            description = "Changes a recorded transaction. Only the given fields change. For transfers, pass either " +
                "leg's id; title, amount, date and category apply to both legs, accountId moves the source and " +
                "toAccountId the destination.",
            inputSchema = schema(required = listOf("id")) {
                stringProperty("id", "Transaction id (from list_transactions).")
                stringProperty("title", "New title.")
                amountProperty("amount", "New positive amount.")
                dateProperty("date", "New date.")
                stringProperty("accountId", "New account (the source, for transfers).")
                stringProperty("toAccountId", "New destination account (transfers only).")
                stringProperty("categoryId", "New category id, or an empty string to clear it.")
            },
            readOnly = false,
            idempotent = true
        ) { args -> updateTransaction(args) },
        McpTool(
            name = "delete_transaction",
            title = "Delete transaction",
            description = "Deletes a recorded transaction (both legs for a transfer). A deleted scheduled transaction " +
                "becomes due again.",
            inputSchema = schema(required = listOf("id")) {
                stringProperty("id", "Transaction id (from list_transactions).")
            },
            readOnly = false,
            destructive = true,
            idempotent = true
        ) { args -> deleteTransaction(args) },
        McpTool(
            name = "save_account",
            title = "Create or update account",
            description = "Creates an account, or updates one when id is given (only the given fields change). " +
                "Balance is the user's stated current balance.",
            inputSchema = schema {
                stringProperty("id", "Account to update. Omit to create.")
                stringProperty("name", "Account name. Required when creating.")
                numberProperty("balance", "Current balance (may be negative). Defaults to 0 when creating.")
            },
            readOnly = false,
            idempotent = true
        ) { args -> saveAccount(args) },
        McpTool(
            name = "save_category",
            title = "Create or update category",
            description = "Creates a category, or updates one when id is given (only the given fields change).",
            inputSchema = schema {
                stringProperty("id", "Category to update. Omit to create.")
                stringProperty("name", "Category name. Required when creating.")
                stringProperty("color", "Hex color like #4CAF50, or an empty string to clear it.")
            },
            readOnly = false,
            idempotent = true
        ) { args -> saveCategory(args) },
        McpTool(
            name = "save_schedule",
            title = "Create or update schedule",
            description = "Creates a planned income, expense or transfer, or updates one when id is given (only the " +
                "given fields change). Frequencies: ONCE (on startDate); DAILY, WEEKLY and YEARLY every `interval` " +
                "days/weeks/years; BI_WEEKLY every 2 weeks on startDate's weekday; MONTHLY_BY_DAY on `dayOfMonth` " +
                "(or the last day with lastDayOfMonth) every `interval` months; MONTHLY_BY_NTH_WEEKDAY on the " +
                "`nthWeekday`th `weekdays[0]` every `interval` months; SEMI_MONTHLY on the 15th and last day.",
            inputSchema = schema {
                stringProperty("id", "Schedule to update. Omit to create.")
                stringProperty("title", "Name. Required when creating.")
                amountProperty("amount", "Positive amount per occurrence. Required when creating.")
                enumProperty("type", "Required when creating.", ScheduleType.entries)
                stringProperty("accountId", "Account the money leaves or enters. Required when creating.")
                stringProperty("toAccountId", "Destination account. Required for TRANSFER.")
                stringProperty("categoryId", "Category id, or an empty string to clear it.")
                enumProperty("frequency", "How often it repeats. Defaults to MONTHLY_BY_DAY when creating.", Frequency.entries)
                dateProperty("startDate", "First occurrence. Defaults to today when creating.")
                dateProperty("endDate", "Last day it can occur. Omit for no end; an empty string clears it.")
                integerProperty("interval", "Repeat every N units (default 1).", minimum = 1)
                putJsonObject("weekdays") {
                    put("type", "array")
                    put("description", "WEEKLY: days it falls on (defaults to startDate's day). MONTHLY_BY_NTH_WEEKDAY: the weekday.")
                    putJsonObject("items") {
                        put("type", "string")
                        putJsonArray("enum") { DayOfWeek.entries.forEach { add(JsonPrimitive(it.name)) } }
                    }
                }
                integerProperty("dayOfMonth", "MONTHLY_BY_DAY: day of the month (defaults to startDate's day).", minimum = 1, maximum = 31)
                booleanProperty("lastDayOfMonth", "MONTHLY_BY_DAY: falls on the month's last day.")
                integerProperty("nthWeekday", "MONTHLY_BY_NTH_WEEKDAY: which week of the month (1-5).", minimum = 1, maximum = 5)
                booleanProperty("autoPay", "Paid automatically (informational).")
                stringProperty("notes", "Free-form notes.")
            },
            readOnly = false,
            idempotent = true
        ) { args -> saveSchedule(args) },
        McpTool(
            name = "delete_schedule",
            title = "Delete schedule",
            description = "Deletes a schedule and its per-date overrides. Transactions already recorded from it are kept.",
            inputSchema = schema(required = listOf("id")) {
                stringProperty("id", "Schedule id (from list_schedules).")
            },
            readOnly = false,
            destructive = true,
            idempotent = true
        ) { args -> deleteSchedule(args) },
        McpTool(
            name = "mark_occurrence_paid",
            title = "Mark occurrence paid",
            description = "Records a due scheduled occurrence as a transaction on its date.",
            inputSchema = occurrenceSchema {
                amountProperty("amount", "Amount actually paid, if different from the occurrence.")
            },
            readOnly = false,
            idempotent = true
        ) { args -> markPaid(args) },
        McpTool(
            name = "unmark_occurrence_paid",
            title = "Unmark occurrence paid",
            description = "Deletes the transaction recorded for a scheduled occurrence so it's due again.",
            inputSchema = occurrenceSchema(),
            readOnly = false,
            destructive = true,
            idempotent = true
        ) { args -> unmarkPaid(args) },
        McpTool(
            name = "skip_occurrence",
            title = "Skip occurrence",
            description = "Skips one scheduled occurrence; the rest of the schedule is unchanged.",
            inputSchema = occurrenceSchema(),
            readOnly = false,
            idempotent = true
        ) { args -> skipOccurrence(args) },
        McpTool(
            name = "change_occurrence_amount",
            title = "Change occurrence amount",
            description = "Overrides the amount of one scheduled occurrence; the rest of the schedule is unchanged.",
            inputSchema = occurrenceSchema(required = listOf("amount")) {
                amountProperty("amount", "New positive amount for this occurrence.")
            },
            readOnly = false,
            idempotent = true
        ) { args -> changeOccurrenceAmount(args) }
    )

    // ─── Reads ──────────────────────────────────────────────────────────────

    private suspend fun listAccounts(): JsonElement {
        val all = accountList()
        return buildJsonObject {
            put("accounts", JsonArray(all.map { it.toJson() }))
            put("totalBalance", money(all.sumOf { it.balance }))
        }
    }

    private suspend fun listCategories(): JsonElement = buildJsonObject {
        put("categories", JsonArray(categories.getAll().sortedBy { it.name.lowercase() }.map { it.toJson() }))
    }

    private suspend fun listSchedules(args: JsonObject): JsonElement {
        val type = args.enumOrNull<ScheduleType>("type")
        val accountNames = accountNames()
        val categoryNames = categories.getAll().associate { it.id to it.name }
        val rows = schedules.getAll()
            .filter { type == null || it.type == type }
            .sortedBy { it.title.lowercase() }
            .map { s ->
                buildJsonObject {
                    put("id", s.id)
                    put("title", s.title)
                    put("type", s.type.name)
                    put("amount", money(s.amount))
                    put("recurrence", describeRecurrence(s))
                    put("frequency", s.freq.name)
                    if (s.interval != 1) put("interval", s.interval)
                    put("startDate", s.startDate.toString())
                    s.endDate?.let { put("endDate", it.toString()) }
                    put("accountId", s.accountId)
                    accountNames[s.accountId]?.let { put("accountName", it) }
                    s.counterAccountId?.let {
                        put("toAccountId", it)
                        accountNames[it]?.let { name -> put("toAccountName", name) }
                    }
                    s.categoryId?.let {
                        put("categoryId", it)
                        categoryNames[it]?.let { name -> put("categoryName", name) }
                    }
                    if (s.autoPay) put("autoPay", true)
                    s.notes?.let { put("notes", it) }
                }
            }
        return buildJsonObject { put("schedules", JsonArray(rows)) }
    }

    private suspend fun listTransactions(args: JsonObject): JsonElement {
        val end = args.dateOrNull("endDate") ?: today()
        val start = args.dateOrNull("startDate") ?: end.minus(DEFAULT_TRANSACTION_DAYS - 1, DateTimeUnit.DAY)
        checkRange(start, end)
        val search = args.stringOrNull("search")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val accountId = args.stringOrNull("accountId")
        val limit = args.intOrNull("limit")?.coerceAtLeast(1) ?: DEFAULT_TRANSACTION_LIMIT
        val matching = postings.watchBetween(start, end.plus(1, DateTimeUnit.DAY)).first()
            .filter { accountId == null || it.posting.accountId == accountId }
            .filter { row ->
                search == null || listOfNotNull(row.title, row.accountName, row.categoryName)
                    .any { it.lowercase().contains(search) }
            }
        return buildJsonObject {
            put("startDate", start.toString())
            put("endDate", end.toString())
            put("transactions", JsonArray(matching.take(limit).map { row ->
                val p = row.posting
                buildJsonObject {
                    put("id", p.id)
                    put("date", p.date.toString())
                    put("title", row.title)
                    put("type", p.type.name)
                    put("amount", money(p.amount))
                    put("accountId", p.accountId)
                    row.accountName?.let { put("accountName", it) }
                    p.categoryId?.let { put("categoryId", it) }
                    row.categoryName?.let { put("categoryName", it) }
                    p.scheduleId?.let { put("scheduleId", it) }
                    p.pairId?.takeIf { p.scheduleId == null }?.let { put("pairId", it) }
                }
            }))
            if (matching.size > limit) put("truncated", true)
        }
    }

    private suspend fun listUpcoming(args: JsonObject): JsonElement {
        val start = args.dateOrNull("startDate") ?: today()
        val end = args.dateOrNull("endDate") ?: start.plus(DEFAULT_UPCOMING_DAYS - 1, DateTimeUnit.DAY)
        checkRange(start, end)
        val accountId = args.stringOrNull("accountId")
        checkProjectionWork(start, end.plus(1, DateTimeUnit.DAY))
        val items = window.watchWindow(start, end.plus(1, DateTimeUnit.DAY)).first()
            .filter { accountId == null || it.tx.accountId == accountId || it.tx.counterAccountId == accountId }
        return buildJsonObject {
            put("startDate", start.toString())
            put("endDate", end.toString())
            put("occurrences", JsonArray(items.map { it.toJson() }))
        }
    }

    private suspend fun monthlyBudget(args: JsonObject): JsonElement {
        val month = args.stringOrNull("month")?.let {
            runCatching { YearMonth.parse(it) }.getOrNull() ?: throw McpToolException("month must look like 2026-10")
        } ?: today().let { YearMonth(it.year, it.month) }
        checkProjectionWork(month.firstDay, month.firstDay.plus(1, DateTimeUnit.MONTH))
        val summary = budget.computeMonthlyBudget(month.firstDay)
        val accountNames = accountNames()
        return buildJsonObject {
            put("month", month.toString())
            put("netChange", money(summary.netChange))
            put("lines", JsonArray(summary.lines.map { line ->
                buildJsonObject {
                    put("scheduleId", line.scheduleId)
                    put("title", line.title)
                    put("type", line.type.name)
                    put("occurrences", line.occurrences)
                    put("amountPerOccurrence", money(line.amountPerOccurrence))
                    put("totalAmount", money(line.totalAmount))
                    accountNames[line.accountId]?.let { put("accountName", it) }
                    line.counterAccountId?.let { accountNames[it] }?.let { put("toAccountName", it) }
                    line.category?.let { put("categoryName", it.name) }
                }
            }))
            put("categoryTotals", JsonArray(summary.categoryTotals.map { total ->
                buildJsonObject {
                    put("category", total.category?.name ?: "Uncategorized")
                    total.category?.let { put("categoryId", it.id) }
                    put("total", money(total.total))
                }
            }))
        }
    }

    private suspend fun projectBalance(args: JsonObject): JsonElement {
        val date = args.requireDate("date")
        if (date > today()) {
            checkRange(today(), date.plus(1, DateTimeUnit.DAY))
            checkProjectionWork(today(), date.plus(1, DateTimeUnit.DAY))
        }
        val accountId = args.stringOrNull("accountId")?.also { requireAccount(it) }
        return buildJsonObject {
            put("date", date.toString())
            accountId?.let { put("accountId", it) }
            put("balance", money(window.balanceAt(date, accountId)))
        }
    }

    // ─── Writes ─────────────────────────────────────────────────────────────

    private suspend fun recordTransaction(args: JsonObject): JsonElement {
        val title = args.requireString("title").trim().ifEmpty { throw McpToolException("title can't be blank") }
        val amount = args.requireAmount("amount")
        val type = args.requireEnum<ScheduleType>("type")
        val accountId = requireAccount(args.requireString("accountId")).id
        val categoryId = args.stringOrNull("categoryId")?.takeIf { it.isNotBlank() }?.also { requireCategory(it) }
        val date = args.dateOrNull("date") ?: today()
        val id = if (type == ScheduleType.TRANSFER) {
            val toAccountId = args.stringOrNull("toAccountId") ?: throw McpToolException("toAccountId is required for a TRANSFER")
            requireAccount(toAccountId)
            if (toAccountId == accountId) throw McpToolException("A transfer needs two different accounts")
            postings.recordAdHocTransfer(title, amount, accountId, toAccountId, categoryId, date)
        } else {
            postings.recordAdHoc(title, amount, type, accountId, categoryId, date)
        }
        return buildJsonObject {
            put("id", id)
            put("message", "Recorded ${type.name.lowercase()} \"$title\" of ${money(amount)} on $date")
        }
    }

    private suspend fun updateTransaction(args: JsonObject): JsonElement {
        val posting = requirePosting(args.requireString("id"))
        val partner = postings.getTransferPartner(posting)
        // The debit leg carries the source account, as in the transaction editor.
        val (debit, credit) = when {
            partner == null -> posting to null
            posting.type == ScheduleType.EXPENSE -> posting to partner
            else -> partner to posting
        }
        val title = args.stringOrNull("title")?.trim()?.ifEmpty { throw McpToolException("title can't be blank") }
        val amount = args.amountOrNull("amount")
        val date = args.dateOrNull("date")
        val accountId = args.stringOrNull("accountId")?.also { requireAccount(it) }
        val toAccountId = args.stringOrNull("toAccountId")?.also {
            if (credit == null) throw McpToolException("toAccountId only applies to transfers")
            requireAccount(it)
        }
        val categoryId = args.optionalId("categoryId")?.also { id -> id.value?.let { requireCategory(it) } }
        fun Posting.updated(account: String?) = copy(
            title = title ?: this.title,
            amount = amount ?: this.amount,
            date = date ?: this.date,
            accountId = account ?: this.accountId,
            categoryId = if (categoryId != null) categoryId.value else this.categoryId
        )
        val newDebit = debit.updated(accountId)
        val newCredit = credit?.updated(toAccountId)
        if (newCredit != null && newCredit.accountId == newDebit.accountId) {
            throw McpToolException("A transfer needs two different accounts")
        }
        postings.record(newDebit)
        newCredit?.let { postings.record(it) }
        return buildJsonObject { put("message", "Transaction updated") }
    }

    private suspend fun deleteTransaction(args: JsonObject): JsonElement {
        val posting = requirePosting(args.requireString("id"))
        occurrences.deletePostingWithPartner(posting.id)
        return buildJsonObject { put("message", "Transaction deleted") }
    }

    private suspend fun saveAccount(args: JsonObject): JsonElement {
        val existing = args.stringOrNull("id")?.let { requireAccount(it) }
        val name = args.stringOrNull("name")?.trim()?.ifEmpty { throw McpToolException("name can't be blank") }
        val account = Account(
            id = existing?.id ?: idGenerator.newId(),
            name = name ?: existing?.name ?: throw McpToolException("name is required when creating an account"),
            balance = args.doubleOrNull("balance") ?: existing?.balance ?: 0.0
        )
        accounts.upsert(account)
        return buildJsonObject {
            put("account", account.toJson())
            put("message", if (existing == null) "Account created" else "Account updated")
        }
    }

    private suspend fun saveCategory(args: JsonObject): JsonElement {
        val existing = args.stringOrNull("id")?.let { requireCategory(it) }
        val name = args.stringOrNull("name")?.trim()?.ifEmpty { throw McpToolException("name can't be blank") }
        val color = args.optionalId("color")?.value?.also {
            if (!HEX_COLOR.matches(it)) throw McpToolException("color must be a hex color like #4CAF50")
        }
        val category = Category(
            id = existing?.id ?: idGenerator.newId(),
            name = name ?: existing?.name ?: throw McpToolException("name is required when creating a category"),
            color = if (args.containsKey("color")) color else existing?.color
        )
        categories.upsert(category)
        return buildJsonObject {
            put("category", category.toJson())
            put("message", if (existing == null) "Category created" else "Category updated")
        }
    }

    private suspend fun saveSchedule(args: JsonObject): JsonElement {
        val existing = args.stringOrNull("id")?.let { id ->
            schedules.getAll().firstOrNull { it.id == id } ?: throw McpToolException("No schedule with id $id")
        }
        fun missing(field: String): Nothing = throw McpToolException("$field is required when creating a schedule")

        val type = args.enumOrNull<ScheduleType>("type") ?: existing?.type ?: missing("type")
        val title = args.stringOrNull("title")?.trim()?.ifEmpty { throw McpToolException("title can't be blank") }
            ?: existing?.title ?: missing("title")
        val amount = args.amountOrNull("amount") ?: existing?.amount ?: missing("amount")
        val accountId = args.stringOrNull("accountId")?.also { requireAccount(it) } ?: existing?.accountId ?: missing("accountId")
        val toAccountId = if (type == ScheduleType.TRANSFER) {
            (args.stringOrNull("toAccountId")?.also { requireAccount(it) } ?: existing?.counterAccountId)
                ?.also { if (it == accountId) throw McpToolException("A transfer needs two different accounts") }
                ?: throw McpToolException("toAccountId is required for a TRANSFER")
        } else {
            null
        }
        val categoryId = if (args.containsKey("categoryId")) {
            args.optionalId("categoryId")?.value?.also { requireCategory(it) }
        } else {
            existing?.categoryId
        }
        val freq = args.enumOrNull<Frequency>("frequency") ?: existing?.freq ?: Frequency.MONTHLY_BY_DAY
        val startDate = args.dateOrNull("startDate") ?: existing?.startDate ?: today()
        val endDate = when {
            freq == Frequency.ONCE -> null
            args.containsKey("endDate") -> args.optionalId("endDate")?.value?.let { parseDate("endDate", it) }
            else -> existing?.endDate
        }
        if (endDate != null && endDate < startDate) throw McpToolException("endDate is before startDate")
        val interval = args.intOrNull("interval")?.also { if (it < 1) throw McpToolException("interval must be at least 1") }
            ?: existing?.interval ?: 1
        val weekdays = args.weekdaysOrNull("weekdays")
        val changesRecurrence = existing == null || existing.freq != freq || args.containsKey("startDate")
        val dayMask = when (freq) {
            Frequency.WEEKLY -> weekdays?.toDayMask()?.takeIf { it != 0 }
                ?: existing?.byDayMask?.takeUnless { changesRecurrence }
                ?: (1 shl startDate.dayOfWeek.ordinal)
            Frequency.MONTHLY_BY_NTH_WEEKDAY -> weekdays?.firstOrNull()?.let { 1 shl it.ordinal }
                ?: existing?.byDayMask?.takeUnless { changesRecurrence }
                ?: (1 shl startDate.dayOfWeek.ordinal)
            else -> null
        }
        val monthDay = args.intOrNull("dayOfMonth")?.also {
            if (it !in 1..31) throw McpToolException("dayOfMonth must be between 1 and 31")
        }
        val nth = args.intOrNull("nthWeekday")?.also {
            if (it !in 1..5) throw McpToolException("nthWeekday must be between 1 and 5")
        }
        val schedule = Schedule(
            id = existing?.id ?: idGenerator.newId(),
            title = title,
            amount = amount,
            type = type,
            accountId = accountId,
            counterAccountId = toAccountId,
            categoryId = categoryId,
            startDate = startDate,
            endDate = endDate,
            freq = freq,
            interval = if (freq in REPEATING_WITH_INTERVAL) interval else 1,
            byDayMask = dayMask,
            byMonthDay = when (freq) {
                Frequency.MONTHLY_BY_DAY -> monthDay ?: existing?.byMonthDay?.takeUnless { changesRecurrence } ?: startDate.day
                Frequency.YEARLY -> startDate.day
                else -> null
            },
            nthWeekday = if (freq == Frequency.MONTHLY_BY_NTH_WEEKDAY) {
                nth ?: existing?.nthWeekday?.takeUnless { changesRecurrence } ?: ((startDate.day - 1) / 7 + 1)
            } else {
                null
            },
            lastDayFlag = freq == Frequency.MONTHLY_BY_DAY &&
                (args.booleanOrNull("lastDayOfMonth") ?: existing?.lastDayFlag ?: false),
            autoPay = args.booleanOrNull("autoPay") ?: existing?.autoPay ?: false,
            notes = if (args.containsKey("notes")) args.stringOrNull("notes")?.trim()?.ifBlank { null } else existing?.notes
        )
        schedules.upsert(schedule)
        return buildJsonObject {
            put("id", schedule.id)
            put("recurrence", describeRecurrence(schedule))
            put("message", if (existing == null) "Schedule created" else "Schedule updated")
        }
    }

    private suspend fun deleteSchedule(args: JsonObject): JsonElement {
        val id = args.requireString("id")
        val schedule = schedules.getAll().firstOrNull { it.id == id } ?: throw McpToolException("No schedule with id $id")
        schedules.delete(schedule.id)
        return buildJsonObject { put("message", "${schedule.title} deleted") }
    }

    private suspend fun markPaid(args: JsonObject): JsonElement {
        val item = requireOccurrence(args)
        if (item.posted) throw McpToolException("${item.tx.title} on ${item.tx.date} is already paid")
        val amount = args.amountOrNull("amount")
        val result = occurrences.markPosted(if (amount == null) item else item.copy(tx = item.tx.copy(amount = amount)))
        return buildJsonObject { put("message", result.message) }
    }

    private suspend fun unmarkPaid(args: JsonObject): JsonElement {
        val item = requireOccurrence(args)
        val result = occurrences.unmarkPosted(item)
            ?: throw McpToolException("${item.tx.title} on ${item.tx.date} isn't paid")
        return buildJsonObject { put("message", result.message) }
    }

    private suspend fun skipOccurrence(args: JsonObject): JsonElement {
        val item = requireOccurrence(args)
        if (item.posted) throw McpToolException("${item.tx.title} on ${item.tx.date} is already paid; unmark it first")
        return buildJsonObject { put("message", occurrences.skip(item).message) }
    }

    private suspend fun changeOccurrenceAmount(args: JsonObject): JsonElement {
        val item = requireOccurrence(args)
        if (item.posted) throw McpToolException("${item.tx.title} on ${item.tx.date} is already paid; use update_transaction")
        val amount = args.requireAmount("amount")
        return buildJsonObject { put("message", occurrences.changeAmount(item, amount).message) }
    }

    // ─── Lookups ────────────────────────────────────────────────────────────

    private suspend fun accountList(): List<Account> =
        accounts.getAll().values.sortedBy { it.name.lowercase() }

    private suspend fun accountNames(): Map<String, String> =
        accounts.getAll().values.associate { it.id to it.name }

    private suspend fun requireAccount(id: String): Account =
        accounts.getAll()[id] ?: throw McpToolException("No account with id $id")

    private suspend fun requireCategory(id: String): Category =
        categories.getAll().firstOrNull { it.id == id } ?: throw McpToolException("No category with id $id")

    private suspend fun requirePosting(id: String): Posting =
        postings.getById(id) ?: throw McpToolException("No transaction with id $id")

    private suspend fun requireOccurrence(args: JsonObject): ScheduledTxWithAccount {
        val scheduleId = args.requireString("scheduleId")
        val date = args.requireDate("date")
        checkProjectionWork(date, date.plus(1, DateTimeUnit.DAY))
        return window.watchWindow(date, date.plus(1, DateTimeUnit.DAY)).first()
            .firstOrNull { it.tx.scheduleId == scheduleId && it.tx.date == date }
            ?: throw McpToolException("Schedule $scheduleId has no occurrence on $date (it may be skipped or not fall on that day)")
    }

    private suspend fun checkProjectionWork(start: LocalDate, end: LocalDate) {
        val days = end.toEpochDays().toLong() - start.toEpochDays().toLong()
        val legs = schedules.getIntersecting(start, end).sumOf { if (it.type == ScheduleType.TRANSFER) 2L else 1L }
        if (days * legs > 250_000L) throw McpToolException("Projection is too large; use a smaller date range")
    }

    private fun checkRange(start: LocalDate, end: LocalDate) {
        if (end < start) throw McpToolException("endDate is before startDate")
        if (start.plus(MAX_RANGE_DAYS, DateTimeUnit.DAY) < end) throw McpToolException("Date range is limited to $MAX_RANGE_DAYS days")
    }
}

private val HEX_COLOR = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

private val REPEATING_WITH_INTERVAL = setOf(
    Frequency.DAILY, Frequency.WEEKLY, Frequency.MONTHLY_BY_DAY, Frequency.MONTHLY_BY_NTH_WEEKDAY, Frequency.YEARLY
)

// ─── JSON output ────────────────────────────────────────────────────────────

/** Rounds to cents so binary doubles don't leak as 12.299999999. */
private fun money(amount: Double): JsonPrimitive = JsonPrimitive((amount * 100).roundToLong() / 100.0)

private fun Account.toJson() = buildJsonObject {
    put("id", id)
    put("name", name)
    put("balance", money(balance))
}

private fun Category.toJson() = buildJsonObject {
    put("id", id)
    put("name", name)
    color?.let { put("color", it) }
}

private fun ScheduledTxWithAccount.toJson() = buildJsonObject {
    put("scheduleId", tx.scheduleId)
    put("date", tx.date.toString())
    put("title", tx.title)
    put("type", tx.type.name)
    put("amount", money(tx.amount))
    put("status", if (posted) "paid" else "due")
    postingId?.let { put("transactionId", it) }
    put("accountId", tx.accountId)
    account?.let { put("accountName", it.name) }
    tx.counterAccountId?.takeIf { tx.type == ScheduleType.TRANSFER }?.let { put("toAccountId", it) }
    counterAccount?.takeIf { tx.type == ScheduleType.TRANSFER }?.let { put("toAccountName", it.name) }
    category?.let { put("categoryName", it.name) }
    if (tx.autoPay) put("autoPay", true)
}

// ─── Input schemas ──────────────────────────────────────────────────────────

private fun schema(required: List<String> = emptyList(), properties: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
    put("type", "object")
    putJsonObject("properties", properties)
    if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    put("additionalProperties", false)
}

private fun occurrenceSchema(required: List<String> = emptyList(), extra: JsonObjectBuilder.() -> Unit = {}) =
    schema(required = listOf("scheduleId", "date") + required) {
        stringProperty("scheduleId", "Schedule id (from list_upcoming or list_schedules).")
        dateProperty("date", "The occurrence's date.")
        extra()
    }

private fun JsonObjectBuilder.stringProperty(name: String, description: String, pattern: String? = null) =
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
        pattern?.let { put("pattern", it) }
    }

private fun JsonObjectBuilder.dateProperty(name: String, description: String) =
    putJsonObject(name) {
        put("type", "string")
        put("format", "date")
        put("description", "$description YYYY-MM-DD.")
    }

private fun JsonObjectBuilder.numberProperty(name: String, description: String) =
    putJsonObject(name) {
        put("type", "number")
        put("description", description)
    }

private fun JsonObjectBuilder.amountProperty(name: String, description: String) =
    putJsonObject(name) {
        put("type", "number")
        put("exclusiveMinimum", 0)
        put("description", description)
    }

private fun JsonObjectBuilder.integerProperty(name: String, description: String, minimum: Int? = null, maximum: Int? = null) =
    putJsonObject(name) {
        put("type", "integer")
        put("description", description)
        minimum?.let { put("minimum", it) }
        maximum?.let { put("maximum", it) }
    }

private fun JsonObjectBuilder.booleanProperty(name: String, description: String) =
    putJsonObject(name) {
        put("type", "boolean")
        put("description", description)
    }

private fun <E : Enum<E>> JsonObjectBuilder.enumProperty(name: String, description: String, values: List<E>) =
    putJsonObject(name) {
        put("type", "string")
        putJsonArray("enum") { values.forEach { add(JsonPrimitive(it.name)) } }
        put("description", description)
    }

// ─── Argument parsing ───────────────────────────────────────────────────────

/** A field that may be cleared: present with [value] null means "clear". */
private class OptionalValue(val value: String?)

private fun JsonObject.primitive(name: String): JsonPrimitive? = when (val value = this[name]) {
    null, is JsonNull -> null
    is JsonPrimitive -> value
    else -> throw McpToolException("$name has the wrong type")
}

private fun JsonObject.stringOrNull(name: String): String? = primitive(name)?.let {
    if (!it.isString) throw McpToolException("$name must be a string")
    it.content
}

private fun JsonObject.requireString(name: String): String =
    stringOrNull(name)?.takeIf { it.isNotBlank() } ?: throw McpToolException("$name is required")

/** Present-and-empty (or null) clears the field; absent returns null. */
private fun JsonObject.optionalId(name: String): OptionalValue? =
    if (!containsKey(name)) null else OptionalValue(stringOrNull(name)?.takeIf { it.isNotBlank() })

private fun JsonObject.doubleOrNull(name: String): Double? = primitive(name)?.let {
    if (it.isString) throw McpToolException("$name must be a number")
    it.doubleOrNull ?: throw McpToolException("$name must be a number")
}

private fun JsonObject.amountOrNull(name: String): Double? = doubleOrNull(name)?.also {
    if (it <= 0.0 || it.isNaN() || it.isInfinite()) throw McpToolException("$name must be a positive number")
}

private fun JsonObject.requireAmount(name: String): Double =
    amountOrNull(name) ?: throw McpToolException("$name is required")

private fun JsonObject.intOrNull(name: String): Int? = primitive(name)?.let {
    if (it.isString) throw McpToolException("$name must be a whole number")
    it.intOrNull ?: it.doubleOrNull?.takeIf { d -> d % 1.0 == 0.0 }?.toInt()
        ?: throw McpToolException("$name must be a whole number")
}

private fun JsonObject.booleanOrNull(name: String): Boolean? = primitive(name)?.let {
    if (it.isString) throw McpToolException("$name must be true or false")
    it.booleanOrNull ?: throw McpToolException("$name must be true or false")
}

private fun parseDate(name: String, value: String): LocalDate =
    runCatching { LocalDate.parse(value.trim()) }.getOrNull()
        ?: throw McpToolException("$name must be a date like 2026-10-01")

private fun JsonObject.dateOrNull(name: String): LocalDate? =
    stringOrNull(name)?.takeIf { it.isNotBlank() }?.let { parseDate(name, it) }

private fun JsonObject.requireDate(name: String): LocalDate =
    dateOrNull(name) ?: throw McpToolException("$name is required")

private inline fun <reified E : Enum<E>> JsonObject.enumOrNull(name: String): E? =
    stringOrNull(name)?.let { raw ->
        enumValues<E>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: throw McpToolException("$name must be one of ${enumValues<E>().joinToString { it.name }}")
    }

private inline fun <reified E : Enum<E>> JsonObject.requireEnum(name: String): E =
    enumOrNull<E>(name) ?: throw McpToolException("$name is required")

private fun JsonObject.weekdaysOrNull(name: String): Set<DayOfWeek>? = when (val value = this[name]) {
    null, is JsonNull -> null
    is JsonArray -> value.map { element ->
        val raw = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        DayOfWeek.entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
            ?: throw McpToolException("$name must list weekdays like MONDAY")
    }.toSet()
    else -> throw McpToolException("$name must be an array of weekdays")
}
