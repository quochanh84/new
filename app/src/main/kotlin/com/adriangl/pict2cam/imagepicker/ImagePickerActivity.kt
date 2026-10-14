/*
 * Copyright 2020 Adrián García
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.adriangl.pict2cam.imagepicker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.widget.ImageView
import android.widget.CheckBox
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.os.Bundle
import kotlin.concurrent.thread
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.adriangl.pict2cam.R
import com.adriangl.pict2cam.extensions.getImageCaptureOutputUri
import com.adriangl.pict2cam.extensions.isImageCaptureAction
import com.adriangl.pict2cam.utils.ImageConstants
import com.theartofdev.edmodo.cropper.CropImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import com.theartofdev.edmodo.cropper.CropImage.CROP_IMAGE_ACTIVITY_RESULT_ERROR_CODE

/**
 * Picker activity needed to receive the result of the media query to return it to the original
 * caller. This activity is translucent so the user doesn't perceive any artifacts.
 */
class ImagePickerActivity : AppCompatActivity() {

    companion object {
        private const val PICK_IMAGE_REQUEST_CODE = 1234
        private const val WRITE_EXTERNAL_STORAGE_PERMISSION_REQUEST_CODE = 4321

        private const val INTENT_IMAGE_TYPE = "image/*"
        private const val CONTENT_SCHEME = "content"
    }

    private val outputUri: Uri? by lazy { intent.getImageCaptureOutputUri() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ImagePickerScreen()
        }

        // Remove animation from launcher, the default animation gives a weird effect in API 24+
        overridePendingTransition(0, 0)

        // Remove status tint; basically we're telling the activity's Window to expand above
        // the limits of the screen.
        // Check: http://stackoverflow.com/a/29311321
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        setupImagePicker()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        when (resultCode) {
            Activity.RESULT_OK -> {
                when (requestCode) {
                    PICK_IMAGE_REQUEST_CODE -> {
                        if (data != null) {
                            // Call cropping library
                            openImageCropper(data.data!!, outputUri!!)
                        } else {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        }
                    }

                    CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE -> {
                        val resultUri = CropImage.getActivityResult(data)?.uri

                        if (resultUri == null) {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        } else {
                            showBrightnessDialog(resultUri)
                        }
                    }
                }
            }
            CROP_IMAGE_ACTIVITY_RESULT_ERROR_CODE -> {
                Toast.makeText(this, R.string.crop_image_activity_result_error, Toast.LENGTH_LONG)
                    .show()
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
            else -> {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            WRITE_EXTERNAL_STORAGE_PERMISSION_REQUEST_CODE ->
                if (grantResults.getOrNull(0) == PackageManager.PERMISSION_GRANTED) {
                    showImagePickerChooser()
                }
        }
    }

    private fun setupImagePicker() {
        if (intent.isImageCaptureAction()) {
            if (outputUri != null) {
                if (requestWriteExternalStoragePermission()) {
                    showImagePickerChooser()
                }
            } else {
                // Output URI was not provided by the calling app, so exit.
                Toast.makeText(this, R.string.image_picker_invalid_uri_error, Toast.LENGTH_LONG).show()
                finish()
            }
        } else {
            // Not a valid intent, exit
            Toast.makeText(this, R.string.image_picker_invalid_intent_error, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun requestWriteExternalStoragePermission(): Boolean {
        // No need to request permissions if writing to a content:// Uri
        if (outputUri?.scheme == CONTENT_SCHEME) return true

        if (!isWriteExternalPermissionGranted()) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )) {
                AlertDialog.Builder(this)
                    .setMessage(R.string.image_picker_write_external_rationale_message)
                    .setPositiveButton(
                    R.string.image_picker_write_external_rationale_action_allow
                ) { _, _ ->
                        ActivityCompat.requestPermissions(
                            this@ImagePickerActivity,
                            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                            WRITE_EXTERNAL_STORAGE_PERMISSION_REQUEST_CODE
                        )
                    }
                    .show()

                return false
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    WRITE_EXTERNAL_STORAGE_PERMISSION_REQUEST_CODE
                )

                return false
            }
        } else {
            return true
        }
    }

    private fun showImagePickerChooser() {
        val intent = Intent().apply {
            type = INTENT_IMAGE_TYPE
            action = Intent.ACTION_GET_CONTENT
        }

        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent.createChooser(intent, getString(R.string.image_picker_chooser_title)),
            PICK_IMAGE_REQUEST_CODE
        )
    }

    private fun openImageCropper(imageUri: Uri, outputUri: Uri) {
        CropImage.activity(imageUri)
            // Set crop to full size by default
            .setInitialCropWindowPaddingRatio(0f)
            // Image quality
            .setOutputCompressFormat(ImageConstants.DEFAULT_COMPRESS_FORMAT)
            .setOutputCompressQuality(ImageConstants.DEFAULT_IMAGE_QUALITY)
            .setOutputUri(outputUri)
            .start(this)
    }


    /** Offer a simple brightness adjustment after cropping, before returning the image. */
    private fun showBrightnessDialog(uri: Uri) {
        val original = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: run {
            Toast.makeText(this, "Cannot load image", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        // Keep the original image visible while the slider changes. A color filter provides
        // a live preview without allocating a full-size bitmap for every slider movement.
        val preview = ImageView(this).apply {
            setImageBitmap(original)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (170 * resources.displayMetrics.density).toInt()
            )
        }
        val label = TextView(this).apply {
            text = "Brightness: 0"
            setPadding(0, 12, 0, 8)
        }
        val slider = SeekBar(this).apply {
            max = 200
            progress = 100
        }
        val mirrorCheck = CheckBox(this).apply {
            text = "Mirror ảnh (thử giữ chữ dễ đọc)"
            isChecked = false
            setPadding(0, 4, 0, 8)
            setOnCheckedChangeListener { _, checked ->
                preview.scaleX = if (checked) -1f else 1f
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 8)
            // Put the toggle first and reduce the preview height so the control is visible
            // on phones with shorter screens.
            addView(mirrorCheck)
            addView(preview)
            addView(label)
            addView(slider)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Adjust brightness")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply", null)
            .create()

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val amount = progress - 100
                label.text = "Brightness: $amount"
                if (amount == 0) {
                    preview.clearColorFilter()
                } else {
                    val offset = amount * 255f / 100f
                    val matrix = android.graphics.ColorMatrix(
                        floatArrayOf(
                            1f, 0f, 0f, 0f, offset,
                            0f, 1f, 0f, 0f, offset,
                            0f, 0f, 1f, 0f, offset,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                    preview.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val shouldMirror = mirrorCheck.isChecked
                val brightness = slider.progress - 100
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                if (shouldMirror) {
                    Toast.makeText(this, "Applying mirror and detecting text…", Toast.LENGTH_SHORT).show()
                }
                thread {
                    try {
                        if (shouldMirror) applyMirrorKeepingTextReadable(uri)
                        if (brightness != 0) applyBrightness(uri, brightness)
                        runOnUiThread {
                            setResult(Activity.RESULT_OK)
                            dialog.dismiss()
                            finish()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            Toast.makeText(
                                this,
                                "Could not process image: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                dialog.dismiss()
                finish()
            }
        }
        dialog.setOnCancelListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        dialog.setOnDismissListener {
            original.recycle()
        }
        dialog.show()
    }

    /**
     * Mirrors the image horizontally, then overlays detected text-line crops from the original
     * image at mirrored positions. This is a best-effort OCR correction: complex backgrounds,
     * handwriting, or missed text may still produce artifacts or reversed text.
     */
    private fun applyMirrorKeepingTextReadable(uri: Uri) {
        val original = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: throw IllegalStateException("Cannot decode image for mirror")

        val matrix = android.graphics.Matrix().apply { setScale(-1f, 1f) }
        val mirrored = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val image = InputImage.fromBitmap(original, 0)
            val result = Tasks.await(recognizer.process(image))
            val canvas = Canvas(mirrored)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val box = line.boundingBox ?: continue
                    val left = box.left.coerceIn(0, original.width)
                    val top = box.top.coerceIn(0, original.height)
                    val right = box.right.coerceIn(0, original.width)
                    val bottom = box.bottom.coerceIn(0, original.height)
                    if (right <= left || bottom <= top) continue
                    val src = Rect(left, top, right, bottom)
                    val dst = Rect(
                        (original.width - right).coerceIn(0, original.width),
                        top,
                        (original.width - left).coerceIn(0, original.width),
                        bottom
                    )
                    // Copy the full detected line region, not just glyphs, so the text is upright.
                    canvas.drawBitmap(original, src, dst, paint)
                }
            }
            contentResolver.openOutputStream(uri, "wt")?.use { output ->
                if (!mirrored.compress(ImageConstants.DEFAULT_COMPRESS_FORMAT, ImageConstants.DEFAULT_IMAGE_QUALITY, output)) {
                    throw IllegalStateException("Cannot save mirrored image")
                }
            } ?: throw IllegalStateException("Cannot open output image")
        } finally {
            recognizer.close()
            original.recycle()
            mirrored.recycle()
        }
    }

    private fun applyBrightness(uri: Uri, amount: Int) {
        if (amount == 0) return
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("Cannot decode cropped image")
        val adjusted = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val delta = amount * 255 / 100
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            pixels[i] = Color.argb(Color.alpha(c),
                (Color.red(c) + delta).coerceIn(0, 255),
                (Color.green(c) + delta).coerceIn(0, 255),
                (Color.blue(c) + delta).coerceIn(0, 255))
        }
        adjusted.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        contentResolver.openOutputStream(uri, "wt")?.use { output ->
            if (!adjusted.compress(ImageConstants.DEFAULT_COMPRESS_FORMAT, ImageConstants.DEFAULT_IMAGE_QUALITY, output)) {
                throw IllegalStateException("Cannot save adjusted image")
            }
        } ?: throw IllegalStateException("Cannot open output image")
        bitmap.recycle()
        adjusted.recycle()
    }

    private fun isWriteExternalPermissionGranted() =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
}
