package finance.shilling.shared.ui

import finance.shilling.shared.data.Account
import finance.shilling.shared.data.Category
import finance.shilling.shared.data.Frequency
import finance.shilling.shared.data.Schedule
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.ScheduleRepository
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.minus
import finance.shilling.shared.presentation.startOfMonth
import finance.shilling.shared.presentation.toDayMask
import finance.shilling.shared.presentation.today

/** Developer utility: populate a small, realistic household for local testing. */
suspend fun seedDemoData(
    accountRepo: AccountRepository,
    categoryRepo: CategoryRepository,
    scheduleRepo: ScheduleRepository,
    windowUseCase: ComputeWindowUseCase
) {
    val now = today()
    val account = Account(id = "checking", name = "Checking", balance = 1850.0)
    val savings = Account(id = "savings", name = "Savings", balance = 3200.0)
    accountRepo.upsert(account)
    accountRepo.upsert(savings)
    val housing = Category(id = "cat-housing", name = "Housing", color = "#EF5350")
    val groceries = Category(id = "cat-groceries", name = "Groceries", color = "#8BC34A")
    val subscriptions = Category(id = "cat-subscriptions", name = "Subscriptions", color = "#FF9800")
    val income = Category(id = "cat-income", name = "Income", color = "#4CAF50")
    val transfers = Category(id = "cat-transfers", name = "Transfers", color = "#9C27B0")
    listOf(housing, groceries, subscriptions, income, transfers).forEach { categoryRepo.upsert(it) }
    val startOfMonth = now.startOfMonth()
    val fridayWindow = windowUseCase.computeFridayWindow(now)
    scheduleRepo.upsert(
        Schedule(
            id = "rent",
            title = "Rent",
            amount = 1450.0,
            type = ScheduleType.EXPENSE,
            accountId = account.id,
            categoryId = housing.id,
            startDate = startOfMonth,
            freq = Frequency.MONTHLY_BY_DAY,
            byMonthDay = 1
        )
    )
    scheduleRepo.upsert(
        Schedule(
            id = "groceries",
            title = "Groceries",
            amount = 180.0,
            type = ScheduleType.EXPENSE,
            accountId = account.id,
            categoryId = groceries.id,
            startDate = fridayWindow.first,
            freq = Frequency.WEEKLY,
            byDayMask = setOf(DayOfWeek.SATURDAY).toDayMask()
        )
    )
    scheduleRepo.upsert(
        Schedule(
            id = "subscription",
            title = "Streaming",
            amount = 19.99,
            type = ScheduleType.EXPENSE,
            accountId = account.id,
            categoryId = subscriptions.id,
            startDate = fridayWindow.second.minus(2, DateTimeUnit.DAY),
            freq = Frequency.MONTHLY_BY_DAY,
            byMonthDay = fridayWindow.second.minus(2, DateTimeUnit.DAY).day
        )
    )
    scheduleRepo.upsert(
        Schedule(
            id = "paycheck",
            title = "Paycheck",
            amount = 2100.0,
            type = ScheduleType.INCOME,
            accountId = account.id,
            categoryId = income.id,
            startDate = fridayWindow.first.minus(7, DateTimeUnit.DAY),
            freq = Frequency.WEEKLY,
            byDayMask = setOf(DayOfWeek.FRIDAY).toDayMask()
        )
    )
    scheduleRepo.upsert(
        Schedule(
            id = "auto-save",
            title = "Auto-save",
            amount = 250.0,
            type = ScheduleType.TRANSFER,
            accountId = account.id,
            counterAccountId = savings.id,
            categoryId = transfers.id,
            startDate = startOfMonth,
            freq = Frequency.MONTHLY_BY_DAY,
            byMonthDay = 15
        )
    )
}
