package com.example.androidserver.util

import android.content.Context
import android.util.Log
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 日志工具类 - 通过 shell logcat 命令读取系统日志写入文件
 * 
 * 特性:
 * 1. 持续读取 logcat 写入文件
 * 2. 支持单文件大小限制，自动分割
 * 3. LRU 算法清理旧日志
 * 4. 支持最大存储大小限制
 */
class Logger private constructor(context: Context) {
    
    private val logDir: File = File(context.filesDir, "logs").apply { mkdirs() }
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val indexFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    
    private var logcatProcess: Process? = null
    private var logcatThread: Thread? = null
    private var isRunning = false
    
    private var currentLogFile: File? = null
    private var currentLogSize: AtomicLong = AtomicLong(0)
    private var logWriter: BufferedWriter? = null
    
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    
    // 配置参数
    private var config = Config()
    
    /**
     * 日志配置
     */
    data class Config(
        val maxFileSize: Long = 10 * 1024 * 1024,      // 单文件最大 10MB
        val maxTotalSize: Long = 100 * 1024 * 1024,    // 总存储最大 100MB
        val maxFileCount: Int = 50,                     // 最多 50 个文件
        val logLevel: String = "V",                     // 日志级别
        val tagFilter: String = ""                      // TAG 过滤
    )
    
    companion object {
        private const val TAG = "Logger"
        
        // 日志级别过滤 (可选: V/D/I/W/E/F)
        private const val DEFAULT_LOG_LEVEL = "V"
        
        @Volatile
        private var instance: Logger? = null
        
        fun getInstance(context: Context): Logger {
            return instance ?: synchronized(this) {
                instance ?: Logger(context.applicationContext).also { instance = it }
            }
        }
    }
    
    init {
        Log.i(TAG, "Logger initialized, logDir: ${logDir.absolutePath}")
    }
    
    /**
     * 配置日志参数
     */
    fun configure(
        maxFileSize: Long = 10 * 1024 * 1024,      // 10MB
        maxTotalSize: Long = 100 * 1024 * 1024,    // 100MB
        maxFileCount: Int = 50,
        logLevel: String = "V",
        tagFilter: String = ""
    ) {
        config = Config(
            maxFileSize = maxFileSize,
            maxTotalSize = maxTotalSize,
            maxFileCount = maxFileCount,
            logLevel = logLevel,
            tagFilter = tagFilter
        )
        Log.i(TAG, "Logger configured: maxFileSize=${maxFileSize/1024/1024}MB, maxTotalSize=${maxTotalSize/1024/1024}MB")
    }
    
    /**
     * 启动 logcat 日志采集
     */
    fun startLogcatCapture(
        level: String = config.logLevel,
        tagFilter: String = config.tagFilter
    ) {
        if (isRunning) {
            Log.w(TAG, "Logcat capture already running")
            return
        }
        
        try {
            // 构建 logcat 命令
            val command = buildList {
                add("logcat")
                add("-v")
                add("time")
                if (tagFilter.isNotEmpty()) {
                    add("-s")
                    add(tagFilter)
                } else if (level.isNotEmpty()) {
                    add("*:$level")
                }
            }
            
            Log.i(TAG, "Starting logcat: ${command.joinToString(" ")}")
            Log.i(TAG, "Config: maxFileSize=${config.maxFileSize/1024/1024}MB, maxTotalSize=${config.maxTotalSize/1024/1024}MB")
            
            // 先执行 LRU 清理
            performLRUCleanup()
            
            // 创建初始日志文件
            createNewLogFile()
            
            // 启动 logcat 进程
            logcatProcess = Runtime.getRuntime().exec(command.toTypedArray())
            isRunning = true
            
            // 启动读取线程
            logcatThread = Thread {
                try {
                    logcatProcess?.inputStream?.bufferedReader()?.use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null && isRunning) {
                            writeLogLine(line ?: "")
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Error reading logcat", e)
                    }
                }
            }.apply {
                name = "LogcatReader"
                start()
            }
            
            // 定期检查文件大小和 LRU 清理
            scheduler.scheduleWithFixedDelay({
                checkFileSize()
                performLRUCleanup()
            }, 10, 10, TimeUnit.SECONDS)
            
            Log.i(TAG, "Logcat capture started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start logcat capture", e)
            isRunning = false
        }
    }
    
    /**
     * 停止 logcat 日志采集
     */
    fun stopLogcatCapture() {
        isRunning = false
        
        try {
            logcatProcess?.destroy()
            logcatProcess = null
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying logcat process", e)
        }
        
        try {
            logcatThread?.interrupt()
            logcatThread = null
        } catch (e: Exception) {
            Log.e(TAG, "Error interrupting logcat thread", e)
        }
        
        try {
            logWriter?.close()
            logWriter = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing log writer", e)
        }
        
        scheduler.shutdown()
        
        Log.i(TAG, "Logcat capture stopped")
    }
    
    /**
     * 创建新的日志文件
     */
    private fun createNewLogFile() {
        try {
            // 关闭当前文件
            logWriter?.close()
            
            // 生成文件名: log_20260403_111952_001.txt
            val timestamp = indexFormat.format(Date())
            val index = getNextFileIndex(timestamp)
            val fileName = "log_${timestamp}_${index.toString().padStart(3, '0')}.txt"
            
            currentLogFile = File(logDir, fileName)
            currentLogSize.set(0)
            
            logWriter = BufferedWriter(FileWriter(currentLogFile, false))
            
            // 写入文件头
            logWriter?.write("=== Log Started at ${timestampFormat.format(Date())} ===")
            logWriter?.newLine()
            logWriter?.write("=== MaxFileSize: ${config.maxFileSize/1024/1024}MB, MaxTotalSize: ${config.maxTotalSize/1024/1024}MB ===")
            logWriter?.newLine()
            logWriter?.flush()
            
            Log.i(TAG, "Created new log file: ${currentLogFile?.name}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create new log file", e)
        }
    }
    
    /**
     * 获取下一个文件序号
     */
    private fun getNextFileIndex(timestamp: String): Int {
        val files = logDir.listFiles()
            ?.filter { it.name.startsWith("log_$timestamp") }
            ?.map { 
                // 从文件名提取序号: log_20260403_111952_001.txt
                it.name.substringAfterLast("_").substringBefore(".txt").toIntOrNull() ?: 0
            }
            ?.maxOrNull()
            ?: 0
        
        return files + 1
    }
    
    /**
     * 写入日志行到文件
     */
    private fun writeLogLine(line: String) {
        try {
            val lineBytes = line.toByteArray(Charsets.UTF_8).size.toLong()
            
            // 检查是否需要分割文件
            if (currentLogSize.get() + lineBytes > config.maxFileSize) {
                createNewLogFile()
            }
            
            logWriter?.write(line)
            logWriter?.newLine()
            logWriter?.flush()
            
            currentLogSize.addAndGet(lineBytes + 1)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error writing log line", e)
        }
    }
    
    /**
     * 检查当前文件大小
     */
    private fun checkFileSize() {
        if (currentLogSize.get() >= config.maxFileSize) {
            createNewLogFile()
        }
    }
    
    /**
     * LRU 清理旧日志
     * 基于文件最后修改时间，清理超出限制的旧文件
     */
    private fun performLRUCleanup() {
        try {
            val files = logDir.listFiles()
                ?.filter { it.isFile && (it.name.startsWith("log_") || it.name.startsWith("logcat_")) && it.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() } // 按修改时间降序 (最新的在前)
                ?: return
            
            var totalSize = files.sumOf { it.length() }
            var fileCount = files.size
            
            // 按修改时间从旧到新遍历，删除超出限制的文件
            val filesToDelete = mutableListOf<File>()
            
            for (file in files.reversed()) { // 从最旧的开始
                // 检查总大小限制
                if (totalSize > config.maxTotalSize) {
                    filesToDelete.add(file)
                    totalSize -= file.length()
                    fileCount--
                    continue
                }
                
                // 检查文件数量限制
                if (fileCount > config.maxFileCount) {
                    filesToDelete.add(file)
                    totalSize -= file.length()
                    fileCount--
                    continue
                }
                
                break
            }
            
            // 删除文件
            filesToDelete.forEach { file ->
                if (file.delete()) {
                    Log.d(TAG, "LRU deleted: ${file.name}")
                }
            }
            
            if (filesToDelete.isNotEmpty()) {
                Log.i(TAG, "LRU cleanup: deleted ${filesToDelete.size} files, current total: ${totalSize/1024/1024}MB")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during LRU cleanup", e)
        }
    }
    
    /**
     * 手动写入日志
     */
    fun log(level: String, tag: String, message: String) {
        val priority = when (level.uppercase()) {
            "VERBOSE", "V" -> Log.VERBOSE
            "DEBUG", "D" -> Log.DEBUG
            "INFO", "I" -> Log.INFO
            "WARN", "W" -> Log.WARN
            "ERROR", "E" -> Log.ERROR
            "FATAL", "F" -> Log.ERROR
            else -> Log.INFO
        }
        
        // 只写系统日志，由 startLogcatCapture 统一读logcat写入文件
        Log.println(priority, tag, message)
    }
    
    fun log(level: String, tag: String, message: String, throwable: Throwable) {
        log(level, tag, "$message\n${Log.getStackTraceString(throwable)}")
    }
    
    // 便捷方法
    fun v(tag: String, message: String) = log("V", tag, message)
    fun d(tag: String, message: String) = log("D", tag, message)
    fun i(tag: String, message: String) = log("I", tag, message)
    fun w(tag: String, message: String) = log("W", tag, message)
    fun e(tag: String, message: String) = log("E", tag, message)
    fun e(tag: String, message: String, throwable: Throwable) = log("E", tag, message, throwable)
    
    /**
     * 获取日志目录
     */
    fun getLogDir(): File = logDir
    
    /**
     * 获取当前日志文件
     */
    fun getCurrentLogFile(): File? = currentLogFile
    
    /**
     * 获取日志统计信息
     */
    fun getLogStats(): LogStats {
        val files = logDir.listFiles()
            ?.filter { it.isFile && (it.name.startsWith("log_") || it.name.startsWith("logcat_")) && it.name.endsWith(".txt") }
            ?: emptyList()
        
        return LogStats(
            fileCount = files.size,
            totalSize = files.sumOf { it.length() },
            maxFileSize = config.maxFileSize,
            maxTotalSize = config.maxTotalSize,
            maxFileCount = config.maxFileCount,
            currentFileSize = currentLogSize.get()
        )
    }
    
    data class LogStats(
        val fileCount: Int,
        val totalSize: Long,
        val maxFileSize: Long,
        val maxTotalSize: Long,
        val maxFileCount: Int,
        val currentFileSize: Long
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "fileCount" to fileCount,
            "totalSize" to totalSize,
            "totalSizeMB" to totalSize / 1024.0 / 1024.0,
            "maxFileSize" to maxFileSize,
            "maxFileSizeMB" to maxFileSize / 1024.0 / 1024.0,
            "maxTotalSize" to maxTotalSize,
            "maxTotalSizeMB" to maxTotalSize / 1024.0 / 1024.0,
            "maxFileCount" to maxFileCount,
            "currentFileSize" to currentFileSize,
            "currentFileSizeMB" to currentFileSize / 1024.0 / 1024.0
        )
    }
    
    /**
     * 清理旧日志 (保留指定天数)
     */
    fun cleanOldLogs(keepDays: Int) {
        val cutoffTime = System.currentTimeMillis() - (keepDays * 24L * 60 * 60 * 1000)
        
        logDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoffTime) {
                if (file.delete()) {
                    Log.d(TAG, "Deleted old log: ${file.name}")
                }
            }
        }
    }
    
    /**
     * 清理所有日志
     */
    fun clearAllLogs() {
        logDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.delete()
            }
        }
        currentLogFile = null
        currentLogSize.set(0)
        createNewLogFile()
        Log.i(TAG, "All logs cleared")
    }
    
    /**
     * 导出 logcat 快照
     */
    fun exportLogcatSnapshot(outputFile: File, lines: Int = 1000): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", lines.toString(), "-v", "time")
            )
            
            process.inputStream.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logcat snapshot", e)
            false
        }
    }
    
    /**
     * 获取日志文件列表
     */
    fun getLogFiles(): List<File> {
        return logDir.listFiles()
            ?.filter { it.isFile && (it.name.startsWith("log_") || it.name.startsWith("logcat_")) && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
    
    /**
     * 读取日志文件尾部
     */
    fun tailLogFile(filename: String, lines: Int = 100): String {
        val file = File(logDir, filename)
        if (!file.exists()) return "File not found: $filename"
        
        return try {
            file.readLines().takeLast(lines).joinToString("\n")
        } catch (e: Exception) {
            "Error reading file: ${e.message}"
        }
    }
}
