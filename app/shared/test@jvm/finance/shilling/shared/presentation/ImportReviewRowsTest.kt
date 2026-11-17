package finance.shilling.shared.presentation

import finance.shilling.shared.data.Category
import finance.shilling.shared.data.CsvColumnMapping
import finance.shilling.shared.data.CsvImporter
import finance.shilling.shared.data.DateFormat
import kotlinx.datetime.LocalDate
import kotlin.test.*

class ImportReviewRowsTest {
    private val today = LocalDate(2026, 10, 6)

    @Test
    fun singleRowEditsReuseOtherRowsAndLeavePublishedSnapshotsUnchanged() {
        val source = List(10_000) { ImportReviewRow(it, "Transaction $it", today, "Transaction $it", -(it + 1.25), true) }
        val projection = ImportReviewRows()
        val categories = mapOf("food" to Category("food", "Food", "#123456"))
        fun project(excluded: Int? = null, assigned: Int? = null) = projection.project(
            source, emptySet(), categories, { if (it == assigned) "food" else null }, { it.rowIndex != excluded }, today
        )
        val original = project()
        val unchecked = project(excluded = 5000)
        assertTrue(original[5000].included)
        assertFalse(unchecked[5000].included)
        assertEquals(1, original.indices.count { original[it] !== unchecked[it] }, "One checkbox must not recreate 10,000 row models")
        val categorized = project(excluded = 5000, assigned = 9999)
        assertNull(unchecked[9999].categoryId)
        assertEquals("food", categorized[9999].categoryId)
        assertEquals("Food", categorized[9999].categoryLabel)
        assertEquals("#123456", categorized[9999].categoryColor)
        assertEquals(1, unchecked.indices.count { unchecked[it] !== categorized[it] })
        assertSame(categorized, project(excluded = 5000, assigned = 9999), "An unchanged projection should reuse its published list")
    }

    @Test
    fun duplicateDecisionsCategoryMetadataAndCalendarChangesRefreshTheirLabels() {
        val source = List(3) { ImportReviewRow(it, "Transaction $it", today, "Transaction $it", -15.75, true) }
        val projection = ImportReviewRows()
        fun project(duplicates: Set<Int>, category: Category?, reference: LocalDate = today) = projection.project(
            source, duplicates, listOfNotNull(category).associateBy { it.id }, { if (it == 0) "food" else null },
            { it.rowIndex !in duplicates }, reference
        )
        val original = project(emptySet(), Category("food", "Food"))
        val duplicate = project(setOf(1), Category("food", "Food"))
        assertSame(original[0], duplicate[0])
        assertSame(original[2], duplicate[2])
        assertEquals("Tue, Oct 6 · Already imported", duplicate[1].supporting)
        assertFalse(duplicate[1].included)
        val renamed = project(setOf(1), Category("food", "Groceries", "#abcdef"))
        assertEquals("Groceries", renamed[0].categoryLabel)
        assertEquals("#abcdef", renamed[0].categoryColor)
        assertSame(duplicate[1], renamed[1])
        val deleted = project(emptySet(), null)
        assertNull(deleted[0].categoryId)
        assertEquals("Uncategorized", deleted[0].categoryLabel)
        assertTrue(deleted[1].included)
        assertEquals("Tue, Oct 6", deleted[1].supporting)
        assertEquals("Tue, Oct 6, 2026", project(emptySet(), null, LocalDate(2027, 1, 1))[1].supporting)
    }

    @Test
    fun replacementFilesAndRemappingPreserveInvalidRowDetailsAndClearOldRows() {
        val projection = ImportReviewRows()
        val csv = "date,description,amount\n10/06/2026,,-15.75\nbad,Unreadable,nope\n10/06/2026,Refund,5.00"
        fun parsed(format: DateFormat) = CsvImporter.parseRows(csv, CsvColumnMapping(0, 1, 2, format)).map(ImportReviewRow::from).toList()
        fun project(rows: List<ImportReviewRow>) = projection.project(rows, emptySet(), emptyMap(), { null }, { it.isValid }, today)
        val unreadable = project(parsed(DateFormat.ISO))
        assertEquals("Can't read date", unreadable[0].supporting)
        assertEquals("10/06/2026, , -15.75", unreadable[0].title)
        assertEquals("Can't read date, amount", unreadable[1].supporting)
        val mapped = project(parsed(DateFormat.US_SLASH))
        assertTrue(mapped[0].valid)
        assertFalse(mapped[1].valid)
        assertEquals("Tue, Oct 6", mapped[0].supporting)
        assertEquals("+${DisplayPreferences.currencySymbol}5.00", mapped[2].amount)
        assertTrue(project(emptyList()).isEmpty())
        val replacement = project(listOf(ImportReviewRow(0, "Replacement", today, "Replacement", -9.50, true)))
        assertEquals(1, replacement.size)
        assertEquals("Replacement", replacement[0].title)
        assertEquals("-${DisplayPreferences.currencySymbol}9.50", replacement[0].amount)
        assertEquals("Refund", mapped[2].title, "Previously published states must stay immutable")
    }
}
