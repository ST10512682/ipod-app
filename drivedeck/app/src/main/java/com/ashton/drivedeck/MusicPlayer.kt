package com.ashton.drivedeck

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

object MusicPlayer {
    class Track(val id: Long, val title: String, val artist: String, val album: String, val dur: Long)

    var tracks: List<Track> = emptyList()
    var idx = -1
    var vol = 0.8f
    private var mp: MediaPlayer? = null
    private var viz: Visualizer? = null
    private var app: Context? = null
    private var errs = 0
    private var focusReq: AudioFocusRequest? = null
    private var pausedByFocus = false

    fun init(c: Context) {
        app = c.applicationContext
    }

    fun scan() {
        val c = app ?: return
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(c, perm) != PackageManager.PERMISSION_GRANTED) return
        val list = ArrayList<Track>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION
        )
        val q = c.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
            MediaStore.Audio.Media.IS_MUSIC + "!=0", null,
            MediaStore.Audio.Media.TITLE + " COLLATE NOCASE"
        )
        if (q != null) {
            while (q.moveToNext()) {
                list.add(Track(q.getLong(0), q.getString(1) ?: "", q.getString(2) ?: "", q.getString(3) ?: "", q.getLong(4)))
            }
            q.close()
        }
        tracks = list
    }

    private fun focus(c: Context) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setOnAudioFocusChangeListener { ch ->
                val m = mp
                when (ch) {
                    AudioManager.AUDIOFOCUS_LOSS -> { if (m != null && m.isPlaying) { m.pause(); pausedByFocus = false } }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { if (m != null && m.isPlaying) { m.pause(); pausedByFocus = true } }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> m?.setVolume(vol * 0.25f, vol * 0.25f)
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        m?.setVolume(vol, vol)
                        if (pausedByFocus) { m?.start(); pausedByFocus = false }
                    }
                }
            }.build()
        focusReq = req
        am.requestAudioFocus(req)
    }

    private fun release() {
        try { viz?.release() } catch (e: Exception) { }
        viz = null
        try { mp?.release() } catch (e: Exception) { }
        mp = null
    }

    fun play(i: Int) {
        val c = app ?: return
        if (i !in tracks.indices) return
        release()
        val t = tracks[i]
        idx = i
        val m = MediaPlayer()
        try {
            m.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            m.setDataSource(c, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, t.id))
            m.setOnCompletionListener { next() }
            m.setOnErrorListener { _, _, _ ->
                errs++
                if (errs < 5) next()
                true
            }
            m.prepare()
            m.setVolume(vol, vol)
            focus(c)
            m.start()
            mp = m
            errs = 0
            viz = try {
                val v = Visualizer(m.audioSessionId)
                v.setCaptureSize(Visualizer.getCaptureSizeRange()[0])
                v.setEnabled(true)
                v
            } catch (e: Exception) {
                null
            }
        } catch (e: Exception) {
            try { m.release() } catch (x: Exception) { }
            mp = null
            errs++
            if (errs < 5) next()
        }
    }

    fun playId(id: Long) {
        val i = tracks.indexOfFirst { it.id == id }
        if (i >= 0) play(i)
    }

    fun toggle() {
        val m = mp
        if (m == null) {
            if (tracks.isNotEmpty()) play(if (idx >= 0) idx else 0)
            return
        }
        pausedByFocus = false
        if (m.isPlaying) m.pause() else m.start()
    }

    fun next() {
        if (tracks.isEmpty()) return
        play((idx + 1).mod(tracks.size))
    }

    fun prev() {
        if (tracks.isEmpty()) return
        val m = mp
        if (m != null && m.currentPosition > 3000) { m.seekTo(0); return }
        play((idx - 1).mod(tracks.size))
    }

    fun seek(ms: Int) {
        mp?.seekTo(ms)
    }

    fun changeVol(v: Float) {
        vol = v.coerceIn(0f, 1f)
        mp?.setVolume(vol, vol)
    }

    fun state(): JSONObject {
        val o = JSONObject()
        val m = mp
        val t = if (idx in tracks.indices) tracks[idx] else null
        o.put("playing", m != null && m.isPlaying)
        o.put("pos", if (m != null) m.currentPosition else 0)
        o.put("dur", if (m != null) m.duration else 0)
        o.put("idx", idx)
        o.put("id", t?.id ?: -1L)
        o.put("title", t?.title ?: "")
        o.put("artist", t?.artist ?: "")
        o.put("album", t?.album ?: "")
        o.put("vol", vol.toDouble())
        o.put("count", tracks.size)
        return o
    }

    fun list(): String {
        val a = JSONArray()
        tracks.forEachIndexed { i, t ->
            val o = JSONObject()
            o.put("i", i); o.put("id", t.id); o.put("t", t.title); o.put("a", t.artist)
            a.put(o)
        }
        return a.toString()
    }

    fun fft(): String {
        val v = viz ?: return ""
        return try {
            val n = v.captureSize
            val b = ByteArray(n)
            if (v.getFft(b) != Visualizer.SUCCESS) return ""
            val bands = 24
            val bins = n / 2
            val sb = StringBuilder()
            for (k in 0 until bands) {
                val s = Math.pow(bins.toDouble(), k.toDouble() / bands).toInt().coerceAtLeast(1)
                val e = Math.pow(bins.toDouble(), (k + 1).toDouble() / bands).toInt().coerceAtLeast(s + 1).coerceAtMost(bins)
                var sum = 0.0
                var cnt = 0
                for (j in s until e) {
                    val re = b[2 * j].toInt()
                    val im = b[2 * j + 1].toInt()
                    sum += Math.sqrt((re * re + im * im).toDouble())
                    cnt++
                }
                val mag = if (cnt > 0) sum / cnt else 0.0
                if (k > 0) sb.append(',')
                sb.append(Math.min(255.0, mag * 6.0).toInt())
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }
}
