# Android Server API 文档

基于 Ktor 构建的 Android 设备远程管理服务。

## 基础信息

- **默认端口**: 8080
- **基础路径**: `http://<设备IP>:8080`
- **Content-Type**: `application/json` (除文件上传外)

---

## 目录

1. [健康检查](#1-健康检查)
2. [设备管理](#2-设备管理)
3. [应用管理](#3-应用管理)
4. [文件上传](#4-文件上传)
5. [日志管理](#5-日志管理)
6. [系统日志 (Logcat)](#6-系统日志-logcat)
7. [系统状态](#7-系统状态)
8. [服务器配置](#8-服务器配置)

---

## 1. 健康检查

### GET /

服务器健康检查。

**请求**
```
GET /
```

**响应**
```json
{
  "status": "ok",
  "message": "Android Server is running",
  "version": "1.0",
  "port": 8080,
  "pid": 12345,
  "serverUrl": "http://192.168.1.100:8080"
}
```

---

### GET /api

服务器 API 信息。

**请求**
```
GET /api
```

**响应**
```json
{
  "status": "ok",
  "message": "Android Server API",
  "version": "1.0",
  "webUI": "/"
}
```

---

## 2. 设备管理

### GET /api/device/info

获取设备详细信息。

**请求**
```
GET /api/device/info
```

**响应**
```json
{
  "brand": "Xiaomi",
  "manufacturer": "Xiaomi",
  "model": "Redmi K50",
  "device": "rubens",
  "product": "rubens",
  "androidVersion": "13",
  "sdkVersion": 33,
  "androidId": "a1b2c3d4e5f6g7h8",
  "wifiIp": "192.168.1.100",
  "macAddress": "AA:BB:CC:DD:EE:FF",
  "packageName": "com.example.androidserver",
  "versionName": "1.0",
  "versionCode": 1,
  "currentTime": 1712123456789,
  "serverUrl": "http://192.168.1.100:8080",
  "sandbox": {
    "filesDir": "/data/data/com.example.androidserver/files",
    "updatesDir": "/data/data/com.example.androidserver/files/updates",
    "logsDir": "/data/data/com.example.androidserver/files/logs",
    "uploadsDir": "/data/data/com.example.androidserver/files/uploads",
    "maxFileSize": 2147483648
  }
}
```

### POST /api/device/reboot

重启设备。

**请求**
```
POST /api/device/reboot
```

**响应**
```json
{
  "status": "ok",
  "message": "Device reboot initiated"
}
```

**注意**: 需要系统权限或 Root 权限。

---

## 3. 应用管理

### POST /api/app/restart

重启应用。

**请求**
```
POST /api/app/restart
```

**响应**
```json
{
  "status": "ok",
  "message": "App restart initiated"
}
```

### GET /api/app/update/check

检查应用版本。

**请求**
```
GET /api/app/update/check
```

**响应**
```json
{
  "currentVersion": "1.0",
  "versionCode": 1,
  "packageName": "com.example.androidserver",
  "updateAvailable": false,
  "lastCheckTime": 1712123456789
}
```

### POST /api/app/update/upload

上传 APK 文件并自动安装。

**请求**
```
POST /api/app/update/upload
Content-Type: multipart/form-data

参数:
- apk: APK 文件 (binary)
```

**响应**
```json
{
  "status": "ok",
  "message": "APK uploaded successfully, installing...",
  "filename": "update_1712123456789.apk",
  "size": 12345678,
  "path": "/data/data/com.example.androidserver/files/updates/update_1712123456789.apk"
}
```

**示例**
```bash
curl -X POST http://192.168.1.100:8080/api/app/update/upload \
  -F "apk=@app-release.apk"
```

---

## 4. 文件上传

### POST /api/files/upload

上传文件 (流式处理，支持大文件)。

**请求**
```
POST /api/files/upload
Content-Type: multipart/form-data

参数:
- file: 文件 (binary)
```

**响应**
```json
{
  "status": "ok",
  "message": "Files uploaded successfully",
  "files": [
    {
      "name": "document.pdf",
      "size": 1048576,
      "path": "/data/data/com.example.androidserver/files/uploads/document.pdf"
    }
  ],
  "count": 1
}
```

**示例**
```bash
curl -X POST http://192.168.1.100:8080/api/files/upload \
  -F "file=@large_file.zip"
```

### GET /api/files

获取已上传文件列表。

**响应**
```json
[
  {
    "name": "document.pdf",
    "size": 1048576,
    "lastModified": 1712123456789
  }
]
```

### GET /api/files/{filename}

下载文件。

**请求**
```
GET /api/files/document.pdf
```

**响应**: 文件二进制流

### DELETE /api/files/{filename}

删除文件。

**响应**
```json
{
  "status": "ok",
  "message": "File deleted"
}
```

---

## 5. 日志管理

### GET /api/logs

获取日志文件列表。

**响应**
```json
[
  {
    "name": "log_20260403_111952_001.txt",
    "size": 5242880,
    "lastModified": 1712123456789,
    "path": "/data/data/.../logs/log_20260403_111952_001.txt"
  }
]
```

### GET /api/logs/stats

获取日志统计信息。

**响应**
```json
{
  "fileCount": 12,
  "totalSize": 45678901,
  "totalSizeMB": 43.56,
  "maxFileSize": 10485760,
  "maxFileSizeMB": 10.0,
  "maxTotalSize": 104857600,
  "maxTotalSizeMB": 100.0,
  "maxFileCount": 50,
  "currentFileSize": 5242880,
  "currentFileSizeMB": 5.0
}
```

### POST /api/logs/config

配置日志参数。

**请求**
```json
{
  "maxFileSize": 20971520,
  "maxTotalSize": 209715200,
  "maxFileCount": 100
}
```

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| maxFileSize | Long | 10485760 (10MB) | 单文件最大大小 |
| maxTotalSize | Long | 104857600 (100MB) | 总存储最大大小 |
| maxFileCount | Int | 50 | 最大文件数量 |

**响应**
```json
{
  "status": "ok",
  "message": "Logger configured",
  "config": {
    "maxFileSize": 20971520,
    "maxTotalSize": 209715200,
    "maxFileCount": 100
  }
}
```

### GET /api/logs/{filename}

下载日志文件。

**响应**: 日志文件文本内容

### GET /api/logs/{filename}/tail

读取日志文件尾部。

**请求**
```
GET /api/logs/log_20260403_111952_001.txt/tail?lines=100
```

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| lines | Int | 100 | 读取行数 |

**响应**: 日志文本内容

### DELETE /api/logs/{filename}

删除指定日志文件。

### DELETE /api/logs

清空所有日志文件。

**响应**
```json
{
  "status": "ok",
  "message": "All logs cleared"
}
```

### POST /api/logs/clean

清理旧日志。

**请求**
```
POST /api/logs/clean?days=7
```

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| days | Int | 7 | 保留天数 |

**响应**
```json
{
  "status": "ok",
  "message": "Old logs cleaned, keep 7 days"
}
```

---

## 6. 系统日志 (Logcat)

### GET /api/logcat

获取系统 logcat 输出。

**请求**
```
GET /api/logcat?lines=500&filter=KtorServerService
```

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| lines | Int | 500 | 读取行数 |
| filter | String | "" | TAG 过滤 |

**响应**: logcat 文本内容

### POST /api/logcat/export

导出 logcat 到文件。

**请求**
```
POST /api/logcat/export?lines=5000
```

**响应**
```json
{
  "status": "ok",
  "message": "Logcat exported",
  "filename": "logcat_1712123456789.txt",
  "size": 524288,
  "path": "/data/data/.../logs/logcat_1712123456789.txt"
}
```

### DELETE /api/logcat

清除 logcat 缓冲区。

**响应**
```json
{
  "status": "ok",
  "message": "Logcat buffer cleared"
}
```

---

## 7. 系统状态

### GET /api/system/status

获取系统状态。

**响应**
```json
{
  "memory": {
    "totalMemory": 134217728,
    "freeMemory": 67108864,
    "usedMemory": 67108864,
    "maxMemory": 268435456
  },
  "storage": {
    "totalSpace": 64000000000,
    "freeSpace": 32000000000,
    "usedSpace": 32000000000
  },
  "serverRunning": true,
  "serverPort": 8080,
  "processId": 12345
}
```

---

## 8. 服务器配置

### GET /api/config

获取服务器配置。

**响应**
```json
{
  "port": 8080,
  "maxFileSize": 10485760,
  "maxFileSizeMB": 10,
  "maxTotalSize": 104857600,
  "maxTotalSizeMB": 100,
  "maxFileCount": 50,
  "logLevel": "V",
  "tagFilter": "",
  "serverRunning": true,
  "uptime": 3600000
}
```

### POST /api/config/port

修改服务器端口。

**请求**
```json
{
  "port": 9090
}
```

**响应**
```json
{
  "status": "ok",
  "message": "Port updated to 9090",
  "newPort": 9090,
  "requiresRestart": false
}
```

### POST /api/logs/config

配置日志参数 (代码实际实现)。

**请求**
```json
{
  "maxFileSize": 20971520,
  "maxTotalSize": 209715200,
  "maxFileCount": 100
}
```

### POST /api/config/restart

重启服务器。

**响应**
```json
{
  "status": "ok",
  "message": "Server restarting..."
}
```

### POST /api/device/time

设置设备时间。

**请求**
```json
{
  "timestamp": 1712123456789,
  "timezone": "Asia/Shanghai"
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| timestamp | Long | 时间戳 (毫秒)，可选 |
| timezone | String | 时区，如 "Asia/Shanghai"，可选 |

**响应**
```json
{
  "success": true,
  "method": "root",
  "timestamp": 1712123456789,
  "message": "Time updated"
}
```

**注意**: 修改设备时间需要 Root 权限。

---

## 错误响应

所有错误响应格式:

```json
{
  "status": "error",
  "message": "Error description"
}
```

HTTP 状态码:
- `200` - 成功
- `400` - 请求参数错误
- `404` - 资源不存在
- `500` - 服务器内部错误

---

## 文件上传限制

| 配置 | 值 |
|------|-----|
| 单文件最大 | 2GB |
| 缓冲区大小 | 64KB |

---

## 日志文件管理

### 特性
- **自动分文件**: 单文件达到 maxFileSize 时自动分割
- **LRU 清理**: 自动删除最旧的文件，保持总大小和文件数限制
- **命名规则**: `log_YYYYMMDD_HHMMSS_XXX.txt`

### 存储位置
```
/data/data/com.example.androidserver/files/
├── logs/           # 日志文件
├── uploads/        # 上传文件
├── updates/        # APK 更新文件
└── temp/           # 临时文件
```

---

## 完整示例

### Python 客户端

```python
import requests

BASE_URL = "http://192.168.1.100:8080"

# 健康检查
resp = requests.get(f"{BASE_URL}/")
print(resp.json())

# 获取设备信息
resp = requests.get(f"{BASE_URL}/api/device/info")
print(resp.json())

# 上传 APK
with open("app.apk", "rb") as f:
    resp = requests.post(f"{BASE_URL}/api/app/update/upload", files={"apk": f})
print(resp.json())

# 上传文件
with open("data.zip", "rb") as f:
    resp = requests.post(f"{BASE_URL}/api/files/upload", files={"file": f})
print(resp.json())

# 获取日志统计
resp = requests.get(f"{BASE_URL}/api/logs/stats")
print(resp.json())

# 配置日志 (注意: 实际路由是 /api/logs/config)
resp = requests.post(f"{BASE_URL}/api/logs/config", json={
    "maxFileSize": 20971520,
    "maxTotalSize": 209715200,
    "maxFileCount": 100
})
print(resp.json())

# 修改端口
resp = requests.post(f"{BASE_URL}/api/config/port", json={"port": 9090})
print(resp.json())

# 重启服务器
resp = requests.post(f"{BASE_URL}/api/config/restart")
print(resp.json())

# 设置设备时间 (需要 root)
resp = requests.post(f"{BASE_URL}/api/device/time", json={
    "timezone": "Asia/Shanghai"
})
print(resp.json())
```

### cURL 示例

```bash
# 健康检查
curl http://192.168.1.100:8080/

# 获取设备信息
curl http://192.168.1.100:8080/api/device/info

# 上传 APK
curl -X POST http://192.168.1.100:8080/api/app/update/upload -F "apk=@app.apk"

# 上传文件
curl -X POST http://192.168.1.100:8080/api/files/upload -F "file=@data.zip"

# 获取日志统计
curl http://192.168.1.100:8080/api/logs/stats

# 配置日志
curl -X POST http://192.168.1.100:8080/api/logs/config \
  -H "Content-Type: application/json" \
  -d '{"maxFileSize":20971520,"maxTotalSize":209715200,"maxFileCount":100}'

# 获取 logcat
curl "http://192.168.1.100:8080/api/logcat?lines=100&filter=KtorServerService"

# 重启应用
curl -X POST http://192.168.1.100:8080/api/app/restart
```

---

## API 总览

| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/` | 健康检查 |
| GET | `/api/device/info` | 设备信息 |
| POST | `/api/device/time` | 设置设备时间 |
| POST | `/api/device/reboot` | 重启设备 |
| POST | `/api/app/restart` | 重启应用 |
| GET | `/api/app/update/check` | 检查版本 |
| POST | `/api/app/update/upload` | 上传 APK 安装 |
| POST | `/api/files/upload` | 上传文件 |
| GET | `/api/files` | 文件列表 |
| GET | `/api/files/{name}` | 下载文件 |
| DELETE | `/api/files/{name}` | 删除文件 |
| GET | `/api/logs` | 日志列表 |
| GET | `/api/logs/stats` | 日志统计 |
| GET | `/api/logs/{name}` | 下载日志 |
| GET | `/api/logs/{name}/tail` | 读取日志尾部 |
| DELETE | `/api/logs/{name}` | 删除日志 |
| DELETE | `/api/logs` | 清空日志 |
| POST | `/api/logs/config` | 配置日志参数 |
| POST | `/api/logs/clean` | 清理旧日志 |
| GET | `/api/logcat` | 获取 logcat |
| POST | `/api/logcat/export` | 导出 logcat |
| DELETE | `/api/logcat` | 清除 logcat |
| GET | `/api/system/status` | 系统状态 |
| GET | `/api/config` | 获取服务器配置 |
| POST | `/api/config/port` | 修改端口 |
| POST | `/api/config/restart` | 重启服务器 |

---

## 版本

- API Version: 1.0
- Ktor Version: 2.3.12
- Kotlin Version: 1.9.22
