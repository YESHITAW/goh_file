package com.example.answeringmachine.storage

data class VoicemailItem(
    val id: String,
    val callerNumber: String,
    val callerName: String?,
    val timestampMillis: Long,
    val durationMillis: Long,
    val filePath: String,
    val isHeard: Boolean
)
