package com.bcova.namplayer.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bcova.namplayer.t3k.AuthPurpose
import com.bcova.namplayer.t3k.T3kConfig
import com.bcova.namplayer.t3k.BlockKind
import com.bcova.namplayer.t3k.BlockTarget
import com.bcova.namplayer.t3k.Tone

// ---------------------------------------------------------------------------------------------
// 1. Partnership splash (TONE3000 design rule 2): shown before the first sign-in.
// ---------------------------------------------------------------------------------------------

@Composable
fun PartnershipScreen(target: BlockTarget, vm: T3kViewModel) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth()) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.Default.Close, contentDescription = "Close") }
        }
        Spacer(Modifier.weight(1f))
        Text("NAM Player", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("powered by", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        T3kLogo(height = 40.dp)
        Spacer(Modifier.height(32.dp))
        // Wording avoids implying an official partnership, which TONE3000's API terms
        // say needs their written approval.
        Text(
            "Get access to TONE3000's massive library of NAM captures and IRs of real analog gear, " +
                "created by a global community of musicians.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        // TONE3000 design rule 3: sign in through TONE3000's own flow (email + 6-digit code).
        Button(
            onClick = { vm.launchTone3000(context, AuthPurpose.SELECT_TONE, target) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Continue", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
        Text(
            "You'll sign in with your TONE3000 account in a secure browser tab.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 2. Your Tones (TONE3000 design rule 4): first view after sign-in.
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(target: BlockTarget, vm: T3kViewModel) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, vm.tab) { if (nearEnd) vm.loadMore() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Column {
                        T3kLogo(height = 18.dp)
                        Text(
                            "Your Tones · ${choosingLabel(target.kind)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (T3kConfig.FULL_CATALOG_SEARCH) {
                        IconButton(onClick = { vm.openSearch(target) }) { Icon(Icons.Default.Search, contentDescription = "Search TONE3000") }
                    }
                    // Show who is signed in.
                    vm.user?.let { user ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                            Avatar(user.avatarUrl, 28.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("@${user.username}", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
                        }
                    }
                },
            )
        },
        bottomBar = {
            // Persistent path to the full catalog.
            Surface(tonalElevation = 3.dp) {
                BrowseT3kButton(
                    onClick = { vm.launchTone3000(context, AuthPurpose.SELECT_TONE, target) },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (T3kConfig.FULL_CATALOG_SEARCH) {
                // Looks like a search box; opens the full search screen.
                Surface(
                    onClick = { vm.openSearch(target) },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text("Search all tones, tags, gear…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        PoweredByT3k()
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), Alignment.CenterEnd) { PoweredByT3k() }
            }
            ScrollableTabRow(selectedTabIndex = vm.tab.ordinal, edgePadding = 8.dp) {
                LibraryTab.entries.forEach { t ->
                    Tab(selected = vm.tab == t, onClick = { vm.selectTab(t) }, text = { Text(t.label) })
                }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(vm.tones, key = { it.id }) { tone ->
                    ToneRow(tone) { vm.openTone(tone, target) }
                    HorizontalDivider(Modifier.padding(start = 92.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                }
                if (vm.listLoading) {
                    item { Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() } }
                }
                vm.listError?.let { err ->
                    item { ErrorBox(err, onRetry = vm::refreshList) }
                }
                if (!vm.listLoading && vm.listError == null && vm.tones.isEmpty()) {
                    item { EmptyTab(vm.tab) }
                }
                // Partial lists invite users to discover more on TONE3000.
                if (!vm.listLoading && (vm.tab == LibraryTab.TRENDING || vm.tab == LibraryTab.LATEST)) {
                    item {
                        Card(
                            Modifier.fillMaxWidth().padding(16.dp)
                                .clickable { vm.launchTone3000(context, AuthPurpose.SELECT_TONE, target) },
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Discover more on TONE3000", fontWeight = FontWeight.Bold)
                                    Text(
                                        "Search thousands of captures and IRs",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                T3kMark(height = 18.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTab(tab: LibraryTab) {
    val text = when (tab) {
        LibraryTab.FAVORITES -> "No favorites yet. Tap \"Browse TONE3000\" and favorite tones you like."
        LibraryTab.DOWNLOADED -> "Tones you download will show up here."
        LibraryTab.CREATED -> "Tones you upload to TONE3000 will show up here."
        else -> "Nothing here right now."
    }
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        TextButton(onClick = onRetry) { Text("Try again") }
    }
}

// ---------------------------------------------------------------------------------------------
// 3. Tone details (TONE3000 design rule 6) with a model selector.
// ---------------------------------------------------------------------------------------------

@Composable
fun ToneDetailScreen(vm: T3kViewModel, onInstall: () -> Unit) {
    val context = LocalContext.current
    val state = vm.detail
    val tone = state.tone

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.padding(12.dp)) {
                    val canInstall = tone != null && tone.isSupported && state.selectedModel != null && !state.installing
                    Button(onClick = onInstall, enabled = canInstall, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        if (state.installing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Downloading…")
                        } else {
                            val kind = (vm.screen as? Screen.ToneDetail)?.target?.kind
                            Text("Load into ${kind?.label?.lowercase() ?: "chain"} block", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            Box {
                ToneImage(tone?.imageUrl, Modifier.fillMaxWidth().aspectRatio(1.4f), corner = 0.dp)
                IconButton(onClick = { vm.back() }, modifier = Modifier.padding(8.dp)) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.background.copy(alpha = 0.7f)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.padding(6.dp))
                    }
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tone == null) {
                    if (state.loading) Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
                } else {
                    Text(tone.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (tone.gearLabel.isNotEmpty()) Chip(tone.gearLabel)
                        Chip(tone.formatLabel, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f))
                    }
                    CreatorRow(tone.user, avatarSize = 24.dp)
                    PoweredByT3k()
                    tone.description?.takeIf { it.isNotBlank() }?.let { Description(it) }

                    Text("MODEL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.loading) CircularProgressIndicator(Modifier.size(24.dp))
                    state.models.forEach { model ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = state.selectedModel?.id == model.id,
                                    onClick = { state.selectedModel = model },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.selectedModel?.id == model.id, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(model.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (model.sizeLabel.isNotEmpty()) {
                                    Text(model.sizeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        if (state.models.isEmpty() && tone.isSupported) TextButton(onClick = vm::retryDetail) { Text("Try again") }
                    }
                    tone.url?.let { url ->
                        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) {
                            Text("View on ")
                            T3kLogo(height = 14.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Description(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (text.length > 180) {
            Text(
                if (expanded) "Show less" else "Read more",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp),
            )
        }
    }
}

/** "choosing a pedal", "choosing an amp", … */
fun choosingLabel(kind: BlockKind): String = when (kind) {
    BlockKind.AMP -> "choosing an amp"
    BlockKind.OUTBOARD -> "choosing outboard gear"
    BlockKind.ANY -> "choosing a tone"
    BlockKind.CAB -> "choosing a cabinet"
    else -> "choosing a ${kind.label.lowercase()}"
}
