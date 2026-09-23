# Second Brain

An offline-first Android knowledge workspace that turns notes and shared text into a local knowledge graph. It combines vector similarity with graph traversal so answers can use both semantic relevance and explicit relationships.

> This is an early-stage project. The bundled diagnostic probe is intended for development verification; do not expose it in production builds without an appropriate permission or access control.

## Highlights

- Capture notes in the app or share plain text from another Android app.
- Store notes, entities, and relationships locally in CozoDB.
- Retrieve relevant notes with HNSW vector search, then expand related graph context.
- Explore the ontology with an interactive force-directed graph or a filtered list.
- Use a local Qwen GGUF model for extraction and grounded answers when one is installed.
- Fall back to deterministic local embeddings and rule-based extraction when the model is unavailable.

## Architecture

```text
Capture / Android share sheet
           |
           v
Ingestion pipeline ----> embeddings ----> CozoDB HNSW index
           |                                  |
           +----> entities and relations ----> knowledge graph
                                                  |
Question -> query embedding -> similar notes -> graph expansion -> local LLM answer
```

## Requirements

- Android Studio with Android SDK Platform 35.
- JDK 17.
- An Android 9 (API 28) or newer `arm64-v8a` device or emulator.
- A high-memory Android device for the default 9B model; the tested device has 16 GB RAM.
- A connected device and Android Platform Tools for installation and probe commands.

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

## On-device model

The default model is [Qwen3.5-9B-GGUF](https://huggingface.co/unsloth/Qwen3.5-9B-GGUF), using the `Q4_K_M` quantization (5,680,522,464 bytes). The app downloads it into private app storage and runs it through Nexa SDK 0.0.24 with the OpenCL GPU backend. Model files are intentionally excluded from source control.

This is a demanding mobile configuration. On the tested RMX5011/Snapdragon 8 Elite device with 16 GB RAM, the model loaded in roughly 23 seconds and Nexa reported about 10.2 prompt tokens/second and 2.7 generated tokens/second. Performance and memory use vary by device and runtime. Without the model, capture, graph storage, search, and rule-based extraction continue to work; chat presents retrieved graph context instead of generating an LLM response.

## Verify on a device

After installing a debug build, run the diagnostic receiver:

```bash
adb shell am broadcast -a com.secondbrain.app.PROBE -p com.secondbrain.app
adb logcat -d -s BrainProbe:V
```

The probe opens the local database, creates embeddings, ingests sample notes, runs vector search, and performs hybrid retrieval.

## Project layout

```text
app/src/main/java/com/secondbrain/app/
├── ai/       # Local embedding and LLM integrations
├── data/     # CozoDB store and ontology data types
├── domain/   # Ingestion and hybrid retrieval workflows
├── probe/    # ADB diagnostic receiver
└── ui/       # Jetpack Compose screens and state holder
```

## Privacy and security notes

Knowledge is stored in the app’s private Android storage. The optional model download contacts Hugging Face only to fetch the model file; once installed, inference runs on-device. Review third-party dependency licenses and secure or remove the diagnostic receiver before distributing a production APK.

## Contributing

Keep generated APKs, AARs, model files, local SDK settings, and secrets out of commits. Before opening a pull request, run:

```bash
./gradlew assembleDebug
```

## License

No license has been selected yet. Until one is added, the repository is not licensed for reuse or redistribution.
