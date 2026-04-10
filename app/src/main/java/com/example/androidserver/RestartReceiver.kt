package com.example.androidserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.androidserver.server.KtorServerService

/**
 * 接收来自 :server 进程的广播，通知主进程
 */
class RestartReceiver : BroadcastReceiver() {
    
    companion object {
        const val ACTION_RESTART_APP = "com.example.androidserver.ACTION_RESTART_APP"
        const val ACTION_SERVER_STARTED = "com.example.androidserver.ACTION_SERVER_STARTED"
        const val ACTION_SERVER_STOPPED = "com.example.androidserver.ACTION_SERVER_STOPPED"
        const val ACTION_CONFIG_UPDATE = "com.example.androidserver.ACTION_CONFIG_UPDATE"
        const val EXTRA_PORT = "port"
        const val EXTRA_CONFIG_JSON = "config_json"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i("RestartReceiver", "Boot completed, launching app (server will be started by main process)")
                // 不直接启动服务，因为密码需要由主进程传递
                // 打开应用界面，MainActivity 会自动启动服务
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                launchIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // 标记需要自动启动服务
                    it.putExtra("auto_start_server", true)
                    context.startActivity(it)
                }
            }
            ACTION_RESTART_APP -> {
                val launchIntent = context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                launchIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    context.startActivity(it)
                }
            }
            ACTION_SERVER_STARTED -> {
                val port = intent.getIntExtra(EXTRA_PORT, 8080)
                val prefs = context.getSharedPreferences("server_status", Context.MODE_PRIVATE)
                prefs.edit()
                    .putInt("port", port)
                    .apply()
                Log.i("RestartReceiver", "Server started on port $port")
            }
            ACTION_SERVER_STOPPED -> {
                Log.i("RestartReceiver", "Server stopped")
            }
            ACTION_CONFIG_UPDATE -> {
                val json = intent.getStringExtra(EXTRA_CONFIG_JSON) ?: return@onReceive
                Log.i("RestartReceiver", "Config update: $json")
                // 透传给主进程的 MainActivityConfigReceiver
//                val forwardIntent = Intent(ACTION_CONFIG_UPDATE).apply {
//                    putExtra(EXTRA_CONFIG_JSON, json)
//                    setPackage(context.packageName)
//                }
//                context.sendBroadcast(forwardIntent)
            }
        }
    }
}
