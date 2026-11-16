package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Gives the built-in profiles their translated names. Call before reading the profiles. */
@Composable
fun rememberDownloadProfileNames() {
    val cached = stringResource(Res.string.downloads_profile_cached)
    val http = stringResource(Res.string.downloads_profile_http)
    val diy = stringResource(Res.string.downloads_profile_diy)
    remember(cached, http, diy) {
        DownloadProfilesRepository.setBuiltInNames(cached, http, diy)
        DownloadProfilesRepository.ensureLoaded()
    }
}

@Composable
private fun DownloadProfile.summary(): String = when (id) {
    PROFILE_ID_CACHED -> stringResource(Res.string.downloads_profile_cached_description)
    PROFILE_ID_HTTP -> stringResource(Res.string.downloads_profile_http_description)
    else -> stringResource(Res.string.downloads_profile_counts, rules.size, exclusions.size)
}

/** Pick the profile bulk download uses, or open one to view or edit it. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadProfilesSheet(onDismiss: () -> Unit) {
    rememberDownloadProfileNames()
    val profiles by DownloadProfilesRepository.profiles.collectAsStateWithLifecycle()
    val activeId by DownloadProfilesRepository.activeId.collectAsStateWithLifecycle()
    var editingId by remember { mutableStateOf<String?>(null) }
    var showImport by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        scope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }
    val copyName = stringResource(Res.string.downloads_profile_copy_name, "%s")

    NuvioModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        val editingProfile = editingId?.let { id -> profiles.firstOrNull { it.id == id } }
        if (editingProfile != null) {
            DownloadProfileEditorContent(profile = editingProfile, onBack = { editingId = null })
        } else if (showImport) {
            ImportProfileDialog(
                onDismiss = { showImport = false },
                onImported = { profile ->
                    showImport = false
                    DownloadProfilesRepository.setActive(profile.id)
                    editingId = profile.id
                },
            )
        } else Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            Text(
                text = stringResource(Res.string.downloads_profile_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(Res.string.downloads_profile_sheet_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            NuvioBottomSheetDivider()
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val preferredResolution by DownloadsRepository.preferredResolution.collectAsStateWithLifecycle()
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.downloads_resolution_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(Res.string.downloads_resolution_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PREFERRED_RESOLUTION_CHOICES.forEach { height ->
                            FilterChip(
                                selected = preferredResolution == height,
                                onClick = { DownloadsRepository.setPreferredResolution(height) },
                                label = {
                                    Text(
                                        if (height == 0) stringResource(Res.string.downloads_resolution_auto)
                                        else stringResource(Res.string.downloads_resolution_value, height),
                                    )
                                },
                            )
                        }
                    }
                }
                NuvioBottomSheetDivider()
                profiles.forEach { profile ->
                    NuvioBottomSheetActionRow(
                        title = profile.name,
                        selected = profile.id == activeId,
                        onClick = { DownloadProfilesRepository.setActive(profile.id) },
                        trailingContent = {
                            IconButton(onClick = { editingId = profile.id }) {
                                Icon(
                                    imageVector = if (profile.builtIn) Icons.Rounded.Visibility else Icons.Rounded.Edit,
                                    contentDescription = stringResource(
                                        if (profile.builtIn) Res.string.downloads_profile_view else Res.string.downloads_profile_edit,
                                    ),
                                )
                            }
                        },
                    )
                    Text(
                        text = profile.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                    )
                }
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.Rounded.ContentCopy,
                    title = stringResource(Res.string.downloads_profile_new),
                    onClick = {
                        val source = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
                        val copy = DownloadProfilesRepository.duplicate(source, copyName.replace("%s", source.name))
                        DownloadProfilesRepository.setActive(copy.id)
                        editingId = copy.id
                    },
                )
                NuvioBottomSheetActionRow(
                    icon = Icons.Rounded.FileDownload,
                    title = stringResource(Res.string.downloads_profile_import),
                    onClick = { showImport = true },
                )
            }
        }
    }

}

@Composable
private fun ImportProfileDialog(onDismiss: () -> Unit, onImported: (DownloadProfile) -> Unit) {
    var text by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    InlineDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.downloads_profile_import)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; failed = false },
                label = { Text(stringResource(Res.string.downloads_profile_import_hint)) },
                isError = failed,
                supportingText = if (failed) {
                    { Text(stringResource(Res.string.downloads_profile_import_failed)) }
                } else {
                    null
                },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val imported = DownloadProfilesRepository.import(text)
                    if (imported == null) failed = true else onImported(imported)
                },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(Res.string.downloads_dialog_import)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.downloads_dialog_cancel)) }
        },
    )
}

/**
 * The rules of one profile, drawn inside the profiles sheet. A built-in profile is shown read-only,
 * with a button to duplicate it. It must not open a second sheet: the native iOS sheet host keeps one
 * sheet at a time, and a second one would close the first without telling it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.DownloadProfileEditorContent(profile: DownloadProfile, onBack: () -> Unit) {
    val dismiss = onBack
    val readOnly = profile.builtIn
    val copyName = stringResource(Res.string.downloads_profile_copy_name, "%s")
    // A condition being added or changed: where it goes and its position, or null for a new one.
    var editing by remember { mutableStateOf<ConditionTarget?>(null) }
    var showExport by remember { mutableStateOf(false) }

    fun update(change: (DownloadProfile) -> DownloadProfile) {
        if (!readOnly) DownloadProfilesRepository.save(change(profile))
    }

    run {
        // Forms replace the sheet's content: a dialog would open behind a native iOS sheet.
        val target = editing
        if (target != null) {
            val existing = target.position?.let { at ->
                if (target.rule != null) profile.rules.getOrNull(target.rule)?.conditions?.getOrNull(at)
                else profile.exclusions.getOrNull(target.exclusion ?: -1)?.conditions?.getOrNull(at)
            }
            ConditionDialog(
                initial = existing,
                onDismiss = { editing = null },
                onSave = { condition ->
                    editing = null
                    update { p ->
                        fun List<ProfileCondition>.put(): List<ProfileCondition> =
                            if (target.position == null) this + condition
                            else mapIndexed { i, old -> if (i == target.position) condition else old }
                        if (target.rule != null) {
                            p.copy(rules = p.rules.mapAt(target.rule) { it.copy(conditions = it.conditions.put()) })
                        } else {
                            p.copy(
                                exclusions = p.exclusions.mapAt(target.exclusion ?: -1) {
                                    it.copy(conditions = it.conditions.put())
                                },
                            )
                        }
                    }
                },
            )
        } else if (showExport) {
            InlineDialog(
                onDismissRequest = { showExport = false },
                title = { Text(stringResource(Res.string.downloads_profile_export)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(Res.string.downloads_profile_export_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = remember(profile) { DownloadProfilesRepository.export(profile) },
                            onValueChange = {},
                            readOnly = true,
                            maxLines = 12,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showExport = false }) { Text(stringResource(Res.string.downloads_dialog_close)) }
                },
            )
        } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = nuvioSafeBottomPadding(24.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                Text(stringResource(Res.string.downloads_profile_back), modifier = Modifier.padding(start = 8.dp))
            }
            if (readOnly) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.downloads_profile_builtin_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    textRes = Res.string.downloads_profile_duplicate,
                    onClick = {
                        val copy = DownloadProfilesRepository.duplicate(profile, copyName.replace("%s", profile.name))
                        DownloadProfilesRepository.setActive(copy.id)
                        dismiss()
                    },
                )
            } else {
                OutlinedTextField(
                    value = profile.name,
                    onValueChange = { value -> update { it.copy(name = value) } },
                    label = { Text(stringResource(Res.string.downloads_profile_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionTitle(Res.string.downloads_profile_rules, Res.string.downloads_profile_rules_hint)
            profile.rules.forEachIndexed { index, rule ->
                RuleCard(
                    title = stringResource(Res.string.downloads_profile_rule_n, index + 1),
                    conditions = rule.conditions,
                    readOnly = readOnly,
                    onEditCondition = { at -> editing = ConditionTarget(rule = index, position = at) },
                    onRemoveCondition = { at ->
                        update { p ->
                            p.copy(rules = p.rules.mapAt(index) { r -> r.copy(conditions = r.conditions.removedAt(at)) })
                        }
                    },
                    onAddCondition = { editing = ConditionTarget(rule = index, position = null) },
                    extra = {
                        Text(
                            text = stringResource(Res.string.downloads_profile_pick),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProfilePick.entries.forEach { pick ->
                                FilterChip(
                                    selected = rule.pick == pick,
                                    enabled = !readOnly,
                                    onClick = {
                                        update { p -> p.copy(rules = p.rules.mapAt(index) { it.copy(pick = pick) }) }
                                    },
                                    label = { Text(pickLabel(pick)) },
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(Res.string.downloads_profile_rule_enabled),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = rule.enabled,
                                enabled = !readOnly,
                                onCheckedChange = { on ->
                                    update { p -> p.copy(rules = p.rules.mapAt(index) { it.copy(enabled = on) }) }
                                },
                            )
                        }
                    },
                    actions = {
                        if (!readOnly) {
                            IconButton(
                                enabled = index > 0,
                                onClick = { update { p -> p.copy(rules = p.rules.moved(index, index - 1)) } },
                            ) {
                                Icon(Icons.Rounded.ArrowUpward, stringResource(Res.string.downloads_profile_move_up))
                            }
                            IconButton(
                                enabled = index < profile.rules.lastIndex,
                                onClick = { update { p -> p.copy(rules = p.rules.moved(index, index + 1)) } },
                            ) {
                                Icon(Icons.Rounded.ArrowDownward, stringResource(Res.string.downloads_profile_move_down))
                            }
                            IconButton(onClick = { update { p -> p.copy(rules = p.rules.removedAt(index)) } }) {
                                Icon(Icons.Rounded.Delete, stringResource(Res.string.downloads_profile_remove))
                            }
                        }
                    },
                )
            }
            if (!readOnly) {
                OutlinedButton(
                    onClick = { update { p -> p.copy(rules = p.rules + ProfileRule()) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text(stringResource(Res.string.downloads_profile_add_rule), modifier = Modifier.padding(start = 8.dp))
                }
            }

            SectionTitle(Res.string.downloads_profile_exclusions, Res.string.downloads_profile_exclusions_hint)
            profile.exclusions.forEachIndexed { index, group ->
                RuleCard(
                    title = stringResource(Res.string.downloads_profile_exclusion_n, index + 1),
                    conditions = group.conditions,
                    readOnly = readOnly,
                    onEditCondition = { at -> editing = ConditionTarget(exclusion = index, position = at) },
                    onRemoveCondition = { at ->
                        update { p ->
                            p.copy(
                                exclusions = p.exclusions.mapAt(index) { g ->
                                    g.copy(conditions = g.conditions.removedAt(at))
                                },
                            )
                        }
                    },
                    onAddCondition = { editing = ConditionTarget(exclusion = index, position = null) },
                    extra = {},
                    actions = {
                        if (!readOnly) {
                            IconButton(onClick = { update { p -> p.copy(exclusions = p.exclusions.removedAt(index)) } }) {
                                Icon(Icons.Rounded.Delete, stringResource(Res.string.downloads_profile_remove))
                            }
                        }
                    },
                )
            }
            if (!readOnly) {
                OutlinedButton(
                    onClick = { update { p -> p.copy(exclusions = p.exclusions + ProfileConditionGroup()) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text(stringResource(Res.string.downloads_profile_add_exclusion), modifier = Modifier.padding(start = 8.dp))
                }
            }

            HorizontalDivider()
            OutlinedButton(onClick = { showExport = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Share, contentDescription = null)
                Text(stringResource(Res.string.downloads_profile_export), modifier = Modifier.padding(start = 8.dp))
            }
            if (!readOnly) {
                val isDiy = profile.id == PROFILE_ID_DIY
                TextButton(
                    onClick = {
                        DownloadProfilesRepository.delete(profile.id)
                        dismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(
                            if (isDiy) Res.string.downloads_profile_reset else Res.string.downloads_profile_delete,
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        }
    }
}

private data class ConditionTarget(
    val rule: Int? = null,
    val exclusion: Int? = null,
    /** The condition being changed, or null to add one. */
    val position: Int?,
)

@Composable
private fun Button(textRes: org.jetbrains.compose.resources.StringResource, onClick: () -> Unit) {
    androidx.compose.material3.Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(textRes))
    }
}

@Composable
private fun SectionTitle(
    title: org.jetbrains.compose.resources.StringResource,
    hint: org.jetbrains.compose.resources.StringResource,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleCard(
    title: String,
    conditions: List<ProfileCondition>,
    readOnly: Boolean,
    onEditCondition: (Int) -> Unit,
    onRemoveCondition: (Int) -> Unit,
    onAddCondition: () -> Unit,
    extra: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                actions()
            }
            if (conditions.isEmpty()) {
                Text(
                    text = stringResource(Res.string.downloads_profile_no_conditions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            conditions.forEachIndexed { index, condition ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onEditCondition(index) }, enabled = !readOnly, modifier = Modifier.weight(1f)) {
                        Text(
                            text = condition.describe(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (!readOnly) {
                        IconButton(onClick = { onRemoveCondition(index) }) {
                            Icon(Icons.Rounded.Delete, stringResource(Res.string.downloads_profile_remove))
                        }
                    }
                }
            }
            if (!readOnly) {
                TextButton(onClick = onAddCondition) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text(stringResource(Res.string.downloads_profile_add_condition), modifier = Modifier.padding(start = 8.dp))
                }
            }
            extra()
        }
    }
}

@Composable
private fun pickLabel(pick: ProfilePick): String = stringResource(
    when (pick) {
        ProfilePick.First -> Res.string.downloads_profile_pick_first
        ProfilePick.Smallest -> Res.string.downloads_profile_pick_smallest
        ProfilePick.Largest -> Res.string.downloads_profile_pick_largest
    },
)

@Composable
private fun sourceLabel(source: ProfileSource): String = stringResource(
    when (source) {
        ProfileSource.Debrid -> Res.string.downloads_condition_source_debrid
        ProfileSource.Usenet -> Res.string.downloads_condition_source_usenet
        ProfileSource.Http -> Res.string.downloads_condition_source_http
        ProfileSource.Torrent -> Res.string.downloads_condition_source_torrent
    },
)

@Composable
private fun opLabel(op: ProfileResolutionOp): String = stringResource(
    when (op) {
        ProfileResolutionOp.Equals -> Res.string.downloads_condition_op_equals
        ProfileResolutionOp.AtLeast -> Res.string.downloads_condition_op_at_least
        ProfileResolutionOp.AtMost -> Res.string.downloads_condition_op_at_most
    },
)

@Composable
private fun ProfileCondition.describe(): String {
    val body = when (type) {
        // "Not cached" reads better than "Not: cached", so this one folds the reversal in.
        ProfileConditionType.Cached -> return stringResource(
            if (cached != negate) Res.string.downloads_condition_cached_yes else Res.string.downloads_condition_cached_no,
        )
        ProfileConditionType.Source -> stringResource(
            Res.string.downloads_condition_summary_source,
            sources.map { sourceLabel(it) }.joinToString(", "),
        )
        ProfileConditionType.Resolution -> stringResource(
            Res.string.downloads_condition_summary_resolution,
            opLabel(resolutionOp),
            if (resolution > 0) "${resolution}p" else stringResource(Res.string.downloads_condition_resolution_preferred),
        )
        ProfileConditionType.Size -> stringResource(
            Res.string.downloads_condition_summary_size,
            listOfNotNull(minGb?.let { "≥ $it GB" }, maxGb?.let { "≤ $it GB" }).joinToString(" "),
        )
        ProfileConditionType.Text -> stringResource(Res.string.downloads_condition_summary_text, text)
    }
    return if (negate) stringResource(Res.string.downloads_condition_summary_not, body) else body
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionDialog(
    initial: ProfileCondition?,
    onDismiss: () -> Unit,
    onSave: (ProfileCondition) -> Unit,
) {
    var draft by remember { mutableStateOf(initial ?: ProfileCondition(type = ProfileConditionType.Cached)) }
    var minText by remember { mutableStateOf(initial?.minGb?.toString().orEmpty()) }
    var maxText by remember { mutableStateOf(initial?.maxGb?.toString().orEmpty()) }
    val typeLabels = mapOf(
        ProfileConditionType.Cached to stringResource(Res.string.downloads_condition_type_cached),
        ProfileConditionType.Source to stringResource(Res.string.downloads_condition_type_source),
        ProfileConditionType.Resolution to stringResource(Res.string.downloads_condition_type_resolution),
        ProfileConditionType.Size to stringResource(Res.string.downloads_condition_type_size),
        ProfileConditionType.Text to stringResource(Res.string.downloads_condition_type_text),
    )
    val resolutionChoices = remember { listOf(0) + PREFERRED_RESOLUTION_CHOICES.filter { it > 0 } + 576 }.distinct()

    InlineDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.downloads_condition_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileConditionType.entries.forEach { type ->
                        FilterChip(
                            selected = draft.type == type,
                            onClick = { draft = draft.copy(type = type) },
                            label = { Text(typeLabels.getValue(type)) },
                        )
                    }
                }
                when (draft.type) {
                    ProfileConditionType.Cached -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = draft.cached,
                            onClick = { draft = draft.copy(cached = true) },
                            label = { Text(stringResource(Res.string.downloads_condition_cached_yes)) },
                        )
                        FilterChip(
                            selected = !draft.cached,
                            onClick = { draft = draft.copy(cached = false) },
                            label = { Text(stringResource(Res.string.downloads_condition_cached_no)) },
                        )
                    }
                    ProfileConditionType.Source -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProfileSource.entries.forEach { source ->
                            FilterChip(
                                selected = source in draft.sources,
                                onClick = {
                                    draft = draft.copy(
                                        sources = if (source in draft.sources) draft.sources - source else draft.sources + source,
                                    )
                                },
                                label = { Text(sourceLabel(source)) },
                            )
                        }
                    }
                    ProfileConditionType.Resolution -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProfileResolutionOp.entries.forEach { op ->
                                FilterChip(
                                    selected = draft.resolutionOp == op,
                                    onClick = { draft = draft.copy(resolutionOp = op) },
                                    label = { Text(opLabel(op)) },
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            resolutionChoices.forEach { height ->
                                FilterChip(
                                    selected = draft.resolution == height,
                                    onClick = { draft = draft.copy(resolution = height) },
                                    label = {
                                        Text(
                                            if (height == 0) stringResource(Res.string.downloads_condition_resolution_preferred)
                                            else "${height}p",
                                        )
                                    },
                                )
                            }
                        }
                        Note()
                    }
                    ProfileConditionType.Size -> {
                        OutlinedTextField(
                            value = minText,
                            onValueChange = { minText = it },
                            label = { Text(stringResource(Res.string.downloads_condition_min_gb)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = maxText,
                            onValueChange = { maxText = it },
                            label = { Text(stringResource(Res.string.downloads_condition_max_gb)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Note()
                    }
                    ProfileConditionType.Text -> OutlinedTextField(
                        value = draft.text,
                        onValueChange = { draft = draft.copy(text = it) },
                        label = { Text(stringResource(Res.string.downloads_condition_text_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // "Cached" has its own yes and no, so it has no separate reversal.
                if (draft.type != ProfileConditionType.Cached) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(Res.string.downloads_condition_not),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = draft.negate, onCheckedChange = { draft = draft.copy(negate = it) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val comma = ','
                    onSave(
                        draft.copy(
                            negate = if (draft.type == ProfileConditionType.Cached) false else draft.negate,
                            minGb = minText.replace(comma, '.').toDoubleOrNull()?.takeIf { it >= 0 },
                            maxGb = maxText.replace(comma, '.').toDoubleOrNull()?.takeIf { it >= 0 },
                            text = draft.text.trim(),
                        ),
                    )
                },
            ) { Text(stringResource(Res.string.downloads_dialog_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.downloads_dialog_cancel)) }
        },
    )
}

@Composable
private fun Note() {
    Text(
        text = stringResource(Res.string.downloads_condition_unknown_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun <T> List<T>.mapAt(index: Int, change: (T) -> T): List<T> =
    mapIndexed { i, item -> if (i == index) change(item) else item }

private fun <T> List<T>.removedAt(index: Int): List<T> = filterIndexed { i, _ -> i != index }

private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices) return this
    val list = toMutableList()
    list.add(to, list.removeAt(from))
    return list
}

/**
 * A title, content and buttons drawn inside the sheet. A dialog window would open behind the native
 * iOS sheet, where it cannot be seen or used.
 */
@Composable
private fun InlineDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = nuvioSafeBottomPadding(24.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.material3.ProvideTextStyle(
            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        ) { title() }
        text()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            dismissButton()
            confirmButton()
        }
    }
}
