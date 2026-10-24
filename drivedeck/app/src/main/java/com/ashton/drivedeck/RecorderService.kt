package com.ashton.drivedeck

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class RecorderService : LifecycleService(), SensorEventListener {

    companion object {
        @Volatile var instance: RecorderService? = null
        @Volatile var previewView: PreviewView? = null

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RecorderService::class.java))
        }

        fun clipsDir(ctx: Context): File {
            val d = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "DriveDeck")
            d.mkdirs()
            return d
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var currentFile: File? = null
    private var prevFile: File? = null
    private var lockCurrent = false
    private var errCount = 0
    private var starting = false
    private var lastImpactMs = 0L

    @Volatile var wantRec = false
    @Volatile var isRec = false
    @Volatile var startedAt = 0L
    @Volatile var lastImpact = 0L

    private val roll = Runnable { recording?.stop() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc != null) sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        startFg()
        if (!wantRec) startRecording()
        return START_NOT_STICKY
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("rec", "Recording", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "rec")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("DriveDeck is recording")
            .setContentText("Loop recording is on")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (Prefs.getInt(this, "mic", 0) == 1 && hasMic()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, 1, n, type)
    }

    fun startRecording() {
        if (isRec || starting) return
        starting = true
        wantRec = true
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                provider = f.get()
                bindUseCases()
                beginSegment()
            } catch (e: Exception) {
                fail()
            } finally {
                starting = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val p = provider ?: return
        p.unbindAll()
        val pv = Preview.Builder().build()
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
            .build()
        val vc = VideoCapture.withOutput(recorder)
        p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, vc)
        preview = pv
        videoCapture = vc
        bindPreview()
    }

    fun bindPreview() {
        val pv = previewView ?: return
        preview?.setSurfaceProvider(pv.surfaceProvider)
    }

    fun unbindPreview() {
        preview?.setSurfaceProvider(null)
    }

    private fun beginSegment() {
        val vc = videoCapture ?: return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(clipsDir(this), "clip_$stamp.mp4")
        var pending = vc.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
        if (Prefs.getInt(this, "mic", 0) == 1 && hasMic()) pending = pending.withAudioEnabled()
        currentFile = file
        lockCurrent = false
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            if (ev is VideoRecordEvent.Finalize) onFinalize(file, ev)
        }
        if (!isRec) {
            isRec = true
            startedAt = SystemClock.elapsedRealtime()
        }
        main.removeCallbacks(roll)
        main.postDelayed(roll, Prefs.getInt(this, "seg", 3) * 60_000L)
    }

    private fun lockFile(f: File): File {
        if (!f.exists() || f.name.startsWith("LOCK_")) return f
        val n = File(f.parentFile, "LOCK_" + f.name)
        return if (f.renameTo(n)) n else f
    }

    private fun onFinalize(f: File, ev: VideoRecordEvent.Finalize) {
        main.removeCallbacks(roll)
        var out = f
        if (lockCurrent && f.exists()) out = lockFile(f)
        prevFile = out
        enforceCap()
        if (ev.hasError()) errCount++ else errCount = 0
        if (wantRec && errCount < 5) {
            main.postDelayed({ if (wantRec) beginSegment() }, if (ev.hasError()) 3000L else 0L)
        } else {
            finishUp()
        }
    }

    private fun finishUp() {
        wantRec = false
        isRec = false
        try { provider?.unbindAll() } catch (e: Exception) { }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail() {
        wantRec = false
        isRec = false
        finishUp()
    }

    fun stopRecording() {
        wantRec = false
        val r = recording
        if (r != null) r.stop() else finishUp()
    }

    fun lockNow() {
        main.post {
            if (!isRec) return@post
            lockCurrent = true
            prevFile = prevFile?.let { lockFile(it) }
            lastImpact = System.currentTimeMillis()
            main.removeCallbacks(roll)
            main.postDelayed(roll, 20_000L)
        }
    }

    private fun enforceCap() {
        val cap = Prefs.getInt(this, "cap", 4096) * 1024L * 1024L
        val files = clipsDir(this).listFiles { x ->
            x.isFile && x.name.endsWith(".mp4") && !x.name.startsWith("LOCK_")
        }?.sortedBy { it.lastModified() } ?: return
        var total = 0L
        for (x in files) total += x.length()
        for (x in files) {
            if (total <= cap) break
            if (x == currentFile) continue
            total -= x.length()
            x.delete()
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / 9.81f
        val th = when (Prefs.getInt(this, "sens", 1)) {
            0 -> 3.0f
            2 -> 1.2f
            else -> 2.0f
        }
        val now = SystemClock.elapsedRealtime()
        if (isRec && abs(g - 1f) > th && now - lastImpactMs > 8000L) {
            lastImpactMs = now
            lockNow()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.unregisterListener(this)
        try { recording?.stop() } catch (e: Exception) { }
        try { provider?.unbindAll() } catch (e: Exception) { }
        super.onDestroy()
    }
}
