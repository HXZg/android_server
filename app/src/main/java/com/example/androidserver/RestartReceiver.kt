package com.example.androidserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 接收来自 :server 进程的广播，通知主进程
 */
class RestartReceiver : BroadcastReceiver() {
    
    companion object {
        const val ACTION_RESTART_APP = "com.example.androidserver.ACTION_RESTART_APP"
        const val ACTION_SERVER_STARTED = "com.example.androidserver.ACTION_SERVER_STARTED"
        const val ACTION_SERVER_STOPPED = "com.example.androidserver.ACTION_SERVER_STOPPED"
        const val EXTRA_PORT = "port"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
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
                // 只持久化端口，不持久化运行状态（避免 App 被杀后残留旧状态）
                val prefs = context.getSharedPreferences("server_status", Context.MODE_PRIVATE)
                prefs.edit()
                    .putInt("port", port)
                    .apply()
                Log.i("RestartReceiver", "Server started on port $port")
            }
            ACTION_SERVER_STOPPED -> {
                // 不需要写 SP，服务停止是默认状态
                Log.i("RestartReceiver", "Server stopped")
            }
        }
    }
}
