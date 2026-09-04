package com.itantra.speech

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs

/**
 * Describes the run-time device's hardware capabilities and its estimated class.
 * Used by the model-selection engine to pick an appropriate model (lightweight
 * quantized for LOW, higher-quality for HIGH) without relying on brand/model names.
 */
enum class DeviceClass {
    LOW,      // under ~2GB RAM, few cores — use lightweight quantized models
    MID,      // 2-6GB RAM — balanced quality/size
    HIGH      // 6GB+ RAM, many cores — higher-quality models when latency allows
}

data class DeviceCapabilityProfile(
    val totalRamMb: Long,
    val cpuCoreCount: Int,
    val cpuArchitecture: String,   // e.g. "arm64-v8a", "armeabi-v7a"
    val androidSdk: Int,
    val availableStorageMb: Long,
    val deviceClass: DeviceClass
) {
    /** Whether the device can afford loading a model of the given size (MB) in RAM. */
    fun canAffordModel(modelMb: Int, budgetFraction: Float = 0.4f): Boolean {
        return totalRamMb * budgetFraction >= modelMb
    }
}

/**
 * Static profiling from real hardware characteristics. Does NOT use brand/model names
 * to classify (two "MID" phones from different vendors can differ — we use RAM/cores).
 */
object DeviceProfiler {

    fun profile(context: Context): DeviceCapabilityProfile {
        val ramMb = readRamMb(context)
        val cores = readCoreCount()
        val arch = readArch()
        val sdk = Build.VERSION.SDK_INT
        val storageMb = readAvailableStorageMb()
        val deviceClass = classify(ramMb, cores)

        return DeviceCapabilityProfile(
            totalRamMb = ramMb,
            cpuCoreCount = cores,
            cpuArchitecture = arch,
            androidSdk = sdk,
            availableStorageMb = storageMb,
            deviceClass = deviceClass
        )
    }

    private fun readRamMb(context: Context): Long {
        return try {
            val info = android.app.ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
                ?.getMemoryInfo(info)
            info?.totalMem?.div(1024 * 1024) ?: 2048L
        } catch (e: Exception) {
            2048L
        }
    }

    private fun readCoreCount(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

    private fun readArch(): String {
        val abis = Build.SUPPORTED_ABIS
        return if (abis.isNotEmpty()) abis[0] else Build.CPU_ABI
    }

    private fun readAvailableStorageMb(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            (stat.availableBytes / (1024 * 1024))
        } catch (e: Exception) {
            0L
        }
    }

    private fun classify(ramMb: Long, cores: Int): DeviceClass {
        return when {
            ramMb >= 6144 -> DeviceClass.HIGH
            ramMb >= 2048 -> DeviceClass.MID
            else -> DeviceClass.LOW
        }
    }
}
