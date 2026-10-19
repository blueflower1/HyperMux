# HyperMux 一键自检：确认后端、App 接口、后台接口、OpenList、图片代理是否都正常
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = 'http://127.0.0.1:8080'
function OK($label, $cond, $extra) {
  $tag = if ($cond) { 'OK  ' } else { 'FAIL' }
  Write-Host ($label.PadRight(30) + ' ' + $tag + ' ' + $extra) -ForegroundColor $(if ($cond) { 'Green' } else { 'Red' })
}
Write-Host '=========== HyperMux 自检 ===========' -ForegroundColor Cyan
try {
  $al = Invoke-RestMethod -Uri "$base/api/app/auth/login" -Method Post -ContentType 'application/json; charset=utf-8' -Body '{"username":"apptest","password":"Apptest123"}' -TimeoutSec 10
  OK 'App 登录(apptest)' ($al.code -eq 0) $al.data.user.username
  $ah = @{ Authorization = "Bearer $($al.data.token)" }
  $hv = Invoke-RestMethod -Uri "$base/api/app/home" -Headers $ah -TimeoutSec 15
  OK 'App 首页' ($hv.code -eq 0) ("分类 " + $hv.data.categories.Count + " / 续看 " + $hv.data.continueWatching.Count)
  if ($hv.data.categories.Count -gt 0) {
    $cards = Invoke-RestMethod -Uri ("$base/api/app/category/" + $hv.data.categories[0].id + "/movies?page=1&size=5") -Headers $ah -TimeoutSec 15
    $c = $cards.data.list[0]
    OK '片单 + 横图' ($null -ne $c) ($c.title + "  横图=" + (-not [string]::IsNullOrEmpty($c.backdropUrl)))
    if ($c.coverUrl) {
      $r = Invoke-WebRequest -Uri ($base + '/img?u=' + [uri]::EscapeDataString($c.coverUrl)) -UseBasicParsing -TimeoutSec 30
      OK '图片代理出图' ($r.StatusCode -eq 200 -and $r.Headers['Content-Type'] -like 'image/*') ($r.Headers['Content-Type'] + ' ' + [math]::Round($r.RawContentLength/1024,1) + 'KB')
    }
  }
} catch { OK 'App 接口' $false $_.Exception.Message }
try {
  $adm = Invoke-RestMethod -Uri "$base/api/admin/auth/login" -Method Post -ContentType 'application/json; charset=utf-8' -Body '{"username":"admin","password":"Admin@1234"}' -TimeoutSec 10
  OK '后台登录(admin)' ($adm.code -eq 0) $adm.data.user.username
  $h = @{ Authorization = "Bearer $($adm.data.token)" }
  $db = Invoke-RestMethod -Uri "$base/api/admin/dashboard" -Headers $h -TimeoutSec 15
  OK '后台总览' ($db.code -eq 0) ("分类" + $db.data.categories + " 发布" + $db.data.publishedMovies + " 用户" + $db.data.users + " 源" + $db.data.sources)
  $so = Invoke-RestMethod -Uri "$base/api/admin/sources" -Headers $h -TimeoutSec 15
  if ($so.data.list.Count -gt 0) {
    $t = Invoke-RestMethod -Uri ("$base/api/admin/sources/" + $so.data.list[0].id + '/test') -Method Post -Headers $h -ContentType 'application/json' -TimeoutSec 40
    OK 'OpenList 连通' ($t.data.ok) $t.data.message
  }
  $ui = Invoke-WebRequest -Uri "$base/" -UseBasicParsing -TimeoutSec 10
  OK '后台页面(同源)' ($ui.StatusCode -eq 200 -and $ui.Content -match 'HyperMux') ('HTTP ' + $ui.StatusCode)
} catch { OK '后台接口' $false $_.Exception.Message }
Write-Host '=====================================' -ForegroundColor Cyan
Read-Host '按回车关闭'