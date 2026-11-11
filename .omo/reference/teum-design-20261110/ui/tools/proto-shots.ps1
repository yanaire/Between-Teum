# 프로토타입 화면 여러 개를 헤드리스 Edge로 찍는다. 서버: node ui/serve.mjs
# 예: powershell -File ui\tools\proto-shots.ps1 -ids "H-01,M-F1" -q "&view=app&theme=dark"
param([string]$ids, [string]$q = "&view=app", [int]$w = 900, [int]$h = 1250, [string]$prefix = "p")
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$dir = Join-Path $env:TEMP "teum-shots"; New-Item -ItemType Directory -Force $dir | Out-Null
foreach ($id in $ids.Split(',')) {
  $out = Join-Path $dir "$prefix-$id.png"
  Start-Process -FilePath $edge -ArgumentList @("--headless=new","--disable-gpu","--hide-scrollbars","--user-data-dir=$dir\ud","--window-size=$w,$h","--virtual-time-budget=4000","--screenshot=$out","http://localhost:5179/proto/?s=$id$q") -Wait -WindowStyle Hidden
  $out
}
