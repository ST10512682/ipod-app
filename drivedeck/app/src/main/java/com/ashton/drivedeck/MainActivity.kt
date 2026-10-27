package com.ashton.drivedeck

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    lateinit var web: WebView

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPerms() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        MusicPlayer.init(this)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        preview = PreviewView(this)
        preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        preview.scaleType = PreviewView.ScaleType.FILL_CENTER
        preview.visibility = View.INVISIBLE

        web = WebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(this), "Android")

        root.addView(preview, FrameLayout.LayoutParams(1, 1))
        root.addView(
            web,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
        hideBars()

        RecorderService.previewView = preview
        web.loadUrl("file:///android_asset/index.html")
        askPerms()
    }

    private fun hideBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onResume() {
        super.onResume()
        RecorderService.instance?.bindPreview()
    }

    override fun onPause() {
        super.onPause()
        RecorderService.instance?.unbindPreview()
    }

    override fun onDestroy() {
        if (RecorderService.previewView === preview) RecorderService.previewView = null
        web.destroy()
        super.onDestroy()
    }

    fun granted(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    fun askPerms() {
        val want = ArrayList<String>()
        want.add(Manifest.permission.CAMERA)
        want.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            want.add(Manifest.permission.POST_NOTIFICATIONS)
            want.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            want.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val need = want.filter { !granted(it) }
        if (need.isEmpty()) onPerms() else permLauncher.launch(need.toTypedArray())
    }

    private fun onPerms() {
        MusicPlayer.scan()
        if (granted(Manifest.permission.CAMERA) &&
            Prefs.getInt(this, "auto", 1) == 1 &&
            RecorderService.instance == null
        ) {
            RecorderService.start(this)
        }
        web.evaluateJavascript("window.onNative&&onNative('perms')", null)
    }

    fun startRec() {
        if (!granted(Manifest.permission.CAMERA)) {
            askPerms()
            return
        }
        val s = RecorderService.instance
        if (s == null) RecorderService.start(this) else s.startRecording()
    }

    fun evalJs(js: String) {
        web.evaluateJavascript(js, null)
    }

    fun setPreview(l: Double, t: Double, w: Double, h: Double) {
        runOnUiThread {
            val d = resources.displayMetrics.density
            val lp = FrameLayout.LayoutParams((w * d).toInt().coerceAtLeast(1), (h * d).toInt().coerceAtLeast(1))
            lp.leftMargin = (l * d).toInt()
            lp.topMargin = (t * d).toInt()
            preview.layoutParams = lp
            preview.visibility = if (w > 4 && h > 4) View.VISIBLE else View.INVISIBLE
        }
    }
}
