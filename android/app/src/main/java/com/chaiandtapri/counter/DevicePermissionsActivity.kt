package com.chaiandtapri.counter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** CT-04: explains optional device access and asks only when the operator chooses. */
class DevicePermissionsActivity : ComponentActivity() {
    private val permissionHistory by lazy { getSharedPreferences("ct04_permission_history", MODE_PRIVATE) }
    private lateinit var content: LinearLayout
    private lateinit var status: TextView

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        render("Permission result received; current Android state is shown above.")
    }
    private val cameraPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        render("Camera permission result received; current Android state is shown above.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 32, 28, 28) }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    override fun onResume() { super.onResume(); if (::content.isInitialized) render() }

    private fun render(message: String? = null) {
        content.removeAllViews()
        content.addView(label("Device Permissions · CT-04", 26f))
        content.addView(label("Optional access is requested only when you choose. Denying microphone access does not block typed sales, orders, stock or payments.", 15f))
        content.addView(label("Microphone · ${permissionState(Manifest.permission.RECORD_AUDIO)}", 17f))
        content.addView(label("Used only for an operator-started speech-recognition request. This prototype does not save audio and does not provide continuous background listening.", 14f))
        content.addView(label("Bluetooth route access · ${if (Build.VERSION.SDK_INT < 31) "Not required on this Android version" else permissionState(Manifest.permission.BLUETOOTH_CONNECT)}", 17f))
        content.addView(label("Used to inspect/select a supported communication route when you choose to use speech. A connected headset does not prove that its microphone is the active audio source.", 14f))
        content.addView(label("Permission help", 17f))
        content.addView(label("If an optional permission was denied, typed counter workflows remain available. If Android no longer shows its permission prompt, use Open Android app settings to review access.", 14f))
        content.addView(label("Camera · ${permissionState(Manifest.permission.CAMERA)}", 17f))
        content.addView(label("Used only when you choose Scan product with camera in Quick Sale. The camera scanner reads product codes locally; it does not read payment QR codes or save images. Typed and paired-scanner fallback remain available.", 14f))
        content.addView(label("Files · No broad storage permission requested", 17f))
        content.addView(label("Encrypted backup and restore use Android's document picker.", 14f))
        status = label(message ?: "Permission status refreshed from Android.", 14f)
        content.addView(status)
        content.addView(button("Request optional voice permissions") { requestVoicePermissions() })
        content.addView(button("Request camera permission for product scanning") { requestCameraPermission() })
        content.addView(button("Open Android app settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        content.addView(button("Back") { finish() })
    }

    private fun requestVoicePermissions() {
        val requested = buildList {
            if (ContextCompat.checkSelfPermission(this@DevicePermissionsActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this@DevicePermissionsActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (requested.isEmpty()) render("Optional voice permissions are already granted.")
        else {
            requested.forEach { permissionHistory.edit().putBoolean("requested:$it", true).apply() }
            permissionRequest.launch(requested.toTypedArray())
        }
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            render("Camera permission is already granted; the camera opens only when you start a product scan.")
        } else {
            permissionHistory.edit().putBoolean("requested:${Manifest.permission.CAMERA}", true).apply()
            cameraPermissionRequest.launch(Manifest.permission.CAMERA)
        }
    }

    private fun permissionState(permission: String): String {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return "Granted"
        if (!permissionHistory.getBoolean("requested:$permission", false)) return "Not granted · optional; typed fallback available"
        return if (shouldShowRequestPermissionRationale(permission)) "Denied · can be requested again; typed fallback available"
        else "Denied · review in Android app settings; typed fallback available"
    }

    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 10, 0, 10) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; minHeight = 56
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { action() }
    }
}
