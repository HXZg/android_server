package com.example.androidserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * 监听应用安装完成广播，安装成功后打开程序并删除 APK
 */
class PackageInstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageInstallReceiver"
        // 等待系统准备 app 的延迟
        private const val LAUNCH_DELAY_MS = 2000L
        private const val DELETE_DELAY_MS = 3000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return

        // 只处理本 app 的安装/更新
        if (packageName != context.packageName) return

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                Log.i(TAG, "App installed/replaced: $packageName")

                // 延迟等待系统准备 app
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        // 打开主界面
                        val launchIntent = context.packageManager
                            .getLaunchIntentForPackage(packageName)
                        launchIntent?.let {
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            context.startActivity(it)
                            Log.i(TAG, "App launched after install")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch app after install", e)
                    }

                    // 再延迟删除 APK
                    Handler(Looper.getMainLooper()).postDelayed({
                        deleteApkFiles(context)
                    }, DELETE_DELAY_MS)
                }, LAUNCH_DELAY_MS)
            }
        }
    }

    /**
     * 删除 updates 目录下的所有 APK 文件
     */
    private fun deleteApkFiles(context: Context) {
        try {
            val updatesDir = File(context.filesDir, "updates")
            if (updatesDir.exists()) {
                updatesDir.listFiles()?.forEach { file ->
                    if (file.isFile && (file.name.endsWith(".apk", ignoreCase = true)
                                || file.name.startsWith("update_"))) {
                        if (file.delete()) {
                            Log.i(TAG, "APK deleted: ${file.name}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete APK files", e)
        }
    }
}
