# sengled-pair.ps1
# Automatizacion de pareo del foco Sengled W21-N11 a RAGNAR-2.4
# Uso:  powershell -ExecutionPolicy Bypass -File sengled-pair.ps1
#
# Flujo:
#   1) Reglas de firewall (pide UAC)
#   2) Espera el AP del foco (Sengled_Wi-Fi Bulb_9373)
#   3) Conecta la PC al AP del foco
#   4) Lanza el wizard no-interactivo con credenciales + --http-server-ip
#   5) Apenas el foco acepta credenciales -> vuelve a RAGNAR-2.4
#   6) Espera verificacion, muestra el log, busca el foco por ARP

param(
  [string]$BulbAP   = "Sengled_Wi-Fi Bulb_9373",
  [string]$HomeSSID = "RAGNAR-2.4",
  [string]$HomePass = "SUPERCELL123",
  [string]$ToolDir  = "C:\Users\tiran\Documents\Focos sengle\SengledTools"
)

$out = Join-Path $env:TEMP "sengled-pair-out.txt"
$bat = Join-Path $env:TEMP "sengled-pair-run.bat"
Remove-Item $out -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "   PAREADO AUTOMATICO FOCO SENGLE -> $HomeSSID" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

# ---- 0) Firewall (UAC) -----------------------------------------------
Write-Host "`n[1/7] Reglas de firewall (acepta el aviso de UAC si aparece)..." -ForegroundColor Yellow
$rules = @(
  'netsh advfirewall firewall add rule name="SengledTools HTTP 57542" dir=in action=allow protocol=TCP localport=57542 profile=private',
  'netsh advfirewall firewall add rule name="SengledTools UDP 9080" dir=in action=allow protocol=UDP localport=9080 profile=private'
)
foreach ($r in $rules) {
  try {
    Start-Process -FilePath "netsh.exe" -ArgumentList $r.Split(' ') -Verb RunAs -WindowStyle Hidden -Wait -ErrorAction Stop | Out-Null
    Write-Host "   regla OK" -ForegroundColor Green
  } catch {
    Write-Host "   (sin permisos admin: la verificacion puede fallar por firewall)" -ForegroundColor DarkYellow
    break
  }
}

# ---- 1) IP actual de casa ---------------------------------------------
function Get-WifiIP {
  $ip = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.InterfaceAlias -like "*Wi-Fi*" -and $_.IPAddress -notlike "169.254*" -and $_.IPAddress -ne "0.0.0.0" } |
        Select-Object -First 1
  if ($ip) { return $ip.IPAddress }
  return $null
}
$homeIP = Get-WifiIP
if (-not $homeIP) { Write-Host "[ERROR] No hay IP en el adaptador WiFi. Conectate a $HomeSSID y reintenta." -ForegroundColor Red; exit 1 }
Write-Host "`n[2/7] IP actual (red de casa): $homeIP" -ForegroundColor Green

# ---- 2) Esperar el AP del foco ----------------------------------------
Write-Host "`n[3/7] Esperando el AP del foco '$BulbAP'..." -ForegroundColor Yellow
Write-Host "   >>> SI NO APARECE: hace el RESET del foco (flick 5x rapido hasta que parpadee) <<<" -ForegroundColor Magenta
$found = $false
for ($i = 0; $i -lt 60; $i++) {
  $nets = netsh wlan show networks 2>$null | Out-String
  if ($nets -match [regex]::Escape($BulbAP)) { $found = $true; break }
  Start-Sleep -Seconds 5
}
if (-not $found) {
  Write-Host "[ERROR] El AP del foco no aparecio en 5 min. Hace el reset 5x y volve a correr." -ForegroundColor Red
  exit 1
}
Write-Host "   AP del foco detectado." -ForegroundColor Green

# ---- 3) Conectar al AP del foco ---------------------------------------
Write-Host "`n[4/7] Conectando al AP del foco..." -ForegroundColor Yellow
netsh wlan connect name="$BulbAP" | Out-Null
$bulbApIP = $null
for ($i = 0; $i -lt 30; $i++) {
  Start-Sleep -Seconds 2
  $bulbApIP = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
              Where-Object { $_.InterfaceAlias -like "*Wi-Fi*" -and $_.IPAddress -like "192.168.8.*" }).IPAddress
  if ($bulbApIP) { break }
}
if (-not $bulbApIP) {
  Write-Host "[ERROR] No se pudo tomar IP del AP del foco. Chequea el perfil '$BulbAP'." -ForegroundColor Red
  exit 1
}
Write-Host "   Conectado al foco (IP $bulbApIP). Enviando credenciales..." -ForegroundColor Green

# ---- 4) Wizard no-interactivo ------------------------------------------
@"
@echo off
set PYTHONIOENCODING=utf-8
cd /d "$ToolDir"
echo. | ".venv\Scripts\python.exe" sengled_tool.py --setup-wifi --verbose --ssid "$HomeSSID" --password "$HomePass" --http-server-ip "$homeIP" > "$out" 2>&1
"@ | Set-Content -Path $bat -Encoding ASCII
$p = Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "`"$bat`"" -PassThru -WindowStyle Hidden

# ---- 5) Esperar "credentials accepted" -> volver a casa -----------------
$accepted = $false
for ($i = 0; $i -lt 60; $i++) {
  Start-Sleep -Seconds 2
  if (Test-Path $out) {
    $log = Get-Content $out -Raw -ErrorAction SilentlyContinue
    if ($log -match "credentials accepted") { $accepted = $true; break }
    if ($log -match "Wi-Fi setup failed") { break }
  }
  if ($p.HasExited) { break }
}
if ($accepted) {
  Write-Host "`n[5/7] Credenciales aceptadas. Volviendo a '$HomeSSID'..." -ForegroundColor Green
  netsh wlan connect name="$HomeSSID" | Out-Null
  Start-Sleep -Seconds 6
} else {
  Write-Host "[AVISO] No se detecto la aceptacion de credenciales en el log." -ForegroundColor DarkYellow
}

# ---- 6) Esperar resultado de verificacion ------------------------------
Write-Host "`n[6/7] Esperando verificacion del foco (hasta 4 min, el foco parpadea)..." -ForegroundColor Yellow
$result = "running"
for ($i = 0; $i -lt 120; $i++) {
  Start-Sleep -Seconds 2
  if (Test-Path $out) {
    $log = Get-Content $out -Raw -ErrorAction SilentlyContinue
    if ($log -match "Wi-Fi setup complete") { $result = "success"; break }
    if ($log -match "Wi-Fi setup failed") { $result = "failed"; break }
  }
  if ($p.HasExited) { break }
}
if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }

# SIEMPRE volver a la red de casa (aunque el wizard haya fallado/crasheado)
$curIf = netsh wlan show interfaces 2>$null | Select-String "SSID"
if ($curIf -and $curIf.Line -match [regex]::Escape($BulbAP)) {
  Write-Host "`n(aun en el AP del foco - volviendo a '$HomeSSID')" -ForegroundColor Yellow
  netsh wlan connect name="$HomeSSID" | Out-Null
  Start-Sleep -Seconds 6
}

Write-Host "`n---------------- LOG DEL WIZARD ----------------" -ForegroundColor Cyan
if (Test-Path $out) { Get-Content $out -Tail 40 -Encoding UTF8 }

# ---- 7) Buscar el foco en la red de casa -------------------------------
Write-Host "`n[7/7] Buscando el foco en $HomeSSID (ARP sweep)..." -ForegroundColor Yellow
$hip = Get-WifiIP
if ($hip) {
  $parts = $hip.Split('.')
  $base = "$($parts[0]).$($parts[1]).$($parts[2])."
  1..254 | ForEach-Object { ping -n 1 -w 200 -i 1 "$base$_" > $null }
  $arp = arp -a | Select-String -Pattern "80-a0-36"
  if ($arp) {
    Write-Host "   FOCO ENCONTRADO:" -ForegroundColor Green
    $arp | ForEach-Object { Write-Host "   $($_.Line.Trim())" -ForegroundColor Green }
  } else {
    Write-Host "   Foco no visible todavia en ARP. Si el wizard dijo complete, espera 30s y proba controlarlo por UDP." -ForegroundColor Yellow
  }
}

Write-Host ""
if ($result -eq "success") { Write-Host "RESULTADO: PAREADO COMPLETO" -ForegroundColor Green }
elseif ($result -eq "failed") { Write-Host "RESULTADO: VERIFICACION FALLIDA (revisar firewall / log)" -ForegroundColor Red }
else { Write-Host "RESULTADO: SIN CONFIRMACION DEL WIZARD (revisar log)" -ForegroundColor Yellow }
Write-Host ""
