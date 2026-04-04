package com.example.androidserver.model

import java.io.Serializable

/**
 * 设备信息模型
 */
data class DeviceInfo(
    val brand: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val device: String? = null,
    val product: String? = null,
    val androidVersion: String? = null,
    val sdkVersion: Int = 0,
    val androidId: String? = null,
    val wifiIp: String? = null,
    val macAddress: String? = null,
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Int = 0,
    val currentTime: Long = System.currentTimeMillis(),
    val serverUrl: String? = null
) : Serializable
