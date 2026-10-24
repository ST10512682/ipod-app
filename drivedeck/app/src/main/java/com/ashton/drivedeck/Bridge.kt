package com.ashton.drivedeck

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Bridge(private val a: MainActivity) {

    private fun dir(): File = RecorderService.clipsDir(a)
    private fun clip(name: String): File = File(dir(), File(name).name)

    @JavascriptInterface
    fun state(): String {
        val s = RecorderService.instance
        val o = JSONObject()
        val rec = s != null && s.isRec
        o.put("rec", rec)
        o.put("secs", if (s != null && rec) (SystemClock.elapsedRealtime() - s.startedAt) / 1000 else 0L)
        o.put("impact", s?.lastImpact ?: 0L)
        var clips = 0
        var locked = 0
        var used = 0L
        dir().listFiles()?.forEach {
            if (it.isFile && it.name.endsWith(".mp4")) {
                clips++
                if (it.name.startsWith("LOCK_")) locked++
                used += it.length()
            }
        }
        o.put("clips", clips)
        o.put("locked", locked)
        o.put("usedMb", used / 1048576L)
        o.put("freeMb", try { StatFs(dir().path).availableBytes / 1048576L } catch (e: Exception) { 0L })
        o.put("cam", a.granted(android.Manifest.permission.CAMERA))
        o.put("music", MusicPlayer.state())
        return o.toString()
    }

    @JavascriptInterface
    fun settings(): String {
        val o = JSONObject()
        o.put("seg", Prefs.getInt(a, "seg", 3))
        o.put("cap", Prefs.getInt(a, "cap", 4096))
        o.put("mic", Prefs.getInt(a, "mic", 0))
        o.put("sens", Prefs.getInt(a, "sens", 1))
        o.put("auto", Prefs.getInt(a, "auto", 1))
        return o.toString()
    }

    @JavascriptInterface
    fun set(k: String, v: Int) {
        if (k in listOf("seg", "cap", "mic", "sens", "auto")) Prefs.setInt(a, k, v)
    }

    @JavascriptInterface fun requestPerms() { a.runOnUiThread { a.askPerms() } }
    @JavascriptInterface fun startRec() { a.runOnUiThread { a.startRec() } }
    @JavascriptInterface fun stopRec() { a.runOnUiThread { RecorderService.instance?.stopRecording() } }
    @JavascriptInterface fun lockNow() { RecorderService.instance?.lockNow() }
    @JavascriptInterface fun setPreview(l: Double, t: Double, w: Double, h: Double) { a.setPreview(l, t, w, h) }

    // ---- music ----
    @JavascriptInterface fun tracks(): String = MusicPlayer.list()
    @JavascriptInterface fun rescan() { MusicPlayer.scan() }
    @JavascriptInterface fun play(i: Int) { a.runOnUiThread { MusicPlayer.play(i) } }
    @JavascriptInterface fun playId(id: Double) { a.runOnUiThread { MusicPlayer.playId(id.toLong()) } }
    @JavascriptInterface fun toggle() { a.runOnUiThread { MusicPlayer.toggle() } }
    @JavascriptInterface fun next() { a.runOnUiThread { MusicPlayer.next() } }
    @JavascriptInterface fun prev() { a.runOnUiThread { MusicPlayer.prev() } }
    @JavascriptInterface fun seek(ms: Int) { a.runOnUiThread { MusicPlayer.seek(ms) } }
    @JavascriptInterface fun setVol(v: Double) { a.runOnUiThread { MusicPlayer.setVol(v.toFloat()) } }
    @JavascriptInterface fun fft(): String = MusicPlayer.fft()

    // ---- clips ----
    @JavascriptInterface
    fun clips(): String {
        val arr = JSONArray()
        val files = dir().listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        for (f in files) {
            val o = JSONObject()
            o.put("name", f.name)
            o.put("size", f.length())
            o.put("time", f.lastModified())
            o.put("locked", f.name.startsWith("LOCK_"))
            arr.put(o)
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun openClip(name: String) {
        val f = clip(name)
        if (!f.exists()) return
        a.runOnUiThread {
            try {
                val uri = FileProvider.getUriForFile(a, a.packageName + ".fp", f)
                val i = Intent(Intent.ACTION_VIEW)
                i.setDataAndType(uri, "video/mp4")
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                a.startActivity(i)
            } catch (e: Exception) { }
        }
    }

    @JavascriptInterface
    fun deleteClip(name: String): Boolean {
        val f = clip(name)
        return f.exists() && f.delete()
    }

    @JavascriptInterface
    fun toggleLock(name: String): Boolean {
        val f = clip(name)
        if (!f.exists()) return false
        val n = if (f.name.startsWith("LOCK_")) File(f.parentFile, f.name.removePrefix("LOCK_"))
        else File(f.parentFile, "LOCK_" + f.name)
        return f.renameTo(n)
    }

    @JavascriptInterface
    fun exportClip(name: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val f = clip(name)
        if (!f.exists()) return false
        return try {
            val v = ContentValues()
            v.put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/DriveDeck")
            val uri = a.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return false
            a.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            true
        } catch (e: Exception) {
            false
        }
    }
}
