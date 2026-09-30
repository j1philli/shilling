package finance.shilling.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Calendar_month
import com.composables.icons.materialicons.filled.Close
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import finance.shilling.shared.presentation.DisplayPreferences
import finance.shilling.shared.presentation.formatDateMedium
import finance.shilling.shared.presentation.parseAmountInput
import finance.shilling.shared.presentation.today

private const val MILLIS_PER_DAY = 86_400_000L

private fun LocalDate.toPickerMillis(): Long = toEpochDays() * MILLIS_PER_DAY
private fun Long.toPickerDate(): LocalDate = LocalDate.fromEpochDays(this / MILLIS_PER_DAY)

/** Single-line text field with sentence capitalization — the default for names and titles. */
@Composable
fun TextInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/** Money input: numeric keyboard, currency prefix, rejects anything that isn't a number. */
@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "Amount",
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    allowNegative: Boolean = false
) {
    val invalid = value.isNotBlank() && parseAmountInput(value) == null
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val filtered = raw.filterIndexed { index, ch ->
                ch.isDigit() || ch == '.' || ch == ',' || (allowNegative && ch == '-' && index == 0)
            }
            if (filtered.count { it == '.' } <= 1) onValueChange(filtered)
        },
        label = { Text(label) },
        prefix = { Text(DisplayPreferences.currencySymbol) },
        placeholder = { Text("0.00") },
        isError = invalid,
        supportingText = when {
            invalid -> { { Text("Enter a valid amount") } }
            supportingText != null -> { { Text(supportingText) } }
            else -> null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        modifier = modifier.fillMaxWidth()
    )
}

/** Whole-number input (day of month, interval). */
@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    isError: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onValueChange(raw.filter { it.isDigit() }.take(3)) },
        label = { Text(label) },
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = modifier
    )
}

/** Read-only field that opens a date picker. Set [onClear] to make the date optional. */
@Composable
fun DateField(
    value: LocalDate?,
    onValueChange: (LocalDate) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "Select a date",
    supportingText: String? = null,
    onClear: (() -> Unit)? = null
) {
    var showPicker by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value?.let { formatDateMedium(it) } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            supportingText = supportingText?.let { { Text(it) } },
            trailingIcon = {
                if (value != null && onClear != null) {
                    IconButton(onClick = onClear) {
                        Icon(MaterialIcons.Filled.Close, contentDescription = "Clear $label")
                    }
                } else {
                    Icon(MaterialIcons.Filled.Calendar_month, contentDescription = null)
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        // Overlay catches taps on the read-only field; it stops short of the trailing
        // clear button so that stays tappable.
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(end = if (value != null && onClear != null) 48.dp else 0.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showPicker = true }
        )
    }
    if (showPicker) {
        DatePickerModal(
            initial = value ?: today(),
            onConfirm = {
                onValueChange(it)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerModal(
    initial: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "OK"
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.toPickerMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onConfirm(it.toPickerDate()) } ?: onDismiss() }
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DatePicker(state = state)
    }
}

/**
 * Dropdown styled as a text field so selects line up with inputs in every form.
 * [noneOption] adds a leading "no selection" entry (e.g. "Uncategorized").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownField(
    label: String,
    selected: T?,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    noneOption: String? = null,
    placeholder: String = "Select",
    supportingText: String? = null,
    isError: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selected?.let(optionLabel) ?: noneOption ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            supportingText = supportingText?.let { { Text(it) } },
            isError = isError,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            noneOption?.let { none ->
                DropdownMenuItem(
                    text = { Text(none) },
                    onClick = { onSelect(null); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { onSelect(option); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}
