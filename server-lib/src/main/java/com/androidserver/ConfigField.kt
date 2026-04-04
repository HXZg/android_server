package com.androidserver

/**
 * 配置字段类型
 */
enum class FieldType {
    INT,       // 整数 → <input type="number">
    LONG,      // 长整数 → <input type="number">
    STRING,    // 字符串 → <input type="text">
    BOOLEAN,   // 布尔 → <input type="checkbox">
    SELECT     // 下拉选择 → <select>
}

/**
 * 下拉选项
 */
data class SelectOption(
    val value: String,
    val label: String
)

/**
 * 配置字段定义
 *
 * 第三方应用通过注册 ConfigField 来扩展配置页面，
 * 前端会根据字段定义自动渲染对应的表单控件。
 *
 * 使用示例：
 * ```
 * ConfigRegistry.register(ConfigField(
 *     key = "my_feature_enabled",
 *     label = "启用我的功能",
 *     type = FieldType.BOOLEAN,
 *     defaultValue = true,
 *     group = "我的模块",
 *     description = "开启后自动执行某功能"
 * ))
 * ```
 */
data class ConfigField(
    /** 唯一标识，如 "server_port" */
    val key: String,
    /** 显示名称，如 "服务器端口" */
    val label: String,
    /** 字段类型 */
    val type: FieldType,
    /** 默认值，类型需与 type 匹配 */
    val defaultValue: Any,
    /** 分组名称，相同 group 的字段会显示在同一卡片中 */
    val group: String = "通用",
    /** 帮助说明文字 */
    val description: String = "",
    /** SELECT 类型的选项列表 */
    val options: List<SelectOption> = emptyList(),
    /** INT/LONG 类型的最小值 */
    val min: Number? = null,
    /** INT/LONG 类型的最大值 */
    val max: Number? = null,
    /** 同组内的排序权重，越小越靠前 */
    val order: Int = 0
)
