package com.erkam.temasa.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.erkam.temasa.play.InstallReceiver
import com.erkam.temasa.yt.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

/** Uygulama içi güncelleme: yeni sürümü GitHub'dan indirip kurar. */
object Updater {
    private const val API = "https://api.github.com/repos/mitturkeyhack/Temasa/releases/latest"

    enum class Phase { IDLE, CHECKING, LATEST, AVAILABLE, DOWNLOADING, READY, INSTALLING, ERROR }

    data class Release(val version: String, val notes: List<String>, val url: String, val size: Long)

    data class State(
        val phase: Phase = Phase.IDLE,
        val release: Release? = null,
        val bytes: Long = 0,
        val error: String? = null,
        val checkedAt: Long = 0,
    ) {
        val progress get() = if ((release?.size ?: 0) > 0) (bytes.toFloat() / release!!.size).coerceIn(0f, 1f) else 0f
        val hasUpdate get() = phase == Phase.AVAILABLE || phase == Phase.DOWNLOADING || phase == Phase.READY || phase == Phase.INSTALLING
    }

    val state = MutableStateFlow(State())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun current(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    private fun nums(v: String) = Regex("\\d+").findAll(v).map { it.value.toInt() }.toList()

    private fun newer(a: String, b: String): Boolean {
        val x = nums(a); val y = nums(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    private fun apk(ctx: Context) = File(ctx.cacheDir, "guncelleme").apply { mkdirs() }.resolve("Temasa.apk")

    /** Yeni sürüm var mı? silent: arka planda, hata göstermeden */
    fun check(ctx: Context, silent: Boolean = false) {
        val s = state.value
        if (s.phase == Phase.CHECKING || s.phase == Phase.DOWNLOADING || s.phase == Phase.INSTALLING) return
        if (silent && System.currentTimeMillis() - s.checkedAt < 3 * 3600_000L) return
        val app = ctx.applicationContext
        if (!silent) state.update { it.copy(phase = Phase.CHECKING, error = null) }
        scope.launch {
            val r = runCatching {
                val req = Request.Builder().url(API)
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "Temasa").build()
                Net.client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw IllegalStateException("GitHub ${res.code}")
                    val o = JSONObject(res.body.string())
                    val assets = o.getJSONArray("assets")
                    val a = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk") } ?: throw IllegalStateException("APK yok")
                    val notes = o.optString("body").lines().map { it.trim() }
                        .filter { it.startsWith("- ") || it.startsWith("* ") }.map { it.drop(2).trim() }.take(5)
                    Release(o.optString("tag_name").removePrefix("v"), notes, a.getString("browser_download_url"), a.optLong("size"))
                }
            }
            val now = System.currentTimeMillis()
            r.onSuccess { rel ->
                val cur = current(app)
                val ready = apk(app).let { f -> f.exists() && f.length() == rel.size && state.value.release?.version == rel.version && state.value.phase == Phase.READY }
                state.value = when {
                    !newer(rel.version, cur) -> State(Phase.LATEST, rel, checkedAt = now)
                    ready -> state.value.copy(checkedAt = now)
                    else -> State(Phase.AVAILABLE, rel, checkedAt = now)
                }
            }
            r.onFailure { e ->
                if (!silent) state.update { it.copy(phase = Phase.ERROR, error = "Denetlenemedi. İnternet bağlantını kontrol et.", checkedAt = now) }
            }
        }
    }

    fun download(ctx: Context) {
        val rel = state.value.release ?: return
        val app = ctx.applicationContext
        job?.cancel()
        state.update { it.copy(phase = Phase.DOWNLOADING, bytes = 0, error = null) }
        job = scope.launch {
            val f = apk(app)
            val r = runCatching {
                Net.client.newCall(Request.Builder().url(rel.url).header("User-Agent", "Temasa").build()).execute().use { res ->
                    if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                    val tmp = File(f.path + ".part")
                    tmp.outputStream().use { out ->
                        val inp = res.body.byteStream(); val buf = ByteArray(64 * 1024); var n: Int; var got = 0L
                        while (inp.read(buf).also { n = it } >= 0) {
                            coroutineContext.ensureActive()
                            out.write(buf, 0, n); got += n
                            state.update { it.copy(bytes = got) }
                        }
                    }
                    tmp.renameTo(f)
                }
            }
            r.onSuccess { state.update { it.copy(phase = Phase.READY, bytes = rel.size) }; withContext(Dispatchers.Main) { install(app) } }
            r.onFailure { if (isActive) state.update { it.copy(phase = Phase.ERROR, error = "İndirilemedi. Tekrar dene.") } }
        }
    }

    fun cancel() {
        job?.cancel()
        state.update { it.copy(phase = Phase.AVAILABLE, bytes = 0) }
    }

    /** İndirilen sürümü kur (Android "Yükle" onayı ister) */
    fun install(ctx: Context) {
        val f = apk(ctx)
        if (!f.exists()) { state.update { it.copy(phase = Phase.AVAILABLE) }; return }
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            state.update { it.copy(phase = Phase.READY, error = "Önce \"Bu kaynaktan izin ver\"i aç, sonra Yükle'ye tekrar bas.") }
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        state.update { it.copy(phase = Phase.INSTALLING, error = null) }
        scope.launch {
            runCatching {
                val pi = ctx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(ctx.packageName)
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    s.openWrite("Temasa.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName), flags)
                    s.commit(pending.intentSender)
                }
            }.onFailure { failed("Kurulum başlatılamadı") }
        }
    }

    fun failed(msg: String?) {
        state.update { it.copy(phase = Phase.READY, error = msg ?: "Kurulum tamamlanmadı. Yükle'ye tekrar bas.") }
    }
}
