package com.example.util

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FlashlightManager(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private var cameraIdWithFlash: String? = null

    init {
        try {
            cameraManager?.cameraIdList?.forEach { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraIdWithFlash = id
                    return@forEach
                }
            }
            if (cameraIdWithFlash == null && cameraManager?.cameraIdList?.isNotEmpty() == true) {
                cameraIdWithFlash = cameraManager.cameraIdList[0]
            }
        } catch (e: Exception) {
            Log.e("FlashlightManager", "Failed to initialize camera flash", e)
        }
    }

    /**
     * Flashes flashlight `count` times with specified `delayMs`.
     */
    fun flash(count: Int = 6, delayMs: Long = 200L, scope: CoroutineScope) {
        val camId = cameraIdWithFlash ?: return
        val manager = cameraManager ?: return

        scope.launch(Dispatchers.IO) {
            try {
                repeat(count) {
                    try {
                        manager.setTorchMode(camId, true)
                    } catch (e: Exception) {
                        Log.e("FlashlightManager", "Error turning torch ON", e)
                    }
                    delay(delayMs)
                    try {
                        manager.setTorchMode(camId, false)
                    } catch (e: Exception) {
                        Log.e("FlashlightManager", "Error turning torch OFF", e)
                    }
                    delay(delayMs)
                }
            } catch (e: Exception) {
                Log.e("FlashlightManager", "Error during flashing loop", e)
            } finally {
                try {
                    manager.setTorchMode(camId, false)
                } catch (_: Exception) {}
            }
        }
    }
}
