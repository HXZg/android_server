package com.example.androidserver.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.CORS
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.delete
import io.ktor.server.routing.routing
import io.ktor.server.routing.route

import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.net.NetworkInterface
import java.util.*
import java.util.concurrent.TimeUnit
import com.example.androidserver.util.Logger
import com.example.androidserver.RestartReceiver
import io.ktor.http.HttpHeaders
import io.ktor.server.engine.stop

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.streamProvider
import io.ktor.serialization.gson.GsonConverter
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.request.receive
import io.ktor.server.response.header
import java.text.SimpleDateFormat

/**
 * Ktor Server Service - 运行在独立进程中
 * 提供远程管理 API
 *
 * 支持大文件上传，使用流式处理避免内存溢出
 */
class KtorServerService : Service() {

    companion object {
        private const val TAG = "KtorServerService"
        private const val DEFAULT_PORT = 8080
        private const val CHANNEL_ID = "ktor_server_channel"
        private const val NOTIFICATION_ID = 1001

        // 大文件上传配置
        private const val BUFFER_SIZE = 64 * 1024 // 64KB buffer
        private const val MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024 // 2GB max

        // 鉴权配置
        private const val TOKEN_EXPIRE_MS = 24 * 60 * 60 * 1000L // 24小时
        private const val AUTH_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
    }

    // 鉴权状态
    private var authToken: String? = null
    private var tokenExpireTime: Long = 0
    private var authUsername: String = "admin"
    private var authPasswordHash: String = "" // SHA-256 hash

    private var server: ApplicationEngine? = null
    private var port = DEFAULT_PORT
    private var isRunning = false

    // 沙箱目录
    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var updatesDir: File
    private lateinit var logsDir: File
    private lateinit var uploadsDir: File
    private lateinit var tempDir: File  // 临时文件目录

    // 日志工具
    private lateinit var logger: Logger

    // 服务器配置
    private lateinit var config: ServerConfig
    private var startTime: Long = 0

    override fun onCreate() {
        super.onCreate()

        // 加载服务器配置
        config = loadServerConfig()
        port = config.port
        startTime = System.currentTimeMillis()

        // 初始化沙箱目录
        initSandboxDirectories()

        // 初始化日志工具并启动 logcat 捕获
        logger = Logger.getInstance(applicationContext)
        logger.configure(
            maxFileSize = config.maxFileSize,
            maxTotalSize = config.maxTotalSize,
            maxFileCount = config.maxFileCount,
            logLevel = config.logLevel,
            tagFilter = config.tagFilter
        )
        logger.startLogcatCapture()
        logger.i(TAG, "Service onCreate, PID: ${Process.myPid()}")

        // 加载鉴权配置（在 logger 初始化之后）
        loadAuthConfig()

        // 创建前台服务通知
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        Log.i(TAG, "FilesDir: ${filesDir.absolutePath}")
        Log.i(TAG, "UpdatesDir: ${updatesDir.absolutePath}")
    }

    private fun initSandboxDirectories() {
        filesDir = getFilesDir()
        cacheDir = getCacheDir()

        // APK 更新目录
        updatesDir = File(filesDir, "updates").apply { mkdirs() }
        // 日志目录
        logsDir = File(filesDir, "logs").apply { mkdirs() }
        // 上传文件目录
        uploadsDir = File(filesDir, "uploads").apply { mkdirs() }
        // 临时文件目录
        tempDir = File(cacheDir, "temp").apply { mkdirs() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 从 Intent 接收鉴权信息（由主进程传递）
        if (intent?.hasExtra("auth_username") == true) {
            authUsername = intent.getStringExtra("auth_username") ?: "admin"
        }
        if (intent?.hasExtra("auth_password") == true) {
            val password = intent.getStringExtra("auth_password") ?: "admin"
            authPasswordHash = sha256Hash(password)
            logger.i(TAG, "Auth credentials received from main process")
        }

        // 只在 Intent 明确携带 port 时才覆盖（避免 MainActivity 硬编码覆盖已保存的配置）
        if (intent?.hasExtra("port") == true) {
            intent.getIntExtra("port", DEFAULT_PORT).let { port = it }
        }

        if (!isRunning) {
            startServer()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        logger.i(TAG, "Service onDestroy")
        logger.stopLogcatCapture()
        stopServer()
        // 确保独立进程被杀掉，端口一定释放
        stopForeground(STOP_FOREGROUND_REMOVE)
        Process.killProcess(Process.myPid())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ktor Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Android Server background service"
            }

            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android Server")
            .setContentText("Server running on port $port")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    private fun startServer() {
        try {
            logger.i(TAG, "Starting Ktor server on port $port")

            server = embeddedServer(Netty, port) {
                install(DefaultHeaders)

                install(CORS) {
                    anyHost()
                    allowHeader(HttpHeaders.ContentType)
                    allowHeader(HttpHeaders.Accept)
                    allowHeader(HttpHeaders.Authorization) // 允许 Authorization header
                }

                install(ContentNegotiation) {
                    gson {
                        serializeNulls()
                        disableHtmlEscaping()
                    }
                    register(ContentType.Application.Json, GsonConverter())
                }

                configureRoutes()
            }

            Thread {
                try {
                    server?.start()
                    isRunning = true
                    logger.i(TAG, "Ktor server started successfully on port $port")
                    logger.i(TAG, "Web UI: http://${wifiIpAddress}:$port")
                    // 通知主进程服务已启动
                    sendBroadcast(Intent(RestartReceiver.ACTION_SERVER_STARTED).apply {
                        putExtra(RestartReceiver.EXTRA_PORT, port)
                    })
                } catch (e: Exception) {
                    logger.e(TAG, "Failed to start Ktor server", e)
                }
            }.start()

        } catch (e: Exception) {
            logger.e(TAG, "Error starting server", e)
        }
    }

    private fun stopServer() {
        server?.let {
            logger.i(TAG, "Stopping Ktor server")
            it.stop(1000, 5000, TimeUnit.MILLISECONDS)
            isRunning = false
            logger.i(TAG, "Ktor server stopped")
            // 通知主进程服务已停止
            sendBroadcast(Intent(RestartReceiver.ACTION_SERVER_STOPPED))
        }
    }

    private fun Application.configureRoutes() {
        routing {
            // ========== 登录接口（无需鉴权） ==========
            
            post("/api/login") {
                try {
                    val params = call.receive<Map<String, String>>()
                    val username = params["username"] ?: ""
                    val password = params["password"] ?: ""

                    if (validateLogin(username, password)) {
                        val token = generateToken()
                        call.respond(mapOf(
                            "status" to "ok",
                            "message" to "Login successful",
                            "token" to token,
                            "expiresIn" to TOKEN_EXPIRE_MS
                        ))
                    } else {
                        call.respond(HttpStatusCode.Unauthorized, mapOf(
                            "status" to "error",
                            "message" to "Invalid username or password"
                        ))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, errorResponse("Invalid request"))
                }
            }

            // 登出
            post("/api/logout") {
                authToken = null
                tokenExpireTime = 0
                call.respond(mapOf(
                    "status" to "ok",
                    "message" to "Logged out"
                ))
            }

            // 检查登录状态
            get("/api/auth/check") {
                val token = call.request.headers[AUTH_HEADER]?.removePrefix(BEARER_PREFIX)
                val isValid = token != null && validateToken(token)
                call.respond(mapOf(
                    "status" to if (isValid) "ok" else "unauthorized",
                    "authenticated" to isValid,
                    "username" to if (isValid) authUsername else null
                ))
            }

            // ========== 鉴权拦截器 ==========
            // 对 /api/* 路由进行鉴权（排除登录相关接口）
            route("/api") {
                intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
                    val path = call.request.local.uri
                    
                    // 跳过登录相关接口
                    if (path == "/api/login" || 
                        path == "/api/logout" || 
                        path == "/api/auth/check") {
                        return@intercept
                    }

                    // 验证 token
                    val token = call.request.headers[AUTH_HEADER]?.removePrefix(BEARER_PREFIX)
                    if (token == null || !validateToken(token)) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf(
                            "status" to "error",
                            "message" to "Unauthorized",
                            "code" to 401
                        ))
                        finish()
                    }
                }
            }

            // 静态资源 - Web UI (从 assets 目录提供，无需鉴权)
            get("/") {
                call.respondText(getAssetContent("index.html"), ContentType.Text.Html)
            }

            get("/index.html") {
                call.respondText(getAssetContent("index.html"), ContentType.Text.Html)
            }

            get("/style.css") {
                call.respondText(getAssetContent("style.css"), ContentType.Text.CSS)
            }

            get("/app.js") {
                call.respondText(getAssetContent("app.js"), ContentType.Application.JavaScript)
            }

            // ========== 健康检查 ==========
            get("/api") {
                call.respond(mapOf(
                    "status" to "ok",
                    "message" to "Android Server API",
                    "version" to versionName,
                    "port" to port,
                    "pid" to Process.myPid(),
                    "serverUrl" to "http://${wifiIpAddress}:$port",
                    "webUI" to "/"
                ))
            }

            // ========== 设备管理 ==========
            get("/api/device/info") {
                call.respond(getDeviceInfo())
            }

            // 设置设备时间
            post("/api/device/time") {
                val params = call.receive<Map<String, Any?>>()
                val timestamp = (params["timestamp"] as? Number)?.toLong()
                val timezone = params["timezone"] as? String

                val result = setDeviceTime(timestamp, timezone)
                call.respond(result)
            }

            post("/api/device/reboot") {
                call.respond(mapOf(
                    "status" to "ok",
                    "message" to "Device reboot initiated"
                ))
                Thread {
                    Thread.sleep(1000)
                    rebootDevice()
                }.start()
            }

            // ========== 应用管理 ==========
            post("/api/app/restart") {
                call.respond(mapOf(
                    "status" to "ok",
                    "message" to "App restart initiated"
                ))
                Thread {
                    Thread.sleep(500)
                    restartApp()
                }.start()
            }

            // APK 上传并安装 (支持大文件)
            post("/api/app/update/upload") {
                call.handleApkUpload()
            }

            // ========== 服务器配置 ==========

            // 获取服务器配置
            get("/api/config") {
                call.respond(getServerConfig())
            }

            // 修改端口 (需要重启服务)
            post("/api/config/port") {
                val params = call.receive<Map<String, Any?>>()
                val newPort = (params["port"] as? Number)?.toInt()

                if (newPort != null && newPort in 1024..65535) {
                    val needRestart = newPort != port
                    val actualNewPort = newPort

                    // 先保存配置
                    saveServerConfig(actualNewPort, config.maxFileSize, config.maxTotalSize, config.maxFileCount)

                    // 先返回响应，让浏览器收到后再重启
                    call.respond(mapOf(
                        "status" to "ok",
                        "message" to if (needRestart) "Port updated, redirecting..." else "Port unchanged",
                        "newPort" to actualNewPort,
                        "requiresRestart" to needRestart
                    ))

                    // 端口变更时，异步重启服务器
                    if (needRestart) {
                        Thread {
                            Thread.sleep(500) // 等响应发送完毕
                            stopServer()
                            port = actualNewPort
                            config = config.copy(port = actualNewPort)
                            startServer()
                            logger.i(TAG, "Server restarted on port $port")
                        }.start()
                    }
                } else {
                    call.respond(HttpStatusCode.BadRequest, errorResponse("Invalid port (1024-65535)"))
                }
            }

            // 修改日志配置
            post("/api/config/logs") {
                val params = call.receive<Map<String, Any?>>()

                val maxFileSize = (params["maxFileSize"] as? Number)?.toLong() ?: config.maxFileSize
                val maxTotalSize = (params["maxTotalSize"] as? Number)?.toLong() ?: config.maxTotalSize
                val maxFileCount = (params["maxFileCount"] as? Number)?.toInt() ?: config.maxFileCount

                // 更新日志配置
                logger.configure(
                    maxFileSize = maxFileSize,
                    maxTotalSize = maxTotalSize,
                    maxFileCount = maxFileCount,
                    logLevel = config.logLevel,
                    tagFilter = config.tagFilter
                )

                // 同步更新内存中的 config
                config = config.copy(maxFileSize = maxFileSize, maxTotalSize = maxTotalSize, maxFileCount = maxFileCount)

                // 保存配置
                saveServerConfig(port, maxFileSize, maxTotalSize, maxFileCount)

                call.respond(mapOf(
                    "status" to "ok",
                    "message" to "Log config updated",
                    "config" to mapOf(
                        "maxFileSize" to maxFileSize,
                        "maxTotalSize" to maxTotalSize,
                        "maxFileCount" to maxFileCount
                    )
                ))
            }

            // 重启服务器
            post("/api/config/restart") {
                call.respond(mapOf(
                    "status" to "ok",
                    "message" to "Server restarting..."
                ))
                Thread {
                    Thread.sleep(500)
                    restartServer()
                }.start()
            }

            // 发送自定义 JSON 配置到主进程
            post("/api/config/custom") {
                val json = call.receiveText()
                
                sendBroadcast(Intent(RestartReceiver.ACTION_CONFIG_UPDATE).apply {
                    putExtra(RestartReceiver.EXTRA_CONFIG_JSON, json)
                })
                
                call.respond(mapOf(
                    "status" to "ok",
                    "message" to "Config sent to app"
                ))
            }

            // ========== 文件上传 (支持大文件) ==========

            post("/api/files/upload") {
                call.handleFileUpload()
            }

            get("/api/files") {
                call.respond(getUploadedFiles())
            }

            get("/api/files/{filename}") {
                val filename = call.parameters["filename"]?.let { sanitizeFilename(it) }
                if (filename == null || filename.contains("..")) {
                    call.respond(HttpStatusCode.BadRequest, errorResponse("Invalid filename"))
                    return@get
                }
                val file = File(uploadsDir, filename)

                if (file.exists() && file.isFile) {
                    call.response.header("Content-Disposition", "attachment; filename=\"$filename\"")
                    call.respond(object : OutgoingContent.WriteChannelContent() {
                        override val contentLength = file.length()
                        override val contentType = ContentType.Application.OctetStream
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            file.inputStream().use { input ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    channel.writeFully(buffer, 0, bytesRead)
                                }
                            }
                        }
                    })
                } else {
                    call.respond(HttpStatusCode.NotFound, errorResponse("File not found: $filename"))
                }
            }

            delete("/api/files/{filename}") {
                val filename = call.parameters["filename"]?.let { sanitizeFilename(it) }
                if (filename == null || filename.contains("..")) {
                    call.respond(HttpStatusCode.BadRequest, errorResponse("Invalid filename"))
                    return@delete
                }
                val file = File(uploadsDir, filename)

                val response = if (file.exists() && file.delete()) {
                    mapOf("status" to "ok", "message" to "File deleted")
                } else {
                    mapOf("status" to "error", "message" to "Failed to delete file")
                }
                call.respond(response)
            }

            // ========== 日志管理 ==========
            get("/api/logs") {
                call.respond(getLogFiles())
            }

            get("/api/logs/{filename}") {
                val filename = call.parameters["filename"]?.let { sanitizeFilename(it) }
                if (filename == null || filename.contains("..")) {
                    call.respond(HttpStatusCode.BadRequest, errorResponse("Invalid filename"))
                    return@get
                }
                val logFile = File(logger.getLogDir(), filename)

                if (logFile.exists() && logFile.isFile) {
                    call.response.header("Content-Disposition", "attachment; filename=\"$filename\"")
                    call.respond(object : OutgoingContent.WriteChannelContent() {
                        override val contentLength = logFile.length()
                        override val contentType = ContentType.Text.Plain
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            logFile.inputStream().use { input ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    channel.writeFully(buffer, 0, bytesRead)
                                }
                            }
                        }
                    })
                } else {
                    call.respond(HttpStatusCode.NotFound, errorResponse("Log file not found: $filename"))
                }
            }

            delete("/api/logs/{filename}") {
                val filename = call.parameters["filename"]?.let { sanitizeFilename(it) }
                if (filename == null || filename.contains("..")) {
                    call.respond(HttpStatusCode.BadRequest, errorResponse("Invalid filename"))
                    return@delete
                }
                val logFile = File(logger.getLogDir(), filename)

                val response = if (logFile.exists() && logFile.delete()) {
                    mapOf("status" to "ok", "message" to "Log file deleted")
                } else {
                    mapOf("status" to "error", "message" to "Failed to delete log file")
                }
                call.respond(response)
            }

            // 获取日志统计信息
            get("/api/logs/stats") {
                call.respond(logger.getLogStats().toMap())
            }

            // 清理所有日志
            delete("/api/logs") {
                logger.clearAllLogs()
                call.respond(mapOf("status" to "ok", "message" to "All logs cleared"))
            }

            // ========== 系统日志 (logcat) ==========

            // 获取 logcat 输出
            get("/api/logcat") {
                val lines = call.request.queryParameters["lines"]?.toIntOrNull() ?: 500
                val filter = call.request.queryParameters["filter"] ?: ""
                call.respondText(getLogcat(lines, filter))
            }

            // 导出 logcat 到文件
            post("/api/logcat/export") {
                val lines = call.request.queryParameters["lines"]?.toIntOrNull() ?: 5000
                val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                val filename = "logcat_${sdf.format(java.util.Date())}.txt"
                val logFile = File(logger.getLogDir(), filename)

                if (exportLogcat(logFile, lines)) {
                    call.respond(mapOf(
                        "status" to "ok",
                        "message" to "Logcat exported",
                        "filename" to filename,
                        "size" to logFile.length(),
                        "path" to logFile.absolutePath
                    ))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, errorResponse("Failed to export logcat"))
                }
            }

            // ========== 系统状态 ==========
            get("/api/system/status") {
                call.respond(getSystemStatus())
            }
        }
    }

    // ========== 文件上传处理 ==========

    private suspend fun ApplicationCall.handleFileUpload() {
        try {
            val multipart = receiveMultipart()
            val uploadedFiles = mutableListOf<Map<String, Any?>>()

            var part: PartData? = multipart.readPart()
            while (part != null) {
                val currentPart = part
                if (currentPart is PartData.FileItem) {
                    val originalName = currentPart.originalFileName ?: "file_${System.currentTimeMillis()}"
                    val targetFile = File(uploadsDir, originalName)
                    var fileSize = 0L
                    val buffer = ByteArray(BUFFER_SIZE)

                    // 使用流式写入，避免内存溢出
                    targetFile.outputStream().use { output ->
                        currentPart.streamProvider().use { input ->
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                fileSize += bytesRead
                            }
                        }
                    }

                    uploadedFiles.add(mapOf(
                        "name" to originalName,
                        "size" to fileSize,
                        "path" to targetFile.absolutePath
                    ))

                    logger.i(TAG, "File uploaded: $originalName, size: $fileSize bytes")
                }
                currentPart.dispose()
                part = multipart.readPart()
            }

            respond(mapOf(
                "status" to "ok",
                "message" to "Files uploaded successfully",
                "files" to uploadedFiles,
                "count" to uploadedFiles.size
            ))

        } catch (e: Exception) {
            logger.e(TAG, "Error handling file upload", e)
            respond(HttpStatusCode.InternalServerError, errorResponse("Upload failed: ${e.message}"))
        }
    }

    // ========== APK 上传处理 ==========

    private suspend fun ApplicationCall.handleApkUpload() {
        try {
            val multipart = receiveMultipart()
            val filename = "update_${System.currentTimeMillis()}.apk"
            val apkFile = File(updatesDir, filename)
            val tempFile = File(tempDir, "${System.currentTimeMillis()}.tmp")
            var fileSize = 0L

            var part: PartData? = multipart.readPart()
            while (part != null) {
                val currentPart = part
                if (currentPart is PartData.FileItem) {
                    val originalName = currentPart.originalFileName
                    if (originalName?.lowercase()?.endsWith(".apk") == true) {
                        // 流式写入临时文件
                        val buffer = ByteArray(BUFFER_SIZE)
                        tempFile.outputStream().use { output ->
                            currentPart.streamProvider().use { input ->
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    fileSize += bytesRead
                                }
                            }
                        }
                        Log.i(TAG, "APK saved to temp: $fileSize bytes")
                    }
                }
                currentPart.dispose()
                part = multipart.readPart()
            }

            if (fileSize > 0 && tempFile.exists()) {
                // 移动到最终位置
                tempFile.copyTo(apkFile, overwrite = true)
                tempFile.delete()

                respond(mapOf(
                    "status" to "ok",
                    "message" to "APK uploaded successfully, installing...",
                    "filename" to filename,
                    "size" to fileSize,
                    "path" to apkFile.absolutePath
                ))

                // 安装 APK
                Thread {
                    Thread.sleep(500)
                    installApk(apkFile)
                }.start()
            } else {
                tempFile.delete()
                respond(HttpStatusCode.BadRequest, errorResponse("No valid APK file received"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling APK upload", e)
            respond(HttpStatusCode.InternalServerError, errorResponse("Upload failed: ${e.message}"))
        }
    }

    // ========== APK 安装 ==========

    private fun installApk(apkFile: File) {
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file not found: ${apkFile.absolutePath}")
            return
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                val apkUri = FileProvider.getUriForFile(
                    this@KtorServerService,
                    "$packageName.fileprovider",
                    apkFile
                )
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            Log.i(TAG, "Installing APK from sandbox: ${apkFile.absolutePath}")
            startActivity(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK", e)

            // 尝试静默安装 (root)
            try {
                Runtime.getRuntime().exec("su").let {
                    it.outputStream.use { stream ->
                        stream.write("pm install -r ${apkFile.absolutePath}\n".toByteArray())
                        stream.flush()
                        stream.close()
                    }
                    it.waitFor()
                }

                Log.i(TAG, "Silent install attempted")
            } catch (ex: Exception) {
                Log.e(TAG, "Silent install failed", ex)
            }
        }
    }

    // ========== 辅助方法 ==========

    private fun errorResponse(message: String) = mapOf(
        "status" to "error",
        "message" to message
    )

    /**
     * 清理文件名，防止路径穿越攻击
     * 只保留文件名部分，去除路径分隔符
     */
    private fun sanitizeFilename(filename: String): String {
        return File(filename).name
    }

    // ========== 设备信息 ==========

    private fun getDeviceInfo(): Map<String, Any?> {
        return mapOf(
            "brand" to Build.BRAND,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "product" to Build.PRODUCT,
            "androidVersion" to Build.VERSION.RELEASE,
            "sdkVersion" to Build.VERSION.SDK_INT,
            "androidId" to androidId,
            "wifiIp" to wifiIpAddress,
            "macAddress" to "N/A (Android 6+)",
            "packageName" to packageName,
            "versionName" to versionName,
            "versionCode" to versionCode,
            "currentTime" to System.currentTimeMillis(),
            "serverUrl" to "http://${wifiIpAddress}:$port",
            "sandbox" to mapOf(
                "filesDir" to filesDir.absolutePath,
                "updatesDir" to updatesDir.absolutePath,
                "logsDir" to logsDir.absolutePath,
                "uploadsDir" to uploadsDir.absolutePath,
                "maxFileSize" to MAX_FILE_SIZE
            )
        )
    }

    private fun getSystemStatus(): Map<String, Any?> {
        val runtime = Runtime.getRuntime()
        return mapOf(
            "memory" to mapOf(
                "totalMemory" to runtime.totalMemory(),
                "freeMemory" to runtime.freeMemory(),
                "usedMemory" to runtime.totalMemory() - runtime.freeMemory(),
                "maxMemory" to runtime.maxMemory()
            ),
            "storage" to mapOf(
                "totalSpace" to filesDir.totalSpace,
                "freeSpace" to filesDir.freeSpace,
                "usedSpace" to filesDir.totalSpace - filesDir.freeSpace
            ),
            "serverRunning" to isRunning,
            "serverPort" to port,
            "processId" to Process.myPid(),
            "uptime" to System.currentTimeMillis() - startTime
        )
    }

    // ========== 日志管理 ==========

    private fun getLogFiles(): List<Map<String, Any?>> {
        return logger.getLogFiles().map { file ->
            mapOf(
                "name" to file.name,
                "size" to file.length(),
                "lastModified" to file.lastModified(),
                "path" to file.absolutePath
            )
        }
    }

    private fun getUploadedFiles(): List<Map<String, Any?>> {
        return uploadsDir.listFiles()
            ?.filter { it.isFile }
            ?.map { file ->
                mapOf(
                    "name" to file.name,
                    "size" to file.length(),
                    "lastModified" to file.lastModified()
                )
            } ?: emptyList()
    }

    private fun tailFile(file: File, lines: Int): String {
        return file.readLines().takeLast(lines).joinToString("\n")
    }

    // ========== 系统操作 ==========

    private fun restartApp() {
        // 发显式广播给主进程，由主进程重启 Activity
        sendBroadcast(Intent(RestartReceiver.ACTION_RESTART_APP))
        logger.i(TAG, "Restart broadcast sent to main process")
        stopServer()
        Process.killProcess(Process.myPid())
    }

    private fun rebootDevice() {
        try {
            (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.reboot(null)
        } catch (e: Exception) {
            Log.e(TAG, "Reboot failed: ${e.message}")
            try {
                Runtime.getRuntime().exec("su -c reboot")
            } catch (ex: IOException) {
                Log.e(TAG, "Root reboot failed", ex)
            }
        }
    }

    // ========== Logcat 工具方法 ==========

    /**
     * 获取 logcat 输出
     */
    private fun getLogcat(lines: Int = 500, filter: String = ""): String {
        return try {
            val command = buildList {
                add("logcat")
                add("-d")
                add("-t")
                add(lines.toString())
                add("-v")
                add("time")
                if (filter.isNotEmpty()) {
                    add("-s")
                    add(filter)
                }
            }

            val process = Runtime.getRuntime().exec(command.toTypedArray())
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get logcat", e)
            "Error reading logcat: ${e.message}"
        }
    }

    /**
     * 导出 logcat 到文件
     */
    private fun exportLogcat(outputFile: File, lines: Int = 5000): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", lines.toString(), "-v", "time")
            )

            process.inputStream.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Log.i(TAG, "Logcat exported to ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logcat", e)
            false
        }
    }

    // ========== 设备时间设置 ==========

    /**
     * 设置设备时间
     * @param timestamp 时间戳 (毫秒)，如果为 null 则同步系统时间
     * @param timezone 时区，如 "Asia/Shanghai"，如果为 null 则不修改时区
     */
    private fun setDeviceTime(timestamp: Long?, timezone: String?): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()

        try {
            // 设置时区
            timezone?.let { tz ->
                val tzResult = setTimezone(tz)
                if (!tzResult) {
                    result["timezoneResult"] = "failed (may require root)"
                }
            }

            // 设置时间
            timestamp?.let { ts ->
                val dateStr = SimpleDateFormat("MMddHHmmyyyy.ss", Locale.US).format(Date(ts))

                // 方法1: 使用 setprop (需要 root)
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "setprop persist.sys.timezone $timezone"))
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "date -s $dateStr"))
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "setprop dalvik.system.TouchTyping false"))

                    result["method"] = "root"
                    result["timestamp"] = ts
                    result["dateString"] = dateStr
                    result["success"] = true
                } catch (e: Exception) {
                    // 方法2: 尝试通过系统设置 (需要权限)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            // Android 6.0+ 需要 Settings.Secure 或 root
                            result["method"] = "settings (limited)"
                            result["message"] = "Time sync requires root permission on Android 6.0+"
                            result["success"] = false
                        }
                    } catch (ex: Exception) {
                        result["method"] = "failed"
                        result["error"] = ex.message
                        result["success"] = false
                    }
                }
            } ?: run {
                // 同步系统时间 (仅通知，不实际修改)
                result["message"] = "System time is already synchronized"
                result["currentTime"] = System.currentTimeMillis()
                result["success"] = true
            }

        } catch (e: Exception) {
            result["success"] = false
            result["error"] = e.message
        }

        return result
    }

    /**
     * 设置时区
     */
    private fun setTimezone(timezone: String): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "setprop persist.sys.timezone $timezone")).waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set timezone", e)
            false
        }
    }

    // ========== 服务器配置管理 ==========

    // 服务器配置数据类
    data class ServerConfig(
        val port: Int = DEFAULT_PORT,
        val maxFileSize: Long = 10 * 1024 * 1024,
        val maxTotalSize: Long = 100 * 1024 * 1024,
        val maxFileCount: Int = 50,
        val logLevel: String = "V",
        val tagFilter: String = ""
    )

    // 获取服务器配置
    private fun getServerConfig(): Map<String, Any?> {
        return mapOf(
            "port" to port,
            "maxFileSize" to config.maxFileSize,
            "maxFileSizeMB" to config.maxFileSize / 1024 / 1024,
            "maxTotalSize" to config.maxTotalSize,
            "maxTotalSizeMB" to config.maxTotalSize / 1024 / 1024,
            "maxFileCount" to config.maxFileCount,
            "logLevel" to config.logLevel,
            "tagFilter" to config.tagFilter,
            "serverRunning" to isRunning,
            "uptime" to System.currentTimeMillis() - startTime
        )
    }

    // 保存服务器配置到 SharedPreferences
    private fun saveServerConfig(
        serverPort: Int,
        maxFileSize: Long,
        maxTotalSize: Long,
        maxFileCount: Int
    ) {
        try {
            getSharedPreferences("server_config", Context.MODE_PRIVATE)
                .edit()
                .putInt("port", serverPort)
                .putLong("maxFileSize", maxFileSize)
                .putLong("maxTotalSize", maxTotalSize)
                .putInt("maxFileCount", maxFileCount)
                .apply()
            logger.i(TAG, "Server config saved")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to save config", e)
        }
    }

    // 从 SharedPreferences 加载配置
    private fun loadServerConfig(): ServerConfig {
        return try {
            val prefs = getSharedPreferences("server_config", Context.MODE_PRIVATE)
            ServerConfig(
                port = prefs.getInt("port", DEFAULT_PORT),
                maxFileSize = prefs.getLong("maxFileSize", 10 * 1024 * 1024),
                maxTotalSize = prefs.getLong("maxTotalSize", 100 * 1024 * 1024),
                maxFileCount = prefs.getInt("maxFileCount", 50),
                logLevel = prefs.getString("logLevel", "V") ?: "V",
                tagFilter = prefs.getString("tagFilter", "") ?: ""
            )
        } catch (e: Exception) {
            ServerConfig()
        }
    }

    // 重启服务器
    private fun restartServer() {
        logger.i(TAG, "Restarting server...")
        stopServer()
        startServer()
        logger.i(TAG, "Server restarted on port $port")
    }

    // ========== 工具属性 ==========

    private val androidId: String
        get() = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    private val wifiIpAddress: String
        get() = try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            wifiManager?.connectionInfo?.ipAddress?.let { ip ->
                Formatter.formatIpAddress(ip)
            } ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting WiFi IP", e)
            "unknown"
        }

    private val macAddress: String
        get() = try {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.find { it.name == "wlan0" }
                ?.hardwareAddress
                ?.joinToString(":") { "%02X".format(it) }
                ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting MAC address", e)
            "unknown"
        }

    private val versionName: String
        get() = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }

    private val versionCode: Long
        get() = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            0
        }

    // assets 文件缓存，避免每次请求重新读取
    private val assetCache = mutableMapOf<String, String>()

    /**
     * 从 assets 目录读取文件内容（带缓存）
     */
    private fun getAssetContent(fileName: String): String {
        return assetCache.getOrPut(fileName) {
            try {
                assets.open(fileName).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read $fileName from assets", e)
                when {
                    fileName.endsWith(".html") -> """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Error</title></head>
                    <body>
                        <h1>Failed to load $fileName</h1>
                        <p>Please ensure $fileName is in the assets folder.</p>
                    </body>
                    </html>
                """.trimIndent()
                    else -> "/* Failed to load $fileName */"
                }
            }
        }
    }

    // ========== 鉴权辅助方法 ==========

    /**
     * 验证登录
     */
    private fun validateLogin(username: String, password: String): Boolean {
        return username == authUsername && sha256Hash(password) == authPasswordHash
    }

    /**
     * 验证 token
     */
    private fun validateToken(token: String): Boolean {
        if (authToken == null || tokenExpireTime == 0L) return false
        
        // 检查 token 是否匹配且未过期
        return token == authToken && System.currentTimeMillis() < tokenExpireTime
    }

    /**
     * 生成新 token
     */
    private fun generateToken(): String {
        authToken = UUID.randomUUID().toString()
        tokenExpireTime = System.currentTimeMillis() + TOKEN_EXPIRE_MS
        logger.i(TAG, "New token generated, expires at $tokenExpireTime")
        return authToken!!
    }

    /**
     * SHA-256 哈希
     */
    private fun sha256Hash(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 加载鉴权配置
     */
    private fun loadAuthConfig() {
        // 只加载用户名，密码由主进程通过 Intent 传递
        try {
            val prefs = getSharedPreferences("auth_config", Context.MODE_PRIVATE)
            authUsername = prefs.getString("username", "admin") ?: "admin"
            // 密码初始为空，等待主进程传递
            authPasswordHash = ""
            logger.i(TAG, "Auth config loaded, username: $authUsername, waiting for password from main process")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to load auth config", e)
            authUsername = "admin"
            authPasswordHash = ""
        }
    }

    /**
     * 保存鉴权配置（仅用户名，密码不持久化）
     */
    private fun saveAuthConfig() {
        try {
            getSharedPreferences("auth_config", Context.MODE_PRIVATE)
                .edit()
                .putString("username", authUsername)
                // 不保存密码哈希
                .apply()
            logger.i(TAG, "Auth config saved (username only)")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to save auth config", e)
        }
    }
}
