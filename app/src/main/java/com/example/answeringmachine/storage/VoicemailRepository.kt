package com.example.answeringmachine.storage

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

class VoicemailRepository private constructor(private val appContext: Context) {

    private val voicemailsDir: File = File(appContext.filesDir, "voicemails").apply { mkdirs() }
    private val greetingsDir: File = File(appContext.filesDir, "greetings").apply { mkdirs() }
    private val indexFile: File = File(voicemailsDir, "voicemails_index.json")

    private val lock = Any()

    private val _voicemails = MutableStateFlow<List<VoicemailItem>>(emptyList())
    val voicemails: StateFlow<List<VoicemailItem>> = _voicemails.asStateFlow()

    private val _hasCustomGreeting = MutableStateFlow(hasValidCustomGreeting())
    val hasCustomGreeting: StateFlow<Boolean> = _hasCustomGreeting.asStateFlow()

    init {
        refreshSync()
    }

    fun getCustomGreetingFile(): File {
        val mp3File = File(greetingsDir, "custom_greeting.mp3")
        if (mp3File.exists() && mp3File.length() > 0L) return mp3File
        val legacyM4a = File(greetingsDir, "custom_greeting.m4a")
        return if (legacyM4a.exists() && legacyM4a.length() > 0L) legacyM4a else mp3File
    }

    fun getCustomGreetingWriteTarget(): File = File(greetingsDir, "custom_greeting.mp3")

    private fun hasValidCustomGreeting(): Boolean {
        val file = getCustomGreetingFile()
        return file.exists() && file.length() > 0L
    }

    fun refreshCustomGreetingStatus() {
        _hasCustomGreeting.value = hasValidCustomGreeting()
    }

    fun deleteCustomGreeting(): Boolean {
        val mp3Deleted = File(greetingsDir, "custom_greeting.mp3").let { !it.exists() || it.delete() }
        val legacyDeleted = File(greetingsDir, "custom_greeting.m4a").let { !it.exists() || it.delete() }
        refreshCustomGreetingStatus()
        return mp3Deleted && legacyDeleted
    }

    suspend fun importCustomGreetingFromUri(sourceUri: Uri): Boolean = withContext(Dispatchers.IO) {
        greetingsDir.mkdirs()
        val targetFile = getCustomGreetingWriteTarget()
        val tempFile = File(greetingsDir, "import_temp.mp3")
        try {
            appContext.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext false

            if (!tempFile.exists() || tempFile.length() <= 0L) {
                tempFile.delete()
                return@withContext false
            }

            // Validate playable audio before replacing active custom greeting
            val duration = readAudioDuration(tempFile)
            if (duration <= 0L) {
                tempFile.delete()
                return@withContext false
            }

            File(greetingsDir, "custom_greeting.m4a").delete()
            if (targetFile.exists()) targetFile.delete()
            val moved = tempFile.renameTo(targetFile)
            if (!moved) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            refreshCustomGreetingStatus()
            true
        } catch (_: Exception) {
            tempFile.delete()
            false
        }
    }

    fun createRecordingTargetFile(timestampMillis: Long = System.currentTimeMillis()): File {
        voicemailsDir.mkdirs()
        return File(voicemailsDir, "vm_${timestampMillis}_${UUID.randomUUID().toString().take(6)}.mp3")
    }

    suspend fun registerCompletedRecording(
        file: File,
        callerNumber: String,
        callerName: String?,
        timestampMillis: Long,
        durationMillis: Long
    ): VoicemailItem? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0L) {
            file.delete()
            return@withContext null
        }
        val measuredDuration = if (durationMillis > 0L) durationMillis else readAudioDuration(file)
        val item = VoicemailItem(
            id = UUID.randomUUID().toString(),
            callerNumber = callerNumber.ifBlank { "Unknown Caller" },
            callerName = callerName?.takeIf { it.isNotBlank() },
            timestampMillis = timestampMillis,
            durationMillis = measuredDuration,
            filePath = file.absolutePath,
            isHeard = false
        )
        synchronized(lock) {
            val current = readIndexInternal().toMutableList()
            current.add(0, item)
            writeIndexInternal(current)
            _voicemails.value = current.sortedByDescending { it.timestampMillis }
        }
        item
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        refreshSync()
    }

    private fun refreshSync() {
        synchronized(lock) {
            val indexed = readIndexInternal().filter { File(it.filePath).exists() }.toMutableList()
            val knownPaths = indexed.map { it.filePath }.toSet()

            val audioFiles = voicemailsDir.listFiles { f ->
                f.isFile && (
                    f.extension.equals("mp3", ignoreCase = true) ||
                        f.extension.equals("m4a", ignoreCase = true) ||
                        f.extension.equals("3gp", ignoreCase = true)
                    )
            }?.sortedByDescending { it.lastModified() }.orEmpty()

            var changed = false
            for (file in audioFiles) {
                if (file.absolutePath !in knownPaths && file.length() > 0L) {
                    indexed.add(
                        VoicemailItem(
                            id = UUID.randomUUID().toString(),
                            callerNumber = "Unknown Caller",
                            callerName = null,
                            timestampMillis = file.lastModified(),
                            durationMillis = readAudioDuration(file),
                            filePath = file.absolutePath,
                            isHeard = false
                        )
                    )
                    changed = true
                }
            }

            val sorted = indexed.sortedByDescending { it.timestampMillis }
            if (changed || sorted.size != readIndexInternal().size) {
                writeIndexInternal(sorted)
            }
            _voicemails.value = sorted
        }
        refreshCustomGreetingStatus()
    }

    suspend fun markAsHeard(id: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val updated = readIndexInternal().map {
                if (it.id == id) it.copy(isHeard = true) else it
            }
            writeIndexInternal(updated)
            _voicemails.value = updated.sortedByDescending { it.timestampMillis }
        }
    }

    suspend fun deleteVoicemail(id: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = readIndexInternal()
            val target = current.find { it.id == id }
            if (target != null) {
                File(target.filePath).delete()
            }
            val updated = current.filterNot { it.id == id }
            writeIndexInternal(updated)
            _voicemails.value = updated.sortedByDescending { it.timestampMillis }
        }
    }

    fun getShareableUri(item: VoicemailItem): Uri? {
        val file = File(item.filePath)
        if (!file.exists()) return null
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file
        )
    }

    private fun readAudioDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            FileInputStream(file).use { fis ->
                retriever.setDataSource(fis.fd)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readIndexInternal(): List<VoicemailItem> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val raw = indexFile.readText()
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        VoicemailItem(
                            id = obj.getString("id"),
                            callerNumber = obj.optString("callerNumber", "Unknown Caller"),
                            callerName = obj.optString("callerName", "").takeIf { it.isNotBlank() },
                            timestampMillis = obj.optLong("timestampMillis", 0L),
                            durationMillis = obj.optLong("durationMillis", 0L),
                            filePath = obj.getString("filePath"),
                            isHeard = obj.optBoolean("isHeard", false)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeIndexInternal(items: List<VoicemailItem>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("callerNumber", item.callerNumber)
                put("callerName", item.callerName ?: "")
                put("timestampMillis", item.timestampMillis)
                put("durationMillis", item.durationMillis)
                put("filePath", item.filePath)
                put("isHeard", item.isHeard)
            }
            array.put(obj)
        }
        indexFile.writeText(array.toString())
    }

    companion object {
        @Volatile
        private var instance: VoicemailRepository? = null

        fun getInstance(context: Context): VoicemailRepository {
            return instance ?: synchronized(this) {
                instance ?: VoicemailRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
