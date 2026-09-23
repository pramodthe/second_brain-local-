# Second Brain

An offline-first Android knowledge workspace that turns notes and shared text into a local knowledge graph. It combines vector similarity with graph traversal so answers can use both semantic relevance and explicit relationships.

> This is an early-stage project. The diagnostic probe is registered only in debug builds and is not exported by release builds.

## Highlights

- Capture notes in the app or share plain text from another Android app.
- Record voice notes, preserve the original audio, play it back, and transcribe it fully offline.
- Save raw notes immediately, edit them later, and retain created/modified timestamps.
- Continue transcription and knowledge extraction through a durable background queue after restarts.
- Store notes, entities, and relationships locally in CozoDB.
- Retrieve relevant notes with HNSW vector search, then expand related graph context.
- Explore the ontology with an interactive force-directed graph or a filtered list.
- Use a local Qwen GGUF model for extraction and grounded answers when one is installed.
- Fall back to deterministic local embeddings and rule-based extraction when the model is unavailable.

## Architecture

```text
Text / share sheet ---------> durable note ----> persistent job queue ----> embeddings
                                  ^                  |                         |
Voice ----> durable WAV ----> offline transcript <---+                         v
                                                                       CozoDB HNSW index
                                                                              |
                                                entities + relationships <-----+
                                                                         |
                                                                         v
                                                                  knowledge graph
                                                                         |
Question -> query embedding -> similar notes -> graph expansion -> local LLM answer
```

Capture is intentionally durable-first. Original text or audio is written to private local storage before transcription, embeddings, or ontology extraction runs in the background. A slow, unavailable, or interrupted model therefore cannot prevent a memory from being saved.

## Product roadmap

- **Phase 1 — Capture foundation (complete):** instant text capture, optional titles, editing, timestamps, and background organization.
- **Phase 2 — Voice capture (complete):** durable recordings, playback, offline transcription, visible processing state, and retry.
- **Phase 3 — Processing queue (complete):** persistent, resumable AI jobs with visible status, retries, cancellation, and deduplication.
- **Phase 4 — Knowledge quality:** aliases, duplicate resolution, evidence, confidence, and review workflows.
- **Phase 5 — Retrieval:** stronger search, related notes, timelines, and source-grounded answers.
- **Phase 6 — Ownership:** encrypted export, backup, restore, and production hardening.

## Requirements

- Android Studio with Android SDK Platform 35.
- JDK 17.
- An Android 9 (API 28) or newer `arm64-v8a` device or emulator.
- A high-memory Android device for the default 9B model; the tested device has 16 GB RAM.
- A connected device and Android Platform Tools for installation and probe commands.
- Microphone permission when recording a voice note.

The app is packaged only for `arm64-v8a`, because its on-device dependencies include native libraries.

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

The default model is [Qwen3.5-9B-GGUF](https://huggingface.co/unsloth/Qwen3.5-9B-GGUF), using the `Q4_K_M` quantization (5,680,522,464 bytes). The app downloads it into private app storage and runs it through Nexa SDK 0.0.24 with the OpenCL GPU backend. Model files are intentionally excluded from source control.

This is a demanding mobile configuration. On the tested RMX5011/Snapdragon 8 Elite device with 16 GB RAM, the model loaded in roughly 23 seconds and Nexa reported about 10.2 prompt tokens/second and 2.7 generated tokens/second. Performance and memory use vary by device and runtime. Without the model, capture, graph storage, search, and rule-based extraction continue to work; chat presents retrieved graph context instead of generating an LLM response.

### Speech recognition

Voice notes use Nexa SDK 0.0.24's `whisper.cpp` backend with the multilingual Whisper Tiny model (`whisper-tiny.bin`, 77,691,730 bytes). The first transcription downloads the model from [unslothai/whisper-tiny-GGUF](https://huggingface.co/unslothai/whisper-tiny-GGUF); progress is shown in the app and partial downloads can resume. The recording is saved immediately and remains playable if the download or transcription fails.

The engine tries NPU, GPU, and CPU in that order. On the tested RMX5011/Snapdragon 8 Elite, Nexa loaded the model on the NPU and transcribed the synthetic device test correctly. Speech recognition does not upload recordings. Whisper Tiny is the Phase 2 baseline; the `SpeechEngine` boundary keeps a later Qualcomm Voice AI or larger-model upgrade isolated from the capture and note-storage layers.

## Reliable background processing

Every transcription and ontology-extraction request is first persisted in CozoDB. Android WorkManager then drains this queue serially so only one heavy on-device inference task runs at a time. Stable job IDs deduplicate repeated requests, while a note edited during processing supersedes the older run and is processed again with its latest contents.

The **Tasks** screen shows queued, running, completed, failed, and cancelled work with progress and attempt counts. Failed or cancelled jobs can be retried; active jobs can be cancelled cooperatively. If Android terminates the process, any interrupted job returns to the queue when WorkManager or the app starts again.

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

Knowledge and original voice recordings are stored in the app’s private Android storage. Optional model downloads contact Hugging Face only to fetch model files; once installed, inference runs on-device. Android backups may include app-private data while `allowBackup` is enabled. Review third-party dependency licenses and secure or remove the diagnostic receiver before distributing a production APK.

## Contributing

Keep generated APKs, AARs, model files, local SDK settings, and secrets out of commits. Before opening a pull request, run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

## License

No license has been selected yet. Until one is added, the repository is not licensed for reuse or redistribution.
