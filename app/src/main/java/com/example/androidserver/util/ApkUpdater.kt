package com.example.androidserver.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 更新工具类
 */
class ApkUpdater(private val context: Context) {
    
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private var downloadId: Long = -1
    
    /**
     * 下载 APK
     */
    fun downloadApk(url: String, fileName: String): Long {
        val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        
        // 如果已存在则删除
        if (apkFile.exists()) {
            apkFile.delete()
        }
        
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType("application/vnd.android.package-archive")
            setTitle("App Update")
            setDescription("Downloading $fileName")
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setVisibleInDownloadsUi(true)
        }
        
        downloadId = downloadManager.enqueue(request)
        
        // 注册下载完成广播接收器
        context.registerReceiver(
            downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        )
        
        return downloadId
    }
    
    /**
     * 安装 APK
     */
    fun installApk(fileName: String) {
        val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        installApk(apkFile)
    }
    
    /**
     * 安装 APK (通过 File 对象)
     */
    fun installApk(apkFile: File) {
        if (!apkFile.exists()) return
        
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        context.startActivity(intent)
    }
    
    /**
     * 获取下载进度
     */
    fun getDownloadProgress(): Int {
        if (downloadId == -1L) return 0
        
        val query = DownloadManager.Query().setFilterById(downloadId)
        
        downloadManager.query(query).use { cursor ->
            if (cursor.moveToFirst()) {
                val bytesDownloaded = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                )
                val bytesTotal = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                )
                
                if (bytesTotal > 0) {
                    return (bytesDownloaded * 100L / bytesTotal).toInt()
                }
            }
        }
        
        return 0
    }
    
    /**
     * 取消下载
     */
    fun cancelDownload() {
        if (downloadId != -1L) {
            downloadManager.remove(downloadId)
            downloadId = -1
        }
    }
    
    /**
     * 下载完成广播接收器
     */
    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            
            if (id == downloadId) {
                // 下载完成，自动安装
                val query = DownloadManager.Query().setFilterById(downloadId)
                
                downloadManager.query(query).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val localUri = cursor.getString(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                        )
                        
                        localUri?.let {
                            val apkFile = File(Uri.parse(it).path!!)
                            installApk(apkFile)
                        }
                    }
                }
                
                // 取消注册广播接收器
                try {
                    context.unregisterReceiver(this)
                } catch (e: Exception) {
                    // 忽略
                }
            }
        }
    }
}
