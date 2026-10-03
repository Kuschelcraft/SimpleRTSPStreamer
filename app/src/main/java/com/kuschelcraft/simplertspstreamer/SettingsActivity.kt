package com.kuschelcraft.simplertspstreamer

import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)
        val root = findViewById<android.view.View>(R.id.settingsRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private var cameras: List<CameraOption> = emptyList()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // androidx.preference writes to <package>_preferences, which ConfigResolver reads.
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val ctx = requireContext()
            cameras = CameraCatalog.list(ctx)

            val cameraPref = findPreference<ListPreference>(ConfigResolver.KEY_CAMERA)!!
            if (cameras.isEmpty()) {
                cameraPref.isEnabled = false
            } else {
                cameraPref.entries = cameras.map { cameraLabel(it) }.toTypedArray()
                cameraPref.entryValues = cameras.map { it.id }.toTypedArray()
                val current = cameras.firstOrNull { it.id == cameraPref.value } ?: ConfigResolver.defaultCamera(cameras)
                cameraPref.value = current.id
                populateCameraDependent(current, keepSelection = true)
                cameraPref.setOnPreferenceChangeListener { _, newValue ->
                    cameras.firstOrNull { it.id == newValue }?.let { populateCameraDependent(it, keepSelection = false) }
                    true
                }
            }

            findPreference<EditTextPreference>(ConfigResolver.KEY_PORT)?.apply {
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
                setOnPreferenceChangeListener { _, newValue ->
                    val port = (newValue as? String)?.toIntOrNull()
                    if (port == null || port !in 1024..65535) {
                        Toast.makeText(ctx, R.string.port_invalid, Toast.LENGTH_LONG).show()
                        false
                    } else {
                        true
                    }
                }
            }

            findPreference<Preference>("battery")?.setOnPreferenceClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
                true
            }

            findPreference<Preference>("version")?.summary = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            } catch (e: Exception) {
                "?"
            }
        }

        private fun cameraLabel(c: CameraOption): String = when (c.facing) {
            CameraCharacteristics.LENS_FACING_BACK -> getString(R.string.camera_back, c.id)
            CameraCharacteristics.LENS_FACING_FRONT -> getString(R.string.camera_front, c.id)
            else -> getString(R.string.camera_external, c.id)
        }

        /** Resolution and frame-rate choices depend on what the selected camera offers. */
        private fun populateCameraDependent(cam: CameraOption, keepSelection: Boolean) {
            val resPref = findPreference<ListPreference>(ConfigResolver.KEY_RESOLUTION) ?: return
            resPref.entries = cam.sizes.map { "${it.width} × ${it.height}" }.toTypedArray()
            resPref.entryValues = cam.sizes.map { ConfigResolver.sizeKey(it) }.toTypedArray()
            val currentRes = resPref.value
            resPref.value = if (keepSelection && currentRes != null && cam.sizes.any { ConfigResolver.sizeKey(it) == currentRes }) {
                currentRes
            } else {
                ConfigResolver.sizeKey(ConfigResolver.defaultSize(cam))
            }

            val fpsPref = findPreference<ListPreference>(ConfigResolver.KEY_FPS) ?: return
            fpsPref.entries = cam.fpsOptions.map { "$it fps" }.toTypedArray()
            fpsPref.entryValues = cam.fpsOptions.map { it.toString() }.toTypedArray()
            val currentFps = fpsPref.value?.toIntOrNull()
            fpsPref.value = (if (keepSelection && currentFps != null && currentFps in cam.fpsOptions) currentFps else ConfigResolver.defaultFps(cam)).toString()
        }
    }
}
