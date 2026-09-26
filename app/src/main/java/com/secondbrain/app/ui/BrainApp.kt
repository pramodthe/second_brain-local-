package com.secondbrain.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.automirrored.filled.Notes
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secondbrain.app.data.EntityCategory
import com.secondbrain.app.data.EntityNode
import com.secondbrain.app.data.ActionItem
import com.secondbrain.app.data.ActionStatus
import com.secondbrain.app.data.KnowledgeReviewItem
import com.secondbrain.app.data.ReviewKind
import com.secondbrain.app.data.NoteDocument
import com.secondbrain.app.data.ProcessingJob
import com.secondbrain.app.data.ProcessingJobStatus
import com.secondbrain.app.data.ProcessingJobType
import com.secondbrain.app.data.RelationEdge
import com.secondbrain.app.data.RetrievedSource
import com.secondbrain.app.data.TranscriptionStatus
import com.secondbrain.app.data.TrashedNote
import com.secondbrain.app.domain.DailyReview
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

enum class BrainTab(val label: String) {
    TODAY("Today"),
    NOTES("Notes"),
    EXPLORE("Explore"),
    PROCESSING("Tasks"),
    ASK("Ask"),
    OWNERSHIP("Own")
}

private val primaryTabs = listOf(
    BrainTab.TODAY,
    BrainTab.NOTES,
    BrainTab.EXPLORE,
    BrainTab.ASK,
    BrainTab.OWNERSHIP
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainApp(viewModel: BrainViewModel) {
    var selectedTab by rememberSaveable { mutableStateOf(BrainTab.TODAY) }
    var showComposer by rememberSaveable { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NoteDocument?>(null) }
    var showActionEditor by rememberSaveable { mutableStateOf(false) }
    var editingAction by remember { mutableStateOf<ActionItem?>(null) }
    val appError by viewModel.appError.collectAsState()
    val openActionsRequest by viewModel.openActionsRequest.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    var openProcessingInActions by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.refreshData()
    }
    val requestNotificationPermission = {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(appError) {
        appError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearAppError()
        }
    }

    LaunchedEffect(openActionsRequest) {
        if (openActionsRequest > 0L) {
            openProcessingInActions = true
            selectedTab = BrainTab.PROCESSING
            viewModel.consumeOpenActionsRequest()
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
                                BrainTab.TODAY -> "Today"
                                BrainTab.NOTES -> "Second Brain"
                                BrainTab.EXPLORE -> "Explore connections"
                                BrainTab.PROCESSING -> "Tasks & review"
                                BrainTab.ASK -> "Ask your notes"
                                BrainTab.OWNERSHIP -> "Own your data"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = when (selectedTab) {
                                BrainTab.TODAY -> "Capture now, organize in the background"
                                BrainTab.NOTES -> "Your private knowledge library"
                                BrainTab.EXPLORE -> "See how your ideas connect"
                                BrainTab.PROCESSING -> "Processing you can trust and verify"
                                BrainTab.ASK -> "Answers grounded in your knowledge"
                                BrainTab.OWNERSHIP -> "Encrypted backup, restore, and privacy"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    if (selectedTab == BrainTab.PROCESSING) {
                        IconButton(onClick = { selectedTab = BrainTab.TODAY }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Today")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                primaryTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        enabled = !isRecording || selectedTab == tab,
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    BrainTab.TODAY -> Icons.Default.Today
                                    BrainTab.NOTES -> Icons.Default.Description
                                    BrainTab.EXPLORE -> Icons.Default.Hub
                                    BrainTab.PROCESSING -> Icons.Default.PendingActions
                                    BrainTab.ASK -> Icons.Default.AutoAwesome
                                    BrainTab.OWNERSHIP -> Icons.Default.Security
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
                BrainTab.TODAY -> TodayScreen(
                    viewModel = viewModel,
                    onNewNote = {
                        editingNote = null
                        showComposer = true
                    },
                    onEditNote = { note ->
                        editingNote = note
                        showComposer = true
                    },
                    onNewAction = {
                        editingAction = null
                        showActionEditor = true
                    },
                    onEditAction = { action ->
                        editingAction = action
                        showActionEditor = true
                    },
                    onOpenNotes = { selectedTab = BrainTab.NOTES },
                    onOpenActions = {
                        openProcessingInActions = true
                        selectedTab = BrainTab.PROCESSING
                    },
                    onOpenProcessing = { selectedTab = BrainTab.PROCESSING }
                )
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
                BrainTab.PROCESSING -> ProcessingScreen(
                    viewModel = viewModel,
                    onNewAction = {
                        editingAction = null
                        showActionEditor = true
                    },
                    onEditAction = { action ->
                        editingAction = action
                        showActionEditor = true
                    },
                    onEditNote = { note ->
                        editingNote = note
                        showComposer = true
                    },
                    openActionsInitially = openProcessingInActions,
                    onInitialActionsOpened = { openProcessingInActions = false }
                )
                BrainTab.ASK -> ChatScreen(viewModel)
                BrainTab.OWNERSHIP -> OwnershipScreen(viewModel)
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

    if (showActionEditor) {
        ActionEditorSheet(
            action = editingAction,
            onDismiss = {
                showActionEditor = false
                editingAction = null
            },
            onSave = { text, dueDate ->
                if (dueDate != null) requestNotificationPermission()
                viewModel.saveAction(editingAction, text, dueDate) {
                    showActionEditor = false
                    editingAction = null
                }
            }
        )
    }
}

@Composable
fun TodayScreen(
    viewModel: BrainViewModel,
    onNewNote: () -> Unit,
    onEditNote: (NoteDocument) -> Unit,
    onNewAction: () -> Unit,
    onEditAction: (ActionItem) -> Unit,
    onOpenNotes: () -> Unit,
    onOpenActions: () -> Unit,
    onOpenProcessing: () -> Unit
) {
    val notes by viewModel.notes.collectAsState()
    val jobs by viewModel.processingJobs.collectAsState()
    val reviews by viewModel.knowledgeReviews.collectAsState()
    val actionItems by viewModel.actionItems.collectAsState()
    val dailyReview by viewModel.dailyReview.collectAsState()
    val dailyBriefing by viewModel.dailyBriefing.collectAsState()
    val modelLoaded by viewModel.modelLoaded.collectAsState()
    val isModelBusy by viewModel.isModelBusy.collectAsState()
    val isIngesting by viewModel.isIngesting.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingElapsedMs by viewModel.recordingElapsedMs.collectAsState()
    val isVoiceCaptureBusy by viewModel.isVoiceCaptureBusy.collectAsState()
    val context = LocalContext.current
    var quickText by rememberSaveable { mutableStateOf("") }
    var captureMessage by remember { mutableStateOf<String?>(null) }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startVoiceRecording()
        else viewModel.reportAppError("Microphone permission is required to record a voice note.")
    }

    val startOfToday = remember(notes) { startOfTodaySeconds() }
    val todaysNotes = remember(notes, startOfToday) {
        notes.filter { it.timestamp >= startOfToday }
    }
    val activeJobs = remember(jobs) { jobs.count { it.status.isActive } }
    val failedJobs = remember(jobs) { jobs.count { it.status == ProcessingJobStatus.FAILED } }
    val openActions = remember(actionItems) {
        actionItems.filter { it.status == ActionStatus.OPEN }
    }
    val dueNow = remember(openActions) {
        val today = LocalDate.now()
        openActions.count { actionDueDate(it)?.isAfter(today) == false }
    }
    val notesById = remember(notes) { notes.associateBy(NoteDocument::id) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    todayGreeting(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text("Quick capture", fontWeight = FontWeight.SemiBold)
                            Text(
                                "No title or filing required. Save the thought first.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    if (isRecording) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Recording ${formatDuration(recordingElapsedMs)}", fontWeight = FontWeight.SemiBold)
                                    Text("Tap stop when the thought is complete", style = MaterialTheme.typography.bodySmall)
                                }
                                FilledIconButton(
                                    onClick = {
                                        viewModel.stopVoiceRecording("") {
                                            captureMessage = "Voice note saved. Transcription is running locally."
                                        }
                                    },
                                    enabled = !isVoiceCaptureBusy,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    if (isVoiceCaptureBusy) {
                                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Stop, contentDescription = "Stop and save recording")
                                    }
                                }
                                IconButton(
                                    onClick = viewModel::cancelVoiceRecording,
                                    enabled = !isVoiceCaptureBusy
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Discard recording")
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = quickText,
                            onValueChange = {
                                quickText = it
                                captureMessage = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("What's on your mind?") },
                            minLines = 3,
                            maxLines = 7,
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    val captured = quickText
                                    viewModel.saveNote("", captured) {
                                        quickText = ""
                                        captureMessage = "Saved now · organization continues in the background."
                                    }
                                },
                                enabled = quickText.isNotBlank() && !isIngesting && !isVoiceCaptureBusy,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isIngesting) {
                                    CircularProgressIndicator(
                                        Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(Icons.Default.Add, null)
                                }
                                Spacer(Modifier.width(7.dp))
                                Text(if (isIngesting) "Saving…" else "Save thought")
                            }
                            FilledTonalIconButton(
                                onClick = {
                                    captureMessage = null
                                    if (
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        viewModel.startVoiceRecording()
                                    } else {
                                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                enabled = !isIngesting && !isVoiceCaptureBusy
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = "Record a voice note")
                            }
                        }
                    }

                    captureMessage?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = onNewNote, enabled = !isRecording && !isVoiceCaptureBusy) {
                        Icon(Icons.Default.OpenInFull, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Open full editor")
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TodayMetric("Captured", todaysNotes.size, Icons.Default.EditNote, Modifier.weight(1f))
                TodayMetric("Due", dueNow, Icons.Default.Event, Modifier.weight(1f))
                TodayMetric("Open", openActions.size, Icons.AutoMirrored.Filled.FactCheck, Modifier.weight(1f))
            }
        }

        item {
            DailyReviewCard(
                review = dailyReview,
                briefing = dailyBriefing,
                modelLoaded = modelLoaded,
                modelBusy = isModelBusy,
                sourceNotes = remember(dailyReview, notesById, todaysNotes) {
                    (dailyReview.focusActions.mapNotNull { notesById[it.noteId] } +
                        dailyReview.memories.map { it.note } +
                        todaysNotes.take(3))
                        .distinctBy(NoteDocument::id)
                        .take(6)
                },
                enabled = !isRecording,
                onGenerate = viewModel::generateDailyBriefing,
                onOpenNote = onEditNote
            )
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Your actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (openActions.isEmpty()) "Nothing is waiting for you"
                        else "${openActions.size} ${if (openActions.size == 1) "open action" else "open actions"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    TextButton(onClick = onNewAction, enabled = !isRecording) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                    TextButton(onClick = onOpenActions, enabled = !isRecording) { Text("Manage") }
                }
            }
        }

        if (openActions.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.TaskAlt, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Write “TODO: …” or an explicit reminder in any note to see it here.")
                    }
                }
            }
        } else {
            items(openActions.take(5), key = { "action-${it.id}" }) { action ->
                ActionItemCard(
                    item = action,
                    sourceNote = notesById[action.noteId],
                    enabled = !isRecording,
                    onToggle = { viewModel.updateActionStatus(action, ActionStatus.COMPLETED) },
                    onDismiss = { viewModel.updateActionStatus(action, ActionStatus.DISMISSED) },
                    onEdit = { onEditAction(action) },
                    onRescheduleTomorrow = {
                        viewModel.saveAction(action, action.text, LocalDate.now().plusDays(1))
                    },
                    onOpenNote = notesById[action.noteId]?.let { note -> { onEditNote(note) } }
                )
            }
        }

        item {
            Card(
                onClick = onOpenProcessing,
                enabled = !isRecording,
                colors = CardDefaults.cardColors(
                    containerColor = if (failedJobs > 0) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when {
                            failedJobs > 0 -> Icons.Default.ErrorOutline
                            activeJobs > 0 || reviews.isNotEmpty() -> Icons.Default.AutoAwesome
                            else -> Icons.Default.CheckCircle
                        },
                        contentDescription = null
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                failedJobs > 0 -> "$failedJobs ${if (failedJobs == 1) "task needs" else "tasks need"} attention"
                                activeJobs > 0 -> "Your brain is organizing $activeJobs ${if (activeJobs == 1) "note" else "notes"}"
                                reviews.isNotEmpty() -> "${reviews.size} knowledge ${if (reviews.size == 1) "suggestion" else "suggestions"} to review"
                                else -> "Processing and review are caught up"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (activeJobs > 0 || failedJobs > 0 || reviews.isNotEmpty()) {
                                "Tap to inspect progress and knowledge suggestions"
                            } else {
                                "View completed activity and review history"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Captured today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (todaysNotes.isEmpty()) "Your day is ready for its first thought"
                        else "${todaysNotes.size} ${if (todaysNotes.size == 1) "memory" else "memories"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onOpenNotes, enabled = !isRecording) { Text("All notes") }
            }
        }

        if (todaysNotes.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WbSunny, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Write or record anything above. The date and time are added automatically.")
                    }
                }
            }
        } else {
            items(todaysNotes.take(4), key = { "today-${it.id}" }) { note ->
                TodayMemoryRow(note = note, enabled = !isRecording, onClick = { onEditNote(note) })
            }
        }

        if (dailyReview.memories.isNotEmpty()) {
            item {
                Column {
                    Text("Worth revisiting", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Selected from your notes using active actions and connected topics.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(dailyReview.memories, key = { "resurface-${it.note.id}" }) { memory ->
                Card(
                    onClick = { onEditNote(memory.note) },
                    enabled = !isRecording,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(noteDisplayTitle(memory.note), fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                memory.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Saved ${formatRelativeDay(memory.note.timestamp)} · ${notePreviewText(memory.note)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.78f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayMetric(
    label: String,
    value: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DailyReviewCard(
    review: DailyReview,
    briefing: DailyBriefingUiState,
    modelLoaded: Boolean,
    modelBusy: Boolean,
    sourceNotes: List<NoteDocument>,
    enabled: Boolean,
    onGenerate: () -> Unit,
    onOpenNote: (NoteDocument) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.padding(9.dp).size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("Daily review", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "An explainable plan from your local notes and actions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Text(review.summary, style = MaterialTheme.typography.bodyLarge)

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    ReviewMetricPill(
                        label = "${review.overdueCount} overdue",
                        icon = Icons.Default.WarningAmber
                    )
                }
                item {
                    ReviewMetricPill(
                        label = "${review.dueTodayCount} due today",
                        icon = Icons.Default.Event
                    )
                }
                item {
                    ReviewMetricPill(
                        label = "${review.completedTodayCount} completed",
                        icon = Icons.Default.TaskAlt
                    )
                }
            }

            if (briefing.text.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(7.dp))
                            Text("On-device briefing", fontWeight = FontWeight.SemiBold)
                        }
                        Text(briefing.text, style = MaterialTheme.typography.bodyMedium)
                        if (sourceNotes.isNotEmpty()) {
                            Text("Open sources", style = MaterialTheme.typography.labelMedium)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(sourceNotes, key = { "brief-source-${it.id}" }) { note ->
                                    SuggestionChip(
                                        onClick = { onOpenNote(note) },
                                        label = {
                                            Text(
                                                noteDisplayTitle(note),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            briefing.error?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            OutlinedButton(
                onClick = onGenerate,
                enabled = enabled && modelLoaded && !briefing.isGenerating && !modelBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (briefing.isGenerating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        briefing.isGenerating -> "Writing on-device…"
                        briefing.text.isNotBlank() -> "Refresh AI briefing"
                        modelBusy -> "Local model loading…"
                        !modelLoaded -> "AI briefing available when model is ready"
                        else -> "Create AI briefing"
                    }
                )
            }
        }
    }
}

@Composable
private fun ReviewMetricPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun TodayMemoryRow(note: NoteDocument, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(
                    if (note.audioPath != null) Icons.Default.GraphicEq else Icons.AutoMirrored.Filled.Notes,
                    null,
                    Modifier.padding(10.dp).size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(noteDisplayTitle(note), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    notePreviewText(note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date((note.timestamp * 1_000).toLong())),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun ActionItemCard(
    item: ActionItem,
    sourceNote: NoteDocument?,
    enabled: Boolean = true,
    onToggle: () -> Unit,
    onDismiss: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onRescheduleTomorrow: (() -> Unit)? = null,
    onOpenNote: (() -> Unit)? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val completed = item.status == ActionStatus.COMPLETED
    val dueDate = actionDueDate(item)
    val overdue = item.status == ActionStatus.OPEN && dueDate?.isBefore(LocalDate.now()) == true
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (overdue) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = completed,
                onCheckedChange = { onToggle() },
                enabled = enabled
            )
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.text,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (dueDate == null) Icons.Default.Inbox else Icons.Default.Event,
                        null,
                        Modifier.size(15.dp),
                        tint = if (overdue) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        actionDueLabel(item),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (overdue) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    sourceNote?.let {
                        Text(" · ", color = MaterialTheme.colorScheme.outline)
                        if (onOpenNote != null) {
                            TextButton(
                                onClick = onOpenNote,
                                enabled = enabled,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.heightIn(min = 28.dp)
                            ) {
                                Text(
                                    noteDisplayTitle(it),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        } else {
                            Text(
                                noteDisplayTitle(it),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
            if (onEdit != null || (!completed && onDismiss != null)) {
                Box {
                    IconButton(onClick = { menuExpanded = true }, enabled = enabled) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Action options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        onEdit?.let { edit ->
                            DropdownMenuItem(
                                text = { Text("Edit action") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = {
                                    menuExpanded = false
                                    edit()
                                }
                            )
                        }
                        if (!completed && onRescheduleTomorrow != null) {
                            DropdownMenuItem(
                                text = { Text("Move to tomorrow") },
                                leadingIcon = { Icon(Icons.Default.Event, null) },
                                onClick = {
                                    menuExpanded = false
                                    onRescheduleTomorrow()
                                }
                            )
                        }
                        if (!completed && onDismiss != null) {
                            DropdownMenuItem(
                                text = { Text("Dismiss") },
                                leadingIcon = { Icon(Icons.Default.Close, null) },
                                onClick = {
                                    menuExpanded = false
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionEditorSheet(
    action: ActionItem?,
    onDismiss: () -> Unit,
    onSave: (String, LocalDate?) -> Unit
) {
    var text by remember(action?.id) { mutableStateOf(action?.text.orEmpty()) }
    var dueDate by remember(action?.id) { mutableStateOf(action?.let(::actionDueDate)) }
    var showDatePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Text(
                    if (action == null) "New action" else "Edit action",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (action?.noteId?.isNotBlank() == true) {
                        "This keeps its link to the original note."
                    } else {
                        "Add something you want to move forward."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(240) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Action") },
                placeholder = { Text("What needs to happen?") },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (text.isNotBlank()) onSave(text.trim(), dueDate) }
                ),
                shape = RoundedCornerShape(14.dp)
            )
            Text("Due date", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = dueDate == null,
                        onClick = { dueDate = null },
                        label = { Text("No date") }
                    )
                }
                item {
                    FilterChip(
                        selected = dueDate == LocalDate.now(),
                        onClick = { dueDate = LocalDate.now() },
                        label = { Text("Today") }
                    )
                }
                item {
                    FilterChip(
                        selected = dueDate == LocalDate.now().plusDays(1),
                        onClick = { dueDate = LocalDate.now().plusDays(1) },
                        label = { Text("Tomorrow") }
                    )
                }
                item {
                    OutlinedButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            dueDate?.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
                                ?: "Choose date"
                        )
                    }
                }
            }
            if (dueDate != null) {
                Text(
                    "Second Brain will send a private on-device reminder around 9:00 am.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = { onSave(text.trim(), dueDate) },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(Modifier.width(8.dp))
                Text(if (action == null) "Add action" else "Save changes")
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = dueDate
                ?.atStartOfDay(ZoneOffset.UTC)
                ?.toInstant()
                ?.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dueDate = pickerState.selectedDateMillis?.let {
                            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                        showDatePicker = false
                    },
                    enabled = pickerState.selectedDateMillis != null
                ) { Text("Choose") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
fun NotesScreen(
    viewModel: BrainViewModel,
    onNewNote: () -> Unit,
    onEditNote: (NoteDocument) -> Unit
) {
    val notes by viewModel.notes.collectAsState()
    val trashedNotes by viewModel.trashedNotes.collectAsState()
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
    var permanentlyDelete by remember { mutableStateOf<NoteDocument?>(null) }

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
                FilterChip(
                    selected = notesMode == NotesMode.TRASH,
                    onClick = { notesMode = NotesMode.TRASH },
                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp)) },
                    label = { Text("Trash") }
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
                            notesMode == NotesMode.TRASH -> "Trash"
                            notesMode == NotesMode.TIMELINE -> "Knowledge timeline"
                            else -> "Recent notes"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (notesMode == NotesMode.TRASH && searchQuery.isBlank()) {
                            "${trashedNotes.size} ${if (trashedNotes.size == 1) "deleted note" else "deleted notes"}"
                        } else {
                            "${visibleNotes.size} ${if (visibleNotes.size == 1) "note" else "notes"}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (notes.isEmpty()) TextButton(onClick = onNewNote) { Text("Create one") }
            }
        }

        if (searchQuery.isBlank() && notesMode == NotesMode.TRASH) {
            if (trashedNotes.isEmpty()) {
                item {
                    EmptyTrashState()
                }
            } else {
                items(trashedNotes, key = { "trash-${it.note.id}" }) { trashed ->
                    TrashedNoteCard(
                        item = trashed,
                        onRestore = { viewModel.restoreNote(trashed.note) },
                        onDeleteForever = { permanentlyDelete = trashed.note }
                    )
                }
            }
        } else if (visibleNotes.isEmpty() && !isSearchingNotes) {
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
                        onTrash = { viewModel.moveNoteToTrash(note) },
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
                    onTrash = { viewModel.moveNoteToTrash(note) },
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

    permanentlyDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { permanentlyDelete = null },
            icon = { Icon(Icons.Default.DeleteForever, null) },
            title = { Text("Delete forever?") },
            text = {
                Text(
                    "“${noteDisplayTitle(note)}” and its recording, actions, search vector, reviews, and unsupported graph facts will be permanently removed. This cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.permanentlyDeleteNote(note) { permanentlyDelete = null }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete forever") }
            },
            dismissButton = {
                TextButton(onClick = { permanentlyDelete = null }) { Text("Cancel") }
            }
        )
    }
}

private enum class NotesMode { RECENT, TIMELINE, TRASH }

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
private fun EmptyTrashState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.DeleteSweep, null, Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Trash is empty", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Deleted notes stay recoverable here until you remove them forever.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TrashedNoteCard(
    item: TrashedNote,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(noteDisplayTitle(item.note), fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        "Deleted ${formatRelativeDay(item.deletedTimestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                notePreviewText(item.note),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDeleteForever) {
                    Icon(Icons.Default.DeleteForever, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete forever")
                }
                Button(onClick = onRestore) {
                    Icon(Icons.Default.RestoreFromTrash, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Restore")
                }
            }
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
    onTrash: () -> Unit,
    onTogglePlayback: () -> Unit,
    onRetryTranscription: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
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
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Note options")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Move to Trash") },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                            onClick = {
                                menuExpanded = false
                                onTrash()
                            }
                        )
                    }
                }
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
    var confirmTrash by rememberSaveable(existingNote?.id) { mutableStateOf(false) }
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
            if (existingNote != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { confirmTrash = true },
                    enabled = !isIngesting && !isRecording && !isVoiceCaptureBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.DeleteOutline, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Move note to Trash")
                }
            }
        }
    }

    if (confirmTrash && existingNote != null) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text("Move note to Trash?") },
            text = { Text("The note can be restored later. Its actions and graph facts will be hidden while it is in Trash.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.moveNoteToTrash(existingNote) {
                            confirmTrash = false
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Move to Trash") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTrash = false }) { Text("Cancel") }
            }
        )
    }
}

private enum class ActivityMode { PROCESSING, ACTIONS, REVIEW }

@Composable
fun ProcessingScreen(
    viewModel: BrainViewModel,
    onNewAction: () -> Unit,
    onEditAction: (ActionItem) -> Unit,
    onEditNote: (NoteDocument) -> Unit,
    openActionsInitially: Boolean,
    onInitialActionsOpened: () -> Unit
) {
    val jobs by viewModel.processingJobs.collectAsState()
    val reviews by viewModel.knowledgeReviews.collectAsState()
    val actionItems by viewModel.actionItems.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val notesById = remember(notes) { notes.associateBy { it.id } }
    val visibleActions = remember(actionItems) {
        actionItems.filter { it.status != ActionStatus.DISMISSED }
    }
    val today = LocalDate.now()
    val actionGroups = remember(visibleActions, today) {
        val open = visibleActions.filter { it.status == ActionStatus.OPEN }
        listOf(
            "Overdue" to open.filter { actionDueDate(it)?.isBefore(today) == true },
            "Today" to open.filter { actionDueDate(it) == today },
            "Upcoming" to open.filter { actionDueDate(it)?.isAfter(today) == true },
            "No date" to open.filter { actionDueDate(it) == null },
            "Completed" to visibleActions
                .filter { it.status == ActionStatus.COMPLETED }
                .sortedByDescending(ActionItem::updatedTimestamp)
        ).filter { it.second.isNotEmpty() }
    }
    val activeCount = jobs.count { it.status.isActive }
    val failedCount = jobs.count { it.status == ProcessingJobStatus.FAILED }
    val completedCount = jobs.count { it.status == ProcessingJobStatus.COMPLETED }
    var mode by rememberSaveable { mutableStateOf(ActivityMode.PROCESSING) }

    LaunchedEffect(openActionsInitially) {
        if (openActionsInitially) {
            mode = ActivityMode.ACTIONS
            onInitialActionsOpened()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = mode == ActivityMode.PROCESSING,
                        onClick = { mode = ActivityMode.PROCESSING },
                        label = { Text("Jobs") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = mode == ActivityMode.ACTIONS,
                        onClick = { mode = ActivityMode.ACTIONS },
                        label = { Text("Actions") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = mode == ActivityMode.REVIEW,
                        onClick = { mode = ActivityMode.REVIEW },
                        label = { Text("Review") },
                        modifier = Modifier.weight(1f)
                    )
                }
                when (mode) {
                    ActivityMode.PROCESSING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LibraryStat("Active", activeCount, Icons.Default.Sync, Modifier.weight(1f))
                        LibraryStat("Done", completedCount, Icons.Default.CheckCircle, Modifier.weight(1f))
                        LibraryStat("Failed", failedCount, Icons.Default.ErrorOutline, Modifier.weight(1f))
                    }
                    }
                    ActivityMode.ACTIONS -> {
                        val open = visibleActions.count { it.status == ActionStatus.OPEN }
                        val completed = visibleActions.count { it.status == ActionStatus.COMPLETED }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            LibraryStat("Open", open, Icons.Default.RadioButtonUnchecked, Modifier.weight(1f))
                            LibraryStat("Done", completed, Icons.Default.TaskAlt, Modifier.weight(1f))
                            LibraryStat(
                                "Due today",
                                visibleActions.count {
                                    it.status == ActionStatus.OPEN && actionDueDate(it) == LocalDate.now()
                                },
                                Icons.Default.Event,
                                Modifier.weight(1f)
                            )
                        }
                    }
                    ActivityMode.REVIEW -> {
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
        }

        if (mode == ActivityMode.PROCESSING) item {
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

        when (mode) {
            ActivityMode.PROCESSING -> {
                if (jobs.isEmpty()) {
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
                } else {
                    items(jobs, key = { it.id }) { job ->
                        ProcessingJobCard(
                            job = job,
                            note = notesById[job.noteId],
                            onRetry = { viewModel.retryProcessingJob(job) },
                            onCancel = { viewModel.cancelProcessingJob(job) }
                        )
                    }
                }
            }
            ActivityMode.ACTIONS -> {
                item(key = "new-action") {
                    FilledTonalButton(
                        onClick = onNewAction,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.AddTask, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add an action")
                    }
                }
                if (visibleActions.isEmpty()) {
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
                                    Icon(Icons.Default.TaskAlt, null, Modifier.size(32.dp))
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("No actions yet", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Add one directly, or write TODO, Reminder, or an unchecked checklist in a note.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    actionGroups.forEach { (label, actions) ->
                        item(key = "action-section-$label") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    actions.size.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(actions, key = { it.id }) { action ->
                            val sourceNote = notesById[action.noteId]
                            ActionItemCard(
                                item = action,
                                sourceNote = sourceNote,
                                onToggle = {
                                    viewModel.updateActionStatus(
                                        action,
                                        if (action.status == ActionStatus.COMPLETED) {
                                            ActionStatus.OPEN
                                        } else {
                                            ActionStatus.COMPLETED
                                        }
                                    )
                                },
                                onDismiss = if (action.status == ActionStatus.OPEN) {
                                    { viewModel.updateActionStatus(action, ActionStatus.DISMISSED) }
                                } else {
                                    null
                                },
                                onEdit = { onEditAction(action) },
                                onRescheduleTomorrow = if (action.status == ActionStatus.OPEN) {
                                    {
                                        viewModel.saveAction(
                                            action,
                                            action.text,
                                            LocalDate.now().plusDays(1)
                                        )
                                    }
                                } else {
                                    null
                                },
                                onOpenNote = sourceNote?.let { note -> { onEditNote(note) } }
                            )
                        }
                    }
                }
            }
            ActivityMode.REVIEW -> {
                if (reviews.isEmpty()) {
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    items(reviews, key = { it.id }) { review ->
                        KnowledgeReviewCard(
                            item = review,
                            note = notesById[review.noteId],
                            onAccept = { viewModel.acceptKnowledgeReview(review) },
                            onReject = { viewModel.rejectKnowledgeReview(review) }
                        )
                    }
                }
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
fun OwnershipScreen(viewModel: BrainViewModel) {
    val backupState by viewModel.backupState.collectAsState()
    // Credentials deliberately stay out of Android saved-instance state.
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var includeRecordings by rememberSaveable { mutableStateOf(true) }
    var showPassphrase by rememberSaveable { mutableStateOf(false) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { viewModel.exportBackup(it, passphrase, includeRecordings) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.restoreBackup(it, passphrase) }
    }

    LaunchedEffect(backupState.completedOperation) {
        if (backupState.completedOperation > 0L) {
            passphrase = ""
            confirmation = ""
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, Modifier.size(32.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Your memories, in your hands", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Backups use AES-256-GCM encryption before leaving the app.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Backup passphrase", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "This is never saved or uploaded. If you forget it, the backup cannot be recovered.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = {
                            passphrase = it
                            localError = null
                            viewModel.clearBackupStatus()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Passphrase") },
                        singleLine = true,
                        visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassphrase = !showPassphrase }) {
                                Icon(
                                    if (showPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showPassphrase) "Hide passphrase" else "Show passphrase"
                                )
                            }
                        }
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = {
                            confirmation = it
                            localError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Confirm for export") },
                        supportingText = { Text("Required only when creating a new backup") },
                        singleLine = true,
                        visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation()
                    )
                    localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Archive, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Create encrypted backup", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Notes, graph, evidence, aliases, and review history",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Include voice recordings")
                            Text(
                                "Model files are never included.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = includeRecordings, onCheckedChange = { includeRecordings = it })
                    }
                    Button(
                        onClick = {
                            when {
                                passphrase.length < 8 -> localError = "Use at least 8 characters."
                                passphrase != confirmation -> localError = "The passphrases do not match."
                                else -> exportLauncher.launch(defaultBackupFilename())
                            }
                        },
                        enabled = !backupState.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FileUpload, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose location & export")
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Restore, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Restore a backup", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Merges safely; unchanged or newer local notes are kept.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            if (passphrase.length < 8) {
                                localError = "Enter the backup passphrase first."
                            } else {
                                restoreLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
                            }
                        },
                        enabled = !backupState.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FileDownload, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose backup & restore")
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Privacy posture", fontWeight = FontWeight.SemiBold)
                    PrivacyRow(Icons.Default.PhoneAndroid, "Notes and inference stay on this device")
                    PrivacyRow(Icons.Default.CloudOff, "Android cloud backup is disabled")
                    PrivacyRow(Icons.Default.WifiOff, "Cleartext network traffic is blocked")
                    PrivacyRow(Icons.Default.Key, "Your passphrase is never saved by the app")
                }
            }
        }

        if (backupState.isBusy || backupState.message != null || backupState.error != null) item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (backupState.error != null) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (backupState.isBusy) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (backupState.error != null) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                            contentDescription = null
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(backupState.error ?: backupState.message.orEmpty(), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PrivacyRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

private fun defaultBackupFilename(): String =
    "second-brain-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.sbrain"

@Composable
fun ChatScreen(viewModel: BrainViewModel) {
    var queryText by rememberSaveable { mutableStateOf("") }
    val messages by viewModel.chatMessages.collectAsState()
    val pendingPlan by viewModel.pendingAgentPlan.collectAsState()
    val executions by viewModel.agentExecutions.collectAsState()
    val canUndoPlan by viewModel.canUndoAgentPlan.collectAsState()
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
                    "Qwen3.5 4B · On-device GPU",
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

        pendingPlan?.let { pending ->
            AgentPlanConfirmationCard(
                pending = pending,
                onConfirm = viewModel::confirmAgentPlan,
                onCancel = viewModel::cancelAgentPlan
            )
        }

        if (pendingPlan == null) executions.firstOrNull()?.let { execution ->
            AgentExecutionCard(
                execution = execution,
                canUndo = canUndoPlan && execution.status == AgentExecutionStatus.SUCCEEDED,
                onUndo = viewModel::undoLastAgentPlan
            )
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp, shadowElevation = 4.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().imePadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = queryText,
                    onValueChange = { queryText = it },
                    placeholder = { Text("Ask—or tell the agent what to change") },
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
            "Create a note: My next idea is…",
            "Show my deleted notes"
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
private fun AgentPlanConfirmationCard(
    pending: PendingAgentPlan,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 2.dp
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GppMaybe, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(9.dp))
                Text("Confirmation required", fontWeight = FontWeight.SemiBold)
            }
            Text(
                "The agent will run these ${pending.steps.size} ${if (pending.steps.size == 1) "step" else "steps"} in order:",
                style = MaterialTheme.typography.bodyMedium
            )
            pending.steps.forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.Top) {
                    Surface(
                        modifier = Modifier.size(24.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(step.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Confirm change") }
            }
        }
    }
}

@Composable
private fun AgentExecutionCard(
    execution: AgentExecutionUi,
    canUndo: Boolean,
    onUndo: () -> Unit
) {
    val color = when (execution.status) {
        AgentExecutionStatus.SUCCEEDED -> MaterialTheme.colorScheme.tertiaryContainer
        AgentExecutionStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        AgentExecutionStatus.UNDONE -> MaterialTheme.colorScheme.surfaceVariant
        AgentExecutionStatus.RUNNING -> MaterialTheme.colorScheme.secondaryContainer
    }
    Surface(color = color, tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when (execution.status) {
                    AgentExecutionStatus.SUCCEEDED -> Icons.Default.CheckCircle
                    AgentExecutionStatus.FAILED -> Icons.Default.ErrorOutline
                    AgentExecutionStatus.UNDONE -> Icons.Default.History
                    AgentExecutionStatus.RUNNING -> Icons.Default.Sync
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("Agent history · ${execution.message}", style = MaterialTheme.typography.labelLarge)
                Text(
                    execution.stepSummaries.joinToString(" · ").take(180),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (canUndo) TextButton(onClick = onUndo) {
                Icon(Icons.Default.History, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("Undo")
            }
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
                        if (modelReady) "Qwen3.5 4B is ready to load."
                        else "Download the 2.7 GB model to answer privately.",
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
                if (!isUser && message.toolName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BuildCircle, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            message.toolName.replace('_', ' ').replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
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

private fun startOfTodaySeconds(): Double =
    LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond().toDouble()

private fun todayGreeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "A quiet moment to think"
}

private fun formatRelativeDay(timestampSeconds: Double): String {
    val savedDate = Instant.ofEpochSecond(timestampSeconds.toLong())
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    val days = ChronoUnit.DAYS.between(savedDate, LocalDate.now()).toInt().coerceAtLeast(1)
    return when (days) {
        1 -> "yesterday"
        in 2..6 -> "$days days ago"
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault())
            .format(Date((timestampSeconds * 1_000).toLong()))
    }
}

private fun actionDueDate(item: ActionItem): LocalDate? = item.dueTimestamp?.let {
    Instant.ofEpochSecond(it.toLong()).atZone(ZoneId.systemDefault()).toLocalDate()
}

private fun actionDueLabel(item: ActionItem): String {
    val due = actionDueDate(item) ?: return "No due date"
    val today = LocalDate.now()
    return when (due) {
        today -> "Due today"
        today.plusDays(1) -> "Due tomorrow"
        else -> {
            val prefix = if (due.isBefore(today)) "Overdue · " else "Due "
            prefix + due.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        }
    }
}

private fun timelineDayLabel(note: NoteDocument): String =
    SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
        .format(Date((note.timestamp * 1_000).toLong()))
