# 启动 HyperMux 后端（幂等：已在运行则先停旧的再启动）
# 用法：右键「使用 PowerShell 运行」，或双击同目录的 start.cmd

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$srv = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $srv 'target\hypermux-server.jar'
$log = Join-Path $srv 'target\run.log'
$mvn = 'D:\ai-projects\hyper-mux\tools\apache-maven-3.9.9\bin\mvn.cmd'
$java = 'D:\DEV_HOME\jdk17\bin\java.exe'

$env:JAVA_HOME = 'D:\DEV_HOME\jdk17'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'   # 中文 Windows 必须，否则乱码

Write-Host '=== HyperMux 后端启动 ===' -ForegroundColor Cyan

# 1) 停掉已在运行的实例
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*hypermux-server.jar*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host "  已停止旧进程 PID=$($_.ProcessId)" }
Start-Sleep -Seconds 2

# 2) jar 不存在就先构建
if (-not (Test-Path $jar)) {
    Write-Host '  未找到 jar，正在用 Maven 构建（首次约 1 分钟）…'
    if (-not (Test-Path $mvn)) { Write-Host "  找不到 Maven：$mvn" -ForegroundColor Red; Read-Host '回车退出'; exit 1 }
    Push-Location $srv
    & $mvn -B -o package -DskipTests
    Pop-Location
    if (-not (Test-Path $jar)) { Write-Host '  构建失败，请查看上面的错误。' -ForegroundColor Red; Read-Host '回车退出'; exit 1 }
}

# 3) 启动
Remove-Item $log -ErrorAction SilentlyContinue
Start-Process -FilePath $java -ArgumentList '-jar', $jar -WorkingDirectory $srv `
    -RedirectStandardOutput $log -RedirectStandardError (Join-Path $srv 'target\run.err.log') -WindowStyle Hidden

# 4) 等待 8080 就绪
$ready = $false
for ($i = 0; $i -lt 45; $i++) {
    try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', 8080); $c.Close(); $ready = $true; break }
    catch { Start-Sleep -Seconds 2 }
}

if ($ready) {
    Write-Host '  启动成功 ✔' -ForegroundColor Green
    Write-Host ''
    Write-Host '  后台管理面板（浏览器打开）：' -ForegroundColor Yellow
    Write-Host '    http://127.0.0.1:8080/            账号 admin / Admin@1234'
    Write-Host ''
    Write-Host '  手机 App 服务器地址请填（同一局域网）：' -ForegroundColor Yellow
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
        ForEach-Object { Write-Host ("    http://" + $_.IPAddress + ":8080      (" + $_.InterfaceAlias + ")") }
    Write-Host ''
    Write-Host '  日志：target\run.log     停止服务：双击 stop.cmd' -ForegroundColor DarkGray
} else {
    Write-Host '  启动失败（8080 未监听），日志尾部：' -ForegroundColor Red
    if (Test-Path $log) { Get-Content $log -Tail 30 -Encoding UTF8 }
}
Write-Host ''
Read-Host '按回车关闭此窗口（服务已在后台继续运行）'
