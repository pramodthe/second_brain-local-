package com.secondbrain.app.backup

import android.content.Context
import android.net.Uri
import com.secondbrain.app.ai.EmbedderEngine
import com.secondbrain.app.data.BackupNote
import com.secondbrain.app.data.BackupPayload
import com.secondbrain.app.data.BackupReport
import com.secondbrain.app.data.BrainStore
import com.secondbrain.app.data.RestoreReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupManager(
    private val context: Context,
    private val store: BrainStore,
    private val embedder: EmbedderEngine
) {

    suspend fun export(uri: Uri, passphrase: CharArray, includeRecordings: Boolean): BackupReport =
        withContext(Dispatchers.IO) {
            try {
                val notes = store.getAllNotes().getOrThrow()
                val entities = store.getAllEntities().getOrThrow()
                val edges = store.getAllEdges().getOrThrow()
                val noteEntities = store.getNoteEntityNames().getOrThrow()
                val reviews = store.getAllKnowledgeReviews().getOrThrow()
                val actions = store.getActionItems(status = null, limit = 100_000).getOrThrow()
                val audioByNoteId = if (includeRecordings) {
                    notes.mapNotNull { note ->
                        note.audioPath?.let(::File)?.takeIf { it.isFile }?.let { note.id to it }
                    }.toMap()
                } else {
                    emptyMap()
                }
                var totalAudioBytes = 0L
                audioByNoteId.values.forEach { audio ->
                    require(audio.length() <= MAX_AUDIO_BYTES) { "A recording is too large to back up" }
                    totalAudioBytes += audio.length()
                    require(totalAudioBytes <= MAX_TOTAL_AUDIO_BYTES) {
                        "Recordings exceed the backup size limit"
                    }
                }
                val payload = BackupPayload(
                    notes = notes.map { note ->
                        BackupNote(note, audioByNoteId[note.id]?.let { audioEntryFor(note.id) })
                    },
                    entities = entities,
                    edges = edges,
                    noteEntityNames = noteEntities,
                    reviews = reviews,
                    actions = actions
                )
                val manifest = BackupJson.encode(payload).toByteArray(Charsets.UTF_8)
                require(manifest.size <= MAX_MANIFEST_BYTES) { "Backup manifest is too large" }
                val output = context.contentResolver.openOutputStream(uri, "w")
                    ?: error("Could not open the selected backup file")
                output.use { stream ->
                    BackupCrypto.encrypt(stream, passphrase) { encryptedStream ->
                        ZipOutputStream(encryptedStream).use { zip ->
                            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                            zip.write(manifest)
                            zip.closeEntry()
                            payload.notes.forEach { item ->
                                val entryName = item.audioEntry ?: return@forEach
                                val source = audioByNoteId[item.note.id] ?: return@forEach
                                zip.putNextEntry(ZipEntry(entryName))
                                source.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                }
                BackupReport(notes.size, entities.size, edges.size, audioByNoteId.size, actions.size)
            } finally {
                passphrase.fill('\u0000')
            }
        }

    suspend fun restore(uri: Uri, passphrase: CharArray): RestoreReport = withContext(Dispatchers.IO) {
        val decryptedZip = File(context.cacheDir, "restore-${UUID.randomUUID()}.zip")
        val createdAudio = mutableListOf<File>()
        val persistedAudio = mutableSetOf<File>()
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Could not open the selected backup")
            input.use { encrypted ->
                FileOutputStream(decryptedZip).use { plain ->
                    BackupCrypto.decrypt(encrypted, passphrase) {
                        it.copyLimitedTo(plain, MAX_DECRYPTED_BYTES)
                    }
                }
            }
            passphrase.fill('\u0000')
            require(decryptedZip.length() <= MAX_DECRYPTED_BYTES) { "Backup is too large" }

            ZipFile(decryptedZip).use { zip ->
                require(zip.size() <= MAX_ENTRIES) { "Backup contains too many files" }
                val manifestEntry = zip.getEntry(MANIFEST_ENTRY) ?: error("Backup manifest is missing")
                require(manifestEntry.size in 1..MAX_MANIFEST_BYTES) { "Backup manifest is invalid" }
                val manifest = zip.getInputStream(manifestEntry).use { it.readLimited(MAX_MANIFEST_BYTES) }
                val payload = BackupJson.decode(manifest.toString(Charsets.UTF_8))
                require(payload.notes.size <= MAX_NOTES) { "Backup contains too many notes" }
                require(payload.actions.size <= MAX_ACTIONS) { "Backup contains too many actions" }
                val audioEntries = payload.notes.mapNotNull(BackupNote::audioEntry)
                require(audioEntries.distinct().size == audioEntries.size) {
                    "Backup contains duplicate recording references"
                }
                var totalAudioBytes = 0L
                audioEntries.forEach { entryName ->
                    require(isSafeAudioEntry(entryName)) { "Backup contains an unsafe audio path" }
                    val entry = zip.getEntry(entryName) ?: error("A referenced recording is missing")
                    require(entry.size in 0..MAX_AUDIO_BYTES) { "A recording in the backup is too large" }
                    totalAudioBytes += entry.size
                    require(totalAudioBytes <= MAX_TOTAL_AUDIO_BYTES) {
                        "Recordings exceed the restore size limit"
                    }
                }

                var imported = 0
                var skipped = 0
                var recordingCount = 0
                val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
                payload.notes.forEach { backupNote ->
                    val local = store.getNote(backupNote.note.id, includeTrashed = true).getOrThrow()
                    if (local != null && local.modifiedTimestamp >= backupNote.note.modifiedTimestamp) {
                        skipped++
                        return@forEach
                    }
                    val restoredAudio = backupNote.audioEntry?.let { entryName ->
                        val entry = zip.getEntry(entryName)!!
                        val target = File(
                            recordingsDir,
                            "restored-${safeId(backupNote.note.id)}-${UUID.randomUUID()}.wav"
                        )
                        zip.getInputStream(entry).use { source ->
                            FileOutputStream(target).use { destination ->
                                source.copyLimitedTo(destination, MAX_AUDIO_BYTES)
                            }
                        }
                        createdAudio += target
                        recordingCount++
                        target.absolutePath
                    } ?: local?.audioPath
                    val restoredNote = backupNote.note.copy(audioPath = restoredAudio)
                    val embeddingText = listOf(restoredNote.title, restoredNote.content)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                    val embedding = embedder.embed(embeddingText)
                    store.putNote(restoredNote, embedding = embedding).getOrThrow()
                    restoredAudio?.let(::File)?.takeIf(createdAudio::contains)?.let(persistedAudio::add)
                    imported++
                }
                store.restoreGraph(payload.entities, payload.edges, payload.noteEntityNames).getOrThrow()
                store.putKnowledgeReviews(payload.reviews).getOrThrow()
                store.restoreActionItems(payload.actions).getOrThrow()
                RestoreReport(
                    importedNotes = imported,
                    skippedNewerNotes = skipped,
                    entities = payload.entities.size,
                    edges = payload.edges.size,
                    recordings = recordingCount,
                    actions = payload.actions.size
                )
            }
        } catch (error: Throwable) {
            createdAudio.filterNot(persistedAudio::contains).forEach { runCatching { it.delete() } }
            val friendly = when {
                error.message?.contains("tag", ignoreCase = true) == true ||
                    error.message?.contains("mac", ignoreCase = true) == true ->
                    "The passphrase is incorrect or the backup was damaged"
                else -> error.message ?: "The backup could not be restored"
            }
            throw IllegalArgumentException(friendly, error)
        } finally {
            passphrase.fill('\u0000')
            runCatching { decryptedZip.delete() }
        }
    }

    private fun InputStream.readLimited(maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        copyLimitedTo(output, maxBytes)
        return output.toByteArray()
    }

    private fun InputStream.copyLimitedTo(output: java.io.OutputStream, maxBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Backup entry exceeds its size limit" }
            output.write(buffer, 0, count)
        }
    }

    private fun safeId(value: String): String = value.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120)
    private fun audioEntryFor(noteId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(noteId.toByteArray(Charsets.UTF_8))
        return "audio/${digest.joinToString("") { "%02x".format(it) }}.wav"
    }
    private fun isSafeAudioEntry(value: String): Boolean =
        value.matches(Regex("audio/[a-zA-Z0-9._-]{1,120}\\.wav")) && ".." !in value

    companion object {
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val MAX_ENTRIES = 10_000
        private const val MAX_NOTES = 100_000
        private const val MAX_ACTIONS = 100_000
        private const val MAX_MANIFEST_BYTES = 20L * 1024 * 1024
        private const val MAX_AUDIO_BYTES = 1024L * 1024 * 1024
        private const val MAX_TOTAL_AUDIO_BYTES = 3L * 1024 * 1024 * 1024
        private const val MAX_DECRYPTED_BYTES = 4L * 1024 * 1024 * 1024
    }
}
