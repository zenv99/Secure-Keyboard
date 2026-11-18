// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.inputlogic.TextReplacement
import helium314.keyboard.latin.inputlogic.TextReplacementRules
import helium314.keyboard.latin.inputlogic.TextReplacements
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog

@Composable
fun TextReplacementScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    TextReplacements.ensureLoaded(context)
    var entries by remember { mutableStateOf(TextReplacements.all()) }
    var editing by remember { mutableStateOf<TextReplacement?>(null) }
    var adding by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    fun save(updated: List<TextReplacement>) {
        saveFailed = !TextReplacements.replaceAll(context, updated)
        entries = TextReplacements.all()
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.text_replacement_title),
        settings = emptyList(),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            TextButton(onClick = { adding = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.text_replacement_add))
            }
            Text(
                stringResource(R.string.text_replacement_summary),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (saveFailed) {
                Text(
                    stringResource(R.string.text_replacement_error_save),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.text_replacement_empty),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            entries.forEach { entry ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editing = entry }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(entry.shortcut, style = MaterialTheme.typography.titleMedium)
                    Text(entry.phrase, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    val current = editing
    if (adding || current != null) {
        ReplacementDialog(
            initial = current ?: TextReplacement("", ""),
            existing = entries,
            isNew = current == null,
            onDismiss = { adding = false; editing = null },
            onSave = { shortcut, phrase ->
                val next = entries.filterNot { it.shortcut == current?.shortcut || it.shortcut == shortcut }
                    .plus(TextReplacement(shortcut, phrase))
                    .sortedBy { it.shortcut.lowercase() }
                save(next)
                adding = false
                editing = null
            },
            onDelete = {
                if (current != null) save(entries.filterNot { it.shortcut == current.shortcut })
                adding = false
                editing = null
            },
        )
    }
}

@Composable
private fun ReplacementDialog(
    initial: TextReplacement,
    existing: List<TextReplacement>,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (shortcut: String, phrase: String) -> Unit,
    onDelete: () -> Unit,
) {
    var shortcut by remember { mutableStateOf(initial.shortcut) }
    var phrase by remember { mutableStateOf(initial.phrase) }
    val trimmedShortcut = shortcut.trim()
    val duplicate = existing.any {
        it.shortcut != initial.shortcut && it.shortcut.equals(trimmedShortcut, ignoreCase = true)
    }
    val error = if (duplicate) "duplicate" else TextReplacementRules.validate(shortcut, phrase)
    ThreeButtonAlertDialog(
        onDismissRequest = onDismiss,
        onConfirmed = { onSave(trimmedShortcut, phrase.trim()) },
        checkOk = { error == null },
        confirmButtonText = stringResource(R.string.save),
        neutralButtonText = if (isNew) null else stringResource(R.string.delete),
        onNeutral = onDelete,
        title = {
            Text(stringResource(if (isNew) R.string.text_replacement_add else R.string.text_replacement_edit))
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = shortcut,
                    onValueChange = { shortcut = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.text_replacement_shortcut)) },
                    placeholder = { Text(stringResource(R.string.text_replacement_shortcut_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                TextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.text_replacement_phrase)) },
                    placeholder = { Text(stringResource(R.string.text_replacement_phrase_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                val message = when (error) {
                    "shortcut" -> stringResource(R.string.text_replacement_error_shortcut)
                    "phrase" -> stringResource(R.string.text_replacement_error_phrase)
                    "same" -> stringResource(R.string.text_replacement_error_same)
                    "duplicate" -> stringResource(R.string.text_replacement_error_duplicate)
                    else -> null
                }
                if (message != null && (shortcut.isNotEmpty() || phrase.isNotEmpty())) {
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}
