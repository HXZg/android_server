package com.androidserver

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 配置注册中心 - 动态配置系统核心
 *
 * 第三方应用通过 register() 注册配置字段，库内部和前端自动适配。
 *
 * 生命周期：
 * 1. Application.onCreate() 中调用 ConfigRegistry.register(...) 注册自定义字段
 * 2. 库内部在 KtorServerService 启动时注册内置字段
 * 3. GET /api/config/fields 返回所有字段定义 + 当前值（供前端动态渲染）
 * 4. POST /api/config/values 批量保存配置值
 */
object ConfigRegistry {

    private const val TAG = "ConfigRegistry"
    private const val PREFS_NAME = "android_server_config"

    private val fields = ConcurrentHashMap<String, ConfigField>()

    // 配置变更监听
    private val listeners = mutableListOf<OnConfigChangeListener>()

    /**
     * 配置变更监听器
     */
    interface OnConfigChangeListener {
        fun onConfigChanged(context: Context, key: String, oldValue: Any?, newValue: Any?)
    }

    // ==================== 字段注册 ====================

    /**
     * 注册一个配置字段
     */
    fun register(field: ConfigField) {
        fields[field.key] = field
        Log.d(TAG, "Registered config field: ${field.key} (${field.group})")
    }

    /**
     * 批量注册配置字段
     */
    fun registerAll(newFields: List<ConfigField>) {
        newFields.forEach { register(it) }
    }

    /**
     * 注销一个配置字段
     */
    fun unregister(key: String) {
        fields.remove(key)
        Log.d(TAG, "Unregistered config field: $key")
    }

    /**
     * 获取单个字段定义
     */
    fun getField(key: String): ConfigField? = fields[key]

    /**
     * 获取所有已注册字段（按 group + order 排序）
     */
    fun getAllFields(): List<ConfigField> {
        return fields.values.sortedWith(compareBy({ it.group }, { it.order }, { it.label }))
    }

    /**
     * 获取所有分组名称（按字母排序）
     */
    fun getGroups(): List<String> {
        return fields.values.map { it.group }.distinct().sorted()
    }

    // ==================== 值读写 ====================

    /**
     * 获取配置值（类型自动转换）
     */
    fun getValue(context: Context, key: String): Any? {
        val field = fields[key] ?: return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        return when (field.type) {
            FieldType.INT -> prefs.getInt(key, (field.defaultValue as? Number)?.toInt() ?: 0)
            FieldType.LONG -> prefs.getLong(key, (field.defaultValue as? Number)?.toLong() ?: 0L)
            FieldType.STRING -> prefs.getString(key, field.defaultValue as? String ?: "")
            FieldType.BOOLEAN -> prefs.getBoolean(key, field.defaultValue as? Boolean ?: false)
            FieldType.SELECT -> prefs.getString(key, (field.defaultValue as? String) ?: "")
        }
    }

    /**
     * 获取配置值，带类型转换辅助
     */
    fun getInt(context: Context, key: String, default: Int = 0): Int {
        val field = fields[key]
        val realDefault = if (field != null) (field.defaultValue as? Number)?.toInt() ?: default else default
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(key, realDefault)
    }

    fun getLong(context: Context, key: String, default: Long = 0L): Long {
        val field = fields[key]
        val realDefault = if (field != null) (field.defaultValue as? Number)?.toLong() ?: default else default
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(key, realDefault)
    }

    fun getString(context: Context, key: String, default: String = ""): String {
        val field = fields[key]
        val realDefault = if (field != null) (field.defaultValue as? String) ?: default else default
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(key, realDefault) ?: realDefault
    }

    fun getBoolean(context: Context, key: String, default: Boolean = false): Boolean {
        val field = fields[key]
        val realDefault = if (field != null) (field.defaultValue as? Boolean) ?: default else default
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(key, realDefault)
    }

    /**
     * 保存单个配置值
     */
    fun setValue(context: Context, key: String, value: Any) {
        val oldValue = getValue(context, key)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            when (value) {
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Number -> putLong(key, value.toLong())
                else -> putString(key, value.toString())
            }
            apply()
        }

        // 通知监听器
        listeners.forEach { listener ->
            try {
                listener.onConfigChanged(context, key, oldValue, value)
            } catch (e: Exception) {
                Log.e(TAG, "Config change listener error for key: $key", e)
            }
        }
    }

    /**
     * 批量保存配置值
     */
    fun setValues(context: Context, values: Map<String, Any>) {
        values.forEach { (key, value) ->
            setValue(context, key, value)
        }
    }

    /**
     * 获取所有字段定义 + 当前值（供前端渲染用）
     */
    fun getAllFieldsWithValues(context: Context): List<Map<String, Any?>> {
        return getAllFields().map { field ->
            mapOf(
                "key" to field.key,
                "label" to field.label,
                "type" to field.type.name,
                "group" to field.group,
                "description" to field.description,
                "defaultValue" to field.defaultValue,
                "currentValue" to getValue(context, field.key),
                "options" to field.options.map { mapOf("value" to it.value, "label" to it.label) },
                "min" to field.min,
                "max" to field.max,
                "order" to field.order
            )
        }
    }

    // ==================== 监听器 ====================

    fun addListener(listener: OnConfigChangeListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: OnConfigChangeListener) {
        listeners.remove(listener)
    }

    // ==================== 数据迁移 ====================

    /**
     * 从旧版 server_config SharedPreferences 迁移数据
     */
    fun migrateFromLegacy(context: Context) {
        val oldPrefs = context.getSharedPreferences("server_config", Context.MODE_PRIVATE)
        if (!oldPrefs.contains("port")) return // 没有旧数据，无需迁移

        Log.i(TAG, "Migrating legacy config...")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // port
        if (oldPrefs.contains("port")) {
            val port = oldPrefs.getInt("port", 8080)
            editor.putInt("server_port", port)
        }
        // maxFileSize (旧的是字节，新的存 MB)
        if (oldPrefs.contains("maxFileSize")) {
            val bytes = oldPrefs.getLong("maxFileSize", 10 * 1024 * 1024L)
            editor.putInt("log_max_file_size_mb", (bytes / 1024 / 1024).toInt())
        }
        // maxTotalSize (旧的是字节，新的存 MB)
        if (oldPrefs.contains("maxTotalSize")) {
            val bytes = oldPrefs.getLong("maxTotalSize", 100 * 1024 * 1024L)
            editor.putInt("log_max_total_size_mb", (bytes / 1024 / 1024).toInt())
        }
        // maxFileCount
        if (oldPrefs.contains("maxFileCount")) {
            val count = oldPrefs.getInt("maxFileCount", 50)
            editor.putInt("log_max_file_count", count)
        }
        // logLevel
        if (oldPrefs.contains("logLevel")) {
            val level = oldPrefs.getString("logLevel", "V") ?: "V"
            editor.putString("log_level", level)
        }
        // tagFilter
        if (oldPrefs.contains("tagFilter")) {
            val filter = oldPrefs.getString("tagFilter", "") ?: ""
            editor.putString("log_tag_filter", filter)
        }

        editor.apply()

        // 清除旧数据标记
        oldPrefs.edit().clear().apply()
        Log.i(TAG, "Legacy config migration completed")
    }
}
