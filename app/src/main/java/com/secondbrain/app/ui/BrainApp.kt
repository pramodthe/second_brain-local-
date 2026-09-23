package com.secondbrain.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secondbrain.app.data.EntityCategory
import com.secondbrain.app.data.EntityNode
import com.secondbrain.app.data.NoteDocument
import com.secondbrain.app.data.RelationEdge
import java.text.DateFormat
import java.util.Date

enum class BrainTab(val label: String) {
    NOTES("Notes"),
    EXPLORE("Explore"),
    ASK("Ask")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainApp(viewModel: BrainViewModel) {
    var selectedTab by rememberSaveable { mutableStateOf(BrainTab.NOTES) }
    var showComposer by rememberSaveable { mutableStateOf(false) }
    val appError by viewModel.appError.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(appError) {
        appError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearAppError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = when (selectedTab) {
                                BrainTab.NOTES -> "Second Brain"
                                BrainTab.EXPLORE -> "Explore connections"
                                BrainTab.ASK -> "Ask your notes"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = when (selectedTab) {
                                BrainTab.NOTES -> "Your private knowledge library"
                                BrainTab.EXPLORE -> "See how your ideas connect"
                                BrainTab.ASK -> "Answers grounded in your knowledge"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                BrainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    BrainTab.NOTES -> Icons.Default.Description
                                    BrainTab.EXPLORE -> Icons.Default.Hub
                                    BrainTab.ASK -> Icons.Default.AutoAwesome
                                },
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == BrainTab.NOTES) {
                ExtendedFloatingActionButton(
                    onClick = { showComposer = true },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    text = { Text("New note") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                BrainTab.NOTES -> NotesScreen(viewModel, onNewNote = { showComposer = true })
                BrainTab.EXPLORE -> GraphScreen(viewModel)
                BrainTab.ASK -> ChatScreen(viewModel)
            }
        }
    }

    if (showComposer) {
        NoteComposerSheet(
            viewModel = viewModel,
            onDismiss = { showComposer = false }
        )
    }
}

@Composable
fun NotesScreen(
    viewModel: BrainViewModel,
    onNewNote: () -> Unit
) {
    val notes by viewModel.notes.collectAsState()
    val stats by viewModel.stats.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val visibleNotes = remember(notes, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) notes else notes.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.content.contains(query, ignoreCase = true) ||
                it.source.contains(query, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search notes and ideas") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp)
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LibraryStat("Notes", stats["notes"] ?: 0, Icons.Default.Description, Modifier.weight(1f))
                LibraryStat("Ideas", stats["entities"] ?: 0, Icons.Default.Lightbulb, Modifier.weight(1f))
                LibraryStat("Links", stats["edges"] ?: 0, Icons.Default.Link, Modifier.weight(1f))
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        if (searchQuery.isBlank()) "Recent notes" else "Search results",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${visibleNotes.size} ${if (visibleNotes.size == 1) "note" else "notes"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (notes.isEmpty()) TextButton(onClick = onNewNote) { Text("Create one") }
            }
        }

        if (visibleNotes.isEmpty()) {
            item { EmptyNotesState(searchQuery.isNotBlank(), onNewNote) }
        } else {
            items(visibleNotes, key = { it.id }) { note -> NoteCard(note) }
        }
    }
}

@Composable
private fun LibraryStat(
    label: String,
    value: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyNotesState(isSearching: Boolean, onNewNote: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (isSearching) Icons.Default.SearchOff else Icons.Default.EditNote,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (isSearching) "No matching notes" else "Start building your second brain",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (isSearching) "Try another word or phrase." else "Capture an idea, decision, quote, or useful discovery.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!isSearching) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = onNewNote) { Text("Create your first note") }
        }
    }
}

@Composable
fun NoteCard(note: NoteDocument) {
    var expanded by rememberSaveable(note.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        note.title.ifBlank { "Untitled note" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        formatNoteDate(note.timestamp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse note" else "Expand note",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                note.content.trim(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 24.sp
            )
            Spacer(Modifier.height(14.dp))
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    note.source.replaceFirstChar { it.uppercase() },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteComposerSheet(viewModel: BrainViewModel, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    val isIngesting by viewModel.isIngesting.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { if (!isIngesting) onDismiss() },
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Text("New note", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Write naturally. Connections are extracted after you save.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                placeholder = { Text("Give this idea a name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("Note") },
                placeholder = { Text("Capture an idea, quote, decision, or observation…") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.saveNote(title, content) { onDismiss() } },
                enabled = content.isNotBlank() && !isIngesting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isIngesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Finding connections…")
                } else {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save note")
                }
            }
        }
    }
}

private enum class ExploreMode { GRAPH, LIST }

@Composable
fun GraphScreen(viewModel: BrainViewModel) {
    val entities by viewModel.entities.collectAsState()
    val edges by viewModel.edges.collectAsState()
    var mode by rememberSaveable { mutableStateOf(ExploreMode.GRAPH) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategoryName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedCategory = selectedCategoryName?.let(EntityCategory::fromString)

    val visibleEntities = remember(entities, selectedCategory, searchQuery) {
        entities.filter { entity ->
            (selectedCategory == null || entity.category == selectedCategory) &&
                (searchQuery.isBlank() || entity.name.contains(searchQuery.trim(), ignoreCase = true) ||
                    entity.description.contains(searchQuery.trim(), ignoreCase = true))
        }
    }
    val visibleNames = remember(visibleEntities) { visibleEntities.map { normalizeGraphName(it.name) }.toSet() }
    val visibleEdges = remember(edges, visibleNames) {
        edges.filter {
            normalizeGraphName(it.source) in visibleNames && normalizeGraphName(it.target) in visibleNames
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search your knowledge", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == ExploreMode.GRAPH,
                        onClick = { mode = ExploreMode.GRAPH },
                        leadingIcon = { Icon(Icons.Default.Hub, null, Modifier.size(18.dp)) },
                        label = { Text("Graph") }
                    )
                    FilterChip(
                        selected = mode == ExploreMode.LIST,
                        onClick = { mode = ExploreMode.LIST },
                        leadingIcon = { Icon(Icons.Default.ViewAgenda, null, Modifier.size(18.dp)) },
                        label = { Text("List") }
                    )
                }
                Text(
                    "${visibleEntities.size} ideas · ${visibleEdges.size} links",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategoryName = null },
                        label = { Text("All") }
                    )
                }
                items(EntityCategory.entries) { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategoryName = category.name },
                        leadingIcon = {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(categoryColor(category)))
                        },
                        label = { Text(category.label) }
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (visibleEntities.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AccountTree,
                        null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No connected ideas found", fontWeight = FontWeight.SemiBold)
                    Text("Try a different search or category.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (mode == ExploreMode.GRAPH) {
            ForceGraphView(visibleEntities, visibleEdges, Modifier.fillMaxSize())
        } else {
            EntityList(visibleEntities, visibleEdges, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun EntityList(entities: List<EntityNode>, edges: List<RelationEdge>, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(entities, key = { "${it.category.name}:${it.name}" }) { entity ->
            val entityName = normalizeGraphName(entity.name)
            val connections = edges.filter {
                normalizeGraphName(it.source) == entityName || normalizeGraphName(it.target) == entityName
            }
            EntityItem(entity, connections)
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
fun EntityItem(entity: EntityNode, connections: List<RelationEdge> = emptyList()) {
    var expanded by rememberSaveable(entity.name) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(categoryColor(entity.category)))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(entity.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${entity.category.label} · ${connections.size} ${if (connections.size == 1) "connection" else "connections"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "Collapse" else "Show connections"
                )
            }

            AnimatedVisibility(expanded) {
                Column {
                    if (entity.description.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(entity.description, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (connections.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(10.dp))
                        connections.forEach { edge ->
                            val outgoing = normalizeGraphName(edge.source) == normalizeGraphName(entity.name)
                            Text(
                                if (outgoing) "${edge.relation.name.replace('_', ' ').lowercase()} → ${edge.target}"
                                else "${edge.source} → ${edge.relation.name.replace('_', ' ').lowercase()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

fun categoryColor(category: EntityCategory): Color = when (category) {
    EntityCategory.CONCEPT -> Color(0xFF8B7CF6)
    EntityCategory.PROJECT -> Color(0xFF4CA6E8)
    EntityCategory.RESOURCE -> Color(0xFF42B883)
    EntityCategory.PERSON -> Color(0xFFF0A04B)
    EntityCategory.DECISION -> Color(0xFFE26767)
    EntityCategory.INSIGHT -> Color(0xFFD3A927)
}

internal fun normalizeGraphName(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

@Composable
fun ChatScreen(viewModel: BrainViewModel) {
    var queryText by rememberSaveable { mutableStateOf("") }
    val messages by viewModel.chatMessages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val modelReady by viewModel.modelReady.collectAsState()
    val modelLoaded by viewModel.modelLoaded.collectAsState()
    val isModelBusy by viewModel.isModelBusy.collectAsState()
    val modelProgress by viewModel.modelDownloadProgress.collectAsState()
    val modelError by viewModel.modelError.collectAsState()
    val listState = rememberLazyListState()

    fun send() {
        val query = queryText.trim()
        if (query.isNotEmpty() && !isGenerating) {
            queryText = ""
            viewModel.askQuestion(query)
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!modelLoaded) {
            ModelSetupCard(modelReady, isModelBusy, modelProgress, modelError, viewModel::setupLocalModel)
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF42B883)))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Qwen3.5 9B · On-device GPU",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (messages.isEmpty()) item { AskEmptyState(onSuggestion = { queryText = it }) }
            items(messages) { message -> ChatBubble(message) }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp, shadowElevation = 4.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().imePadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = queryText,
                    onValueChange = { queryText = it },
                    placeholder = { Text("Ask about your notes") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() })
                )
                Spacer(Modifier.width(10.dp))
                FilledIconButton(
                    onClick = { send() },
                    enabled = queryText.isNotBlank() && !isGenerating,
                    modifier = Modifier.size(52.dp)
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send question")
                    }
                }
            }
        }
    }
}

@Composable
private fun AskEmptyState(onSuggestion: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(modifier = Modifier.size(76.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.AutoAwesome,
                    null,
                    modifier = Modifier.size(34.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Ask your second brain", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Answers use your notes and their connections.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(22.dp))
        listOf(
            "What have I learned recently?",
            "Which ideas are connected?",
            "Summarize my important decisions"
        ).forEach { suggestion ->
            SuggestionChip(
                onClick = { onSuggestion(suggestion) },
                label = { Text(suggestion) },
                modifier = Modifier.padding(vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun ModelSetupCard(
    modelReady: Boolean,
    isBusy: Boolean,
    progress: Int,
    error: String?,
    onSetup: () -> Unit
) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SmartToy, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Private on-device AI", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (modelReady) "Qwen3.5 9B is ready to load."
                        else "Download the 5.7 GB model to answer privately.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(onClick = onSetup, enabled = !isBusy) { Text(if (modelReady) "Load" else "Download") }
            }
            if (isBusy) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { if (modelReady) 1f else progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (modelReady) "Loading on the GPU…" else "Downloading · $progress%",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessageItem) {
    val isUser = message.sender == "user"
    var expandedSources by rememberSaveable { mutableStateOf(false) }
    val context = message.context

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 20.dp
            ),
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 360.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = message.text.ifBlank { "Thinking…" }.replace("**", ""),
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 24.sp
                )

                if (!isUser && context != null &&
                    (context.relatedNotes.isNotEmpty() || context.anchorEntities.isNotEmpty())
                ) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { expandedSources = !expandedSources }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.LibraryBooks, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Sources used",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            if (expandedSources) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expandedSources) "Hide sources" else "Show sources",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    AnimatedVisibility(expandedSources) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            context.relatedNotes.distinctBy { it.id }.take(4).forEach { note ->
                                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(note.title.ifBlank { "Untitled note" }, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            note.content,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            if (context.relatedNotes.isEmpty()) {
                                Text(
                                    context.anchorEntities.take(4).joinToString(" · ") { it.name },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatNoteDate(timestampSeconds: Double): String {
    val milliseconds = (timestampSeconds * 1_000).toLong()
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(milliseconds))
}
