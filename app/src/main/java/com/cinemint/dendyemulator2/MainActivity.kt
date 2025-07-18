package com.cinemint.dendyemulator2

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.RequiresApi
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.Variable
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var retroView: GLRetroView
    private val coreFileName = "mgba.so"
    private val romFileName = "starlight.gba"

    @SuppressLint("SetWorldReadable")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // Copy ROM from resources to internal storage
            val romFile = File(filesDir, romFileName)
            resources.openRawResource(R.raw.starlight).use { input ->
                romFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // Set proper permissions
            romFile.setReadable(true, false)

            // Validate ROM file
            if (!romFile.exists() || !romFile.canRead()) {
                throw IllegalStateException("ROM file is missing or unreadable: ${romFile.absolutePath}")
            }
            if (romFile.length() == 0L) {
                throw IllegalStateException("ROM file is empty")
            }

            // Load core file
            val coreFile = File(filesDir, coreFileName)
            resources.openRawResource(R.raw.mgba).use { input ->
                coreFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // Set proper permissions
            coreFile.setReadable(true, false)
            coreFile.setExecutable(true, false)

            // Validate core file
            if (!coreFile.exists() || !coreFile.canRead()) {
                throw IllegalStateException("Core file is missing or unreadable: ${coreFile.absolutePath}")
            }
            if (coreFile.length() == 0L) {
                throw IllegalStateException("Core file is empty")
            }

            // Ensure core is executable (important for native libraries)
            if (!coreFile.canExecute()) {
                android.util.Log.d("MainActivity", "Making core file executable...")
                coreFile.setExecutable(true, false)
            }

            // Log detailed file information
            android.util.Log.d("MainActivity", "=== File Debug Info ===")
            android.util.Log.d("MainActivity", "ROM path: ${romFile.canonicalPath}")
            android.util.Log.d("MainActivity", "ROM exists: ${romFile.exists()}")
            android.util.Log.d("MainActivity", "ROM readable: ${romFile.canRead()}")
            android.util.Log.d("MainActivity", "ROM size: ${romFile.length()} bytes")
            android.util.Log.d("MainActivity", "Core path: ${coreFile.canonicalPath}")
            android.util.Log.d("MainActivity", "Core exists: ${coreFile.exists()}")
            android.util.Log.d("MainActivity", "Core readable: ${coreFile.canRead()}")
            android.util.Log.d("MainActivity", "Core executable: ${coreFile.canExecute()}")
            android.util.Log.d("MainActivity", "Core size: ${coreFile.length()} bytes")
            android.util.Log.d("MainActivity", "Files dir: ${filesDir.absolutePath}")
            android.util.Log.d("MainActivity", "ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")

            // Try to read first few bytes of each file to verify they're valid
            try {
                val romHeader = romFile.inputStream().use { it.readNBytes(4) }
                android.util.Log.d("MainActivity", "ROM header: ${romHeader.joinToString { "%02X".format(it) }}")

                try {
                    val coreHeader = coreFile.inputStream().use { it.readNBytes(64) } // Read more bytes
                    android.util.Log.d("MainActivity", "Full ELF header: ${coreHeader.take(16).joinToString { "%02X".format(it) }}")
                    android.util.Log.d("MainActivity", "File size on disk: ${coreFile.length()}")

                    // Verify the magic bytes
                    val elfMagic = coreHeader.sliceArray(0..3)
                    val expectedElf = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)
                    android.util.Log.d("MainActivity", "ELF Magic: ${elfMagic.joinToString { "%02X".format(it) }}")
                    android.util.Log.d("MainActivity", "Expected:  ${expectedElf.joinToString { "%02X".format(it) }}")

                    if (elfMagic.contentEquals(expectedElf)) {
                        val elfClass = coreHeader[4]
                        val elfData = coreHeader[5]
                        val elfVersion = coreHeader[6]
                        val elfOsAbi = coreHeader[7]

                        android.util.Log.d("MainActivity", "ELF class: $elfClass (1=32bit, 2=64bit)")
                        android.util.Log.d("MainActivity", "ELF data: $elfData (1=little endian, 2=big endian)")
                        android.util.Log.d("MainActivity", "ELF version: $elfVersion")
                        android.util.Log.d("MainActivity", "ELF OS/ABI: $elfOsAbi")

                        // Check machine type (bytes 18-19)
                        if (coreHeader.size >= 20) {
                            val machine = (coreHeader[19].toInt() and 0xFF shl 8) or (coreHeader[18].toInt() and 0xFF)
                            android.util.Log.d("MainActivity", "ELF machine type: $machine (183=ARM64, 40=ARM32)")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error reading detailed ELF info", e)
                }

            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error reading file headers", e)
            }

            android.util.Log.d("MainActivity", "Creating GLRetroViewData...")

            // Create GLRetroViewData with canonical paths
            val retroViewData = GLRetroViewData(this).apply {
                android.util.Log.d("MainActivity", "Setting coreFilePath...")
                coreFilePath = coreFile.canonicalPath
                android.util.Log.d("MainActivity", "Setting gameFilePath...")
                gameFilePath = romFile.canonicalPath
                android.util.Log.d("MainActivity", "Setting shader...")
                shader = GLRetroView.SHADER_DEFAULT
                android.util.Log.d("MainActivity", "Setting variables...")
                variables = arrayOf(
                    Variable("mgba_use_bios", "OFF"),         // Already set, but included for completeness
                    Variable("mgba_gb_model", "Autodetect"),  // Game Boy model detection
                    Variable("mgba_skip_bios", "OFF"),        // Skip BIOS intro if applicable
                    Variable("mgba_frameskip", "0"),          // Disable frame skipping
                    Variable("mgba_audio_low_pass_filter", "disabled")  // Audio setting example
                )
                android.util.Log.d("MainActivity", "GLRetroViewData created successfully")
            }

            // Create GLRetroView on UI thread
            runOnUiThread {
                try {
                    android.util.Log.d("MainActivity", "Creating GLRetroView...")
                    // Create GLRetroView
                    retroView = GLRetroView(this, retroViewData)
                    android.util.Log.d("MainActivity", "GLRetroView created, setting audio...")

                    // Set audio enabled
                    retroView.audioEnabled = true
                    android.util.Log.d("MainActivity", "Audio enabled, setting content view...")

                    // Add GLRetroView to the layout
                    setContentView(retroView)
                    android.util.Log.d("MainActivity", "Content view set successfully")

                    // Add a lifecycle listener to catch initialization issues
                    retroView.viewTreeObserver.addOnGlobalLayoutListener {
                        android.util.Log.d("MainActivity", "GLRetroView layout complete")
                    }

                    // Post a delayed check to see if we're still running
                    retroView.postDelayed({
                        android.util.Log.d("MainActivity", "GLRetroView still running after 500ms")
                        try {
                            android.util.Log.d("MainActivity", "Attempting to load game...")
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Error loading game", e)
                        }
                    }, 500)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error creating GLRetroView", e)
                    e.printStackTrace()
                    throw e
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("MainActivity", "Fatal error in onCreate", e)
            // You might want to show an error dialog to the user
            finish()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        event?.let {
            if (::retroView.isInitialized && handleControllerInput(keyCode, KeyEvent.ACTION_DOWN)) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        event?.let {
            if (::retroView.isInitialized && handleControllerInput(keyCode, KeyEvent.ACTION_UP)) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        event?.let {
            if (::retroView.isInitialized &&
                it.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK &&
                it.action == MotionEvent.ACTION_MOVE
            ) {
                handleAnalogInput(it)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun handleControllerInput(keyCode: Int, action: Int): Boolean {
        // Using numeric constants as GLRetroView.BUTTON_* constants might not be available
        val retroButton = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> 1  // A button
            KeyEvent.KEYCODE_BUTTON_B -> 0  // B button
            KeyEvent.KEYCODE_BUTTON_X -> 3  // X button
            KeyEvent.KEYCODE_BUTTON_Y -> 2  // Y button
            KeyEvent.KEYCODE_BUTTON_L1 -> 10 // L button
            KeyEvent.KEYCODE_BUTTON_R1 -> 11 // R button
            KeyEvent.KEYCODE_BUTTON_L2 -> 12 // L2 button
            KeyEvent.KEYCODE_BUTTON_R2 -> 13 // R2 button
            KeyEvent.KEYCODE_BUTTON_START -> 8  // Start button
            KeyEvent.KEYCODE_BUTTON_SELECT -> 9 // Select button
            KeyEvent.KEYCODE_DPAD_UP -> 4    // D-pad Up
            KeyEvent.KEYCODE_DPAD_DOWN -> 5  // D-pad Down
            KeyEvent.KEYCODE_DPAD_LEFT -> 6  // D-pad Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> 7 // D-pad Right
            else -> return false
        }

        // Send the key event to GLRetroView
        retroView.sendKeyEvent(action, retroButton)
        return true
    }

    private fun handleAnalogInput(event: MotionEvent) {
        // Left analog stick
        val leftX = event.getAxisValue(MotionEvent.AXIS_X)
        val leftY = event.getAxisValue(MotionEvent.AXIS_Y)
        retroView.sendMotionEvent(
            0, // MOTION_SOURCE_ANALOG_LEFT
            leftX,
            leftY
        )

        // Right analog stick
        val rightX = event.getAxisValue(MotionEvent.AXIS_Z)
        val rightY = event.getAxisValue(MotionEvent.AXIS_RZ)
        retroView.sendMotionEvent(
            1, // MOTION_SOURCE_ANALOG_RIGHT
            rightX,
            rightY
        )

        // D-pad as analog (some controllers report D-pad as hat axis)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (hatX != 0f || hatY != 0f) {
            retroView.sendMotionEvent(
                2, // MOTION_SOURCE_DPAD
                hatX,
                hatY
            )
        }
    }

    override fun onStart() {
        super.onStart()
        android.util.Log.d("MainActivity", "onStart called")
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.d("MainActivity", "onStop called")
    }

    override fun onDestroy() {
        android.util.Log.d("MainActivity", "onDestroy called")
        super.onDestroy()
        if (::retroView.isInitialized) {
            try {
                retroView.onDestroy()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error destroying retroView", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::retroView.isInitialized) {
            retroView.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::retroView.isInitialized) {
            retroView.onResume()
        }
    }
}