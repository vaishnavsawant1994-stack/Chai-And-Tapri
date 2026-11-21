package com.chaiandtapri.counter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import java.util.concurrent.atomic.AtomicBoolean

/** CT-06 / Mode C: camera input returns a raw product code; Quick Sale validates it locally. */
class CameraBarcodeScannerActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private var cameraController: LifecycleCameraController? = null
    private val scanDelivered = AtomicBoolean(false)
    private val scanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_ITF
            ).build()
        BarcodeScanning.getClient(options)
    }
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else finishWithoutScan("Camera permission was denied. Type a product code or use a paired scanner; no order changed.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireCounterAccess(this)) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 20, 16, 16)
        }
        root.addView(TextView(this).apply {
            text = "Scan a product barcode or product QR code"
            textSize = 21f
            contentDescription = text
        })
        root.addView(TextView(this).apply {
            text = "Only the decoded code is returned to Quick Sale. Payment QR codes are not read as payment evidence."
            textSize = 14f
            setPadding(0, 8, 0, 12)
        })
        previewView = PreviewView(this).apply {
            contentDescription = "Live product barcode camera preview"
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        status = TextView(this).apply {
            text = "Starting camera..."
            textSize = 15f
            setPadding(0, 8, 0, 8)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Cancel scan"
            minHeight = 56
            setOnClickListener { finishWithoutScan("Camera scan cancelled. Type a product code or use a paired scanner; no order changed.") }
        })
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            status.text = "Camera permission is requested only for this scan."
            permissionRequest.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        status.text = "Opening camera..."
        try {
            val executor = ContextCompat.getMainExecutor(this)
            val controller = LifecycleCameraController(this).apply {
                cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                setImageAnalysisAnalyzer(
                    executor,
                    MlKitAnalyzer(
                        listOf(scanner),
                        ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
                        executor
                    ) { result -> handleScanResult(result?.getValue(scanner).orEmpty()) }
                )
                bindToLifecycle(this@CameraBarcodeScannerActivity)
            }
            cameraController = controller
            previewView.controller = controller
            status.text = "Point at one product code. Nothing is added until a unique catalogue match is found."
        } catch (_: Exception) {
            status.text = "Camera is unavailable. Return to Quick Sale and type the code or use a paired scanner."
        }
    }

    private fun handleScanResult(barcodes: List<Barcode>) {
        if (scanDelivered.get()) return
        val codes = barcodes.mapNotNull { it.rawValue?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        when {
            codes.size == 1 && scanDelivered.compareAndSet(false, true) -> {
                setResult(RESULT_OK, resultIntent(codes.single()))
                finish()
            }
            codes.size > 1 -> status.text = "Show one code at a time. No product has been added."
        }
    }

    private fun finishWithoutScan(message: String) {
        setResult(RESULT_CANCELED, resultIntent(message = message))
        finish()
    }

    override fun onDestroy() {
        runCatching { cameraController?.clearImageAnalysisAnalyzer() }
        runCatching { cameraController?.unbind() }
        cameraController = null
        if (::status.isInitialized && !isChangingConfigurations) runCatching { scanner.close() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PRODUCT_CODE = "com.chaiandtapri.counter.extra.PRODUCT_CODE"
        const val EXTRA_RESULT_MESSAGE = "com.chaiandtapri.counter.extra.RESULT_MESSAGE"

        internal fun resultIntent(code: String? = null, message: String? = null): Intent = Intent().apply {
            if (code != null) putExtra(EXTRA_PRODUCT_CODE, code)
            if (message != null) putExtra(EXTRA_RESULT_MESSAGE, message)
        }
    }
}
