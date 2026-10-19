<#
  自绘图标离线自检：用真实 Kotlin 语义跑 PlayerIcons.kt 的 SVG 解析 + 圆弧转贝塞尔代码，
  校验全部图标（抛异常 / 坐标非有限 / 坐标越界）。

  用法（PowerShell，工程根或任意目录均可）：
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\iconcheck\Run-IconCheck.ps1

  原理：直接调用 Gradle 缓存里的 kotlin-compiler-embeddable 编译 tools\iconcheck\IconCheck.kt，
  不需要 device / Android SDK / Gradle 任务。新增自绘图标后跑一遍，比装到机器上试快得多。
#>
$ErrorActionPreference = "Stop"

$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "D:\dev_home\jdk17" }
$java = Join-Path $jdk "bin\java.exe"
if (-not (Test-Path $java)) { throw "找不到 java：$java（请设置 JAVA_HOME）" }

$cache = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1"
function Find-Jar([string]$group, [string]$artifact, [string]$versionLike = "") {
    $base = Join-Path $cache "$group\$artifact"
    if (-not (Test-Path $base)) { return $null }
    Get-ChildItem $base -Recurse -Filter "*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' -and ($versionLike -eq "" -or $_.Name -match $versionLike) } |
        Select-Object -First 1 -ExpandProperty FullName
}

$compiler = Find-Jar "org.jetbrains.kotlin" "kotlin-compiler-embeddable"
$stdlib = Find-Jar "org.jetbrains.kotlin" "kotlin-stdlib" '2\.'
if (-not $stdlib) { $stdlib = Find-Jar "org.jetbrains.kotlin" "kotlin-stdlib" }
if (-not $compiler -or -not $stdlib) { throw "Gradle 缓存里找不到 kotlin-compiler-embeddable / kotlin-stdlib（先跑一次 Gradle 构建）" }

$deps = @(
    $compiler,
    $stdlib,
    (Find-Jar "org.jetbrains.kotlin" "kotlin-reflect"),
    (Find-Jar "org.jetbrains.kotlin" "kotlin-script-runtime"),
    (Find-Jar "org.jetbrains.kotlin" "kotlin-daemon-embeddable"),
    (Find-Jar "org.jetbrains.kotlinx" "kotlinx-coroutines-core-jvm"),
    (Find-Jar "org.jetbrains" "annotations"),
    (Find-Jar "org.jetbrains.intellij.deps" "trove4j")
) | Where-Object { $_ }
$cp = $deps -join ";"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Join-Path $here "IconCheck.kt"
$out = Join-Path $env:TEMP ("iconcheck_out_" + [Guid]::NewGuid().ToString("N").Substring(0, 8))

Write-Output "=== 编译 $src ==="
& $java -cp $cp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler $src -d $out -no-stdlib -cp $stdlib
if ($LASTEXITCODE -ne 0) { throw "编译失败" }

Write-Output "=== 运行 ==="
& $java -cp "$out;$stdlib" IconCheckKt
$code = $LASTEXITCODE
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
exit $code
