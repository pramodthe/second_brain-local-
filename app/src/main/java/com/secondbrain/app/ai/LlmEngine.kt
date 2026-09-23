package com.secondbrain.app.ai

import android.content.Context
import android.util.Log
import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.NexaSdk
import com.nexa.sdk.bean.ChatMessage
import com.nexa.sdk.bean.DeviceIdValue
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmCreateInput
import com.nexa.sdk.bean.LlmStreamResult
import com.nexa.sdk.bean.ModelConfig
import com.nexa.sdk.bean.PluginIdValue
import com.secondbrain.app.data.EntityCategory
import com.secondbrain.app.data.EntityNode
import com.secondbrain.app.data.ExtractedKnowledge
import com.secondbrain.app.data.RelationEdge
import com.secondbrain.app.data.RelationType
import com.secondbrain.app.data.SubgraphContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

data class ModelInfo(
    val name: String,
    val filename: String,
    val repo: String,
    val expectedBytes: Long
) {
    val downloadUrl: String get() = "https://huggingface.co/$repo/resolve/main/$filename"
}

class LlmEngine(private val context: Context) {

    private val operationMutex = Mutex()
    private var llm: LlmWrapper? = null
    private var sdkInitialized = false

    val defaultModel = ModelInfo(
        name = "Qwen3.5-9B",
        filename = "Qwen3.5-9B-Q4_K_M.gguf",
        repo = "unsloth/Qwen3.5-9B-GGUF",
        expectedBytes = 5_680_522_464L
    )

    val modelDir: File
        get() = File(context.filesDir, "models/llm").apply { mkdirs() }

    val modelFile: File
        get() = File(modelDir, defaultModel.filename)

    fun isModelReady(): Boolean = modelFile.isFile && modelFile.length() == defaultModel.expectedBytes

    fun isEngineLoaded(): Boolean = llm != null

    private suspend fun ensureSdkInit(): Boolean {
        if (sdkInitialized) return true
        return suspendCancellableCoroutine { cont ->
            runCatching {
                NexaSdk.getInstance().init(
                    context,
                    object : NexaSdk.InitCallback {
                        override fun onSuccess() {
                            sdkInitialized = true
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onFailure(msg: String) {
                            Log.e(TAG, "Nexa init failed: $msg")
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                )
            }.onFailure { if (cont.isActive) cont.resume(false) }
        }
    }

    suspend fun loadModel(useGpu: Boolean = true): Result<Unit> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            runCatching {
                if (llm != null) return@runCatching Unit
                if (!isModelReady()) error("Model file not found at ${modelFile.absolutePath}")

                check(ensureSdkInit()) { "Failed to initialize Nexa SDK" }

                val dev = if (useGpu) DeviceIdValue.GPU else DeviceIdValue.CPU
                val input = LlmCreateInput(
                    "",
                    modelFile.absolutePath,
                    null,
                    ModelConfig(
                        nCtx = 4_096,
                        nGpuLayers = if (useGpu) 999 else 0,
                        enable_thinking = false,
                        verbose = true
                    ),
                    PluginIdValue.CPU_GPU.value,
                    dev.value
                )

                val instance = LlmWrapper.builder()
                    .llmCreateInput(input)
                    .build()
                    .getOrThrow()

                llm = instance
                Log.i(TAG, "${defaultModel.name} loaded on ${dev.name}")
                Unit
            }
        }
    }

    /**
     * Download helper with resumable .part support.
     */
    suspend fun downloadModel(onProgress: (Int) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (isModelReady()) return@runCatching Unit
            val target = modelFile
            val part = File(target.parentFile, target.name + ".part")
            val have = if (part.isFile) part.length() else 0L

            val conn = (URL(defaultModel.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }

            val responseCode = conn.responseCode
            check(responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_PARTIAL) {
                "Model download failed (HTTP $responseCode)"
            }
            // A server may ignore Range and return the complete file (200). In that case,
            // restart the partial download instead of appending a duplicate file body.
            val append = have > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL
            val startingBytes = if (append) have else 0L
            val total = conn.contentLengthLong.let {
                if (it > 0) it + startingBytes else defaultModel.expectedBytes
            }
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = startingBytes
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
            conn.disconnect()
            check(part.length() == defaultModel.expectedBytes) {
                "Model download has an unexpected size (${part.length()} of ${defaultModel.expectedBytes} bytes)"
            }
            check(part.renameTo(target)) { "Failed to rename part to target file" }
            Unit
        }
    }

    /**
     * Extracts ontology entities and relations from text using LLM or rule-based fallback.
     */
    suspend fun extractOntology(text: String): ExtractedKnowledge = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val engine = llm
            if (engine == null) {
                return@withLock ruleBasedExtraction(text)
            }

            val prompt = """
            You are a conservative Knowledge Graph extractor. Read the following note and extract only entities and relationships directly supported by its words.
            Entity categories: Concept, Project, Resource, Person, Decision, Insight
            Relation types: DEPENDS_ON, CONTRADICTS, EXTENDS, MENTIONS, SUPERSEDES, DERIVED_FROM, USES_CONCEPT, AUTHORED_BY, RELATED_TO

            Return ONLY valid JSON matching this format:
            {
              "entities": [{"name": "...", "category": "Concept|Project|...", "description": "...", "aliases": ["..."], "evidence": "exact quote from the note", "confidence": 0.0}],
              "relations": [{"source": "...", "relation": "DEPENDS_ON|...", "target": "...", "evidence": "exact quote from the note", "confidence": 0.0}]
            }

            Rules:
            - Evidence must be a short, exact substring copied from the note.
            - Confidence is a number from 0 to 1. Use 0.80 or above only when the evidence is explicit.
            - Do not infer facts that the note does not state.
            - Use a canonical, concise entity name. Put alternate spellings or abbreviations in aliases.
            - Relation endpoints must exactly match an entity name or alias in the entities array.

            Text:
            $text
        """.trimIndent()

            val raw = generateSingleTurn(prompt).getOrDefault("")
            parseExtractionJson(raw).ifEmptyFallback { ruleBasedExtraction(text) }
        }
    }

    private suspend fun generateSingleTurn(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val engine = llm ?: error("LLM not loaded")
            val msgs = arrayOf(
                ChatMessage("system", "You are a precise structured information extraction system."),
                ChatMessage("user", prompt)
            )
            val template = engine.applyChatTemplate(msgs, null, false).getOrThrow()
            val sb = StringBuilder()
            engine.generateStreamFlow(
                template.formattedText,
                GenerationConfig(maxTokens = 768)
            ).collect { res ->
                when (res) {
                    is LlmStreamResult.Token -> sb.append(res.text)
                    is LlmStreamResult.Error -> throw res.throwable
                    is LlmStreamResult.Completed -> Unit
                }
            }
            engine.reset()
            sb.toString()
        }
    }

    /**
     * Answers user queries grounded in retrieved subgraph context.
     */
    fun answerWithContext(query: String, context: SubgraphContext): Flow<String> = flow {
        operationMutex.withLock {
            val engine = llm
            if (engine == null) {
                emit("*(On-Device LLM is not loaded yet)*\n\n**Retrieved Graph Context:**\n")
                context.anchorEntities.forEach { emit("• **${it.name}** (${it.category.name}): ${it.description}\n") }
                context.connectedEdges.forEach { emit("  └─ [${it.relation.name}] → ${it.target}\n") }
                if (context.relatedNotes.isNotEmpty()) {
                    emit("\n**Related Notes:**\n")
                    context.relatedNotes.forEach { emit("• ${it.title}: ${it.content.take(150)}...\n") }
                }
                return@withLock
            }

            val contextStr = buildString {
                appendLine("=== KNOWLEDGE GRAPH ENTITIES ===")
                context.anchorEntities.forEach { appendLine("- ${it.name} [${it.category.name}]: ${it.description}") }
                appendLine("\n=== GRAPH RELATIONS ===")
                context.connectedEdges.forEach { appendLine("- (${it.source}) -[${it.relation.name}]-> (${it.target})") }
                appendLine("\n=== RELEVANT NOTES ===")
                context.relatedNotes.forEach { appendLine("Note '${it.title}': ${it.content}") }
            }

            val msgs = arrayOf(
                ChatMessage("system", "You are the user's personal Second Brain. Answer questions accurately based strictly on the provided Knowledge Graph context and notes. Cite connected entities and relations when relevant."),
                ChatMessage("user", "Context:\n$contextStr\n\nQuestion: $query")
            )

            val template = engine.applyChatTemplate(msgs, null, false).getOrThrow()
            engine.generateStreamFlow(
                template.formattedText,
                GenerationConfig(maxTokens = 1024)
            ).collect { res ->
                when (res) {
                    is LlmStreamResult.Token -> emit(res.text)
                    is LlmStreamResult.Error -> emit("\n[Error: ${res.throwable.message}]")
                    is LlmStreamResult.Completed -> Unit
                }
            }
            engine.reset()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseExtractionJson(raw: String): ExtractedKnowledge {
        return runCatching {
            val jsonStart = raw.indexOf('{')
            val jsonEnd = raw.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd <= jsonStart) return ExtractedKnowledge(emptyList(), emptyList())
            val obj = JSONObject(raw.substring(jsonStart, jsonEnd + 1))

            val entities = mutableListOf<EntityNode>()
            obj.optJSONArray("entities")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    entities.add(
                        EntityNode(
                            name = e.getString("name").trim(),
                            category = EntityCategory.fromString(e.optString("category", "Concept")),
                            description = e.optString("description", ""),
                            aliases = e.optJSONArray("aliases")?.toStringList().orEmpty(),
                            evidence = e.optString("evidence", ""),
                            confidence = e.optDouble("confidence", 0.5).coerceIn(0.0, 1.0)
                        )
                    )
                }
            }

            val relations = mutableListOf<RelationEdge>()
            obj.optJSONArray("relations")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    relations.add(
                        RelationEdge(
                            source = r.getString("source").trim(),
                            relation = RelationType.fromString(r.optString("relation", "RELATED_TO")),
                            target = r.getString("target").trim(),
                            evidence = r.optString("evidence", ""),
                            confidence = r.optDouble("confidence", 0.5).coerceIn(0.0, 1.0)
                        )
                    )
                }
            }

            ExtractedKnowledge(entities, relations)
        }.getOrDefault(ExtractedKnowledge(emptyList(), emptyList()))
    }

    private fun ExtractedKnowledge.ifEmptyFallback(fallback: () -> ExtractedKnowledge): ExtractedKnowledge {
        return if (entities.isEmpty() && relations.isEmpty()) fallback() else this
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) {
            optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    /**
     * Fast local rule-based entity and relation extractor when LLM is uninitialized.
     */
    fun ruleBasedExtraction(text: String): ExtractedKnowledge {
        val entities = mutableListOf<EntityNode>()
        val relations = mutableListOf<RelationEdge>()

        // Look for capitalized phrases (e.g., "Event Sourcing", "Project Apollo", "Snapdragon 8 Elite")
        val pattern = Regex("\\b[A-Z][a-zA-Z0-9]*(?:\\s+[A-Z][a-zA-Z0-9]*)*\\b")
        val matches = pattern.findAll(text)
            .map { it.value.trim() }
            .map { it.replace(Regex("^(For|In|With|About|From|On|At|To|By|As)\\s+", RegexOption.IGNORE_CASE), "").trim() }
            .filter { it.length > 2 && !STOP_WORDS.contains(it.lowercase()) }
            .distinct()
            .toList()

        for (m in matches) {
            val cat = when {
                m.startsWith("Project", ignoreCase = true) -> EntityCategory.PROJECT
                m.contains("Book", ignoreCase = true) || m.contains("Paper", ignoreCase = true) -> EntityCategory.RESOURCE
                m.contains("Decision", ignoreCase = true) -> EntityCategory.DECISION
                m.contains("Kleppmann", ignoreCase = true) || m.contains("Forte", ignoreCase = true) -> EntityCategory.PERSON
                else -> EntityCategory.CONCEPT
            }
            val evidence = text.split(Regex("(?<=[.!?])\\s+|\\n+"))
                .firstOrNull { it.contains(m, ignoreCase = true) }
                ?.trim()
                ?.take(220)
                ?: m
            entities.add(
                EntityNode(
                    name = m,
                    category = cat,
                    description = "Candidate extracted from note",
                    confidence = 0.55,
                    evidence = evidence
                )
            )
        }

        // Connect consecutive entities
        if (entities.size >= 2) {
            for (i in 0 until entities.size - 1) {
                relations.add(
                    RelationEdge(
                        source = entities[i].name,
                        relation = RelationType.RELATED_TO,
                        target = entities[i + 1].name,
                        confidence = 0.45,
                        evidence = listOf(entities[i].evidence, entities[i + 1].evidence)
                            .firstOrNull { it.contains(entities[i].name, true) && it.contains(entities[i + 1].name, true) }
                            .orEmpty()
                    )
                )
            }
        }

        return ExtractedKnowledge(entities, relations)
    }

    fun release() {
        runCatching { llm?.close() }
        llm = null
    }

    companion object {
        private const val TAG = "SecondBrainLlm"
        private val STOP_WORDS = setOf(
            "the", "this", "that", "there", "what", "where", "how", "why", "and", "but",
            "for", "with", "from", "about", "into", "over", "under", "after", "before"
        )
    }
}
