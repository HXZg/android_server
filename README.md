# AndroidServer

基于 Ktor 构建的 Android 设备远程管理服务，通过浏览器管理 Android 设备。

## 功能特性

- 📱 **设备管理** — 查看设备信息、重启设备、同步时间
- 📁 **文件管理** — 上传/下载/删除文件，支持大文件（最大 2GB）
- 📦 **应用更新** — 上传 APK 自动安装
- 📋 **日志管理** — 查看/下载/清理日志文件，实时 Logcat
- ⚙️ **系统配置** — 动态修改服务端口、日志参数
- 🌙 **深浅色主题** — 纯 HTML/CSS/JS，无外部依赖
- 🔌 **库化设计** — `server-lib` 模块可被第三方应用集成，支持动态扩展配置字段

## 项目结构

```
android_server/
├── app/                        # 示例应用
│   └── src/main/
│       ├── assets/             # Web 前端资源
│       │   ├── index.html
│       │   ├── style.css
│       │   └── app.js
│       └── java/com/example/androidserver/
│           ├── MainActivity.kt
│           ├── RestartReceiver.kt
│           ├── server/
│           │   └── KtorServerService.kt   # Ktor 服务（独立进程 :server）
│           └── util/
│               └── Logger.kt              # 日志工具
│
└── server-lib/                 # 可复用库模块（开发中）
    └── src/main/java/com/androidserver/
        ├── ConfigField.kt      # 配置字段定义
        └── ConfigRegistry.kt  # 动态配置注册中心
```

## 架构说明

```
主进程 (MainActivity)
    │
    │  startForegroundService()
    ▼
:server 进程 (KtorServerService)
    │
    ├── Ktor HTTP Server (Netty)
    │       ├── GET  /              → Web 管理界面
    │       ├── GET  /api/device/*  → 设备信息
    │       ├── GET  /api/system/*  → 系统状态
    │       ├── POST /api/files/*   → 文件管理
    │       ├── GET  /api/logs/*    → 日志管理
    │       └── POST /api/config/*  → 配置管理
    │
    └── Logger (logcat 持续采集 → 写文件)

跨进程通信：广播 (ACTION_SERVER_STARTED / STOPPED / RESTART_APP)
```

## 快速开始

### 环境要求

- Android Studio Hedgehog+
- Android SDK 34
- Kotlin 1.9.22
- Gradle 8.1

### 构建运行

```bash
git clone https://github.com/HXZg/android_server.git
cd android_server
./gradlew assembleDebug
```

安装到设备后，点击 **Start Server**，浏览器访问：

```
http://<设备WiFi IP>:8080
```

## 动态配置扩展（server-lib）

第三方应用可通过 `ConfigRegistry` 注册自定义配置字段，前端页面自动渲染对应表单控件。

```kotlin
// Application.onCreate() 中注册自定义字段
ConfigRegistry.register(ConfigField(
    key = "upload_auto_rename",
    label = "上传时自动重命名",
    type = FieldType.BOOLEAN,
    defaultValue = true,
    group = "文件管理",
    description = "文件名冲突时自动添加时间戳后缀"
))

ConfigRegistry.register(ConfigField(
    key = "log_level",
    label = "日志级别",
    type = FieldType.SELECT,
    defaultValue = "I",
    group = "日志配置",
    options = listOf(
        SelectOption("V", "Verbose"),
        SelectOption("D", "Debug"),
        SelectOption("I", "Info"),
        SelectOption("W", "Warn"),
        SelectOption("E", "Error")
    )
))

// 读取配置值
val autoRename = ConfigRegistry.getBoolean(context, "upload_auto_rename")
val logLevel = ConfigRegistry.getString(context, "log_level", "I")

// 监听配置变更
ConfigRegistry.addListener(object : ConfigRegistry.OnConfigChangeListener {
    override fun onConfigChanged(context: Context, key: String, oldValue: Any?, newValue: Any?) {
        if (key == "log_level") {
            logger.setLevel(newValue as String)
        }
    }
})
```

### 支持的字段类型

| 类型 | 前端控件 | 说明 |
|------|---------|------|
| `INT` | `<input type="number">` | 整数 |
| `LONG` | `<input type="number">` | 长整数 |
| `STRING` | `<input type="text">` | 字符串 |
| `BOOLEAN` | `<input type="checkbox">` | 布尔开关 |
| `SELECT` | `<select>` | 下拉选择，需提供 `options` |

## API 文档

详见 [API_DOCUMENT.md](API_DOCUMENT.md)

### 主要端点

| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/` | Web 管理界面 |
| GET | `/api/device/info` | 设备信息 |
| GET | `/api/system/status` | 系统状态（内存/存储/PID）|
| POST | `/api/files/upload` | 上传文件（流式，支持 2GB）|
| GET | `/api/files` | 文件列表 |
| GET | `/api/logs` | 日志文件列表 |
| GET | `/api/logcat` | 实时 Logcat |
| POST | `/api/logcat/export` | 导出 Logcat 到文件 |
| GET | `/api/config` | 获取服务器配置 |
| POST | `/api/config/port` | 修改端口（自动跳转）|
| POST | `/api/config/logs` | 修改日志配置 |
| POST | `/api/app/update/upload` | 上传 APK 并安装 |
| POST | `/api/app/restart` | 重启应用 |

## 技术栈

| 组件 | 版本 |
|------|------|
| Kotlin | 1.9.22 |
| Ktor Server (Netty) | 2.3.12 |
| Kotlinx Coroutines | 1.8.0 |
| Android minSdk | 24 (Android 7.0) |
| Android targetSdk | 34 (Android 14) |

## 注意事项

- **重启设备** 和 **设置时间** 需要 Root 权限
- 服务运行在独立进程 `:server`，主进程被杀不影响服务
- 修改端口后浏览器会自动跳转到新端口
- 日志采用 LRU 策略自动清理，默认单文件 10MB、总量 100MB、最多 50 个文件
- `logcat_*.txt`（手动导出）不参与 LRU 自动清理

## License

MIT
