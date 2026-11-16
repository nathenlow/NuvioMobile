package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HdrOn
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.addons.AddonRepository
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/*
 * The profile editor lives inside the profiles sheet, and so do its forms: a dialog or a second
 * sheet would open behind the native iOS sheet, or close it without telling it.
 *
 * To keep many rules readable, a rule is a one-line summary of chips until it is tapped; only the
 * tapped rule shows its controls. Adding a condition first asks which kind, grouped, and then shows
 * only the options of that kind.
 */

/** Where a condition is being added or changed. */
private data class ConditionTarget(
    val rule: Int? = null,
    val exclusion: Int? = null,
    /** The condition being changed, or null to add one. */
    val position: Int? = null,
)

/** Which card is open: a rule or an exclusion, by index. */
private data class OpenCard(val exclusion: Boolean, val index: Int)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColumnScope.DownloadProfileEditorContent(profile: DownloadProfile, onBack: () -> Unit) {
    val readOnly = profile.builtIn
    val copyName = stringResource(Res.string.downloads_profile_copy_name, "%s")
    var editing by remember { mutableStateOf<ConditionTarget?>(null) }
    var open by remember { mutableStateOf<OpenCard?>(null) }
    var showExport by remember { mutableStateOf(false) }

    fun update(change: (DownloadProfile) -> DownloadProfile) {
        if (!readOnly) DownloadProfilesRepository.save(change(profile))
    }

    val target = editing
    if (target != null) {
        val existing = target.position?.let { at ->
            if (target.rule != null) profile.rules.getOrNull(target.rule)?.conditions?.getOrNull(at)
            else profile.exclusions.getOrNull(target.exclusion ?: -1)?.conditions?.getOrNull(at)
        }
        ConditionForm(
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
        ProfileOverview(
            profile = profile,
            readOnly = readOnly,
            copyName = copyName,
            open = open,
            onOpenChange = { open = it },
            onEditCondition = { editing = it },
            onExport = { showExport = true },
            update = ::update,
            onBack = onBack,
        )
    }
}

/** The profile's name, rules, exclusions and actions. */
@Composable
private fun ProfileOverview(
    profile: DownloadProfile,
    readOnly: Boolean,
    copyName: String,
    open: OpenCard?,
    onOpenChange: (OpenCard?) -> Unit,
    onEditCondition: (ConditionTarget) -> Unit,
    onExport: () -> Unit,
    update: ((DownloadProfile) -> DownloadProfile) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = nuvioSafeBottomPadding(24.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackRow(Res.string.downloads_profile_back, onBack)
        if (readOnly) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = stringResource(Res.string.downloads_profile_builtin_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.Button(
                onClick = {
                    val copy = DownloadProfilesRepository.duplicate(profile, copyName.replace("%s", profile.name))
                    DownloadProfilesRepository.setActive(copy.id)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.downloads_profile_duplicate)) }
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
            val isOpen = !readOnly && open == OpenCard(exclusion = false, index = index)
            RuleCard(
                title = stringResource(Res.string.downloads_profile_rule_n, index + 1),
                conditions = rule.conditions,
                enabled = rule.enabled,
                pick = rule.pick,
                isOpen = isOpen,
                readOnly = readOnly,
                onOpen = { onOpenChange(OpenCard(exclusion = false, index = index)) },
                onClose = { onOpenChange(null) },
                onEnabledChange = { on ->
                    update { p -> p.copy(rules = p.rules.mapAt(index) { it.copy(enabled = on) }) }
                },
                onPickChange = { pick ->
                    update { p -> p.copy(rules = p.rules.mapAt(index) { it.copy(pick = pick) }) }
                },
                onEditCondition = { at -> onEditCondition(ConditionTarget(rule = index, position = at)) },
                onRemoveCondition = { at ->
                    update { p ->
                        p.copy(rules = p.rules.mapAt(index) { r -> r.copy(conditions = r.conditions.removedAt(at)) })
                    }
                },
                onAddCondition = { onEditCondition(ConditionTarget(rule = index)) },
                onMoveUp = if (index > 0) {
                    {
                        update { p -> p.copy(rules = p.rules.moved(index, index - 1)) }
                        onOpenChange(OpenCard(exclusion = false, index = index - 1))
                    }
                } else {
                    null
                },
                onMoveDown = if (index < profile.rules.lastIndex) {
                    {
                        update { p -> p.copy(rules = p.rules.moved(index, index + 1)) }
                        onOpenChange(OpenCard(exclusion = false, index = index + 1))
                    }
                } else {
                    null
                },
                onDelete = {
                    update { p -> p.copy(rules = p.rules.removedAt(index)) }
                    onOpenChange(null)
                },
            )
        }
        if (!readOnly) {
            AddButton(Res.string.downloads_profile_add_rule) {
                update { p -> p.copy(rules = p.rules + ProfileRule()) }
                onOpenChange(OpenCard(exclusion = false, index = profile.rules.size))
            }
        }

        SectionTitle(Res.string.downloads_profile_exclusions, Res.string.downloads_profile_exclusions_hint)
        profile.exclusions.forEachIndexed { index, group ->
            val isOpen = !readOnly && open == OpenCard(exclusion = true, index = index)
            RuleCard(
                title = stringResource(Res.string.downloads_profile_exclusion_n, index + 1),
                conditions = group.conditions,
                enabled = null,
                pick = null,
                isOpen = isOpen,
                readOnly = readOnly,
                onOpen = { onOpenChange(OpenCard(exclusion = true, index = index)) },
                onClose = { onOpenChange(null) },
                onEnabledChange = {},
                onPickChange = {},
                onEditCondition = { at -> onEditCondition(ConditionTarget(exclusion = index, position = at)) },
                onRemoveCondition = { at ->
                    update { p ->
                        p.copy(
                            exclusions = p.exclusions.mapAt(index) { g -> g.copy(conditions = g.conditions.removedAt(at)) },
                        )
                    }
                },
                onAddCondition = { onEditCondition(ConditionTarget(exclusion = index)) },
                onMoveUp = null,
                onMoveDown = null,
                onDelete = {
                    update { p -> p.copy(exclusions = p.exclusions.removedAt(index)) }
                    onOpenChange(null)
                },
            )
        }
        if (!readOnly) {
            AddButton(Res.string.downloads_profile_add_exclusion) {
                update { p -> p.copy(exclusions = p.exclusions + ProfileConditionGroup()) }
                onOpenChange(OpenCard(exclusion = true, index = profile.exclusions.size))
            }
        }

        HorizontalDivider()
        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Share, contentDescription = null)
            Text(stringResource(Res.string.downloads_profile_export), modifier = Modifier.padding(start = 8.dp))
        }
        if (!readOnly) {
            val isDiy = profile.id == PROFILE_ID_DIY
            TextButton(
                onClick = {
                    DownloadProfilesRepository.delete(profile.id)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(if (isDiy) Res.string.downloads_profile_reset else Res.string.downloads_profile_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ---- Rule card --------------------------------------------------------------------------------

/**
 * A rule or exclusion. Closed, it is a title and a line of chips, and tapping it opens it. Open, the
 * chips can be changed or removed, and the rest of its controls show. [enabled] and [pick] are null
 * for an exclusion, which has neither.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleCard(
    title: String,
    conditions: List<ProfileCondition>,
    enabled: Boolean?,
    pick: ProfilePick?,
    isOpen: Boolean,
    readOnly: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPickChange: (ProfilePick) -> Unit,
    onEditCondition: (Int) -> Unit,
    onRemoveCondition: (Int) -> Unit,
    onAddCondition: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val off = enabled == false
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!readOnly && !isOpen) Modifier.clickable(onClick = onOpen) else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (isOpen) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (off) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (enabled != null && !readOnly) {
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
            }

            if (conditions.isEmpty()) {
                Text(
                    text = stringResource(Res.string.downloads_profile_no_conditions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                conditions.forEachIndexed { index, condition ->
                    val label = condition.summary()
                    if (isOpen) {
                        InputChip(
                            selected = false,
                            onClick = { onEditCondition(index) },
                            label = { Text(label) },
                            leadingIcon = { Icon(condition.type.icon(), null, Modifier.size(16.dp)) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(Res.string.downloads_profile_remove),
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { onRemoveCondition(index) },
                                )
                            },
                        )
                    } else {
                        SuggestionChip(
                            onClick = { if (!readOnly) onOpen() },
                            label = { Text(label) },
                            icon = { Icon(condition.type.icon(), null, Modifier.size(16.dp)) },
                            enabled = !off,
                        )
                    }
                }
                if (isOpen) {
                    SuggestionChip(
                        onClick = onAddCondition,
                        label = { Text(stringResource(Res.string.downloads_profile_add_condition)) },
                        icon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) },
                    )
                }
            }

            if (pick != null) {
                if (isOpen) {
                    Text(
                        text = stringResource(Res.string.downloads_profile_pick),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProfilePick.entries.forEach { option ->
                            FilterChip(
                                selected = pick == option,
                                onClick = { onPickChange(option) },
                                label = { Text(pickLabel(option)) },
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(Res.string.downloads_profile_then, pickLabel(pick)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (isOpen) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onMoveUp != null || onMoveDown != null) {
                        IconButton(onClick = { onMoveUp?.invoke() }, enabled = onMoveUp != null) {
                            Icon(Icons.Rounded.ArrowUpward, stringResource(Res.string.downloads_profile_move_up))
                        }
                        IconButton(onClick = { onMoveDown?.invoke() }, enabled = onMoveDown != null) {
                            Icon(Icons.Rounded.ArrowDownward, stringResource(Res.string.downloads_profile_move_down))
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Rounded.Delete,
                            stringResource(Res.string.downloads_profile_remove),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text(stringResource(Res.string.downloads_profile_done)) }
                }
            }
        }
    }
}

// ---- Condition form -------------------------------------------------------------------------

private enum class ConditionGroup(val title: StringResource, val types: List<ProfileConditionType>) {
    Stream(
        Res.string.downloads_condition_group_stream,
        listOf(ProfileConditionType.Cached, ProfileConditionType.Source, ProfileConditionType.Addon),
    ),
    Video(
        Res.string.downloads_condition_group_video,
        listOf(
            ProfileConditionType.Resolution,
            ProfileConditionType.Quality,
            ProfileConditionType.Codec,
            ProfileConditionType.Hdr,
            ProfileConditionType.BitDepth,
        ),
    ),
    Audio(
        Res.string.downloads_condition_group_audio,
        listOf(ProfileConditionType.Audio, ProfileConditionType.Language),
    ),
    File(
        Res.string.downloads_condition_group_file,
        listOf(ProfileConditionType.Size, ProfileConditionType.Edition, ProfileConditionType.Text),
    ),
}

/** A new condition starts by choosing its kind; a condition being changed goes straight to its options. */
@Composable
private fun ColumnScope.ConditionForm(
    initial: ProfileCondition?,
    onDismiss: () -> Unit,
    onSave: (ProfileCondition) -> Unit,
) {
    var type by remember { mutableStateOf(initial?.type) }
    val chosen = type
    if (chosen == null) {
        ConditionTypePicker(onPick = { type = it }, onBack = onDismiss)
    } else {
        ConditionOptions(
            initial = initial?.takeIf { it.type == chosen } ?: ProfileCondition(type = chosen),
            onBack = if (initial == null) ({ type = null }) else onDismiss,
            onSave = onSave,
        )
    }
}

@Composable
private fun ConditionTypePicker(onPick: (ProfileConditionType) -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = nuvioSafeBottomPadding(24.dp)),
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            BackRow(Res.string.downloads_dialog_cancel, onBack)
            Text(
                text = stringResource(Res.string.downloads_condition_add_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        ConditionGroup.entries.forEach { group ->
            Text(
                text = stringResource(group.title).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
            )
            group.types.forEach { type ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(type) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(typeLabel(type), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = stringResource(type.hint()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionOptions(
    initial: ProfileCondition,
    onBack: () -> Unit,
    onSave: (ProfileCondition) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var minText by remember { mutableStateOf(initial.minGb?.toString().orEmpty()) }
    var maxText by remember { mutableStateOf(initial.maxGb?.toString().orEmpty()) }
    val type = draft.type

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = nuvioSafeBottomPadding(24.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackRow(Res.string.downloads_dialog_cancel, onBack)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(typeLabel(type), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        // "Is" or "Is not" first, so every kind reads the same way.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (type == ProfileConditionType.Cached) {
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
            } else {
                FilterChip(
                    selected = !draft.negate,
                    onClick = { draft = draft.copy(negate = false) },
                    label = { Text(stringResource(Res.string.downloads_condition_is)) },
                )
                FilterChip(
                    selected = draft.negate,
                    onClick = { draft = draft.copy(negate = true) },
                    label = { Text(stringResource(Res.string.downloads_condition_is_not)) },
                )
            }
        }

        when (type) {
            ProfileConditionType.Cached -> Unit
            ProfileConditionType.Source -> ChoiceChips(
                options = ProfileSource.entries.map { it.name to sourceLabel(it) },
                selected = draft.sources.map { it.name },
                onToggle = { name ->
                    val source = ProfileSource.valueOf(name)
                    draft = draft.copy(
                        sources = if (source in draft.sources) draft.sources - source else draft.sources + source,
                    )
                },
            )
            ProfileConditionType.Addon -> {
                val installed = remember {
                    AddonRepository.uiState.value.addons
                        .filter { addon -> addon.isActive && addon.manifest?.resources?.any { it.name == "stream" } == true }
                        .map { it.displayTitle }
                }
                // Keep addons the condition names even when they are no longer installed.
                val names = (installed + draft.values).distinct()
                if (names.isEmpty()) {
                    Note(Res.string.downloads_condition_no_addons)
                } else {
                    ChoiceChips(
                        options = names.map { it to it },
                        selected = draft.values,
                        onToggle = { name -> draft = draft.copy(values = draft.values.toggled(name)) },
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
                val choices = listOf(0, 2160, 1440, 1080, 720, 576, 480)
                val preferred = stringResource(Res.string.downloads_condition_resolution_preferred)
                if (draft.resolutionOp == ProfileResolutionOp.Equals) {
                    // Several can be picked: "720p or 1080p".
                    val selected = draft.resolutions.ifEmpty { listOf(draft.resolution) }
                    ChoiceChips(
                        options = choices.map { it.toString() to if (it == 0) preferred else "${it}p" },
                        selected = selected.map { it.toString() },
                        onToggle = { value ->
                            val next = selected.map { it.toString() }.toggled(value).map { it.toInt() }
                            draft = draft.copy(resolutions = next, resolution = next.firstOrNull() ?: 0)
                        },
                    )
                } else {
                    ChoiceChips(
                        options = choices.map { it.toString() to if (it == 0) preferred else "${it}p" },
                        selected = listOf(draft.resolution.toString()),
                        onToggle = { value -> draft = draft.copy(resolution = value.toInt()) },
                    )
                }
                Note(Res.string.downloads_condition_unknown_note)
            }
            ProfileConditionType.Quality, ProfileConditionType.Codec, ProfileConditionType.Hdr,
            ProfileConditionType.BitDepth, ProfileConditionType.Audio, ProfileConditionType.Edition -> {
                ChoiceChips(
                    options = ProfileTags.optionsFor(type).map { it.id to it.label },
                    selected = draft.values,
                    onToggle = { id -> draft = draft.copy(values = draft.values.toggled(id)) },
                )
                Note(Res.string.downloads_condition_tags_note)
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
                Note(Res.string.downloads_condition_unknown_note)
            }
            ProfileConditionType.Text, ProfileConditionType.Language -> OutlinedTextField(
                value = draft.text,
                onValueChange = { draft = draft.copy(text = it) },
                label = {
                    Text(
                        stringResource(
                            if (type == ProfileConditionType.Language) Res.string.downloads_condition_language_hint
                            else Res.string.downloads_condition_text_hint,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = {
                    val comma = ','
                    onSave(
                        draft.copy(
                            negate = if (type == ProfileConditionType.Cached) false else draft.negate,
                            minGb = minText.replace(comma, '.').toDoubleOrNull()?.takeIf { it >= 0 },
                            maxGb = maxText.replace(comma, '.').toDoubleOrNull()?.takeIf { it >= 0 },
                            text = draft.text.trim(),
                        ),
                    )
                },
            ) { Text(stringResource(Res.string.downloads_dialog_save)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(
    options: List<Pair<String, String>>,
    selected: List<String>,
    onToggle: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) ->
            FilterChip(
                selected = id in selected,
                onClick = { onToggle(id) },
                label = { Text(label) },
            )
        }
    }
}

// ---- Labels -----------------------------------------------------------------------------------

private fun ProfileConditionType.icon(): ImageVector = when (this) {
    ProfileConditionType.Cached -> Icons.Rounded.Bolt
    ProfileConditionType.Source -> Icons.Rounded.Link
    ProfileConditionType.Addon -> Icons.Rounded.Extension
    ProfileConditionType.Resolution -> Icons.Rounded.HighQuality
    ProfileConditionType.Quality -> Icons.Rounded.Album
    ProfileConditionType.Codec -> Icons.Rounded.Memory
    ProfileConditionType.Hdr -> Icons.Rounded.HdrOn
    ProfileConditionType.BitDepth -> Icons.Rounded.Tune
    ProfileConditionType.Audio -> Icons.Rounded.GraphicEq
    ProfileConditionType.Language -> Icons.Rounded.Language
    ProfileConditionType.Size -> Icons.Rounded.Storage
    ProfileConditionType.Edition -> Icons.Rounded.Movie
    ProfileConditionType.Text -> Icons.Rounded.TextFields
}

private fun ProfileConditionType.hint(): StringResource = when (this) {
    ProfileConditionType.Cached -> Res.string.downloads_condition_hint_cached
    ProfileConditionType.Source -> Res.string.downloads_condition_hint_source
    ProfileConditionType.Addon -> Res.string.downloads_condition_hint_addon
    ProfileConditionType.Resolution -> Res.string.downloads_condition_hint_resolution
    ProfileConditionType.Quality -> Res.string.downloads_condition_hint_quality
    ProfileConditionType.Codec -> Res.string.downloads_condition_hint_codec
    ProfileConditionType.Hdr -> Res.string.downloads_condition_hint_hdr
    ProfileConditionType.BitDepth -> Res.string.downloads_condition_hint_bit_depth
    ProfileConditionType.Audio -> Res.string.downloads_condition_hint_audio
    ProfileConditionType.Language -> Res.string.downloads_condition_hint_language
    ProfileConditionType.Size -> Res.string.downloads_condition_hint_size
    ProfileConditionType.Edition -> Res.string.downloads_condition_hint_edition
    ProfileConditionType.Text -> Res.string.downloads_condition_hint_text
}

@Composable
private fun typeLabel(type: ProfileConditionType): String = stringResource(
    when (type) {
        ProfileConditionType.Cached -> Res.string.downloads_condition_type_cached
        ProfileConditionType.Source -> Res.string.downloads_condition_type_source
        ProfileConditionType.Addon -> Res.string.downloads_condition_type_addon
        ProfileConditionType.Resolution -> Res.string.downloads_condition_type_resolution
        ProfileConditionType.Quality -> Res.string.downloads_condition_type_quality
        ProfileConditionType.Codec -> Res.string.downloads_condition_type_codec
        ProfileConditionType.Hdr -> Res.string.downloads_condition_type_hdr
        ProfileConditionType.BitDepth -> Res.string.downloads_condition_type_bit_depth
        ProfileConditionType.Audio -> Res.string.downloads_condition_type_audio
        ProfileConditionType.Language -> Res.string.downloads_condition_type_language
        ProfileConditionType.Size -> Res.string.downloads_condition_type_size
        ProfileConditionType.Edition -> Res.string.downloads_condition_type_edition
        ProfileConditionType.Text -> Res.string.downloads_condition_type_text
    },
)

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

/** A short chip label, such as "720p or 1080p", "Not BluRay" or "From Torrentio". */
@Composable
private fun ProfileCondition.summary(): String {
    val or = stringResource(Res.string.downloads_condition_or)
    val preferred = stringResource(Res.string.downloads_condition_resolution_preferred)
    fun height(value: Int) = if (value > 0) "${value}p" else preferred
    val typeName = typeLabel(type)
    // "Not cached" reads better than "Not Cached", so Cached folds the reversal in.
    val cachedText = stringResource(
        if (cached != negate) Res.string.downloads_condition_cached_yes else Res.string.downloads_condition_cached_no,
    )
    val body = when (type) {
        ProfileConditionType.Cached -> cachedText
        ProfileConditionType.Source -> sources.map { sourceLabel(it) }.joinToString(or)
        ProfileConditionType.Addon -> stringResource(Res.string.downloads_condition_summary_addon, values.joinToString(or))
        ProfileConditionType.Resolution -> when (resolutionOp) {
            ProfileResolutionOp.Equals -> resolutions.ifEmpty { listOf(resolution) }.joinToString(or) { height(it) }
            ProfileResolutionOp.AtLeast -> "≥ ${height(resolution)}"
            ProfileResolutionOp.AtMost -> "≤ ${height(resolution)}"
        }
        ProfileConditionType.Quality, ProfileConditionType.Codec, ProfileConditionType.Hdr,
        ProfileConditionType.BitDepth, ProfileConditionType.Audio, ProfileConditionType.Edition ->
            values.joinToString(or) { ProfileTags.label(type, it) }
        ProfileConditionType.Size -> stringResource(
            Res.string.downloads_condition_summary_size,
            listOfNotNull(minGb?.let { "≥ $it GB" }, maxGb?.let { "≤ $it GB" }).joinToString(" "),
        )
        ProfileConditionType.Language -> stringResource(Res.string.downloads_condition_summary_language, text)
        ProfileConditionType.Text -> stringResource(Res.string.downloads_condition_summary_text, text)
    }
    val shown = body.ifBlank { typeName }
    val reversed = stringResource(Res.string.downloads_condition_summary_not, shown)
    return if (type == ProfileConditionType.Cached) cachedText else if (negate) reversed else shown
}

// ---- Small pieces -----------------------------------------------------------------------------

@Composable
private fun BackRow(label: StringResource, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
        Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SectionTitle(title: StringResource, hint: StringResource) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            text = stringResource(hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddButton(label: StringResource, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.Add, contentDescription = null)
        Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Note(text: StringResource) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A title, content and buttons drawn inside the sheet. A dialog window would open behind the native
 * iOS sheet, where it cannot be seen or used.
 */
@Composable
internal fun InlineDialog(
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
        ProvideTextStyle(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)) { title() }
        text()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            dismissButton()
            confirmButton()
        }
    }
}

private fun List<String>.toggled(value: String): List<String> = if (value in this) this - value else this + value

private fun <T> List<T>.mapAt(index: Int, change: (T) -> T): List<T> =
    mapIndexed { i, item -> if (i == index) change(item) else item }

private fun <T> List<T>.removedAt(index: Int): List<T> = filterIndexed { i, _ -> i != index }

private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices) return this
    val list = toMutableList()
    list.add(to, list.removeAt(from))
    return list
}
