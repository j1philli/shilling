package finance.shilling.shared.data.store

import finance.shilling.shared.data.*
import finance.shilling.shared.db.*
import kotlinx.datetime.LocalDate

fun Accounts.toDomain() = Account(id = id, name = name, balance = balance)

fun Categories.toDomain() = Category(id = id, name = name, color = color)

fun Schedules.toDomain() = Schedule(
    id = id,
    title = title,
    amount = amount,
    type = ScheduleType.valueOf(type),
    accountId = account_id ?: "",
    counterAccountId = counter_account_id,
    categoryId = category_id,
    startDate = LocalDate.fromEpochDays(start_date.toInt()),
    endDate = end_date?.let { LocalDate.fromEpochDays(it.toInt()) },
    freq = Frequency.valueOf(freq),
    interval = interval_.toInt(),
    byDayMask = by_day_mask?.toInt(),
    byMonthDay = by_month_day?.toInt(),
    nthWeekday = nth_weekday?.toInt(),
    lastDayFlag = last_day_flag == 1L,
    autoPay = auto_pay == 1L,
    notes = notes
)

fun Schedule_exceptions.toDomain() = ScheduleException(
    scheduleId = schedule_id,
    date = LocalDate.fromEpochDays(date.toInt()),
    skip = skip == 1L,
    overrideAmount = override_amount,
    overrideAccountId = override_account_id,
    overrideCounterAccountId = override_counter_account_id
)

fun Postings.toDomain() = Posting(
    id = id,
    scheduleId = schedule_id,
    type = ScheduleType.valueOf(type),
    accountId = account_id,
    date = LocalDate.fromEpochDays(date.toInt()),
    amount = amount,
    pairId = pair_id,
    title = title,
    categoryId = category_id
)

fun Receipts.toDomain() = Receipt(
    id = id,
    postingId = posting_id,
    filePath = file_path,
    originalName = original_name,
    addedAt = added_at,
    receiptDate = receipt_date,
    amount = amount,
    notes = notes
)

fun SelectWithDetails.toDomain(): PostingWithDetails {
    val posting = Posting(
        id = id,
        scheduleId = schedule_id,
        type = ScheduleType.valueOf(type),
        accountId = account_id,
        date = LocalDate.fromEpochDays(date.toInt()),
        amount = amount,
        pairId = pair_id,
        title = title,
        categoryId = category_id
    )
    return PostingWithDetails(
        posting = posting,
        title = title ?: schedule_title ?: "Transaction",
        accountName = account_name,
        categoryName = cat_name,
        categoryColor = cat_color
    )
}

fun SelectRecentWithDetails.toDomain(): PostingWithDetails {
    val posting = Posting(
        id = id,
        scheduleId = schedule_id,
        type = ScheduleType.valueOf(type),
        accountId = account_id,
        date = LocalDate.fromEpochDays(date.toInt()),
        amount = amount,
        pairId = pair_id,
        title = title,
        categoryId = category_id
    )
    return PostingWithDetails(
        posting = posting,
        title = title ?: schedule_title ?: "Transaction",
        accountName = account_name,
        categoryName = cat_name,
        categoryColor = cat_color
    )
}

fun SelectAllWithPostings.toDomain(): ReceiptWithPosting {
    val receipt = Receipt(
        id = id,
        postingId = posting_id,
        filePath = file_path,
        originalName = original_name,
        addedAt = added_at,
        receiptDate = receipt_date,
        amount = amount,
        notes = notes
    )
    return ReceiptWithPosting(
        receipt = receipt,
        postingTitle = posting_title,
        postingDate = posting_date?.let { LocalDate.fromEpochDays(it.toInt()).toString() }
    )
}
