package com.secondbrain.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secondbrain.app.data.EntityNode
import com.secondbrain.app.data.RelationEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class SimNode(
    val entity: EntityNode,
    initialX: Float,
    initialY: Float,
    val radius: Float
) {
    var x by mutableFloatStateOf(initialX)
    var y by mutableFloatStateOf(initialY)
    var vx by mutableFloatStateOf(0f)
    var vy by mutableFloatStateOf(0f)
}

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
    val density = LocalDensity.current
    val nodeRadius = with(density) { 28.dp.toPx() }
    val minimumTouchRadius = with(density) { 32.dp.toPx() }
    val labelPaddingX = with(density) { 7.dp.toPx() }
    val labelPaddingY = with(density) { 4.dp.toPx() }

    var scale by remember { mutableFloatStateOf(0.82f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var selectedNode by remember { mutableStateOf<SimNode?>(null) }
    var simulationEnergy by remember { mutableFloatStateOf(100f) }

    val simNodes = remember(entities, nodeRadius) {
        val count = entities.size.coerceAtLeast(1)
        val baseRadius = max(170f, sqrt(count.toFloat()) * 95f)
        entities.mapIndexed { index, entity ->
            val angle = (2.0 * PI * index / count).toFloat()
            val ring = baseRadius * (0.72f + (index % 3) * 0.14f)
            SimNode(
                entity = entity,
                initialX = ring * cos(angle),
                initialY = ring * sin(angle),
                radius = nodeRadius
            )
        }
    }

    val simEdges = remember(edges, simNodes) {
        val nameIndex = simNodes.mapIndexed { index, node -> normalizeGraphName(node.entity.name) to index }.toMap()
        edges.mapNotNull { edge ->
            val sourceIndex = nameIndex[normalizeGraphName(edge.source)]
            val targetIndex = nameIndex[normalizeGraphName(edge.target)]
            if (sourceIndex != null && targetIndex != null && sourceIndex != targetIndex) {
                SimEdge(edge, sourceIndex, targetIndex)
            } else {
                null
            }
        }
    }

    LaunchedEffect(simNodes, simEdges) {
        simulationEnergy = 100f
        while (isActive && simulationEnergy > 0.15f) {
            stepPhysics(simNodes, simEdges)
            simulationEnergy *= 0.975f
            delay(16)
        }
    }

    val canvasColor = Color(0xFF11131A)
    val labelColor = Color(0xFFF4F2FA)
    val subduedLabelColor = Color(0xFFAAA8B3)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(canvasColor)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(simNodes) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.35f, 3.5f)
                        panOffset += pan
                    }
                }
                .pointerInput(simNodes) {
                    detectTapGestures { tapPosition ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val worldTap = (tapPosition - center - panOffset) / scale
                        val hitRadius = max(nodeRadius * 1.45f, minimumTouchRadius / scale)
                        selectedNode = simNodes
                            .asReversed()
                            .firstOrNull { node ->
                                val dx = node.x - worldTap.x
                                val dy = node.y - worldTap.y
                                sqrt(dx * dx + dy * dy) <= hitRadius
                            }
                    }
                }
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)

            fun toScreen(worldX: Float, worldY: Float): Offset = Offset(
                center.x + panOffset.x + worldX * scale,
                center.y + panOffset.y + worldY * scale
            )

            val focused = selectedNode

            simEdges.forEach { edge ->
                val source = simNodes[edge.sourceIndex]
                val target = simNodes[edge.targetIndex]
                val sourcePoint = toScreen(source.x, source.y)
                val targetPoint = toScreen(target.x, target.y)
                val isConnected = focused == null || source == focused || target == focused

                drawLine(
                    color = when {
                        focused != null && isConnected -> Color(0xFF8BE0D0).copy(alpha = 0.9f)
                        isConnected -> Color(0xFF8C91A3).copy(alpha = 0.55f)
                        else -> Color(0xFF4A4D59).copy(alpha = 0.18f)
                    },
                    start = sourcePoint,
                    end = targetPoint,
                    strokeWidth = if (focused != null && isConnected) 3f else 1.5f
                )
            }

            simNodes.forEach { node ->
                val point = toScreen(node.x, node.y)
                val isSelected = node == focused
                val isConnected = focused == null || isSelected || simEdges.any { edge ->
                    (simNodes[edge.sourceIndex] == focused && simNodes[edge.targetIndex] == node) ||
                        (simNodes[edge.targetIndex] == focused && simNodes[edge.sourceIndex] == node)
                }
                val radius = (node.radius * scale * if (isSelected) 1.16f else 1f).coerceIn(13f, 62f)
                val nodeColor = categoryColor(node.entity.category).let {
                    if (isConnected) it else it.copy(alpha = 0.22f)
                }

                if (isSelected) {
                    drawCircle(Color.White.copy(alpha = 0.12f), radius + 12f, point)
                    drawCircle(Color(0xFF8BE0D0), radius + 5f, point, style = Stroke(3f))
                }
                drawCircle(nodeColor, radius, point)
                drawCircle(Color.White.copy(alpha = if (isConnected) 0.9f else 0.2f), radius * 0.24f, point)

                if (scale >= 0.62f || isSelected) {
                    val label = node.entity.name.take(28)
                    val measured = textMeasurer.measure(
                        text = label,
                        style = TextStyle(
                            color = if (isConnected) labelColor else subduedLabelColor.copy(alpha = 0.35f),
                            fontSize = (12f * scale).coerceIn(11f, 15f).sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    )
                    val labelTopLeft = Offset(
                        point.x - measured.size.width / 2f - labelPaddingX,
                        point.y + radius + 7f
                    )
                    drawRoundRect(
                        color = Color(0xE620222B),
                        topLeft = labelTopLeft,
                        size = Size(
                            measured.size.width + labelPaddingX * 2,
                            measured.size.height + labelPaddingY * 2
                        ),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(labelTopLeft.x + labelPaddingX, labelTopLeft.y + labelPaddingY)
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xD91D2029)
        ) {
            Text(
                "Drag to move · pinch to zoom · tap a node",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = Color(0xFFD7D5DE),
                style = MaterialTheme.typography.labelMedium
            )
        }

        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GraphControl(Icons.Default.Add, "Zoom in") {
                scale = (scale * 1.2f).coerceAtMost(3.5f)
            }
            GraphControl(Icons.Default.Remove, "Zoom out") {
                scale = (scale / 1.2f).coerceAtLeast(0.35f)
            }
            GraphControl(Icons.Default.CenterFocusStrong, "Fit graph") {
                scale = 0.82f
                panOffset = Offset.Zero
                selectedNode = null
            }
        }

        selectedNode?.let { node ->
            val connected = simEdges.filter {
                simNodes[it.sourceIndex] == node || simNodes[it.targetIndex] == node
            }
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(12.dp)
                                .background(categoryColor(node.entity.category), CircleShape)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(node.entity.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${node.entity.category.label} · ${connected.size} ${if (connected.size == 1) "connection" else "connections"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { selectedNode = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Close details")
                        }
                    }
                    if (node.entity.description.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(node.entity.description, style = MaterialTheme.typography.bodyMedium)
                    }
                    connected.take(4).forEach { connection ->
                        val outgoing = simNodes[connection.sourceIndex] == node
                        val neighbor = if (outgoing) simNodes[connection.targetIndex] else simNodes[connection.sourceIndex]
                        Text(
                            if (outgoing) "${connection.edge.relation.name.replace('_', ' ').lowercase()} → ${neighbor.entity.name}"
                            else "${neighbor.entity.name} → ${connection.edge.relation.name.replace('_', ' ').lowercase()}",
                            modifier = Modifier.padding(top = 7.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color(0xE62A2D37),
            contentColor = Color.White
        )
    ) {
        Icon(icon, contentDescription = description)
    }
}

private fun stepPhysics(nodes: List<SimNode>, edges: List<SimEdge>) {
    val repulsion = 90_000f
    val spring = 0.009f
    val restLength = 220f
    val gravity = 0.0035f
    val damping = 0.82f

    for (i in nodes.indices) {
        val first = nodes[i]
        for (j in i + 1 until nodes.size) {
            val second = nodes[j]
            var dx = second.x - first.x
            var dy = second.y - first.y
            var distance = sqrt(dx * dx + dy * dy)
            if (distance < 1f) {
                dx = 0.5f
                dy = 0.5f
                distance = 1f
            }
            val force = repulsion / (distance * distance)
            val forceX = (dx / distance) * force
            val forceY = (dy / distance) * force
            first.vx -= forceX
            first.vy -= forceY
            second.vx += forceX
            second.vy += forceY
        }
    }

    edges.forEach { edge ->
        val source = nodes[edge.sourceIndex]
        val target = nodes[edge.targetIndex]
        val dx = target.x - source.x
        val dy = target.y - source.y
        val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val force = spring * (distance - restLength)
        val forceX = (dx / distance) * force
        val forceY = (dy / distance) * force
        source.vx += forceX
        source.vy += forceY
        target.vx -= forceX
        target.vy -= forceY
    }

    nodes.forEach { node ->
        node.vx = (node.vx - node.x * gravity) * damping
        node.vy = (node.vy - node.y * gravity) * damping
        node.x += node.vx
        node.y += node.vy
    }
}
