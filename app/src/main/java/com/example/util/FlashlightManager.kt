package com.example.util

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FlashlightManager(context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    // カメラ情報の取得は重いため、最初の点滅時にバックグラウンドで一度だけ行う
    private val cameraIdWithFlash: String? by lazy { findCameraIdWithFlash() }

    private fun findCameraIdWithFlash(): String? {
        val manager = cameraManager ?: return null
        return try {
            val idsWithFlash = manager.cameraIdList.filter { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            idsWithFlash.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: idsWithFlash.firstOrNull()
        } catch (e: Exception) {
            Log.e("FlashlightManager", "Failed to initialize camera flash", e)
            null
        }
    }

    /**
     * Flashes flashlight `count` times with specified `delayMs`.
     */
    fun flash(count: Int = 6, delayMs: Long = 200L, scope: CoroutineScope) {
        val manager = cameraManager ?: return

        scope.launch(Dispatchers.IO) {
            val camId = cameraIdWithFlash ?: return@launch
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
            } finally {
                try {
                    manager.setTorchMode(camId, false)
                } catch (_: Exception) {}
            }
        }
    }
}
