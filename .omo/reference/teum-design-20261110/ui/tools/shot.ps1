# 보드 스크린샷 (헤드리스 Edge). 먼저 서버: node ui/serve.mjs (또는 미리보기 'teum-ui')
# 예: powershell -File ui\tools\shot.ps1 -page key-8.html -name k-H-01 -query "?z=0.62&f=H-01"
param([string]$page, [string]$name, [string]$query = "", [int]$w = 1700, [int]$h = 980)
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$dir = Join-Path $env:TEMP "teum-shots"; New-Item -ItemType Directory -Force $dir | Out-Null
$out = Join-Path $dir "$name.png"
$p = Start-Process -FilePath $edge -ArgumentList @("--headless=new","--disable-gpu","--hide-scrollbars","--user-data-dir=$dir\ud","--window-size=$w,$h","--virtual-time-budget=6000","--screenshot=$out","http://localhost:5179/$page$query") -PassThru -Wait -WindowStyle Hidden
"$out exit=$($p.ExitCode)"
