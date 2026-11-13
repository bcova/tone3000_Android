package com.bcova.namplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bcova.namplayer.t3k.Option
import com.bcova.namplayer.t3k.SearchFilters
import com.bcova.namplayer.t3k.SearchOptions
import com.bcova.namplayer.t3k.TargetSlot
import java.text.NumberFormat

/** Which filter sheet is open. */
private enum class Sheet { SORT, GEAR, SIZE, FORMAT, TAGS, MAKES }

/**
 * Full-catalog search with filters, presented as TONE3000 (their terms: no white-labeling).
 * Only reachable when T3kConfig.FULL_CATALOG_SEARCH is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(target: TargetSlot, vm: T3kViewModel) {
    val f = vm.filters
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var queryText by rememberSaveable { mutableStateOf(f.query) }

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, vm.results.size) { if (nearEnd) vm.searchMore() }
    LaunchedEffect(Unit) { if (f.query.isEmpty()) focusRequester.requestFocus() }

    Column(Modifier.fillMaxSize()) {
        // ---- Header: back, search box, "Powered by TONE3000"
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it; vm.setQuery(it) },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = { Text("Search tones, e.g. plexi, 5150") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (queryText.isNotEmpty()) {
                        IconButton(onClick = { queryText = ""; vm.setQuery("") }) { Icon(Icons.Default.Clear, contentDescription = "Clear") }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (target == TargetSlot.AMP) "Choosing an amp" else "Choosing a cabinet",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            PoweredByT3k()
        }

        // ---- Filter chips (scroll sideways)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DropChip(SearchOptions.sorts.labelFor(f.sort) ?: "Sort", f.sort != null) { sheet = Sheet.SORT }
            DropChip(SearchOptions.formats.labelFor(f.format) ?: "Format", f.format != null) { sheet = Sheet.FORMAT }
            DropChip(countLabel("Gear", f.gears.size), f.gears.isNotEmpty()) { sheet = Sheet.GEAR }
            DropChip(countLabel("Size", f.sizes.size), f.sizes.isNotEmpty()) { sheet = Sheet.SIZE }
            DropChip(countLabel("Tags", f.tags.size), f.tags.isNotEmpty()) { sheet = Sheet.TAGS }
            DropChip(countLabel("Makes", f.makes.size), f.makes.isNotEmpty()) { sheet = Sheet.MAKES }
            ToggleChip("A2 only", f.a2Only) { vm.updateFilters(f.copy(a2Only = it)) }
            ToggleChip("Calibrated", f.calibrated) { vm.updateFilters(f.copy(calibrated = it)) }
            ToggleChip("Verified creators", f.verified) { vm.updateFilters(f.copy(verified = it)) }
        }

        // ---- Chosen tags/makes, removable
        if (f.tags.isNotEmpty() || f.makes.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                f.tags.sorted().forEach { tag ->
                    RemovableChip("#$tag") { vm.updateFilters(f.copy(tags = f.tags - tag)) }
                }
                f.makes.sorted().forEach { make ->
                    RemovableChip(make) { vm.updateFilters(f.copy(makes = f.makes - make)) }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (vm.searchLoading && vm.results.isEmpty()) "Searching…"
                else "${NumberFormat.getIntegerInstance().format(vm.resultsTotal)} tones",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (f.activeFilterCount > 0) TextButton(onClick = vm::clearFilters) { Text("Clear filters") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        // ---- Results
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(vm.results, key = { it.id }) { tone ->
                ToneRow(tone) { vm.openTone(tone, target) }
                HorizontalDivider(Modifier.padding(start = 92.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            }
            if (vm.searchLoading) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() } }
            }
            vm.searchError?.let { err ->
                item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(err, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        TextButton(onClick = vm::retrySearch) { Text("Try again") }
                    }
                }
            }
            if (!vm.searchLoading && vm.searchError == null && vm.results.isEmpty()) {
                item {
                    Text(
                        "No tones match. Try fewer filters or different words.",
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // ---- Filter sheets
    sheet?.let { open ->
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            when (open) {
                Sheet.SORT -> SingleChoice("Sort by", SearchOptions.sorts, f.sort ?: "best-match") {
                    vm.updateFilters(f.copy(sort = it)); sheet = null
                }
                Sheet.FORMAT -> SingleChoice("Format", listOf(Option("", "Any")) + SearchOptions.formats, f.format ?: "") {
                    vm.updateFilters(f.copy(format = it.ifEmpty { null })); sheet = null
                }
                Sheet.GEAR -> MultiChoice("Gear type", SearchOptions.gears, f.gears) { vm.updateFilters(f.copy(gears = it)) }
                Sheet.SIZE -> MultiChoice(
                    "Model size",
                    SearchOptions.sizes,
                    f.sizes,
                    hint = "Lite, Feather and Nano run best on phones.",
                ) { vm.updateFilters(f.copy(sizes = it)) }
                Sheet.TAGS -> SuggestionPicker(SuggestionKind.TAGS, f.tags, vm) { vm.updateFilters(vm.filters.copy(tags = it)) }
                Sheet.MAKES -> SuggestionPicker(SuggestionKind.MAKES, f.makes, vm) { vm.updateFilters(vm.filters.copy(makes = it)) }
            }
        }
    }
}

private fun List<Option>.labelFor(value: String?): String? = firstOrNull { it.value == value }?.label

private fun countLabel(name: String, count: Int) = if (count == 0) name else "$name · $count"

@Composable
fun PoweredByT3k() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Powered by ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        T3kLogo(height = 12.dp)
    }
}

@Composable
private fun DropChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
    )
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
    )
}

@Composable
private fun RemovableChip(label: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) },
    )
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
private fun SingleChoice(title: String, options: List<Option>, selected: String, onPick: (String) -> Unit) {
    Column(Modifier.padding(bottom = 24.dp)) {
        SheetTitle(title)
        options.forEach { o ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(o.value) }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(o.label, modifier = Modifier.weight(1f))
                if (o.value == selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun MultiChoice(
    title: String,
    options: List<Option>,
    selected: Set<String>,
    hint: String? = null,
    onChange: (Set<String>) -> Unit,
) {
    Column(Modifier.padding(bottom = 24.dp)) {
        SheetTitle(title)
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
        }
        options.forEach { o ->
            val on = o.value in selected
            Row(
                Modifier.fillMaxWidth().clickable { onChange(if (on) selected - o.value else selected + o.value) }
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                Text(o.label)
            }
        }
    }
}

/** Search-as-you-type picker for tags or makes, backed by TONE3000's /tags and /makes lists. */
@Composable
private fun SuggestionPicker(kind: SuggestionKind, selected: Set<String>, vm: T3kViewModel, onChange: (Set<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(kind) { vm.loadSuggestions(kind, "") }
    Column(Modifier.padding(bottom = 24.dp).heightIn(min = 320.dp)) {
        SheetTitle(if (kind == SuggestionKind.TAGS) "Tags" else "Makes (brands & models)")
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; vm.loadSuggestions(kind, it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            placeholder = { Text(if (kind == SuggestionKind.TAGS) "e.g. high-gain, clean" else "e.g. Marshall, Fender") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
        )
        Spacer(Modifier.size(8.dp))
        if (vm.suggestionsLoading && vm.suggestions.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
        }
        // Already-chosen items first, so they can be unticked.
        val chosen = selected.sorted().filter { name -> vm.suggestions.none { it.name == name } }
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(chosen) { name -> SuggestionRow(name, null, true) { onChange(selected - name) } }
            items(vm.suggestions, key = { it.id }) { s ->
                val on = s.name in selected
                SuggestionRow(s.name, s.tonesCount, on) { onChange(if (on) selected - s.name else selected + s.name) }
            }
        }
    }
}

@Composable
private fun SuggestionRow(name: String, count: Int?, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(8.dp))
        Text(name, modifier = Modifier.weight(1f))
        if (count != null) {
            Text(
                NumberFormat.getIntegerInstance().format(count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}
