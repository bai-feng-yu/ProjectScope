Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$script:StateRoot = Join-Path $script:RepoRoot '.local-browser'
$script:StateFile = Join-Path $script:StateRoot 'state.json'
$script:SecretFile = Join-Path $script:StateRoot 'secrets.dpapi'
$sha = [Security.Cryptography.SHA256]::Create()
try { $script:Owner = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($script:RepoRoot.ToLowerInvariant())))).Replace('-', '').Substring(0, 12).ToLowerInvariant() }
finally { $sha.Dispose() }
$script:Container = "projectscope-browser-$script:Owner"
$script:Volume = "$script:Container-data"

function Initialize-LocalDirectory {
    if (Test-Path -LiteralPath $script:StateRoot) {
        if ((Get-Item -LiteralPath $script:StateRoot).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'The local runtime directory must not be a junction or symbolic link.'
        }
    } else { New-Item -ItemType Directory -Path $script:StateRoot | Out-Null }
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls.exe $script:StateRoot '/inheritance:r' '/grant:r' "${identity}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not restrict local runtime directory permissions.' }
}
function Enter-LocalLock {
    try { return [IO.File]::Open((Join-Path $script:StateRoot 'launcher.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw 'Another local launcher or stop operation is already running.' }
}
function New-LocalSecret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes)
}
function Read-LocalSecrets {
    $protected = [IO.File]::ReadAllText($script:SecretFile) | ConvertTo-SecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($protected)
    try { return ([Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) | ConvertFrom-Json) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $protected.Dispose() }
}
function Save-LocalSecrets($Secrets) {
    $protected = ConvertTo-SecureString ($Secrets | ConvertTo-Json -Compress) -AsPlainText -Force
    try { [IO.File]::WriteAllText($script:SecretFile, ($protected | ConvertFrom-SecureString)) }
    finally { $protected.Dispose() }
}
function Read-LocalState {
    if (Test-Path -LiteralPath $script:StateFile) { return ([IO.File]::ReadAllText($script:StateFile) | ConvertFrom-Json) }
    return $null
}
function Save-LocalState($State) { [IO.File]::WriteAllText($script:StateFile, ($State | ConvertTo-Json -Depth 8)) }
function Test-LocalProcess($Record) {
    if ($null -eq $Record) { return $false }
    $process = Get-Process -Id $Record.Id -ErrorAction SilentlyContinue
    return $null -ne $process -and $process.ProcessName -eq 'java' -and $process.StartTime.ToUniversalTime().Ticks -eq [long]$Record.StartTicks
}
function Stop-LocalProcess($Record) {
    # PID alone is insufficient: never stop a process whose PID has since been reused.
    if (Test-LocalProcess $Record) { Stop-Process -Id $Record.Id -Force }
}
function Start-LocalJava([string[]]$JavaArguments, [string]$LogName) {
    # Oracle's javapath shim spawns a child JVM. Launch the real binary so PID tracking stops the actual service.
    $saved = $ErrorActionPreference
    try { $ErrorActionPreference = 'Continue'; $settings = & java.exe -XshowSettings:properties -version 2>&1 }
    finally { $ErrorActionPreference = $saved }
    $homeLine = @($settings | ForEach-Object { "$_" } | Where-Object { $_ -match '^\s*java.home\s*=' })
    if ($homeLine.Count -ne 1) { throw 'Could not resolve the installed JDK directory.' }
    $javaBinary = Join-Path (($homeLine[0] -split '=',2)[1].Trim()) 'bin/java.exe'
    if (-not (Test-Path -LiteralPath $javaBinary)) { throw 'The resolved JDK executable does not exist.' }
    $quoted = $JavaArguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }
    $process = Start-Process -FilePath $javaBinary -ArgumentList $quoted -WorkingDirectory $script:RepoRoot `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $script:StateRoot "$LogName.out.log") `
        -RedirectStandardError (Join-Path $script:StateRoot "$LogName.err.log")
    return [pscustomobject]@{ Id = $process.Id; StartTicks = $process.StartTime.ToUniversalTime().Ticks }
}
function Assert-LocalPort([int]$Port) {
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, $Port)
    try { $listener.Start() } catch {
        throw "Cannot bind local port ${Port}: it is occupied or reserved by Windows. Choose different launcher ports; inspect reservations with: netsh interface ipv4 show excludedportrange protocol=tcp"
    }
    finally { $listener.Stop() }
}
function Find-LocalPort([int]$Preferred, [int[]]$Exclude) {
    # Windows/Hyper-V can reserve previously usable ports after a reboot. Probe without changing system reservations.
    for ($candidate = $Preferred; $candidate -le [Math]::Min(65535, $Preferred + 2000); $candidate++) {
        if ($Exclude -contains $candidate) { continue }
        try { Assert-LocalPort $candidate; return $candidate } catch { }
    }
    throw "No available local port near $Preferred. Specify explicit launcher ports."
}
function Get-LocalDockerObject([string]$Kind, [string]$Name) {
    $saved = $ErrorActionPreference
    try { $ErrorActionPreference = 'Continue'; $raw = & docker $Kind inspect $Name 2>$null; $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $saved }
    if ($code -ne 0) { return $null }
    return @($raw | ConvertFrom-Json)[0]
}
function Assert-LocalOwnership($Object, [string]$Kind) {
    $labels = if ($Kind -eq 'container') { $Object.Config.Labels } else { $Object.Labels }
    if ($null -eq $labels -or $null -eq $labels.PSObject.Properties['projectscope.local.owner'] -or $labels.'projectscope.local.owner' -ne $script:Owner) {
        throw "Existing $Kind is not owned by this checkout. It will not be modified."
    }
}
function Wait-LocalHttp([string]$Url, $Record, [int]$Seconds = 90) {
    $until = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $until) {
        if (-not (Test-LocalProcess $Record)) { throw 'A local Java process exited. Check .local-browser/*.err.log.' }
        try { return (Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 2) } catch { Start-Sleep -Milliseconds 500 }
    }
    throw "Timed out waiting for $Url. Check the local runtime logs."
}
function Get-LocalToken($State, $Secrets, [string]$User) {
    $health = Invoke-RestMethod -Uri "$($State.HelperUrl)/health" -TimeoutSec 3
    if ($health.service -ne 'projectscope-local-browser' -or $health.session -ne $State.Session) { throw 'Local identity service ownership check failed.' }
    return (Invoke-RestMethod -Uri "$($State.HelperUrl)/token" -Method Post -ContentType 'application/json' `
        -Headers @{ 'X-Local-Control' = $Secrets.ControlKey } -Body (@{user=$User} | ConvertTo-Json) -TimeoutSec 5).access_token
}
