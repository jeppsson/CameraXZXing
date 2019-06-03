package com.jeppsson.cameraxzxing

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.util.Size
import android.view.Menu
import android.view.MenuItem
import android.view.TextureView
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.PermissionChecker
import com.google.zxing.Result
import com.google.zxing.client.android.Decoder
import com.jeppsson.cameraxzxing.settings.SettingsActivity

class MainActivity : AppCompatActivity(), Decoder.OnResultListener {

    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var decoder: Decoder
    private var dialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        // Ensure Camera permission
        if (PermissionChecker.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PermissionChecker.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                Array(1) { Manifest.permission.CAMERA }, REQUEST_PERMISSION_CAMERA
            )
            return
        }

        // Preview
        val textureView: TextureView = findViewById(R.id.preview)
        val previewConfig = PreviewConfig.Builder().build()
        val preview = Preview(previewConfig)
        preview.setOnPreviewOutputUpdateListener { previewOutput ->
            textureView.surfaceTexture = previewOutput.surfaceTexture
        }

        // Analyzer
        val analyzerConfig = ImageAnalysisConfig.Builder().apply {
            val analyzerThread = HandlerThread("BarcodeAnalysis").apply { start() }
            setCallbackHandler(Handler(analyzerThread.looper))
            setTargetResolution(Size(1280, 720))
            setImageReaderMode(ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE)
        }.build()
        imageAnalysis = ImageAnalysis(analyzerConfig)
        setAnalyzer()

        CameraX.bindToLifecycle(this, imageAnalysis, preview)

        decoder = Decoder(this, this)

        updateViewFinder()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_SETTINGS -> Handler().postDelayed({ recreate() }, 500)
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.activity_main_options, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivityForResult(Intent(this, SettingsActivity::class.java), REQUEST_SETTINGS)
                true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun onResult(result: Result?) {
        runOnUiThread {
            // Stop analysis while showing dialog
            imageAnalysis.removeAnalyzer()

            // Avoid showing more than one dialog
            if (dialog?.isShowing == true) {
                return@runOnUiThread
            }

            dialog = AlertDialog.Builder(this)
                .setTitle(result?.barcodeFormat?.name)
                .setMessage(result.toString())
                .setPositiveButton(android.R.string.ok) { _, _ -> setAnalyzer() }
                .show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_PERMISSION_CAMERA) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PermissionChecker.PERMISSION_GRANTED &&
                permissions[0] == Manifest.permission.CAMERA
            ) {
                recreate()
            } else {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(
                        this,
                        Manifest.permission.CAMERA
                    )
                ) {
                    val uri = Uri.Builder()
                        .scheme("package")
                        .opaquePart(packageName)
                        .build()
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
                }
                finish()
            }
        }
    }

    private fun setAnalyzer() {
        imageAnalysis.setAnalyzer { image: ImageProxy, _: Int ->
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.capacity()).also { buffer.get(it) }
            decoder.decode(bytes, image.width, image.height)
        }
    }

    private fun updateViewFinder() {
        val viewFinder = findViewById<FrameLayout>(R.id.view_finder)
        val layoutParams: FrameLayout.LayoutParams =
            viewFinder.layoutParams as FrameLayout.LayoutParams
        layoutParams.height = decoder.framingRect.height()
        layoutParams.width = decoder.framingRect.width()
        layoutParams.topMargin = decoder.framingRect.top
        layoutParams.marginStart = decoder.framingRect.left
        viewFinder.layoutParams = layoutParams
    }

    private companion object {
        private const val REQUEST_SETTINGS = 1000
        private const val REQUEST_PERMISSION_CAMERA = 1001
    }
}
