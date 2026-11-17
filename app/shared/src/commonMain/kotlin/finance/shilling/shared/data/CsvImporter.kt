package finance.shilling.shared.data

import kotlinx.datetime.LocalDate

data class CsvColumnMapping(
    val dateColumn: Int,
    val descriptionColumn: Int,
    val amountColumn: Int,
    val dateFormat: DateFormat = DateFormat.ISO
)

enum class DateFormat {
    ISO,           // 2024-01-15
    US_SLASH,      // 01/15/2024
    US_DASH,       // 01-15-2024
    EU_SLASH,      // 15/01/2024
    EU_DASH        // 15-01-2024
}

data class CsvPreviewRow(
    val rowIndex: Int,
    val raw: List<String>,
    val parsedDate: LocalDate?,
    val parsedDescription: String?,
    val parsedAmount: Double?,
    val isValid: Boolean
)

object CsvImporter {

    fun parseHeaders(csvContent: String): List<String> {
        val first = csvContent.lineSequence().firstOrNull { it.isNotBlank() } ?: return emptyList()
        return parseCsvLine(first)
    }

    fun preview(
        csvContent: String,
        mapping: CsvColumnMapping,
        hasHeader: Boolean = true,
        maxRows: Int = 20
    ): List<CsvPreviewRow> {
        return parseRows(csvContent, mapping, hasHeader).take(maxRows).toList()
    }

    fun parseAll(
        csvContent: String,
        mapping: CsvColumnMapping,
        hasHeader: Boolean = true
    ): List<CsvPreviewRow> = parseRows(csvContent, mapping, hasHeader).toList()

    /** Streaming consumption avoids retaining a second full parsed CSV during import. */
    fun parseRows(
        csvContent: String,
        mapping: CsvColumnMapping,
        hasHeader: Boolean = true
    ): Sequence<CsvPreviewRow> {
        val lines = csvContent.lineSequence().filter { it.isNotBlank() }
        val dataLines = if (hasHeader) lines.drop(1) else lines
        return dataLines.mapIndexed { index, line ->
            val fields = parseCsvLine(line)
            val date = fields.getOrNull(mapping.dateColumn)?.let { parseDate(it.trim(), mapping.dateFormat) }
            val description = fields.getOrNull(mapping.descriptionColumn)?.trim()
            val amount = fields.getOrNull(mapping.amountColumn)?.let { parseAmount(it.trim()) }
            CsvPreviewRow(
                rowIndex = index,
                raw = fields,
                parsedDate = date,
                parsedDescription = description,
                parsedAmount = amount,
                isValid = date != null && description != null && amount != null
            )
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && !inQuotes -> inQuotes = true
                ch == '"' && inQuotes -> {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                }
                ch == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }

    private fun parseDate(text: String, format: DateFormat): LocalDate? {
        return try {
            when (format) {
                DateFormat.ISO -> LocalDate.parse(text)
                DateFormat.US_SLASH -> {
                    val parts = text.split("/")
                    if (parts.size != 3) return null
                    LocalDate(parts[2].toInt(), parts[0].toInt(), parts[1].toInt())
                }
                DateFormat.US_DASH -> {
                    val parts = text.split("-")
                    if (parts.size != 3) return null
                    LocalDate(parts[2].toInt(), parts[0].toInt(), parts[1].toInt())
                }
                DateFormat.EU_SLASH -> {
                    val parts = text.split("/")
                    if (parts.size != 3) return null
                    LocalDate(parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
                }
                DateFormat.EU_DASH -> {
                    val parts = text.split("-")
                    if (parts.size != 3) return null
                    LocalDate(parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAmount(text: String): Double? {
        val cleaned = text
            .replace("$", "")
            .replace(",", "")
            .replace("(", "-")
            .replace(")", "")
            .trim()
        return cleaned.toDoubleOrNull()
    }
}
