package com.kuschelcraft.simplertspstreamer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.OrientationEventListener
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kuschelcraft.simplertspstreamer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), StreamController.Listener {
    private lateinit var binding: ActivityMainBinding
    private val ui = Handler(Looper.getMainLooper())
    private var state = StreamState()
    private var deviceOrientation = 0
    private var zoomRangeFor: String? = null
    private var orientationListener: OrientationEventListener? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            if (hasCameraPermission()) {
                StreamService.start(this)
            } else {
                Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    private val addressRefresh = object : Runnable {
        override fun run() {
            renderAddresses()
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.previewFrame.maxHeightFraction = if (landscape) 0.85f else 0.42f

        val c = binding.controls
        c.startStopButton.setOnClickListener { onStartStopClicked() }
        c.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        c.logButton.setOnClickListener { showLog() }
        c.urlText.setOnClickListener { copyUrl() }
        c.zoomSlider.addOnChangeListener { _, value, fromUser ->
            c.zoomValue.text = getString(R.string.zoom_value, value)
            if (fromUser) StreamController.setZoom(value)
        }

        binding.preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                StreamController.setPreviewTexture(st)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                StreamController.setPreviewTexture(null)
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val snapped = ((degrees + 45) / 90 * 90) % 360
                if (snapped != deviceOrientation) {
                    deviceOrientation = snapped
                    renderHint()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        StreamController.addListener(this)
    }

    override fun onResume() {
        super.onResume()
        orientationListener?.takeIf { it.canDetectOrientation() }?.enable()
        ui.post(addressRefresh)
        applyChangedSettings()
    }

    override fun onPause() {
        orientationListener?.disable()
        ui.removeCallbacks(addressRefresh)
        super.onPause()
    }

    override fun onStop() {
        StreamController.removeListener(this)
        super.onStop()
    }

    // ---------------------------------------------------------------------------------------

    override fun onState(state: StreamState) {
        this.state = state
        render()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun onStartStopClicked() {
        if (state.active) {
            StreamService.stop(this)
            return
        }
        if (hasCameraPermission()) {
            StreamService.start(this)
        } else {
            val wanted = ArrayList<String>()
            wanted.add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }

    /** Settings were changed while streaming: restart with the new values. */
    private fun applyChangedSettings() {
        if (!state.active || state.config == null) return
        val wanted = try { ConfigResolver.resolve(this) } catch (e: Exception) { return }
        if (wanted != state.config) {
            AppLog.log("Settings changed - restarting stream")
            StreamService.start(this)
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun render() {
        val c = binding.controls
        val s = state
        val (statusRes, color) = when (s.phase) {
            Phase.STOPPED -> R.string.status_ready to R.color.status_idle
            Phase.STARTING -> R.string.status_starting to R.color.status_warn
            Phase.STREAMING -> R.string.status_streaming to R.color.status_ok
            Phase.RECOVERING -> R.string.status_recovering to R.color.status_warn
            Phase.ERROR -> R.string.status_error to R.color.status_error
        }
        c.statusText.setText(statusRes)
        c.statusText.setTextColor(ContextCompat.getColor(this, color))

        c.statusDetail.text = when {
            s.phase == Phase.ERROR -> getString(R.string.error_generic, s.message ?: "?")
            s.phase == Phase.RECOVERING && s.message != null -> s.message
            s.config != null && s.active -> {
                val cfg = s.config
                val viewers = if (s.clients == 0) getString(R.string.viewers_none) else getString(R.string.viewers_n, s.clients)
                val stats = if (s.phase == Phase.STREAMING) {
                    getString(R.string.stream_stats, cfg.width, cfg.height, cfg.fps, s.networkKbps / 1000f)
                } else {
                    getString(R.string.stream_stats_idle, cfg.width, cfg.height, cfg.fps)
                }
                "$viewers\n$stats"
            }
            else -> ""
        }
        c.statusDetail.visibility = if (c.statusDetail.text.isEmpty()) View.GONE else View.VISIBLE

        c.startStopButton.setText(if (s.active) R.string.btn_stop else R.string.btn_start)

        binding.previewPlaceholder.visibility = if (s.phase == Phase.STREAMING) View.GONE else View.VISIBLE
        s.config?.let { binding.previewFrame.aspect = it.width.toFloat() / it.height }

        if (s.active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        renderZoom()
        renderHint()
        renderAddresses()
    }

    private fun renderZoom() {
        val c = binding.controls
        val cfg = state.config
        if (cfg == null || !state.active) {
            c.zoomRow.visibility = View.GONE
            zoomRangeFor = null
            return
        }
        if (zoomRangeFor != cfg.cameraId) {
            val cam = CameraCatalog.list(this).firstOrNull { it.id == cfg.cameraId }
            if (cam == null || cam.zoomMax - cam.zoomMin < 0.2f) {
                c.zoomRow.visibility = View.GONE
                return
            }
            c.zoomSlider.valueFrom = cam.zoomMin
            c.zoomSlider.valueTo = cam.zoomMax
            c.zoomSlider.value = StreamController.zoom.coerceIn(cam.zoomMin, cam.zoomMax)
            c.zoomValue.text = getString(R.string.zoom_value, c.zoomSlider.value)
            zoomRangeFor = cfg.cameraId
        }
        c.zoomRow.visibility = View.VISIBLE
    }

    private fun renderAddresses() {
        val c = binding.controls
        val port = state.config?.port ?: ConfigResolver.port(this)
        val addresses = NetworkUtils.addresses()
        if (addresses.isEmpty()) {
            c.urlText.text = getString(R.string.no_network)
            c.urlText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            c.urlMore.visibility = View.GONE
            return
        }
        c.urlText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        c.urlText.text = NetworkUtils.url(addresses.first().ip, port)
        if (addresses.size > 1) {
            c.urlMore.text = getString(R.string.url_other, addresses.drop(1).joinToString("\n") { NetworkUtils.url(it.ip, port) })
            c.urlMore.visibility = View.VISIBLE
        } else {
            c.urlMore.visibility = View.GONE
        }
    }

    /** Tells the user whether the (unrotated) stream is upright for the way the phone is held. */
    private fun renderHint() {
        val hint = binding.controls.orientationHint
        val cfg = state.config
        if (cfg == null || !state.active) {
            hint.visibility = View.GONE
            return
        }
        val rel = if (cfg.lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (cfg.sensorOrientation - deviceOrientation + 360) % 360
        } else {
            (cfg.sensorOrientation + deviceOrientation) % 360
        }
        val (textRes, colorRes) = when (rel) {
            0 -> R.string.hint_upright to R.color.status_ok
            180 -> R.string.hint_upside_down to R.color.status_error
            else -> R.string.hint_sideways to R.color.status_warn
        }
        hint.setText(textRes)
        hint.setTextColor(ContextCompat.getColor(this, colorRes))
        hint.visibility = View.VISIBLE
    }

    private fun copyUrl() {
        val text = binding.controls.urlText.text?.toString().orEmpty()
        if (!text.startsWith("rtsp://")) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("RTSP", text))
        Toast.makeText(this, R.string.url_copied, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------------------------------

    private fun diagnosticText(): String {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        val header = buildString {
            append("Simple RTSP Streamer $version\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
            append("Encoder: ${EncoderSelector.info?.name ?: "none"}\n")
            append("State: ${state.phase} ${state.message.orEmpty()} config=${state.config}\n")
            append("Addresses: ${NetworkUtils.addresses().joinToString { it.iface + "=" + it.ip }}\n")
            append("----\n")
        }
        return header + AppLog.dump()
    }

    private fun showLog() {
        val text = diagnosticText()
        val tv = TextView(this).apply {
            this.text = text
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(48, 24, 48, 24)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.log_title)
            .setView(scroll)
            .setPositiveButton(R.string.log_copy) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Log", text))
                Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.log_share) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                startActivity(Intent.createChooser(send, getString(R.string.log_share)))
            }
            .setNegativeButton(R.string.log_close, null)
            .show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
