# Android Server

基于 Ktor 的 Android 设备远程管理服务，提供 Web 管理控制台和 RESTful API。

## 功能特性

- **Web 管理控制台** - 响应式前端界面，支持主题切换
- **设备管理** - 查看设备信息、重启设备、同步时间
- **文件管理** - 上传/下载/删除文件，支持大文件流式传输
- **日志管理** - 自动日志采集、LRU 清理、logcat 导出
- **应用更新** - APK 上传安装，安装后自动清理
- **配置管理** - 动态端口配置、日志参数配置、自定义 JSON 配置
- **开机自启** - 设备重启后自动启动服务和界面

## 项目结构

```
android_server/
├── app/src/main/
│   ├── java/com/example/androidserver/
│   │   ├── MainActivity.kt           # 主界面
│   │   ├── RestartReceiver.kt        # 广播接收（开机/服务状态）
│   │   ├── PackageInstallReceiver.kt # APK 安装监听
│   │   ├── server/KtorServerService.kt  # Ktor 服务核心
│   │   └── util/Logger.kt            # 日志工具
│   ├── assets/                       # Web 前端资源
│   │   ├── index.html
│   │   ├── app.js
│   │   └── style.css
│   └── res/                          # Android 资源
├── API_DOCUMENT.md                   # 完整 API 文档
└── README.md                         # 本文件
```

## 快速开始

### 环境要求

- Android SDK 34
- Gradle 8.2+
- Kotlin 1.9.22
- Java 8+

### 构建运行

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 访问 Web 控制台

1. 启动应用，点击「启动服务」
2. 浏览器访问 `http://<设备IP>:8080`
3. 默认端口 8080，可在配置页修改

## 核心 API

| 方法 | 端点 | 功能 |
|------|------|------|
| GET | `/api/device/info` | 设备信息 |
| POST | `/api/files/upload` | 文件上传 |
| GET | `/api/files` | 文件列表 |
| GET | `/api/logs` | 日志列表 |
| POST | `/api/config/port` | 修改端口 |
| POST | `/api/config/custom` | 自定义配置 |

完整 API 文档见 [API_DOCUMENT.md](API_DOCUMENT.md)

## 技术栈

| 组件 | 版本 |
|------|------|
| Ktor Server | 2.3.12 |
| Kotlin | 1.9.22 |
| Android SDK | 34 |
| minSdk | 24 |

## 进程架构

```
主进程 (UI)
├── MainActivity          # 界面控制
└── RestartReceiver       # 接收服务广播

:server 进程 (后台)
├── KtorServerService     # HTTP 服务
├── Logger                # 日志采集
└── Broadcast → 主进程   # 状态同步
```

## 配置说明

### 服务器配置

- **端口**: 1024-65535，默认 8080
- **单文件上限**: 2GB
- **缓冲区**: 64KB

### 日志配置

- **单文件大小**: 默认 10MB
- **总存储上限**: 默认 100MB
- **最大文件数**: 默认 50 个
- **LRU 清理**: 自动删除最旧文件

## 权限要求

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

## 注意事项

1. **后台限制**: Android 8+ 需要前台服务保持运行
2. **安装权限**: APK 安装需要用户确认（或 Root）
3. **网络访问**: 确保设备和浏览器在同一网络
4. **存储路径**: 所有文件存储在应用私有目录

## License

MIT
