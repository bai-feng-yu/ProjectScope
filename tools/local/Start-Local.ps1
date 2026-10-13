[CmdletBinding()]
param(
    [ValidateSet('Demo','Live')][string]$Mode = 'Demo',
    [ValidateSet('alice','bob')][string]$User = 'alice',
    [int]$AppPort = 18080, [int]$HelperPort = 18081, [int]$DatabasePort = 13306,
    [switch]$NoBrowser, [switch]$NoClipboard, [switch]$Rebuild
)
. (Join-Path $PSScriptRoot 'Common.ps1')
Initialize-LocalDirectory
$launcherLock = Enter-LocalLock
$savedEnvironment = @{}
$started = @()
$pushed = $false
try {
    foreach ($port in @($AppPort, $HelperPort, $DatabasePort)) { if ($port -lt 1024 -or $port -gt 65535) { throw 'Use unprivileged ports between 1024 and 65535.' } }
    if (@($AppPort,$HelperPort,$DatabasePort | Select-Object -Unique).Count -ne 3) { throw 'The three service ports must be distinct.' }
    foreach ($command in @('java.exe','docker.exe')) { Get-Command $command -ErrorAction Stop | Out-Null }
    $existing = Read-LocalState
    if ($null -ne $existing -and $existing.Owner -ne $script:Owner) { throw 'Launcher state belongs to another checkout.' }
    if ($null -ne $existing) {
        if (-not $PSBoundParameters.ContainsKey('AppPort')) { $AppPort = $existing.AppPort }
        if (-not $PSBoundParameters.ContainsKey('HelperPort')) { $HelperPort = $existing.HelperPort }
        if (-not $PSBoundParameters.ContainsKey('DatabasePort')) { $DatabasePort = $existing.DatabasePort }
    }
    if ($null -ne $existing -and ((Test-LocalProcess $existing.App) -or (Test-LocalProcess $existing.Helper))) {
        if (-not ((Test-LocalProcess $existing.App) -and (Test-LocalProcess $existing.Helper))) { throw 'Only part of the environment is running. Run stop-local.cmd before retrying.' }
        if ($existing.Mode -ne $Mode -or $existing.AppPort -ne $AppPort -or $existing.HelperPort -ne $HelperPort -or $existing.DatabasePort -ne $DatabasePort -or $Rebuild) {
            throw 'The environment is already running with different settings. Run stop-local.cmd first.'
        }
        $state = $existing
        $secrets = Read-LocalSecrets
        Write-Host 'Reusing the local environment; no new processes or database will be created.'
    } else {
        if ($Mode -eq 'Live') {
            foreach ($name in @('LLM_API_KEY','LLM_MODEL','LLM_BASE_URL')) {
                if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) { throw "Live mode requires $name in this shell. Default Demo mode needs no provider keys." }
            }
        }
        Write-Host '[1/5] Checking Docker and reserved loopback ports...'
        $savedPreference = $ErrorActionPreference
        try { $ErrorActionPreference='Continue'; & docker info --format '{{.ServerVersion}}' *> $null; $dockerReady=$LASTEXITCODE -eq 0 }
        finally { $ErrorActionPreference=$savedPreference }
        if (-not $dockerReady) {
            $desktop = Join-Path $env:ProgramFiles 'Docker/Docker/Docker Desktop.exe'
            if (-not (Test-Path -LiteralPath $desktop)) { throw 'Install and start Docker Desktop with Linux containers first.' }
            Start-Process -FilePath $desktop -ArgumentList '--background' -WindowStyle Hidden
            $until=(Get-Date).AddSeconds(90)
            do {
                Start-Sleep -Seconds 2
                try { $ErrorActionPreference='Continue'; & docker info --format '{{.ServerVersion}}' *> $null; $dockerReady=$LASTEXITCODE -eq 0 }
                finally { $ErrorActionPreference=$savedPreference }
            } while (-not $dockerReady -and (Get-Date) -lt $until)
            if (-not $dockerReady) { throw 'Docker did not become ready. Start Docker Desktop and retry.' }
        }
        if (-not $PSBoundParameters.ContainsKey('AppPort')) {
            $AppPort = Find-LocalPort $AppPort @($HelperPort,$DatabasePort)
        }
        if (-not $PSBoundParameters.ContainsKey('HelperPort')) {
            $HelperPort = Find-LocalPort $HelperPort @($AppPort,$DatabasePort)
        }
        Assert-LocalPort $AppPort
        Assert-LocalPort $HelperPort
        Write-Host "Selected local ports: application=$AppPort identity=$HelperPort database=$DatabasePort"
        $database = Get-LocalDockerObject 'container' $script:Container
        $volumeInfo = Get-LocalDockerObject 'volume' $script:Volume
        if ($null -ne $database) { Assert-LocalOwnership $database 'container' }
        if ($null -ne $volumeInfo) { Assert-LocalOwnership $volumeInfo 'volume' }
        if (-not (Test-Path -LiteralPath $script:SecretFile)) {
            if ($null -ne $database -or $null -ne $volumeInfo) { throw 'Existing local database found but its encrypted credentials are missing. Refusing to replace data or guess credentials.' }
            Save-LocalSecrets ([pscustomobject]@{DatabasePassword=(New-LocalSecret); RootPassword=(New-LocalSecret); ControlKey=(New-LocalSecret)})
        }
        $secrets = Read-LocalSecrets
        $environment = @{
            MYSQL_DATABASE='projectscope_browser'; MYSQL_USER='projectscope_browser'; MYSQL_PASSWORD=$secrets.DatabasePassword; MYSQL_ROOT_PASSWORD=$secrets.RootPassword
            DB_URL="jdbc:mysql://127.0.0.1:$DatabasePort/projectscope_browser?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
            DB_USERNAME='projectscope_browser'; DB_PASSWORD=$secrets.DatabasePassword
            AUTH_ISSUER="http://127.0.0.1:$HelperPort"; AUTH_JWK_SET_URI="http://127.0.0.1:$HelperPort/jwks"; AUTH_AUDIENCE='projectscope-browser-local'
            LOCAL_BROWSER_CONTROL_KEY=$secrets.ControlKey; LOCAL_BROWSER_SESSION=[Guid]::NewGuid().ToString(); LOCAL_BROWSER_DEMO=($Mode -eq 'Demo').ToString().ToLowerInvariant()
            AGENT_SCHEDULER_ENABLED='true'; SPRING_APPLICATION_JSON=$null; SPRING_CONFIG_ADDITIONAL_LOCATION=$null
        }
        if ($Mode -eq 'Demo') {
            $environment.LLM_BASE_URL="http://127.0.0.1:$HelperPort"; $environment.LLM_MODEL='local-demo'; $environment.LLM_API_KEY='local-demo-not-a-provider-key'
            $environment.BRAVE_SEARCH_ENABLED='false'; $environment.BRAVE_SEARCH_API_KEY=''
        }
        foreach ($entry in $environment.GetEnumerator()) {
            $savedEnvironment[$entry.Key]=[Environment]::GetEnvironmentVariable($entry.Key)
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
        }
        Write-Host '[2/5] Preparing the dedicated persistent MySQL container...'
        if ($null -eq $database) {
            Assert-LocalPort $DatabasePort
            if ($null -eq $volumeInfo) {
                & docker volume create --label "projectscope.local.owner=$script:Owner" $script:Volume | Out-Null
                if ($LASTEXITCODE -ne 0) { throw 'Could not create the dedicated MySQL volume.' }
            }
            # Docker reads credential values from the process environment, never from command-line arguments.
            & docker create --name $script:Container --label "projectscope.local.owner=$script:Owner" `
                -p "127.0.0.1:${DatabasePort}:3306" --mount "type=volume,source=$script:Volume,target=/var/lib/mysql" `
                -e MYSQL_DATABASE -e MYSQL_USER -e MYSQL_PASSWORD -e MYSQL_ROOT_PASSWORD `
                --health-cmd 'mysqladmin ping -h 127.0.0.1 --silent' --health-interval 2s --health-timeout 2s --health-retries 60 mysql:8.4 | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'MySQL container creation failed. Check Docker Hub connectivity.' }
        } else {
            $bindings = @($database.HostConfig.PortBindings.'3306/tcp')
            if ($bindings.Count -ne 1 -or $bindings[0].HostIp -ne '127.0.0.1' -or $bindings[0].HostPort -ne "$DatabasePort") {
                throw 'Existing local MySQL uses different port bindings; refusing to replace it.'
            }
        }
        & docker start $script:Container | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not start local MySQL.' }
        $until=(Get-Date).AddSeconds(120)
        do {
            $database=Get-LocalDockerObject 'container' $script:Container
            if ($database.State.Health.Status -eq 'healthy') { break }
            Start-Sleep -Seconds 2
        } while ((Get-Date) -lt $until)
        if ($database.State.Health.Status -ne 'healthy') { throw 'MySQL did not become healthy; data was not deleted.' }
        Push-Location $script:RepoRoot; $pushed=$true
        Write-Host '[3/5] Building the application and test-only local identity/model helper...'
        $jar=Join-Path $script:RepoRoot 'target/agent-backend-0.0.1-SNAPSHOT.jar'
        $classpathFile=Join-Path $script:StateRoot 'classpath.txt'
        $needsBuild=$Rebuild -or -not (Test-Path -LiteralPath $jar) -or -not (Test-Path -LiteralPath $classpathFile)
        if (-not $needsBuild) {
            $inputs=@(Get-ChildItem (Join-Path $script:RepoRoot 'src') -Recurse -File | Where-Object Name -ne 'application-local.properties') + @(Get-Item (Join-Path $script:RepoRoot 'pom.xml'))
            $needsBuild=@($inputs | Where-Object LastWriteTimeUtc -gt (Get-Item $jar).LastWriteTimeUtc).Count -gt 0
        }
        if ($needsBuild) {
            $savedPreference=$ErrorActionPreference
            try {
                $ErrorActionPreference='Continue'
                & .\mvnw.cmd -q -DskipTests package dependency:build-classpath '-DincludeScope=test' '-Dmdep.outputFile=.local-browser/classpath.txt' *> (Join-Path $script:StateRoot 'build.log')
                $buildCode=$LASTEXITCODE
            } finally { $ErrorActionPreference=$savedPreference }
            if ($buildCode -ne 0) { throw 'Build failed. Inspect .local-browser/build.log. JDK 21 and Maven download access are required.' }
        }
        $classpath=(Join-Path $script:RepoRoot 'target/test-classes') + ';' + (Join-Path $script:RepoRoot 'target/classes') + ';' + [IO.File]::ReadAllText($classpathFile).Trim()
        Write-Host '[4/5] Starting local services (loopback only)...'
        $helper=Start-LocalJava @('-cp',$classpath,'agent_backend.dev.LocalBrowserServices',"$HelperPort") 'helper'
        $started+=,$helper
        Wait-LocalHttp "http://127.0.0.1:$HelperPort/health" $helper | Out-Null
        $app=Start-LocalJava @('-jar',$jar,"--server.port=$AppPort",'--server.address=127.0.0.1',
            '--spring.config.location=classpath:/application.properties','--spring.profiles.active=browser-local',
            '--spring.main.banner-mode=off','--logging.level.root=INFO') 'app'
        $started+=,$app
        Wait-LocalHttp "http://127.0.0.1:$AppPort/" $app | Out-Null
        $state=[pscustomobject]@{Mode=$Mode; AppPort=$AppPort; HelperPort=$HelperPort; DatabasePort=$DatabasePort; AppUrl="http://127.0.0.1:$AppPort"; HelperUrl="http://127.0.0.1:$HelperPort"; Session=$environment.LOCAL_BROWSER_SESSION; App=$app; Helper=$helper; Owner=$script:Owner}
        Save-LocalState $state
    }
    Write-Host '[5/5] Checking JWT authentication and preparing the browser...'
    $token=Get-LocalToken $state $secrets $User
    Invoke-RestMethod "$($state.AppUrl)/conversations" -Headers @{Authorization="Bearer $token"} -TimeoutSec 10 | Out-Null
    if (-not $NoClipboard) { Set-Clipboard -Value $token }
    Write-Host "Ready: $($state.AppUrl)/   Mode: $($state.Mode)   User: $User"
    if (-not $NoClipboard) { Write-Host 'A one-hour local test JWT is on your clipboard. Paste into the token field and click Connect.' }
    if ($Mode -eq 'Demo') { Write-Host 'Demo replies are fixtures, not real research.' }
    Write-Host 'Data is stored in a separate persistent local MySQL volume.'
    Write-Host 'Stop safely with stop-local.cmd. To refresh a JWT or switch user, rerun start-local.cmd -User bob.'
    if (-not $NoBrowser) { Start-Process "$($state.AppUrl)/" }
} catch {
    foreach ($record in $started) { Stop-LocalProcess $record }
    Write-Error $_
    exit 1
} finally {
    if ($pushed) { Pop-Location }
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    $launcherLock.Dispose()
}
