# Second Brain

An offline-first Android knowledge workspace that turns notes and shared text into a local knowledge graph. It combines vector similarity with graph traversal so answers can use both semantic relevance and explicit relationships.

> This is an early-stage project. The diagnostic probe is registered only in debug builds and is not exported by release builds.

## Highlights

- Start from a daily workspace with one-tap text or voice capture, automatic titles, an explainable daily plan, and relevance-ranked memories.
- Turn explicit TODOs, reminders, and unchecked checklist lines into local actions with evidence-backed due dates.
- Capture notes in the app or share plain text from another Android app.
- Record voice notes, preserve the original audio, play it back, and transcribe it fully offline.
- Save raw notes immediately, edit them later, and retain created/modified timestamps.
- Continue transcription and knowledge extraction through a durable background queue after restarts.
- Store notes, entities, aliases, evidence, and relationships locally in CozoDB.
- Keep uncertain entities, relationships, and possible duplicates out of the graph until you review them.
- Rerank HNSW results with lexical relevance, graph overlap, aliases, and time-aware scoring.
- Discover related notes, browse a chronological timeline, and inspect cited source passages.
- Explore the ontology with an interactive force-directed graph or a filtered list.
- Use a local Qwen GGUF model for extraction and grounded answers when one is installed.
- Create, rename, update, trash, restore, and act on notes from the Ask screen through validated local tools.
- Fall back to deterministic local embeddings and rule-based extraction when the model is unavailable.

## Architecture

```text
Text / share sheet ---------> durable note ----> persistent job queue ----> embeddings
                                  ^                  |                         |
Voice ----> durable WAV ----> offline transcript <---+                         v
                                                                       CozoDB HNSW index
                                                                              |
                                                extracted proposals <----------+
                                                  /             \
                                     verified actions       knowledge proposals
                                              |                   |
                                        Today / Tasks      evidence + confidence
                                                                  |
                                                              resolution
                                                             /          \
                                                       accepted      review inbox
                                                          |               |
                                                          +-> graph <-----+
                                                        |
Question -> query embedding -> similar notes -> graph expansion -> local LLM answer
```

Capture is intentionally durable-first. Original text or audio is written to private local storage before transcription, embeddings, or ontology extraction runs in the background. A slow, unavailable, or interrupted model therefore cannot prevent a memory from being saved.

## Product roadmap

- **Phase 1 — Capture foundation (complete):** instant text capture, optional titles, editing, timestamps, and background organization.
- **Phase 2 — Voice capture (complete):** durable recordings, playback, offline transcription, visible processing state, and retry.
- **Phase 3 — Processing queue (complete):** persistent, resumable AI jobs with visible status, retries, cancellation, and deduplication.
- **Phase 4 — Knowledge quality (complete):** aliases, duplicate resolution, evidence, confidence, and review workflows.
- **Phase 5 — Retrieval (complete):** hybrid-ranked search, related notes, timelines, and source-grounded answers.
- **Phase 6 — Ownership (complete):** encrypted export, safe merge restore, privacy controls, and production hardening.
- **Phase 7 — Daily brain (in progress):** Today workspace, zero-friction capture, automatic titles, explainable daily reviews, grounded on-device briefings, relevance-ranked memories, grouped actions, direct action editing, and private due reminders.
- **Phase 8 — Agent control:** safe note CRUD, recoverable Trash, cascade deletion, conversational tools, local audit records, provenance support, and correction-aware ontology extraction.
- **Phase 9 — Natural agent routing:** schema-validated Qwen tool selection, inventory-bound targets, ambiguity handling, prompt-injection boundaries, deterministic offline fallback, multi-step plans, rollback, and Undo.

## Requirements

- Android Studio with Android SDK Platform 35.
- JDK 17.
- An Android 9 (API 28) or newer `arm64-v8a` device or emulator.
- A modern arm64 Android device; the default 4B model is tuned for practical phone latency and memory use.
- A connected device and Android Platform Tools for installation and probe commands.
- Microphone permission when recording a voice note.

The app is packaged only for `arm64-v8a`, because its on-device dependencies include native libraries.

## Daily capture

The app opens on **Today**, not the database or graph. Type directly into Quick capture and save without choosing a title, category, or folder; the original note and timestamp are persisted immediately, and a concise title is derived locally from its first meaningful line. Ontology extraction and embeddings continue through the background queue.

The microphone button starts a voice note from the same card. Stopping preserves the original recording first, then queues offline transcription. When the transcript arrives, an untitled voice note receives a local title derived from the transcript. Today also surfaces current processing or review work and the day's recent memories.

## Intelligent daily review

Today builds an instant daily plan locally, without waiting for the language model. It prioritizes overdue, due-today, upcoming, and undated actions; tracks today's completed work; and resurfaces older notes using shared accepted entities, active-action provenance, recurring topics, age, and recency. Each resurfaced memory explains why it was selected and opens its original note.

When the Qwen model is loaded, **Create AI briefing** produces an optional concise summary from only the selected actions, accepted graph facts, and numbered source notes. The response streams on-device and exposes buttons back to its original notes. The deterministic review remains usable while the model is absent, loading, or busy.

## Actions and reminders

Explicit action language is extracted immediately when a text note is saved, without waiting for the large language model. It is stored locally with its source note, confidence, evidence, and optional due date. Supported deterministic forms include `TODO:`, `Task:`, `Reminder:`, `remind me to`, `need to`, and Markdown checkboxes such as `- [ ]`. Relative dates such as `today`, `tomorrow`, and `next Monday`, ISO dates, and named dates are resolved against the note's original capture date. Voice transcripts receive the same immediate pass as soon as transcription completes.

The model may propose additional actions in the background, but the app only saves proposals whose evidence is an exact substring of the note. It does not invent a due date when the quoted evidence has none. Open actions appear on Today and in **Tasks → Actions**, grouped into Overdue, Today, Upcoming, and No date; completed items have their own section. Actions can also be created directly, edited, completed, reopened, moved to tomorrow with one tap, or dismissed. User edits remain authoritative when a source note is reprocessed, and completed or dismissed work is not resurrected.

Actions with due dates use WorkManager to schedule an on-device reminder around 9:00 am in the device's local time. Android 13 and newer ask for notification permission only when a dated action is saved. Reminder contents use private lock-screen visibility, overdue migrations are not allowed to flood the notification tray, and delivery state stays local rather than being included in portable backups.

## Note CRUD, Trash, and agent tools

Notes support create, read/search, edit, move to Trash, restore, and permanent deletion. Trash is available from the note card menu, the editor, and the **Notes → Trash** view. Moving a note to Trash immediately hides its actions, processing work, search results, and graph contribution without destroying them. Restoring the note brings those records back and queues fresh organization.

Permanent deletion is only available from Trash and always requires confirmation. It removes the raw note, recording, vector, processing jobs, actions, reminders, review proposals, note-to-entity links, and provenance rows. Entities and relationships are removed only when the deleted note was their final supporting source.

The Ask screen uses the on-device Qwen model to distinguish knowledge questions from requested actions and to select one tool from a strict allowlist. Qwen sees only a bounded read-only inventory and cannot access CozoDB directly. Every returned tool name, argument, date, and target ID is validated; unknown or ambiguous targets produce a clarification instead of a mutation. Explicit command phrases still work as a deterministic fallback when the model is unavailable.

Supported actions include:

- `Create a note: ...` or `Remember that ...`
- `List my notes` and `Show my deleted notes`
- `Rename note Project plan to 2027 plan`
- `Update note Project plan to say ...`
- `Append to note Project plan: ...`
- `Delete note Project plan`
- `Restore note Project plan`
- `Add task Call Sam tomorrow`
- `Complete action Call Sam`

Natural phrasing also works when Qwen is loaded—for example, “Please get rid of yesterday’s meeting note” can resolve to a validated note ID. Requests containing two to six operations become one ordered plan. All writes are previewed together and require one confirmation; read-only lists can run immediately. A failed plan rolls back its completed steps, and the most recent successful plan can be undone from the Ask screen. Permanent deletion remains restricted to the Trash UI. Every executed step and plan outcome is recorded in the local audit relation.

## Automatic ontology maintenance

Ontology extraction is automatic background work, not a manual data-entry workflow. Evidence-backed entities and relationships at or above the acceptance threshold enter the graph automatically. Only uncertain claims, possible duplicates, or unsupported endpoints appear in Review.

Accepted duplicate decisions become canonical aliases used by future extraction. Rejected entity and relationship patterns are stored as local feedback rules so the same suggestion is not repeatedly presented. Reprocessing an edited note now removes its stale vector and graph provenance before writing the new result. Entity and relationship support is tracked per source note, allowing Trash to hide a source and permanent deletion to remove only facts that no remaining note supports.

## Build and run

```bash
git clone https://github.com/pramodthe/second_brain-local-.git
cd second_brain-local-

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.secondbrain.app/.ui.MainActivity
```

If Android Studio cannot find your SDK, create `local.properties` (which is ignored by Git):

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

## On-device models

### Language and knowledge extraction

The default model is [Qwen3.5-4B-GGUF](https://huggingface.co/unsloth/Qwen3.5-4B-GGUF), using the `Q4_K_M` quantization (2,740,937,888 bytes). It is the phone-first balance: substantially lower memory pressure and faster prompt processing than 9B, while retaining strong instruction-following and agent capability. The app downloads it into private app storage and runs it through Nexa SDK 0.0.24 with the OpenCL GPU backend. After the 4B model is installed, superseded private 0.6B and 9B model files are removed to recover storage. Model files are intentionally excluded from source control.

Performance and memory use vary by device and runtime. The 4B default replaces the earlier 9B configuration specifically to reduce prompt latency, startup time, thermal load, and peak memory use on the tested RMX5011/Snapdragon 8 Elite. Without the model, capture, graph storage, search, and rule-based extraction continue to work; chat presents retrieved graph context instead of generating an LLM response.

### Speech recognition

Voice notes use Nexa SDK 0.0.24's `whisper.cpp` backend with the multilingual Whisper Tiny model (`whisper-tiny.bin`, 77,691,730 bytes). The first transcription downloads the model from [unslothai/whisper-tiny-GGUF](https://huggingface.co/unslothai/whisper-tiny-GGUF); progress is shown in the app and partial downloads can resume. The recording is saved immediately and remains playable if the download or transcription fails.

The engine tries NPU, GPU, and CPU in that order. On the tested RMX5011/Snapdragon 8 Elite, Nexa loaded the model on the NPU and transcribed the synthetic device test correctly. Speech recognition does not upload recordings. Whisper Tiny is the Phase 2 baseline; the `SpeechEngine` boundary keeps a later Qualcomm Voice AI or larger-model upgrade isolated from the capture and note-storage layers.

## Reliable background processing

Every transcription and ontology-extraction request is first persisted in CozoDB. Deterministic action extraction runs before the heavy queue, so an explicit TODO is usable as soon as its note is safe. Android WorkManager then drains model work serially so only one heavy on-device inference task runs at a time. Stable job IDs deduplicate repeated requests, while a note edited during processing supersedes the older run and is processed again with its latest contents.

The **Tasks** screen separates background processing, extracted actions, and knowledge review. It shows queued, running, completed, failed, and cancelled work with progress and attempt counts. Failed or cancelled jobs can be retried; active jobs can be cancelled cooperatively. If Android terminates the process, any interrupted job returns to the queue when WorkManager or the app starts again.

## Knowledge quality and review

Ontology extraction is treated as a proposal, not ground truth. The local model must return a confidence score and a short exact quote from the source note for every entity and relationship. The resolver verifies that evidence against the saved note, caps unsupported claims below the automatic-accept threshold, and sends ambiguous output to **Tasks → Review**.

The review inbox supports three decisions:

- Accept or reject a proposed entity.
- Accept or reject a proposed relationship, with its source quote visible.
- Confirm a likely duplicate, which records the proposed spelling as an alias of the canonical entity instead of creating another graph node.

Accepted aliases participate in later entity resolution and Explore search. Entity cards expose confidence, aliases, provenance evidence, and relationship evidence. Existing databases migrate additively: old graph rows remain accepted with their original data, while new quality metadata is stored in separate CozoDB relations.

## Retrieval and grounded answers

Search and chat share one deterministic hybrid retrieval pipeline. It combines four signals:

- CozoDB HNSW cosine distance for semantic similarity.
- Exact phrases and token coverage across note titles and text.
- Accepted entity overlap and two-hop knowledge-graph expansion, including aliases.
- Recency, with stronger weighting for explicitly temporal questions such as “what did I learn recently?”

Results include a relevance score, the matching passage, and human-readable reasons such as `exact phrase`, `shared entities`, or `semantic match`. The connection button on any note opens related memories without returning the source note itself. The Notes screen can also group the library into a chronological timeline.

Chat receives only accepted graph facts and numbered note sources. The system prompt requires square-bracket citations such as `[1]`, refuses unsupported answers, and never sends notes off-device. Expanding **Sources used** beneath an answer shows the exact passages and ranking evidence behind those citations. When the LLM is not loaded, the app still returns the ranked local passages instead of presenting an ungrounded answer.

## Encrypted backup and restore

The **Own** screen creates a portable `.sbrain` archive containing notes, actions, accepted graph data, aliases, evidence, review history, and—optionally—voice recordings. Model files are deliberately excluded. Before the archive leaves app-private storage, it is encrypted with AES-256-GCM using a key derived from the user’s passphrase with PBKDF2-HMAC-SHA256 and a unique random salt. The passphrase is never persisted and cannot be recovered by the app.

Restore decrypts and validates the authenticated archive, rejects unsafe paths and oversized entries, regenerates local search embeddings, and merges records without deleting the existing library. Notes already present with the same or a newer modification time remain untouched. Restore archives are processed through temporary app-private storage and removed after completion.

Android cloud backup is disabled so the raw database and recordings are not copied to a cloud account behind the user’s back. Cleartext network traffic is also blocked; HTTPS remains available only for optional model downloads. The live database is protected by Android’s app sandbox but is not currently encrypted at rest, so device-level encryption and a secure screen lock are still recommended.

## Verify on a device

After installing a debug build, run the diagnostic receiver:

```bash
adb shell am broadcast -a com.secondbrain.app.PROBE -p com.secondbrain.app
adb logcat -d -s BrainProbe:V
```

The probe opens the local database, creates embeddings, ingests sample notes, runs vector search, and performs hybrid retrieval.

To exercise only speech recognition, place a 16 kHz mono PCM WAV at `files/probe/speech-test.wav` in the app's private storage and run:

```bash
adb shell am broadcast \
  -a com.secondbrain.app.PROBE \
  -n com.secondbrain.app/.probe.BrainProbeReceiver \
  --ez speech_only true
adb logcat -d -s BrainProbe:V SpeechEngine:V
```

To enqueue an organization job for an existing diagnostic note and verify the persistent worker:

```bash
adb shell am broadcast \
  -a com.secondbrain.app.PROBE \
  -n com.secondbrain.app/.probe.BrainProbeReceiver \
  --ez queue_only true
adb logcat -d -s BrainProbe:V BrainProcessing:V WM-WorkerWrapper:V
```

To validate hybrid ranking, source numbering, related-note exclusion, and chronological ordering without writing new notes:

```bash
adb shell am broadcast \
  -a com.secondbrain.app.PROBE \
  -n com.secondbrain.app/.probe.BrainProbeReceiver \
  --ez retrieval_only true
adb logcat -d -s BrainProbe:V
```

To verify action insert, query, status updates, and cleanup without leaving a note or action behind:

```bash
adb shell am broadcast \
  -a com.secondbrain.app.PROBE \
  -n com.secondbrain.app/.probe.BrainProbeReceiver \
  --ez actions_only true
adb logcat -d -s BrainProbe:V
```

To verify create/read/trash/restore, hidden graph contribution, and permanent cascade cleanup:

```bash
adb shell am broadcast \
  -a com.secondbrain.app.PROBE \
  -n com.secondbrain.app/.probe.BrainProbeReceiver \
  --ez crud_only true
adb logcat -d -s BrainProbe:V
```

To load Qwen on the GPU and verify that a natural request becomes a validated tool call without changing stored data:

```bash
adb shell am broadcast \
  -a com.secondbrain.app.PROBE \
  -n com.secondbrain.app/.probe.BrainProbeReceiver \
  --ez agent_router_only true
adb shell run-as com.secondbrain.app cat files/probe/agent-router-result.txt
```

To verify the WorkManager reminder path on Android 13 or newer, first grant notification permission through the in-app prompt (save any dated action). On devices that allow the ADB shell to grant runtime permissions, you can use the first command below. Then run the self-cleaning reminder probe:

```bash
adb shell pm grant com.secondbrain.app android.permission.POST_NOTIFICATIONS
adb shell am broadcast \
  -a com.secondbrain.app.PROBE \
  -n com.secondbrain.app/.probe.BrainProbeReceiver \
  --ez reminders_only true
adb logcat -d -s BrainProbe:V
```

## Project layout

```text
app/src/main/java/com/secondbrain/app/
├── ai/       # Local embedding, LLM, recording, and speech integrations
├── data/     # CozoDB store and ontology data types
├── domain/   # Ingestion and hybrid retrieval workflows
├── probe/    # ADB diagnostic receiver
├── ui/       # Jetpack Compose screens and state holder
└── work/     # Persistent WorkManager queue runner
```

## Privacy and security notes

Knowledge and original voice recordings are stored in the app’s private Android storage. Optional model downloads contact Hugging Face only to fetch model files; once installed, inference runs on-device. Android cloud backup and cleartext traffic are disabled. The diagnostic receiver exists only in debug builds and is not packaged into release builds. Review third-party dependency licenses before distribution.

## Contributing

Keep generated APKs, AARs, model files, local SDK settings, and secrets out of commits. Before opening a pull request, run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

## License

No license has been selected yet. Until one is added, the repository is not licensed for reuse or redistribution.
