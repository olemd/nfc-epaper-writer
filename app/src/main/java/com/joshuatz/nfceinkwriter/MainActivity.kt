package com.joshuatz.nfceinkwriter

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView

class MainActivity : AppCompatActivity() {
    private var mPreferencesController: Preferences? = null
    private var mHasReFlashableImage: Boolean = false
    private val mReFlashButton: CardView get() = findViewById(R.id.reflashButton)

    // Modern activity-result API for the image cropper (replaces the deprecated
    // startActivityForResult / onActivityResult flow).
    private val cropImage = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            val croppedBitmap = result.getBitmap(this)
            if (croppedBitmap != null) {
                // Resizing was requested via RESIZE_EXACT, so just persist and flash.
                openFileOutput(GeneratedImageFilename, Context.MODE_PRIVATE).use { fileOutStream ->
                    croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutStream)
                }
                startActivity(Intent(this, NfcFlasher::class.java))
            } else {
                Log.e("Crop image callback", "Crop image result not available")
            }
        } else {
            Log.e("Crop image callback", "Crop failed: ${result.error}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Register action bar / toolbar
        setSupportActionBar(findViewById(R.id.main_toolbar))

        // Get user preferences
        this.mPreferencesController = Preferences(this)
        this.updateScreenSizeDisplay(null)

        // Setup screen size changer
        val screenSizeChangeInvite: Button = findViewById(R.id.changeDisplaySizeInvite)
        screenSizeChangeInvite.setOnClickListener {
            this.mPreferencesController?.showScreenSizePicker(fun(updated: String): Void? {
                this.updateScreenSizeDisplay(updated)
                return null
            })
        }

        // Check for previously generated image, enable re-flash button if available
        checkReFlashAbility()

        mReFlashButton.setOnClickListener {
            if (mHasReFlashableImage) {
                val navIntent = Intent(this, NfcFlasher::class.java)
                startActivity(navIntent)
            } else {
                val toast = Toast.makeText(this, "There is no image to re-flash!", Toast.LENGTH_SHORT)
                toast.show()
            }
        }


        // Setup image file picker
        val imageFilePickerCTA: Button = findViewById(R.id.cta_pick_image_file)
        imageFilePickerCTA.setOnClickListener {
            val screenSizePixels = this.mPreferencesController?.getScreenSizePixels()!!

            cropImage.launch(
                CropImageContractOptions(
                    uri = null,
                    cropImageOptions = CropImageOptions(
                        guidelines = CropImageView.Guidelines.ON,
                        aspectRatioX = screenSizePixels.first,
                        aspectRatioY = screenSizePixels.second,
                        fixAspectRatio = true,
                        outputRequestWidth = screenSizePixels.first,
                        outputRequestHeight = screenSizePixels.second,
                        outputRequestSizeOptions = CropImageView.RequestSizeOptions.RESIZE_EXACT,
                    ),
                )
            )
        }

        // Dither toggle (persisted; applied by the IsoDep flasher)
        val ditherToggle: SwitchCompat = findViewById(R.id.ditherToggle)
        ditherToggle.isChecked = this.mPreferencesController?.getDitherEnabled() ?: true
        ditherToggle.setOnCheckedChangeListener { _, isChecked ->
            this.mPreferencesController?.setDitherEnabled(isChecked)
        }

        // Note: the WYSIWYG/graphic editor is disabled pending a bug fix; its
        // button is hidden in the layout.

        // Setup text button click
        val textEditButtonInvite: Button = findViewById(R.id.cta_new_text)
        textEditButtonInvite.setOnClickListener {
            val intent = Intent(this, TextEditor::class.java)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        checkReFlashAbility()
    }

    private fun updateScreenSizeDisplay(updated: String?) {
        var screenSizeStr = updated
        if (screenSizeStr == null) {
            screenSizeStr = this.mPreferencesController?.getPreferences()
                ?.getString(Constants.PreferenceKeys.DisplaySize, DefaultScreenSize)
        }
        findViewById<TextView>(R.id.currentDisplaySize).text = screenSizeStr ?: DefaultScreenSize
    }

    private fun checkReFlashAbility() {
        val lastGeneratedFile = getFileStreamPath(GeneratedImageFilename)
        val reFlashImagePreview: ImageView = findViewById(R.id.reflashButtonImage)
        if (lastGeneratedFile.exists()) {
            mHasReFlashableImage = true
            // Need to set null first, or else Android will cache previous image
            reFlashImagePreview.setImageURI(null)
            reFlashImagePreview.setImageURI(Uri.fromFile((lastGeneratedFile)))
        } else {
            // Grey out button
            mReFlashButton.setCardBackgroundColor(Color.DKGRAY)
            val drawableImg = AppCompatResources.getDrawable(this, android.R.drawable.stat_sys_warning)
            reFlashImagePreview.setImageDrawable(drawableImg)
        }
    }

}