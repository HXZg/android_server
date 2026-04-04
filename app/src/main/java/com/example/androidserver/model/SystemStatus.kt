package com.example.androidserver.model

/**
 * 系统状态模型
 */
data class SystemStatus(
    val memory: MemoryInfo? = null,
    val storage: StorageInfo? = null,
    val serverRunning: Boolean = false,
    val serverPort: Int = 0,
    val processId: Int = 0
) {
    data class MemoryInfo(
        val totalMemory: Long = 0,
        val freeMemory: Long = 0,
        val usedMemory: Long = 0,
        val maxMemory: Long = 0
    )

    data class StorageInfo(
        val totalSpace: Long = 0,
        val freeSpace: Long = 0,
        val usedSpace: Long = 0
    )
}
