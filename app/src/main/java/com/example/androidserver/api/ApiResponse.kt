package com.example.androidserver.api

import java.io.Serializable

/**
 * API 响应封装类
 */
data class ApiResponse<T>(
    val success: Boolean,
    val message: String? = null,
    val data: T? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val error: String? = null
) : Serializable {
    companion object {
        fun <T> success(data: T) = ApiResponse(
            success = true,
            data = data
        )
        
        fun <T> success(message: String, data: T) = ApiResponse(
            success = true,
            message = message,
            data = data
        )
        
        fun <T> error(message: String) = ApiResponse<T>(
            success = false,
            message = message
        )
        
        fun <T> error(message: String, error: String) = ApiResponse<T>(
            success = false,
            message = message,
            error = error
        )
    }
}
