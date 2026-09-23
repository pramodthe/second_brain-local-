package com.secondbrain.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secondbrain.app.data.EntityCategory
import com.secondbrain.app.data.EntityNode
import com.secondbrain.app.data.NoteDocument
import com.secondbrain.app.data.RelationEdge

enum class BrainTab(val label: String) {
    NOTES("Notes & Capture"),
    GRAPH("Ontology Graph"),
    CHAT("Cognitive Chat")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainApp(viewModel: BrainViewModel) {
    var selectedTab by remember { mutableStateOf(BrainTab.NOTES) }
    val stats by viewModel.stats.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Second Brain", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            "Notes: ${stats["notes"] ?: 0}  •  Entities: ${stats["entities"] ?: 0}  •  Edges: ${stats["edges"] ?: 0}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == BrainTab.NOTES,
                    onClick = { selectedTab = BrainTab.NOTES },
                    icon = { Icon(Icons.Default.EditNote, contentDescription = "Notes") },
                    label = { Text("Capture") }
                )
                NavigationBarItem(
                    selected = selectedTab == BrainTab.GRAPH,
                    onClick = { selectedTab = BrainTab.GRAPH },
                    icon = { Icon(Icons.Default.Hub, contentDescription = "Graph") },
                    label = { Text("Knowledge") }
                )
                NavigationBarItem(
                    selected = selectedTab == BrainTab.CHAT,
                    onClick = { selectedTab = BrainTab.CHAT },
                    icon = { Icon(Icons.Default.Psychology, contentDescription = "Chat") },
                    label = { Text("Chat") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                BrainTab.NOTES -> NotesScreen(viewModel)
                BrainTab.GRAPH -> GraphScreen(viewModel)
                BrainTab.CHAT -> ChatScreen(viewModel)
            }
        }
    }
}

@Composable
fun NotesScreen(viewModel: BrainViewModel) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    val notes by viewModel.notes.collectAsState()
    val isIngesting by viewModel.isIngesting.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Capture New Knowledge", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("Note content / Idea / Book excerpt") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            viewModel.saveNote(title, content) {
                                title = ""
                                content = ""
                            }
                        },
                        enabled = content.isNotBlank() && !isIngesting,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        if (isIngesting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Ingesting...")
                        } else {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save to Brain")
                        }
                    }
                }
            }
        }

        item {
            Text("Recent Knowledge Entries (${notes.size})", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }

        if (notes.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No notes saved yet. Capture your first thought above!", color = Color.Gray)
                }
            }
        }

        items(notes, key = { it.id }) { note ->
            NoteCard(note)
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
fun NoteCard(note: NoteDocument) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(note.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(note.content, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Text("Source: ${note.source}", fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
fun GraphScreen(viewModel: BrainViewModel) {
    val entities by viewModel.entities.collectAsState()
    val edges by viewModel.edges.collectAsState()
    var isCanvasView by remember { mutableStateOf(true) }
    var selectedCategory by remember { mutableStateOf<EntityCategory?>(null) }

    val filteredEntities = remember(entities, selectedCategory) {
        if (selectedCategory == null) entities else entities.filter { it.category == selectedCategory }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Mode Switcher Header
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isCanvasView) "Interactive Graph Canvas" else "Structured Knowledge List",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = isCanvasView,
                        onClick = { isCanvasView = true },
                        leadingIcon = { Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Canvas") }
                    )
                    FilterChip(
                        selected = !isCanvasView,
                        onClick = { isCanvasView = false },
                        leadingIcon = { Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("List") }
                    )
                }
            }
        }

        if (isCanvasView) {
            // Obsidian-style 2D Force Directed Graph Canvas
            ForceGraphView(
                entities = entities,
                edges = edges,
                modifier = Modifier.weight(1f)
            )
        } else {
            // Structured List with Category Filters
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Ontology Filter", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = { selectedCategory = null },
                                label = { Text("All (${entities.size})") }
                            )
                        }
                        items(EntityCategory.entries) { cat ->
                            val count = entities.count { it.category == cat }
                            FilterChip(
                                selected = selectedCategory == cat,
                                onClick = { selectedCategory = cat },
                                label = { Text("${cat.label} ($count)") }
                            )
                        }
                    }
                }

                item {
                    Text("Ontology Entities (${filteredEntities.size})", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }

                items(filteredEntities, key = { it.name }) { entity ->
                    EntityItem(entity)
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Relational Graph Edges (${edges.size})", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }

                items(edges) { edge ->
                    EdgeItem(edge)
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
fun EntityItem(entity: EntityNode) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(categoryColor(entity.category))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(entity.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(entity.category.label, fontSize = 12.sp, color = categoryColor(entity.category))
            }
        }
    }
}

@Composable
fun EdgeItem(edge: RelationEdge) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(edge.source, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    " [${edge.relation.name}] ",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(edge.target, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

fun categoryColor(category: EntityCategory): Color {
    return when (category) {
        EntityCategory.CONCEPT -> Color(0xFF7C4DFF)
        EntityCategory.PROJECT -> Color(0xFF00B0FF)
        EntityCategory.RESOURCE -> Color(0xFF00E676)
        EntityCategory.PERSON -> Color(0xFFFF9100)
        EntityCategory.DECISION -> Color(0xFFFF5252)
        EntityCategory.INSIGHT -> Color(0xFFFFD600)
    }
}

@Composable
fun ChatScreen(viewModel: BrainViewModel) {
    var queryText by remember { mutableStateOf("") }
    val messages by viewModel.chatMessages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Ask questions grounded in your Second Brain's knowledge graph.\n\nE.g.: 'Why did we choose event sourcing?'",
                            color = Color.Gray,
                            lineHeight = 20.sp
                        )
                    }
                }
            }

            items(messages) { msg ->
                ChatBubble(msg)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it },
                placeholder = { Text("Ask your Second Brain...") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val q = queryText
                    queryText = ""
                    viewModel.askQuestion(q)
                },
                enabled = queryText.isNotBlank() && !isGenerating
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessageItem) {
    val isUser = msg.sender == "user"
    var expandedContext by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = msg.text.ifBlank { "Thinking..." },
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )

                // Show retrieved graph context button if available
                msg.context?.let { ctx ->
                    if (ctx.anchorEntities.isNotEmpty() || ctx.connectedEdges.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (expandedContext) "Hide Graph Grounding ▲" else "View Graph Grounding (${ctx.anchorEntities.size} nodes) ▼",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { expandedContext = !expandedContext }
                        )

                        AnimatedVisibility(visible = expandedContext) {
                            Column(modifier = Modifier.padding(top = 6.dp)) {
                                ctx.anchorEntities.forEach { e ->
                                    Text("• Entity: ${e.name} [${e.category.label}]", fontSize = 11.sp)
                                }
                                ctx.connectedEdges.forEach { r ->
                                    Text("  └─ [${r.relation.name}] → ${r.target}", fontSize = 10.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
