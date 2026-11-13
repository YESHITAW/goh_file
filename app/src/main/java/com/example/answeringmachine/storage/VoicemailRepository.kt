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
import java.util.UUID

class VoicemailRepository private constructor(private val appContext: Context) {

    private val voicemailsDir: File = File(appContext.filesDir, "voicemails").apply { mkdirs() }
    private val greetingsDir: File = File(appContext.filesDir, "greetings").apply { mkdirs() }
    private val indexFile: File = File(voicemailsDir, "voicemails_index.json")

    private val lock = Any()

    private val _voicemails = MutableStateFlow<List<VoicemailItem>>(emptyList())
    val voicemails: StateFlow<List<VoicemailItem>> = _voicemails.asStateFlow()

    private val _hasCustomGreeting = MutableStateFlow(getCustomGreetingFile().exists())
    val hasCustomGreeting: StateFlow<Boolean> = _hasCustomGreeting.asStateFlow()

    init {
        refreshSync()
    }

    fun getCustomGreetingFile(): File = File(greetingsDir, "custom_greeting.m4a")

    fun refreshCustomGreetingStatus() {
        val file = getCustomGreetingFile()
        _hasCustomGreeting.value = file.exists() && file.length() > 0L
    }

    fun deleteCustomGreeting(): Boolean {
        val file = getCustomGreetingFile()
        val deleted = if (file.exists()) file.delete() else true
        refreshCustomGreetingStatus()
        return deleted
    }

    fun createRecordingTargetFile(timestampMillis: Long = System.currentTimeMillis()): File {
        voicemailsDir.mkdirs()
        return File(voicemailsDir, "vm_${timestampMillis}_${UUID.randomUUID().toString().take(6)}.m4a")
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

            // Pick up any unindexed .m4a or .3gp files in voicemailsDir
            val audioFiles = voicemailsDir.listFiles { f ->
                f.isFile && (f.extension.equals("m4a", ignoreCase = true) ||
                    f.extension.equals("3gp", ignoreCase = true))
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
            retriever.setDataSource(file.absolutePath)
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
