package com.secondbrain.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomInMap
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secondbrain.app.data.EntityCategory
import com.secondbrain.app.data.EntityNode
import com.secondbrain.app.data.RelationEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class SimNode(
    val entity: EntityNode,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var isPinned: Boolean = false,
    val radius: Float = 22f
)

class SimEdge(
    val edge: RelationEdge,
    val sourceIndex: Int,
    val targetIndex: Int
)

@Composable
fun ForceGraphView(
    entities: List<EntityNode>,
    edges: List<RelationEdge>,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    // Simulation state
    var scale by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var selectedNode by remember { mutableStateOf<SimNode?>(null) }
    var draggedNode by remember { mutableStateOf<SimNode?>(null) }
    var simulationEnergy by remember { mutableFloatStateOf(100f) }

    // Create simulation graph nodes
    val simNodes = remember(entities) {
        val count = entities.size
        val radius = max(180f, count * 35f)
        entities.mapIndexed { index, e ->
            val angle = 2f * Math.PI.toFloat() * index / max(1, count)
            val dist = radius * (0.6f + Random.nextFloat() * 0.5f)
            SimNode(
                entity = e,
                x = dist * kotlin.math.cos(angle),
                y = dist * kotlin.math.sin(angle)
            )
        }
    }

    val simEdges = remember(edges, simNodes) {
        val nameIndex = simNodes.mapIndexed { idx, n -> n.entity.name.lowercase().trim() to idx }.toMap()
        edges.mapNotNull { e ->
            val sIdx = nameIndex[e.source.lowercase().trim()]
            val tIdx = nameIndex[e.target.lowercase().trim()]
            if (sIdx != null && tIdx != null) {
                SimEdge(e, sIdx, tIdx)
            } else null
        }
    }

    // Force simulation physics loop
    LaunchedEffect(simNodes, simEdges) {
        simulationEnergy = 100f
        while (isActive) {
            if (simulationEnergy > 0.1f) {
                stepPhysics(simNodes, simEdges)
                simulationEnergy *= 0.985f
            }
            delay(16) // ~60-120fps physics tick
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101018))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Zoom & Pan
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.3f, 4.0f)
                        panOffset += pan
                    }
                }
                .pointerInput(simNodes, scale, panOffset) {
                    // Tap selection & Dragging
                    detectTapGestures(
                        onTap = { tapPos ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val worldTap = (tapPos - center - panOffset) / scale
                            val hit = simNodes.find { node ->
                                val dx = node.x - worldTap.x
                                val dy = node.y - worldTap.y
                                sqrt(dx * dx + dy * dy) <= (node.radius * 1.5f)
                            }
                            selectedNode = hit
                        }
                    )
                }
                .pointerInput(simNodes, scale, panOffset) {
                    detectDragGestures(
                        onDragStart = { startPos ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val worldStart = (startPos - center - panOffset) / scale
                            draggedNode = simNodes.find { node ->
                                val dx = node.x - worldStart.x
                                val dy = node.y - worldStart.y
                                sqrt(dx * dx + dy * dy) <= (node.radius * 2f)
                            }?.also {
                                it.isPinned = true
                                simulationEnergy = 80f
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            draggedNode?.let { node ->
                                node.x += dragAmount.x / scale
                                node.y += dragAmount.y / scale
                                simulationEnergy = max(simulationEnergy, 40f)
                            }
                        },
                        onDragEnd = {
                            draggedNode?.isPinned = false
                            draggedNode = null
                        },
                        onDragCancel = {
                            draggedNode?.isPinned = false
                            draggedNode = null
                        }
                    )
                }
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)

            // Screen transform helper
            fun toScreen(worldX: Float, worldY: Float): Offset {
                return Offset(
                    center.x + panOffset.x + worldX * scale,
                    center.y + panOffset.y + worldY * scale
                )
            }

            val focused = selectedNode

            // 1. Draw Edges
            for (edge in simEdges) {
                val source = simNodes[edge.sourceIndex]
                val target = simNodes[edge.targetIndex]

                val p1 = toScreen(source.x, source.y)
                val p2 = toScreen(target.x, target.y)

                val isConnectedToFocus = focused == null || source == focused || target == focused
                val edgeColor = if (isConnectedToFocus) {
                    if (focused != null) Color(0xFF00E5FF).copy(alpha = 0.85f)
                    else Color(0xFF8888AA).copy(alpha = 0.45f)
                } else {
                    Color(0xFF444455).copy(alpha = 0.15f)
                }
                val strokeWidth = if (focused != null && isConnectedToFocus) 2.5f * scale else 1.2f * scale

                drawLine(
                    color = edgeColor,
                    start = p1,
                    end = p2,
                    strokeWidth = strokeWidth.coerceIn(1f, 5f)
                )
            }

            // 2. Draw Nodes
            for (node in simNodes) {
                val p = toScreen(node.x, node.y)
                val isSelected = node == focused
                val isConnected = focused == null || isSelected || simEdges.any {
                    (simNodes[it.sourceIndex] == focused && simNodes[it.targetIndex] == node) ||
                            (simNodes[it.targetIndex] == focused && simNodes[it.sourceIndex] == node)
                }

                val baseColor = categoryColor(node.entity.category)
                val nodeColor = if (isConnected) baseColor else baseColor.copy(alpha = 0.25f)
                val r = (node.radius * (if (isSelected) 1.35f else 1.0f) * scale).coerceIn(8f, 60f)

                // Outer Glow ring if selected
                if (isSelected) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.35f),
                        radius = r + 8f,
                        center = p
                    )
                    drawCircle(
                        color = Color(0xFF00E5FF).copy(alpha = 0.8f),
                        radius = r + 4f,
                        center = p,
                        style = Stroke(width = 2.5f)
                    )
                }

                // Node Body
                drawCircle(
                    color = nodeColor,
                    radius = r,
                    center = p
                )

                // Inner core
                drawCircle(
                    color = Color.White.copy(alpha = if (isConnected) 0.85f else 0.2f),
                    radius = (r * 0.35f),
                    center = p
                )

                // Label (only if scale is reasonable or node is focused)
                if (scale >= 0.7f || isSelected) {
                    val label = node.entity.name
                    val measured = textMeasurer.measure(
                        text = label,
                        style = TextStyle(
                            color = if (isConnected) Color.White else Color.Gray.copy(alpha = 0.4f),
                            fontSize = (11f * scale).coerceIn(9f, 16f).sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(p.x - measured.size.width / 2f, p.y + r + 4f)
                    )
                }
            }
        }

        // Overlay Controls (Zoom In, Zoom Out, Reset Pan)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalIconButton(
                onClick = { scale = (scale * 1.25f).coerceAtMost(4.0f) },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                )
            ) {
                Icon(Icons.Default.ZoomInMap, contentDescription = "Zoom In")
            }
            FilledTonalIconButton(
                onClick = { scale = (scale * 0.8f).coerceAtLeast(0.3f) },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                )
            ) {
                Icon(Icons.Default.ZoomOutMap, contentDescription = "Zoom Out")
            }
        }

        // Selected Node Inspection Card (Obsidian-style detail card)
        selectedNode?.let { node ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = categoryColor(node.entity.category)
                            ) {
                                Text(
                                    " ${node.entity.category.label} ",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(node.entity.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        IconButton(onClick = { selectedNode = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                        }
                    }

                    val connected = simEdges.filter {
                        simNodes[it.sourceIndex] == node || simNodes[it.targetIndex] == node
                    }
                    if (connected.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Connected Knowledge (${connected.size} links):", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        connected.take(4).forEach { e ->
                            val isSource = simNodes[e.sourceIndex] == node
                            val neighbor = if (isSource) simNodes[e.targetIndex] else simNodes[e.sourceIndex]
                            val rel = e.edge.relation.name
                            Text(
                                " • ${if (isSource) "[$rel] →" else "← [$rel]"} ${neighbor.entity.name}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 2D Force-Directed Simulation Step (Coulomb repulsion + Hooke spring attraction + gravity).
 */
private fun stepPhysics(nodes: List<SimNode>, edges: List<SimEdge>) {
    val kRepulsion = 1400f
    val kSpring = 0.045f
    val restLength = 110f
    val kGravity = 0.025f
    val damping = 0.86f

    // 1. Repulsion between all node pairs
    for (i in nodes.indices) {
        val n1 = nodes[i]
        for (j in i + 1 until nodes.size) {
            val n2 = nodes[j]
            var dx = n2.x - n1.x
            var dy = n2.y - n1.y
            var dist = sqrt(dx * dx + dy * dy)
            if (dist < 1f) {
                dx = Random.nextFloat() - 0.5f
                dy = Random.nextFloat() - 0.5f
                dist = 1f
            }
            val force = kRepulsion / (dist * dist)
            val fx = (dx / dist) * force
            val fy = (dy / dist) * force

            if (!n1.isPinned) { n1.vx -= fx; n1.vy -= fy }
            if (!n2.isPinned) { n2.vx += fx; n2.vy += fy }
        }
    }

    // 2. Spring attraction along edges
    for (e in edges) {
        val n1 = nodes[e.sourceIndex]
        val n2 = nodes[e.targetIndex]
        var dx = n2.x - n1.x
        var dy = n2.y - n1.y
        var dist = sqrt(dx * dx + dy * dy)
        if (dist < 1f) dist = 1f
        val delta = dist - restLength
        val force = kSpring * delta
        val fx = (dx / dist) * force
        val fy = (dy / dist) * force

        if (!n1.isPinned) { n1.vx += fx; n1.vy += fy }
        if (!n2.isPinned) { n2.vx -= fx; n2.vy -= fy }
    }

    // 3. Center gravity and position update
    for (node in nodes) {
        if (!node.isPinned) {
            node.vx -= node.x * kGravity
            node.vy -= node.y * kGravity
            node.vx *= damping
            node.vy *= damping
            node.x += node.vx
            node.y += node.vy
        }
    }
}
