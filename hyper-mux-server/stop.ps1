# 停止 HyperMux 后端
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$found = $false
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*hypermux-server.jar*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force; $found = $true; Write-Host "已停止 PID=$($_.ProcessId)" }
if (-not $found) { Write-Host '没有正在运行的后端服务。' }
Start-Sleep -Seconds 1
