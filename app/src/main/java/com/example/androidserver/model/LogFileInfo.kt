package com.example.androidserver.model

import java.io.Serializable

/**
 * 日志文件信息模型
 */
data class LogFileInfo(
    val name: String? = null,
    val size: Long = 0,
    val lastModified: Long = 0,
    val path: String? = null
) : Serializable
