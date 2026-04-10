// Android Server Admin Console
(function() {
    const API_BASE = '';
    
    // 鉴权状态
    let authToken = localStorage.getItem('authToken');
    let tokenExpire = parseInt(localStorage.getItem('tokenExpire') || '0');

    function isLoggedIn() {
        return authToken && Date.now() < tokenExpire;
    }

    function setAuth(token, expiresIn) {
        authToken = token;
        tokenExpire = Date.now() + expiresIn;
        localStorage.setItem('authToken', token);
        localStorage.setItem('tokenExpire', tokenExpire.toString());
    }

    function clearAuth() {
        authToken = null;
        tokenExpire = 0;
        localStorage.removeItem('authToken');
        localStorage.removeItem('tokenExpire');
    }

    function showLogin() {
        document.getElementById('login-overlay').classList.remove('hidden');
    }

    function hideLogin() {
        document.getElementById('login-overlay').classList.add('hidden');
    }

    async function checkAuth() {
        if (!authToken) {
            showLogin();
            return false;
        }
        try {
            const res = await fetch(API_BASE + '/api/auth/check', {
                headers: { 'Authorization': 'Bearer ' + authToken }
            });
            const data = await res.json();
            if (data.authenticated) {
                hideLogin();
                return true;
            } else {
                clearAuth();
                showLogin();
                return false;
            }
        } catch (e) {
            // Token 无效，显示登录
            showLogin();
            return false;
        }
    }

    async function handleLogin(e) {
        e.preventDefault();
        const username = document.getElementById('login-username').value;
        const password = document.getElementById('login-password').value;
        const errorEl = document.getElementById('login-error');
        
        try {
            const res = await fetch(API_BASE + '/api/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username: username, password: password })
            });
            const data = await res.json();
            
            if (data.status === 'ok' && data.token) {
                setAuth(data.token, data.expiresIn);
                hideLogin();
                errorEl.style.display = 'none';
                showToast('登录成功', 'success');
                loadDashboard();
            } else {
                errorEl.textContent = data.message || '登录失败';
                errorEl.style.display = 'block';
            }
        } catch (e) {
            errorEl.textContent = '网络错误，请重试';
            errorEl.style.display = 'block';
        }
    }

    async function handleLogout() {
        try {
            await fetch(API_BASE + '/api/logout', {
                method: 'POST',
                headers: { 'Authorization': 'Bearer ' + authToken }
            });
        } catch (e) {}
        clearAuth();
        showLogin();
        showToast('已退出登录', 'info');
    }

    async function handleChangePassword() {
        const oldPassword = document.getElementById('old-password').value;
        const newPassword = document.getElementById('new-password').value;
        
        if (!oldPassword || !newPassword) {
            showToast('请填写完整', 'error');
            return;
        }
        
        try {
            const res = await fetch(API_BASE + '/api/auth/password', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': 'Bearer ' + authToken
                },
                body: JSON.stringify({ oldPassword: oldPassword, newPassword: newPassword })
            });
            const data = await res.json();
            
            if (data.status === 'ok') {
                showToast('密码已修改，请重新登录', 'success');
                handleLogout();
            } else {
                showToast(data.message || '修改失败', 'error');
            }
        } catch (e) {
            showToast('修改失败', 'error');
        }
    }

    function showToast(message, type = 'info') {
        let container = document.getElementById('toast-container');
        if (!container) {
            container = document.createElement('div');
            container.id = 'toast-container';
            container.className = 'toast-container';
            document.body.appendChild(container);
        }
        const toast = document.createElement('div');
        toast.className = 'toast ' + type;
        const icon = type === 'success' ? '✓' : type === 'error' ? '✗' : 'ℹ';
        toast.innerHTML = '<span>' + icon + '</span><span>' + message + '</span>';
        container.appendChild(toast);
        setTimeout(() => toast.remove(), 3000);
    }

    function initTheme() {
        const savedTheme = localStorage.getItem('theme') || 'light';
        document.documentElement.setAttribute('data-theme', savedTheme);
        updateThemeUI(savedTheme);
    }

    function toggleTheme() {
        const current = document.documentElement.getAttribute('data-theme');
        const next = current === 'dark' ? 'light' : 'dark';
        document.documentElement.setAttribute('data-theme', next);
        localStorage.setItem('theme', next);
        updateThemeUI(next);
    }

    function updateThemeUI(theme) {
        const icon = document.getElementById('theme-icon');
        const text = document.getElementById('theme-text');
        if (icon) icon.textContent = theme === 'dark' ? '☀' : '🌙';
        if (text) text.textContent = theme === 'dark' ? '浅色' : '深色';
    }

    function showSection(sectionId) {
        document.querySelectorAll('.section').forEach(s => s.classList.remove('active'));
        document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
        
        const section = document.getElementById(sectionId);
        const nav = document.querySelector('[data-section="' + sectionId + '"]');
        
        if (section) section.classList.add('active');
        if (nav) nav.classList.add('active');

        if (sectionId === 'dashboard') loadDashboard();
        if (sectionId === 'files') loadFiles();
        if (sectionId === 'logs') loadLogs();
        if (sectionId === 'config') loadConfig();
    }

    function formatBytes(bytes) {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }

    function formatDate(timestamp) {
        return new Date(timestamp).toLocaleString('zh-CN');
    }

    async function apiGet(endpoint) {
        const res = await fetch(API_BASE + endpoint, {
            headers: { 'Authorization': 'Bearer ' + authToken }
        });
        if (res.status === 401) {
            clearAuth();
            showLogin();
            throw new Error('Unauthorized');
        }
        return res.json();
    }

    async function apiPost(endpoint, data) {
        const res = await fetch(API_BASE + endpoint, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': 'Bearer ' + authToken
            },
            body: JSON.stringify(data)
        });
        if (res.status === 401) {
            clearAuth();
            showLogin();
            throw new Error('Unauthorized');
        }
        return res.json();
    }

    async function apiDelete(endpoint) {
        const res = await fetch(API_BASE + endpoint, {
            method: 'DELETE',
            headers: { 'Authorization': 'Bearer ' + authToken }
        });
        if (res.status === 401) {
            clearAuth();
            showLogin();
            throw new Error('Unauthorized');
        }
        return res.json();
    }

    async function loadDashboard() {
        try {
            const [device, system, files, logs] = await Promise.all([
                apiGet('/api/device/info'),
                apiGet('/api/system/status'),
                apiGet('/api/files'),
                apiGet('/api/logs')
            ]);

            document.getElementById('device-model').textContent = device.model || '--';
            document.getElementById('android-version').textContent = device.androidVersion || '--';
            document.getElementById('sdk-version').textContent = device.sdkVersion || '--';
            document.getElementById('wifi-ip').textContent = device.wifiIp || '--';
            document.getElementById('server-port').textContent = device.serverUrl ? device.serverUrl.split(':').pop() : '--';
            document.getElementById('app-version').textContent = device.versionName || '--';

            // 内存信息 - JVM 堆内存
            if (system.memory) {
                const used = system.memory.usedMemory || 0;
                const total = system.memory.totalMemory || 0;
                const max = system.memory.maxMemory || 0;
                const pct = total > 0 ? Math.round(used / total * 100) : 0;
                document.getElementById('mem-used').textContent = formatBytes(used) + ' (' + pct + '%)';
                document.getElementById('mem-max').textContent = formatBytes(max);
                document.getElementById('mem-detail').textContent = formatBytes(used) + ' / ' + formatBytes(total);
            }

            // 存储信息 - 应用私有存储分区
            if (system.storage) {
                const used = system.storage.usedSpace || 0;
                const total = system.storage.totalSpace || 1;
                const free = system.storage.freeSpace || 0;
                const pct = Math.round(used / total * 100);
                document.getElementById('storage-used').textContent = formatBytes(used) + ' (' + pct + '%)';
                document.getElementById('storage-free').textContent = formatBytes(free);
                document.getElementById('storage-detail').textContent = formatBytes(used) + ' / ' + formatBytes(total);
            }

            document.getElementById('process-id').textContent = system.processId || '--';
            document.getElementById('file-count').textContent = Array.isArray(files) ? files.length : 0;
            document.getElementById('log-count').textContent = Array.isArray(logs) ? logs.length : 0;
        } catch (e) {
            console.error('Dashboard load error:', e);
        }
    }

    async function loadFiles() {
        try {
            const files = await apiGet('/api/files');
            const tbody = document.getElementById('files-table');
            if (!Array.isArray(files) || files.length === 0) {
                tbody.innerHTML = '<tr><td colspan="4" class="empty-state">暂无文件</td></tr>';
                return;
            }
            tbody.innerHTML = files.map(f => 
                '<tr><td>' + f.name + '</td><td>' + formatBytes(f.size) + '</td><td>' + formatDate(f.lastModified) + '</td>' +
                '<td><button class="btn btn-sm btn-secondary" onclick="downloadFile(\'' + f.name + '\')">下载</button> ' +
                '<button class="btn btn-sm btn-danger" onclick="deleteFile(\'' + f.name + '\')">删除</button></td></tr>'
            ).join('');
        } catch (e) {
            console.error('Files load error:', e);
        }
    }

    window.deleteFile = async function(filename) {
        if (!confirm('确定要删除 ' + filename + ' 吗？')) return;
        try {
            await apiDelete('/api/files/' + filename);
            showToast('文件已删除', 'success');
            loadFiles();
        } catch (e) {
            showToast('删除失败', 'error');
        }
    };

    window.downloadFile = function(filename) {
        // 使用 fetch 下载并带上 Authorization header
        fetch(API_BASE + '/api/files/' + encodeURIComponent(filename), {
            headers: { 'Authorization': 'Bearer ' + authToken }
        }).then(function(res) {
            if (res.status === 401) {
                clearAuth();
                showLogin();
                return;
            }
            return res.blob();
        }).then(function(blob) {
            if (!blob) return;
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = filename;
            a.click();
            URL.revokeObjectURL(url);
        }).catch(function() {
            showToast('下载失败', 'error');
        });
    };

    async function loadLogs() {
        try {
            const logs = await apiGet('/api/logs');
            const tbody = document.getElementById('logs-table');
            if (!Array.isArray(logs) || logs.length === 0) {
                tbody.innerHTML = '<tr><td colspan="4" class="empty-state">暂无日志文件</td></tr>';
                return;
            }
            tbody.innerHTML = logs.map(l => 
                '<tr><td>' + l.name + '</td><td>' + formatBytes(l.size) + '</td><td>' + formatDate(l.lastModified) + '</td>' +
                '<td><button class="btn btn-sm btn-secondary" onclick="downloadLog(\'' + l.name + '\')">下载</button> ' +
                '<button class="btn btn-sm btn-danger" onclick="deleteLog(\'' + l.name + '\')">删除</button></td></tr>'
            ).join('');
        } catch (e) {
            console.error('Logs load error:', e);
        }
    }

    window.deleteLog = async function(filename) {
        if (!confirm('确定要删除 ' + filename + ' 吗？')) return;
        try {
            await apiDelete('/api/logs/' + filename);
            showToast('日志已删除', 'success');
            loadLogs();
        } catch (e) {
            showToast('删除失败', 'error');
        }
    };

    window.downloadLog = function(filename) {
        fetch(API_BASE + '/api/logs/' + encodeURIComponent(filename), {
            headers: { 'Authorization': 'Bearer ' + authToken }
        }).then(function(res) {
            if (res.status === 401) {
                clearAuth();
                showLogin();
                return;
            }
            return res.blob();
        }).then(function(blob) {
            if (!blob) return;
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = filename;
            a.click();
            URL.revokeObjectURL(url);
        }).catch(function() {
            showToast('下载失败', 'error');
        });
    };

    window.clearAllLogs = async function() {
        if (!confirm('确定要清空所有日志吗？')) return;
        try {
            await apiDelete('/api/logs');
            showToast('日志已清空', 'success');
            loadLogs();
        } catch (e) {
            showToast('清空失败', 'error');
        }
    };

    window.loadLogcat = async function() {
        try {
            const filter = document.getElementById('logcat-filter')?.value || '';
            const res = await fetch(API_BASE + '/api/logcat?lines=500&filter=' + encodeURIComponent(filter), {
                headers: { 'Authorization': 'Bearer ' + authToken }
            });
            if (res.status === 401) {
                clearAuth();
                showLogin();
                return;
            }
            const text = await res.text();
            document.getElementById('logcat-content').textContent = text;
        } catch (e) {
            showToast('加载日志失败', 'error');
        }
    };

    window.exportLogcat = async function() {
        try {
            await apiPost('/api/logcat/export');
            showToast('日志导出成功', 'success');
            loadLogs();
        } catch (e) {
            showToast('导出失败', 'error');
        }
    };

    async function loadConfig() {
        try {
            const config = await apiGet('/api/config');
            document.getElementById('config-port').value = config.port || 8080;
            document.getElementById('config-max-file-size').value = config.maxFileSizeMB || 10;
            document.getElementById('config-max-total-size').value = config.maxTotalSizeMB || 100;
            document.getElementById('config-max-file-count').value = config.maxFileCount || 50;
        } catch (e) {
            console.error('Config load error:', e);
        }
    }

    window.updatePort = async function() {
        const port = parseInt(document.getElementById('config-port').value);
        if (!port || port < 1024 || port > 65535) {
            showToast('请输入有效的端口号 (1024-65535)', 'error');
            return;
        }
        try {
            const res = await apiPost('/api/config/port', { port: port });
            if (res.requiresRestart) {
                showToast('端口已更新，正在跳转到新端口...', 'success');
                // 等服务端重启完成后跳转到新端口
                setTimeout(function() {
                    window.location.href = window.location.protocol + '//' + window.location.hostname + ':' + res.newPort + '/';
                }, 1500);
            } else {
                showToast('端口未变化', 'info');
            }
        } catch (e) {
            showToast('更新失败', 'error');
        }
    };

    async function updateLogConfig() {
        const maxFileSize = parseInt(document.getElementById('config-max-file-size').value) * 1024 * 1024;
        const maxTotalSize = parseInt(document.getElementById('config-max-total-size').value) * 1024 * 1024;
        const maxFileCount = parseInt(document.getElementById('config-max-file-count').value);
        try {
            await apiPost('/api/config/logs', { maxFileSize: maxFileSize, maxTotalSize: maxTotalSize, maxFileCount: maxFileCount });
            showToast('日志配置已更新', 'success');
        } catch (e) {
            showToast('更新失败', 'error');
        }
    }

    window.syncTime = async function() {
        try {
            const now = Date.now();
            const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
            await apiPost('/api/device/time', { timestamp: now, timezone: tz });
            showToast('时间同步成功', 'success');
        } catch (e) {
            showToast('时间同步失败', 'error');
        }
    };

    async function restartApp() {
        try {
            await apiPost('/api/app/restart');
            showToast('应用正在重启...', 'info');
        } catch (e) {
            showToast('重启失败', 'error');
        }
    }

    async function rebootDevice() {
        try {
            await apiPost('/api/device/reboot');
            showToast('设备正在重启...', 'info');
        } catch (e) {
            showToast('重启失败', 'error');
        }
    }

    function initUpload(zoneId, inputId, endpoint, onProgress) {
        const zone = document.getElementById(zoneId);
        const input = document.getElementById(inputId);
        if (!zone || !input) return;

        zone.addEventListener('click', () => input.click());
        
        zone.addEventListener('dragover', (e) => {
            e.preventDefault();
            zone.classList.add('dragover');
        });

        zone.addEventListener('dragleave', () => {
            zone.classList.remove('dragover');
        });

        zone.addEventListener('drop', (e) => {
            e.preventDefault();
            zone.classList.remove('dragover');
            handleFiles(e.dataTransfer.files, endpoint, onProgress);
        });

        input.addEventListener('change', (e) => {
            handleFiles(e.target.files, endpoint, onProgress);
        });
    }

    async function handleFiles(files, endpoint, onProgress) {
        for (const file of files) {
            const formData = new FormData();
            formData.append('file', file);

            try {
                const xhr = new XMLHttpRequest();
                
                if (onProgress) {
                    xhr.upload.addEventListener('progress', (e) => {
                        if (e.lengthComputable) {
                            const percent = Math.round((e.loaded / e.total) * 100);
                            onProgress(file.name, percent);
                        }
                    });
                }

                await new Promise((resolve, reject) => {
                    xhr.addEventListener('load', () => resolve(xhr.response));
                    xhr.addEventListener('error', reject);
                    xhr.open('POST', API_BASE + endpoint);
                    xhr.setRequestHeader('Authorization', 'Bearer ' + authToken);
                    xhr.send(formData);
                });

                showToast(file.name + ' 上传成功', 'success');
            } catch (e) {
                showToast(file.name + ' 上传失败', 'error');
            }
        }
        loadFiles();
        loadLogs();
    }

    document.addEventListener('DOMContentLoaded', () => {
        initTheme();
        
        // 登录表单事件
        document.getElementById('login-form')?.addEventListener('submit', handleLogin);
        document.getElementById('btn-logout')?.addEventListener('click', handleLogout);
        document.getElementById('change-password')?.addEventListener('click', handleChangePassword);
        
        // 检查登录状态
        checkAuth().then(function(authed) {
            if (authed) {
                loadDashboard();
            }
        });

        document.getElementById('theme-toggle')?.addEventListener('click', toggleTheme);
        
        document.querySelectorAll('.nav-item').forEach(item => {
            item.addEventListener('click', () => showSection(item.dataset.section));
        });

        document.getElementById('refresh-device')?.addEventListener('click', loadDashboard);
        document.getElementById('refresh-files')?.addEventListener('click', loadFiles);
        document.getElementById('refresh-logs')?.addEventListener('click', loadLogs);
        document.getElementById('clear-logs')?.addEventListener('click', clearAllLogs);
        document.getElementById('refresh-logcat')?.addEventListener('click', loadLogcat);
        document.getElementById('export-logcat')?.addEventListener('click', exportLogcat);
        document.getElementById('save-port')?.addEventListener('click', updatePort);
        document.getElementById('send-custom-config')?.addEventListener('click', function() {
            const jsonText = document.getElementById('custom-config-json').value.trim();
            if (!jsonText) {
                showToast('请输入 JSON 配置', 'error');
                return;
            }
            try {
                JSON.parse(jsonText);
            } catch (e) {
                showToast('JSON 格式错误', 'error');
                return;
            }
            fetch(API_BASE + '/api/config/custom', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': 'Bearer ' + authToken
                },
                body: jsonText
            }).then(function(res) {
                if (res.ok) {
                    showToast('配置已发送', 'success');
                } else {
                    showToast('发送失败', 'error');
                }
            }).catch(function() {
                showToast('发送失败', 'error');
            });
        });

        document.getElementById('save-log-config')?.addEventListener('click', updateLogConfig);
        document.getElementById('restart-server')?.addEventListener('click', async () => {
            if (confirm('确定要重启服务器吗？')) {
                await apiPost('/api/config/restart');
                showToast('服务器正在重启...', 'info');
            }
        });

        document.getElementById('btn-reboot')?.addEventListener('click', () => {
            if (confirm('确定要重启设备吗？')) rebootDevice();
        });

        document.getElementById('btn-restart-app')?.addEventListener('click', () => {
            if (confirm('确定要重启应用吗？')) restartApp();
        });

        document.getElementById('btn-sync-time')?.addEventListener('click', syncTime);

        initUpload('file-upload-zone', 'file-input', '/api/files/upload');
        initUpload('apk-upload-zone', 'apk-input', '/api/app/update/upload', (name, percent) => {
            document.getElementById('apk-upload-progress').style.display = 'block';
            document.getElementById('apk-filename').textContent = name;
            document.getElementById('apk-progress-text').textContent = percent + '%';
            document.getElementById('apk-progress-bar').style.width = percent + '%';
            if (percent === 100) {
                setTimeout(() => {
                    document.getElementById('apk-upload-progress').style.display = 'none';
                }, 2000);
            }
        });
    });

    window.showSection = showSection;
    window.toggleTheme = toggleTheme;
    window.refreshDeviceInfo = loadDashboard;
    window.loadFiles = loadFiles;
    window.loadLogs = loadLogs;
})();
