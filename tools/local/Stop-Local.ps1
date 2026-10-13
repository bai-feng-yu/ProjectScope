[CmdletBinding()]
param([switch]$KeepDatabase)
. (Join-Path $PSScriptRoot 'Common.ps1')
Initialize-LocalDirectory
$launcherLock=Enter-LocalLock
try {
    $state=Read-LocalState
    if ($null -ne $state) {
        if ($state.Owner -ne $script:Owner) { throw 'Launcher state belongs to another checkout.' }
        Stop-LocalProcess $state.App
        Stop-LocalProcess $state.Helper
    }
    if (-not $KeepDatabase) {
        $database=Get-LocalDockerObject 'container' $script:Container
        if ($null -ne $database) {
            Assert-LocalOwnership $database 'container'
            & docker stop $script:Container | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Could not stop the owned MySQL container.' }
        }
    }
    Write-Host 'Local processes stopped. Database volume and encrypted credentials were preserved.'
} finally { $launcherLock.Dispose() }
