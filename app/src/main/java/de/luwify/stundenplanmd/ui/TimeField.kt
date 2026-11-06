package de.luwify.stundenplanmd.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.R
import java.time.LocalTime

/**
 * A time as typed into [TimeField]: at most four digits and no colon, so there is no colon to delete. The last two of
 * three or four digits are the minutes: "1015" = 10:15, "915" = 09:15. Fewer digits are no time yet.
 */
object TimeDigits {
    private const val MAX = 4

    enum class Check { EMPTY, PARTIAL, VALID, INVALID }

    fun of(time: LocalTime): String = "%02d%02d".format(time.hour, time.minute)

    /** The time [digits] stand for; null when incomplete or out of range, never corrected. */
    fun parse(digits: String): LocalTime? {
        if (digits.length !in 3..MAX || !digits.all(::isDigit)) return null
        val hour = digits.dropLast(2).toInt()
        val minute = digits.takeLast(2).toInt()
        return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
    }

    /** PARTIAL = no time yet, but more digits can make one ("91" -> "915", "175" -> "1750"): no error while typing. */
    fun check(digits: String): Check = when {
        digits.isEmpty() -> Check.EMPTY
        parse(digits) != null -> Check.VALID
        digits.length < MAX && ('0'..'9').any { check(digits + it) != Check.INVALID } -> Check.PARTIAL
        else -> Check.INVALID
    }

    /**
     * What an edit leaves: digits only (a pasted "10:15" works). An edit that adds nothing but other characters, or that
     * would need a fifth digit, is refused with the cursor where it was.
     */
    fun edited(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
        val digits = new.text.filter(::isDigit)
        return when {
            digits.length > MAX -> old
            digits == new.text -> new
            digits == old.text -> old
            else -> TextFieldValue(digits, TextRange(new.text.take(new.selection.end).count(::isDigit)))
        }
    }

    private fun isDigit(c: Char) = c in '0'..'9'

    /** "1015" -> "10:15", "915" -> "09:15", "10" -> "10:", "9" -> "9". */
    fun format(digits: String): String = when (digits.length) {
        0, 1 -> digits
        2 -> "$digits:"
        else -> digits.dropLast(2).padStart(2, '0') + ":" + digits.takeLast(2)
    }

    private fun pad(length: Int) = if (length == 3) 1 else 0

    /** Cursor in [format]'s text for a cursor in the digits. The end of the hours is behind the colon. */
    fun shownOffset(offset: Int, length: Int): Int {
        if (length < 2) return offset
        val hours = if (length == 2) 2 else length - 2
        return offset + pad(length) + if (offset >= hours) 1 else 0
    }

    /** Cursor in the digits for one in [format]'s text; both sides of the colon are the same place. */
    fun digitOffset(offset: Int, length: Int): Int {
        if (length < 2) return offset
        return (offset - pad(length) - if (offset > 2) 1 else 0).coerceIn(0, length)
    }
}

private object TimeTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val length = text.length
        return TransformedText(
            AnnotatedString(TimeDigits.format(text.text)),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = TimeDigits.shownOffset(offset, length)
                override fun transformedToOriginal(offset: Int) = TimeDigits.digitOffset(offset, length)
            },
        )
    }
}

/**
 * The one way to enter a time: [digits] as in [TimeDigits], the colon only drawn, and a clock button for the Material
 * time picker. Red for what can't become a time, and for anything unfinished once focus has left; [outOfOrder] marks a
 * good time that doesn't fit its partner (end before start).
 */
@Composable
fun TimeField(digits: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, outOfOrder: Boolean = false) {
    var field by remember { mutableStateOf(TextFieldValue(digits, TextRange(digits.length))) }
    val value = field.takeIf { it.text == digits } ?: TextFieldValue(digits, TextRange(digits.length)) // the picker set it
    var focused by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val check = TimeDigits.check(digits)
    OutlinedTextField(
        value,
        { field = TimeDigits.edited(value, it); onChange(field.text) },
        modifier.onFocusChanged { focused = it.isFocused },
        singleLine = true,
        isError = outOfOrder || check == TimeDigits.Check.INVALID || (!focused && check != TimeDigits.Check.VALID),
        visualTransformation = TimeTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = { picking = true }) { Icon(painterResource(R.drawable.ic_schedule), stringResource(R.string.time_pick, label)) }
        },
    )
    if (picking) {
        TimePick(TimeDigits.parse(digits) ?: LocalTime.MIDNIGHT, onPick = { onChange(TimeDigits.of(it)); picking = false }, onDismiss = { picking = false })
    }
}

/** Material's dial with the toggle to keyboard input, 24 hours. A window too low for the dial gets the keyboard input. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePick(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    var keyboard by rememberSaveable { mutableStateOf(false) }
    val dialFits = LocalConfiguration.current.screenHeightDp.dp > TimePickerDialogDefaults.MinHeightForTimePicker
    val mode = if (keyboard || !dialFits) TimePickerDisplayMode.Input else TimePickerDisplayMode.Picker
    TimePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            DialogWindowAnimation(R.style.Motion_Dialog)
            TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(android.R.string.ok)) }
        },
        title = { TimePickerDialogDefaults.Title(mode) },
        modeToggleButton = { if (dialFits) TimePickerDialogDefaults.DisplayModeToggle({ keyboard = !keyboard }, mode) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        if (mode == TimePickerDisplayMode.Picker) TimePicker(state) else TimeInput(state)
    }
}
