package com.secondbrain.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secondbrain.app.data.EntityCategory
import com.secondbrain.app.data.EntityNode
import com.secondbrain.app.data.KnowledgeReviewItem
import com.secondbrain.app.data.ReviewKind
import com.secondbrain.app.data.NoteDocument
import com.secondbrain.app.data.ProcessingJob
import com.secondbrain.app.data.ProcessingJobStatus
import com.secondbrain.app.data.ProcessingJobType
import com.secondbrain.app.data.RelationEdge
import com.secondbrain.app.data.RetrievedSource
import com.secondbrain.app.data.TranscriptionStatus
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class BrainTab(val label: String) {
    NOTES("Notes"),
    EXPLORE("Explore"),
    PROCESSING("Tasks"),
    ASK("Ask")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainApp(viewModel: BrainViewModel) {
    var selectedTab by rememberSaveable { mutableStateOf(BrainTab.NOTES) }
    var showComposer by rememberSaveable { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NoteDocument?>(null) }
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
                                BrainTab.PROCESSING -> "Tasks & review"
                                BrainTab.ASK -> "Ask your notes"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = when (selectedTab) {
                                BrainTab.NOTES -> "Your private knowledge library"
                                BrainTab.EXPLORE -> "See how your ideas connect"
                                BrainTab.PROCESSING -> "Processing you can trust and verify"
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
                                    BrainTab.PROCESSING -> Icons.Default.PendingActions
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
                    onClick = {
                        editingNote = null
                        showComposer = true
                    },
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
                BrainTab.NOTES -> NotesScreen(
                    viewModel = viewModel,
                    onNewNote = {
                        editingNote = null
                        showComposer = true
                    },
                    onEditNote = { note ->
                        editingNote = note
                        showComposer = true
                    }
                )
                BrainTab.EXPLORE -> GraphScreen(viewModel)
                BrainTab.PROCESSING -> ProcessingScreen(viewModel)
                BrainTab.ASK -> ChatScreen(viewModel)
            }
        }
    }

    if (showComposer) {
        NoteComposerSheet(
            viewModel = viewModel,
            existingNote = editingNote,
            onDismiss = {
                showComposer = false
                editingNote = null
            }
        )
    }
}

@Composable
fun NotesScreen(
    viewModel: BrainViewModel,
    onNewNote: () -> Unit,
    onEditNote: (NoteDocument) -> Unit
) {
    val notes by viewModel.notes.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val processingNoteIds by viewModel.processingNoteIds.collectAsState()
    val speechProcessingNoteIds by viewModel.speechProcessingNoteIds.collectAsState()
    val playingNoteId by viewModel.playingNoteId.collectAsState()
    val searchResults by viewModel.noteSearchResults.collectAsState()
    val isSearchingNotes by viewModel.isSearchingNotes.collectAsState()
    val relatedToNote by viewModel.relatedToNote.collectAsState()
    val relatedNotes by viewModel.relatedNotes.collectAsState()
    val isLoadingRelatedNotes by viewModel.isLoadingRelatedNotes.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var notesMode by rememberSaveable { mutableStateOf(NotesMode.RECENT) }

    LaunchedEffect(searchQuery) {
        viewModel.searchNotes(searchQuery)
    }

    val visibleNotes = remember(notes, searchQuery, searchResults) {
        if (searchQuery.isBlank()) notes else searchResults.map { it.note }
    }
    val searchByNoteId = remember(searchResults) { searchResults.associateBy { it.note.id } }

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
            if (isSearchingNotes) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
            }
        }

        if (searchQuery.isBlank()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = notesMode == NotesMode.RECENT,
                    onClick = { notesMode = NotesMode.RECENT },
                    leadingIcon = { Icon(Icons.Default.ViewAgenda, null, Modifier.size(18.dp)) },
                    label = { Text("Recent") }
                )
                FilterChip(
                    selected = notesMode == NotesMode.TIMELINE,
                    onClick = { notesMode = NotesMode.TIMELINE },
                    leadingIcon = { Icon(Icons.Default.Timeline, null, Modifier.size(18.dp)) },
                    label = { Text("Timeline") }
                )
            }
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
                        when {
                            searchQuery.isNotBlank() -> "Smart search results"
                            notesMode == NotesMode.TIMELINE -> "Knowledge timeline"
                            else -> "Recent notes"
                        },
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

        if (visibleNotes.isEmpty() && !isSearchingNotes) {
            item { EmptyNotesState(searchQuery.isNotBlank(), onNewNote) }
        } else if (searchQuery.isBlank() && notesMode == NotesMode.TIMELINE) {
            notes.groupBy(::timelineDayLabel).forEach { (day, dayNotes) ->
                item(key = "day-$day") { TimelineDayHeader(day, dayNotes.size) }
                items(dayNotes, key = { "timeline-${it.id}" }) { note ->
                    NoteCard(
                        note = note,
                        isProcessing = note.id in processingNoteIds,
                        isSpeechProcessing = note.id in speechProcessingNoteIds,
                        isPlaying = note.id == playingNoteId,
                        onOpen = { onEditNote(note) },
                        onRelated = { viewModel.showRelatedNotes(note) },
                        onTogglePlayback = { viewModel.togglePlayback(note) },
                        onRetryTranscription = { viewModel.retryTranscription(note) }
                    )
                }
            }
        } else {
            items(visibleNotes, key = { it.id }) { note ->
                NoteCard(
                    note = note,
                    retrieval = searchByNoteId[note.id],
                    isProcessing = note.id in processingNoteIds,
                    isSpeechProcessing = note.id in speechProcessingNoteIds,
                    isPlaying = note.id == playingNoteId,
                    onOpen = { onEditNote(note) },
                    onRelated = { viewModel.showRelatedNotes(note) },
                    onTogglePlayback = { viewModel.togglePlayback(note) },
                    onRetryTranscription = { viewModel.retryTranscription(note) }
                )
            }
        }
    }

    relatedToNote?.let { sourceNote ->
        RelatedNotesSheet(
            sourceNote = sourceNote,
            results = relatedNotes,
            isLoading = isLoadingRelatedNotes,
            onDismiss = viewModel::closeRelatedNotes,
            onOpen = { related ->
                viewModel.closeRelatedNotes()
                onEditNote(related)
            }
        )
    }
}

private enum class NotesMode { RECENT, TIMELINE }

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
private fun TimelineDayHeader(day: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.Default.History, null, Modifier.padding(8.dp).size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(day, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Text(
                "$count ${if (count == 1) "memory" else "memories"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelatedNotesSheet(
    sourceNote: NoteDocument,
    results: List<RetrievedSource>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onOpen: (NoteDocument) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Related notes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Connections to ${noteDisplayTitle(sourceNote)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(18.dp))
            when {
                isLoading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text("Following semantic and graph connections…")
                }

                results.isEmpty() -> {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Hub, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(12.dp))
                        Text("No strong connections yet", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Related memories appear as your library grows.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(results, key = { "related-${it.note.id}" }) { result ->
                        Card(
                            onClick = { onOpen(result.note) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        noteDisplayTitle(result.note),
                                        modifier = Modifier.weight(1f),
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${(result.score * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    result.excerpt,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(7.dp))
                                Text(
                                    result.reasons.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun NoteCard(
    note: NoteDocument,
    retrieval: RetrievedSource? = null,
    isProcessing: Boolean,
    isSpeechProcessing: Boolean,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onRelated: () -> Unit,
    onTogglePlayback: () -> Unit,
    onRetryTranscription: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        noteDisplayTitle(note),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            append(formatNoteDateTime(note.timestamp))
                            if (note.modifiedTimestamp > note.timestamp + 1.0) {
                                append(" · Edited ")
                                append(formatNoteDateTime(note.modifiedTimestamp))
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRelated) {
                    Icon(
                        Icons.Default.AccountTree,
                        contentDescription = "Find related notes",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open note",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                retrieval?.excerpt ?: notePreviewText(note),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 24.sp
            )
            retrieval?.let { result ->
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.ManageSearch, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${(result.score * 100).toInt()}% match · ${result.reasons.joinToString()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        note.source.replaceFirstChar { it.uppercase() },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                if (isProcessing) {
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Organizing", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (note.audioPath != null) {
                    AssistChip(
                        onClick = onTogglePlayback,
                        leadingIcon = {
                            Icon(
                                if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text(formatDuration(note.audioDurationMs ?: 0L)) }
                    )
                }
            }
            if (note.audioPath != null && note.transcriptionStatus != TranscriptionStatus.COMPLETE) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        transcriptionLabel(note.transcriptionStatus, isSpeechProcessing),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (note.transcriptionStatus == TranscriptionStatus.FAILED && !isSpeechProcessing) {
                        TextButton(onClick = onRetryTranscription) { Text("Retry") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteComposerSheet(
    viewModel: BrainViewModel,
    existingNote: NoteDocument?,
    onDismiss: () -> Unit
) {
    var title by rememberSaveable(existingNote?.id) { mutableStateOf(existingNote?.title.orEmpty()) }
    var content by rememberSaveable(existingNote?.id) { mutableStateOf(existingNote?.content.orEmpty()) }
    val isIngesting by viewModel.isIngesting.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingElapsedMs by viewModel.recordingElapsedMs.collectAsState()
    val isVoiceCaptureBusy by viewModel.isVoiceCaptureBusy.collectAsState()
    val speechModelReady by viewModel.speechModelReady.collectAsState()
    val speechDownloadProgress by viewModel.speechDownloadProgress.collectAsState()
    val playingNoteId by viewModel.playingNoteId.collectAsState()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startVoiceRecording()
        } else {
            viewModel.reportAppError("Microphone permission is required to record a voice note.")
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!isIngesting && !isRecording && !isVoiceCaptureBusy) onDismiss()
        },
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
            Text(
                if (existingNote == null) "New note" else "Edit note",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (existingNote == null) {
                    "Write naturally. Your note saves before AI organization begins."
                } else {
                    "Created ${formatNoteDateTime(existingNote.timestamp)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            if (existingNote == null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = if (isRecording) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isRecording) Icons.Default.GraphicEq else Icons.Default.Mic,
                                contentDescription = null,
                                tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (isRecording) "Recording ${formatDuration(recordingElapsedMs)}" else "Voice note",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    when {
                                        isRecording -> "Speak naturally. The original audio will be preserved."
                                        !speechModelReady && speechDownloadProgress > 0 ->
                                            "Speech model downloading · $speechDownloadProgress%"
                                        !speechModelReady -> "First transcription downloads a 74 MB offline model."
                                        else -> "Transcribed privately on this device."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (isRecording) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        viewModel.stopVoiceRecording(title) { onDismiss() }
                                    },
                                    enabled = !isVoiceCaptureBusy,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (isVoiceCaptureBusy) "Saving…" else "Stop & save")
                                }
                                TextButton(
                                    onClick = { viewModel.cancelVoiceRecording() },
                                    enabled = !isVoiceCaptureBusy
                                ) { Text("Discard") }
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    if (
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        viewModel.startVoiceRecording()
                                    } else {
                                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                enabled = !isVoiceCaptureBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Record voice note")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            } else if (existingNote.audioPath != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.togglePlayback(existingNote) }) {
                            Icon(
                                if (playingNoteId == existingNote.id) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = if (playingNoteId == existingNote.id) "Stop audio" else "Play audio"
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Original recording", fontWeight = FontWeight.SemiBold)
                            Text(
                                "${formatDuration(existingNote.audioDurationMs ?: 0L)} · ${transcriptionLabel(existingNote.transcriptionStatus, false)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (existingNote.transcriptionStatus == TranscriptionStatus.FAILED) {
                            TextButton(onClick = { viewModel.retryTranscription(existingNote) }) {
                                Text("Retry")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title (optional)") },
                placeholder = { Text("Add a title, or leave it blank") },
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
                onClick = {
                    viewModel.saveNote(existingNote, title, content) { onDismiss() }
                },
                enabled = content.isNotBlank() && !isIngesting && !isRecording && !isVoiceCaptureBusy,
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
                    Text("Saving…")
                } else {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (existingNote == null) "Save note" else "Save changes")
                }
            }
        }
    }
}

@Composable
fun ProcessingScreen(viewModel: BrainViewModel) {
    val jobs by viewModel.processingJobs.collectAsState()
    val reviews by viewModel.knowledgeReviews.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val notesById = remember(notes) { notes.associateBy { it.id } }
    val activeCount = jobs.count { it.status.isActive }
    val failedCount = jobs.count { it.status == ProcessingJobStatus.FAILED }
    val completedCount = jobs.count { it.status == ProcessingJobStatus.COMPLETED }
    var showReviews by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !showReviews,
                        onClick = { showReviews = false },
                        leadingIcon = { Icon(Icons.Default.Sync, null, Modifier.size(18.dp)) },
                        label = { Text("Processing") }
                    )
                    FilterChip(
                        selected = showReviews,
                        onClick = { showReviews = true },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.FactCheck, null, Modifier.size(18.dp)) },
                        label = { Text("Review${if (reviews.isNotEmpty()) " (${reviews.size})" else ""}") }
                    )
                }
                if (!showReviews) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LibraryStat("Active", activeCount, Icons.Default.Sync, Modifier.weight(1f))
                        LibraryStat("Done", completedCount, Icons.Default.CheckCircle, Modifier.weight(1f))
                        LibraryStat("Failed", failedCount, Icons.Default.ErrorOutline, Modifier.weight(1f))
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VerifiedUser, null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("${reviews.size} waiting for you", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Only accepted facts become part of your knowledge graph.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!showReviews) item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Background tasks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Safe to close the app — unfinished work resumes automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (jobs.any { !it.status.isActive }) {
                    TextButton(onClick = viewModel::clearFinishedProcessingJobs) { Text("Clear") }
                }
            }
        }

        if (!showReviews && jobs.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.DoneAll, null, Modifier.size(32.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Everything is processed", fontWeight = FontWeight.SemiBold)
                    Text(
                        "New notes and recordings will appear here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (!showReviews) {
            items(jobs, key = { it.id }) { job ->
                ProcessingJobCard(
                    job = job,
                    note = notesById[job.noteId],
                    onRetry = { viewModel.retryProcessingJob(job) },
                    onCancel = { viewModel.cancelProcessingJob(job) }
                )
            }
        } else if (reviews.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Verified, null, Modifier.size(32.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Your graph is reviewed", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Uncertain entities and relationships will appear here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(reviews, key = { it.id }) { item ->
                KnowledgeReviewCard(
                    item = item,
                    note = notesById[item.noteId],
                    onAccept = { viewModel.acceptKnowledgeReview(item) },
                    onReject = { viewModel.rejectKnowledgeReview(item) }
                )
            }
        }
    }
}

@Composable
private fun KnowledgeReviewCard(
    item: KnowledgeReviewItem,
    note: NoteDocument?,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(
                        when (item.kind) {
                            ReviewKind.ENTITY -> Icons.Default.Category
                            ReviewKind.RELATION -> Icons.Default.Share
                            ReviewKind.DUPLICATE -> Icons.Default.ContentCopy
                        },
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp).size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.kind.label, style = MaterialTheme.typography.labelMedium)
                    Text(
                        when (item.kind) {
                            ReviewKind.ENTITY -> item.subject
                            ReviewKind.RELATION -> "${item.subject} → ${item.candidate}"
                            ReviewKind.DUPLICATE -> "${item.subject} = ${item.candidate}?"
                        },
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                AssistChip(
                    onClick = {},
                    label = { Text("${(item.confidence * 100).toInt()}%") },
                    leadingIcon = { Icon(Icons.Default.Analytics, null, Modifier.size(16.dp)) }
                )
            }

            Text(
                "${item.schemaType.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }} · ${note?.let(::noteDisplayTitle) ?: "Source note"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (item.description.isNotBlank()) {
                Text(item.description, style = MaterialTheme.typography.bodyMedium)
            }
            if (item.aliases.isNotEmpty()) {
                Text(
                    "Aliases: ${item.aliases.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (item.evidence.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Evidence from note", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(4.dp))
                        Text("“${item.evidence}”", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
            ) {
                OutlinedButton(onClick = onReject) { Text("Reject") }
                Button(onClick = onAccept) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Accept")
                }
            }
        }
    }
}

@Composable
private fun ProcessingJobCard(
    job: ProcessingJob,
    note: NoteDocument?,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val statusColor = when (job.status) {
        ProcessingJobStatus.QUEUED -> MaterialTheme.colorScheme.secondaryContainer
        ProcessingJobStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer
        ProcessingJobStatus.COMPLETED -> MaterialTheme.colorScheme.tertiaryContainer
        ProcessingJobStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        ProcessingJobStatus.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = statusColor, modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (job.type == ProcessingJobType.TRANSCRIBE) Icons.Default.GraphicEq else Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.type.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        note?.let(::noteDisplayTitle) ?: "Note ${job.noteId.takeLast(8)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(shape = RoundedCornerShape(10.dp), color = statusColor) {
                    Text(
                        job.status.name.lowercase().replaceFirstChar { it.uppercase() },
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(job.message, style = MaterialTheme.typography.bodyMedium)
            if (job.status.isActive) {
                Spacer(Modifier.height(9.dp))
                LinearProgressIndicator(
                    progress = { job.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                )
            }
            if (job.error.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    job.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Attempt ${job.attempt} · ${formatNoteDateTime(job.updatedTimestamp)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when {
                    job.status.isActive -> TextButton(onClick = onCancel) { Text("Cancel") }
                    job.status.canRetry -> TextButton(onClick = onRetry) { Text("Retry") }
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
                    entity.description.contains(searchQuery.trim(), ignoreCase = true) ||
                    entity.aliases.any { it.contains(searchQuery.trim(), ignoreCase = true) })
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
                        "${entity.category.label} · ${(entity.confidence * 100).toInt()}% confidence · " +
                            "${connections.size} ${if (connections.size == 1) "connection" else "connections"}",
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
                    if (entity.aliases.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Also known as ${entity.aliases.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (entity.evidence.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                "Evidence: “${entity.evidence}”",
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
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
                            if (edge.evidence.isNotBlank()) {
                                Text(
                                    "${(edge.confidence * 100).toInt()}% · “${edge.evidence}”",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
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
                            val rankedSources = context.rankedSources.ifEmpty {
                                context.relatedNotes.distinctBy { it.id }.take(5).mapIndexed { index, note ->
                                    RetrievedSource(index + 1, note, 0.0, note.content.take(240))
                                }
                            }
                            rankedSources.forEach { source ->
                                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface) {
                                    Column(Modifier.padding(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                                Text(
                                                    source.number.toString(),
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                noteDisplayTitle(source.note),
                                                modifier = Modifier.weight(1f),
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (source.score > 0.0) {
                                                Text(
                                                    "${(source.score * 100).toInt()}%",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(5.dp))
                                        Text(
                                            source.excerpt,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (source.reasons.isNotEmpty()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                source.reasons.joinToString(" · "),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
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

private fun noteDisplayTitle(note: NoteDocument): String {
    if (note.title.isNotBlank()) return note.title
    if (note.audioPath != null && note.content.isBlank()) return "Voice note"
    return note.content
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        ?.take(72)
        ?: "Untitled note"
}

private fun notePreviewText(note: NoteDocument): String = when {
    note.content.isNotBlank() -> note.content.trim()
    note.audioPath != null -> transcriptionLabel(note.transcriptionStatus, false)
    else -> "Empty note"
}

private fun transcriptionLabel(status: TranscriptionStatus, isProcessing: Boolean): String = when {
    isProcessing && status == TranscriptionStatus.PENDING -> "Preparing offline transcription…"
    status == TranscriptionStatus.DOWNLOADING -> "Downloading speech model…"
    status == TranscriptionStatus.TRANSCRIBING -> "Transcribing on device…"
    status == TranscriptionStatus.COMPLETE -> "Transcript ready"
    status == TranscriptionStatus.FAILED -> "Transcription needs attention"
    status == TranscriptionStatus.PENDING -> "Waiting to transcribe…"
    else -> "Voice recording"
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

private fun formatNoteDateTime(timestampSeconds: Double): String {
    val milliseconds = (timestampSeconds * 1_000).toLong()
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(milliseconds))
}

private fun timelineDayLabel(note: NoteDocument): String =
    SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
        .format(Date((note.timestamp * 1_000).toLong()))
