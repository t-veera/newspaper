package app.newspaper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/* Standard Android preference rows, built on Material 3 ListItem. */

@Composable
fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
fun Footnote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/** A row that opens something: a sub-screen or a dialog. */
@Composable
fun NavRow(title: String, summary: String? = null, icon: ImageVector? = null, trailing: (@Composable () -> Unit)? = null,
           onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it, maxLines = 2) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = trailing,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun SwitchRow(title: String, summary: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
fun CheckRow(title: String, summary: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
    )
}

@Composable
fun RadioRow(title: String, summary: String? = null, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
}

/** ListPreference: shows the current choice; tapping opens a radio-button dialog. */
@Composable
fun ChoiceRow(title: String, options: List<Pair<String, String>>, selected: String?, icon: ImageVector? = null,
              onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    NavRow(title, options.firstOrNull { it.first == selected }?.second ?: "Not set", icon) { open = true }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn {
                    items(options) { (id, name) ->
                        Row(Modifier.fillMaxWidth().selectable(selected = id == selected, role = Role.RadioButton) {
                            onPick(id); open = false
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = id == selected, onClick = null)
                            Text(name, Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

/** EditTextPreference: shows the value; tapping opens a text dialog. */
@Composable
fun TextRow(title: String, value: String, placeholder: String = "", summary: String? = null, icon: ImageVector? = null,
            secret: Boolean = false, validate: (String) -> Boolean = { true }, onSave: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    NavRow(title, summary ?: value.ifBlank { "Not set" }, icon) { open = true }
    if (open) {
        var draft by remember { mutableStateOf(if (secret) "" else value) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(draft, { draft = it }, singleLine = true, placeholder = { Text(placeholder) },
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = { onSave(draft.trim()); open = false }, enabled = validate(draft.trim())) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRow(title: String, time: LocalTime, enabled: Boolean = true, onChange: (LocalTime) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(time.format(fmt)) },
        modifier = Modifier.clickable(enabled = enabled) { open = true },
    )
    if (open) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { TimePicker(state = state) },
            confirmButton = { TextButton(onClick = { onChange(LocalTime.of(state.hour, state.minute)); open = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun Divider() = HorizontalDivider(Modifier.padding(vertical = 4.dp))

@Composable
fun SettingsColumn(content: @Composable () -> Unit) = Column(Modifier.fillMaxWidth()) { content() }
