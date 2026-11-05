package com.dingdan.assistant

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Local-only structured execution trace. Never records API credentials or request bodies.
 * Each run is stored as JSONL so a partial run remains diagnosable after a crash.
 */
class PipelineTrace(context: Context, flow: String, metadata: JSONObject = JSONObject()) {
    val runId: String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) +
        "_" + UUID.randomUUID().toString().take(8)
    private val startedAt = SystemClock.elapsedRealtime()
    private val file = File(File(context.filesDir, "pipeline_traces").apply { mkdirs() }, "$runId.jsonl")
    private val lock = Any()

    init {
        event("PIPELINE", "START", details = JSONObject()
            .put("run_id", runId)
            .put("flow", flow)
            .put("app_version", runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                info.versionName ?: "unknown"
            }.getOrDefault("unknown"))
            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("metadata", metadata))
    }

    fun mark(): Long = SystemClock.elapsedRealtime()

    fun event(stage: String, status: String, started: Long? = null, details: JSONObject = JSONObject()) {
        val now = SystemClock.elapsedRealtime()
        val row = JSONObject()
            .put("time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date()))
            .put("run_id", runId)
            .put("elapsed_ms", now - startedAt)
            .put("stage", stage)
            .put("status", status)
            .put("duration_ms", if (started == null) JSONObject.NULL else now - started)
            .put("details", details)
        val line = row.toString()
        Log.i("DingDanTrace", line)
        synchronized(lock) {
            runCatching { file.appendText(line + "\n", Charsets.UTF_8) }
        }
    }

    fun error(stage: String, started: Long?, error: Throwable, details: JSONObject = JSONObject()) {
        event(stage, "ERROR", started, details
            .put("error_type", error.javaClass.name)
            .put("error_message", error.localizedMessage ?: error.toString())
            .put("stack_top", error.stackTrace.take(8).joinToString("\n")))
    }

    fun finish(status: String = "SUCCESS", details: JSONObject = JSONObject()) {
        event("PIPELINE", status, startedAt, details.put("trace_file", file.absolutePath))
    }

    fun path(): String = file.absolutePath
}
