package com.example.androidserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.androidserver.server.KtorServerService
import com.example.androidserver.util.Logger

class MainActivity : AppCompatActivity() {
    
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var btnStartServer: Button
    private lateinit var btnStopServer: Button
    private lateinit var scrollView: ScrollView
    
    private val logger by lazy { Logger.getInstance(this) }
    
    private val serverStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                RestartReceiver.ACTION_SERVER_STARTED -> {
                    val port = intent.getIntExtra(RestartReceiver.EXTRA_PORT, 8080)
                    runOnUiThread {
                        updateServerStatus(true)
                        tvStatus.text = "Server Status: Running on port $port"
                        updateDeviceInfo(port)
                    }
                }
                RestartReceiver.ACTION_SERVER_STOPPED -> {
                    runOnUiThread {
                        updateServerStatus(false)
                        tvStatus.text = "Server Status: Stopped"
                    }
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        updateDeviceInfo()
        
        val filter = IntentFilter().apply {
            addAction(RestartReceiver.ACTION_SERVER_STARTED)
            addAction(RestartReceiver.ACTION_SERVER_STOPPED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serverStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serverStatusReceiver, filter)
        }
    }
    
    override fun onResume() {
        super.onResume()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(serverStatusReceiver) } catch (_: Exception) {}
    }
    
    private fun initViews() {
        tvStatus = findViewById(R.id.tv_status)
        tvDeviceInfo = findViewById(R.id.tv_device_info)
        btnStartServer = findViewById(R.id.btn_start_server)
        btnStopServer = findViewById(R.id.btn_stop_server)
        scrollView = findViewById(R.id.scroll_view)
        
        btnStartServer.setOnClickListener { startServer() }
        btnStopServer.setOnClickListener { stopServer() }
        
        syncServerStatusFromPrefs()
    }
    
    private fun syncServerStatusFromPrefs() {
        // 默认停止状态，isRunning 不持久化（App 被杀后不会残留旧状态）
        // 只有收到 ACTION_SERVER_STARTED 广播后才变为运行中
        updateServerStatus(false)
        tvStatus.text = "Server Status: Stopped"
    }
    
    private fun startServer() {
        logger.i(TAG, "Starting server service...")
        
        val intent = Intent(this, KtorServerService::class.java)
        // 不传 port，让服务从 SharedPreferences 读取已保存的配置
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        // 从 SP 读取当前端口显示
        val port = getSharedPreferences("server_status", Context.MODE_PRIVATE).getInt("port", 8080)
        updateServerStatus(true)
        tvStatus.text = "Server Status: Running on port $port"
        
        logger.i(TAG, "Server service started")
    }
    
    private fun stopServer() {
        logger.i(TAG, "Stopping server service...")
        
        val intent = Intent(this, KtorServerService::class.java)
        stopService(intent)
        
        updateServerStatus(false)
        tvStatus.text = "Server Status: Stopped"
        
        logger.i(TAG, "Server service stopped")
    }
    
    private fun updateServerStatus(running: Boolean) {
        btnStartServer.isEnabled = !running
        btnStopServer.isEnabled = running
    }
    
    private fun updateDeviceInfo(port: Int = 0) {
        val sb = StringBuilder()
        
        sb.appendLine("=== Device Info ===")
        sb.appendLine("Brand: ${Build.BRAND}")
        sb.appendLine("Manufacturer: ${Build.MANUFACTURER}")
        sb.appendLine("Model: ${Build.MODEL}")
        sb.appendLine("Device: ${Build.DEVICE}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine()
        sb.appendLine("=== App Info ===")
        
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            sb.appendLine("Package: $packageName")
            sb.appendLine("Version: ${info.versionName}")
            sb.appendLine("Version Code: ${info.longVersionCode}")
        } catch (e: PackageManager.NameNotFoundException) {
            sb.appendLine("Unable to get app info")
        }
        
        sb.appendLine()
        sb.appendLine("=== Server Info ===")
        val displayPort = if (port > 0) port else getSharedPreferences("server_status", Context.MODE_PRIVATE).getInt("port", 8080)
        sb.appendLine("Port: $displayPort")
        sb.appendLine("Process: :server (separate process)")
        
        tvDeviceInfo.text = sb.toString()
    }
    
    companion object {
        private const val TAG = "MainActivity"
    }
}
