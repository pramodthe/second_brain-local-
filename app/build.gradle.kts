plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.secondbrain.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.secondbrain.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf(
                "**/libonnxruntime.so",
                "**/libonnxruntime4j_jni.so",
                "**/libQnn*.so",
                "**/libcalculator.so",
                "**/libCalculator_skel.so",
                "**/libhta_hexagon_runtime_qnn.so",
                "**/libhta_hexagon_runtime_snpe.so",
                "**/libPlatformValidatorShared.so"
            )
        }
        resources { excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES") }
    }
}

val nexaCoreVersion = "0.0.24"
val patchedNexaAar = layout.buildDirectory.file("patched-nexa/core-$nexaCoreVersion-ttsfix.aar")

val patchNexaAar by tasks.registering {
    val outFile = patchedNexaAar
    outputs.file(outFile)
    doLast {
        val conf = configurations.detachedConfiguration(
            dependencies.create("ai.nexa:core:$nexaCoreVersion")
        )
        conf.isTransitive = false
        val srcAar = conf.singleFile
        val work = layout.buildDirectory.dir("patched-nexa/work").get().asFile
        work.deleteRecursively()
        work.mkdirs()
        copy { from(zipTree(srcAar)); into(work) }
        val classesJar = work.resolve("classes.jar")
        val jarWork = work.resolve("classes-work")
        jarWork.mkdirs()
        copy { from(zipTree(classesJar)); into(jarWork) }
        jarWork.resolve("com/nexa/sdk/bean/TtsConfig.class").delete()
        listOf("libonnxruntime.so", "libonnxruntime4j_jni.so").forEach { lib ->
            work.resolve("jni/arm64-v8a/$lib").delete()
        }
        classesJar.delete()
        ant.withGroovyBuilder {
            "zip"("destfile" to classesJar.absolutePath, "basedir" to jarWork.absolutePath)
        }
        jarWork.deleteRecursively()
        val out = outFile.get().asFile
        out.parentFile.mkdirs()
        if (out.exists()) out.delete()
        ant.withGroovyBuilder {
            "zip"("destfile" to out.absolutePath, "basedir" to work.absolutePath)
        }
    }
}

val localAar = file("libs/core-0.0.24-ttsfix.aar")

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")

    // CozoDB: embedded Datalog + graph + HNSW vector index
    implementation("io.github.cozodb:cozo_android:0.7.5") {
        exclude(group = "junit")
    }
    implementation("org.sharegov:mjson:1.4.1") { exclude(group = "junit") }

    // Nexa Core (on-device LLM + Embedding runtime)
    if (localAar.exists()) {
        implementation(files(localAar))
    } else {
        implementation(files(patchedNexaAar).builtBy(patchNexaAar))
    }
}

if (!localAar.exists()) {
    tasks.named("preBuild").configure { dependsOn(patchNexaAar) }
}
