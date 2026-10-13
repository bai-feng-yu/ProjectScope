[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$ModelConfig = 'src/main/resources/application-local.properties',
    [ValidateRange(1,16)][int]$Parallelism = 1,
    [ValidateRange(30,1800)][int]$ProviderTimeoutSeconds = 300,
    [ValidateRange(0,5)][int]$MaxRetries = 2,
    [switch]$AllowPaidModel
)
$ErrorActionPreference = 'Stop'
if (-not $AllowPaidModel) { throw 'This suite makes paid model calls. Review docs/memory-baseline.md and explicitly pass -AllowPaidModel.' }
Push-Location ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')))
try {
    $attemptDirectory = Join-Path $OutputDirectory ("attempt-" + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))
    $stages = @(
        @{Name='longmemeval'; InputDirectory='.memory-eval/data/longmemeval-s-56-seed20261013'; Limit=15000},
        @{Name='personamem'; InputDirectory='.memory-eval/data/personamem-32k-28-seed20261013'; Limit=3000},
        @{Name='memora'; InputDirectory='.memory-eval/data/memora-weekly-software-engineer'; Limit=2000}
    )
    foreach ($stage in $stages) {
        foreach ($file in @('input.json','gold.json','manifest.json')) {
            if (-not (Test-Path -LiteralPath (Join-Path $stage.InputDirectory $file))) { throw "Missing $file for $($stage.Name). Prepare datasets first." }
        }
    }
    foreach ($stage in $stages) {
        Write-Host "Starting $($stage.Name) at $([DateTime]::UtcNow.ToString('o'))"
        $destination = Join-Path $attemptDirectory $stage.Name
        & (Join-Path $PSScriptRoot 'Run-MemoryBaseline.ps1') -InputFile (Join-Path $stage.InputDirectory 'input.json') `
            -OutputDirectory $destination -ModelConfig $ModelConfig -MaxModelRequests $stage.Limit `
            -Parallelism $Parallelism -ProviderTimeoutSeconds $ProviderTimeoutSeconds -MaxRetries $MaxRetries
        $classpath = 'target/test-classes;target/classes;' + [IO.File]::ReadAllText((Join-Path (Get-Location) '.memory-eval/classpath.txt')).Trim()
        & java -cp $classpath agent_backend.eval.MemoryScoring $stage.Name (Join-Path $stage.InputDirectory 'gold.json') $destination $ModelConfig
        if ($LASTEXITCODE -ne 0) { throw "Scoring failed for $($stage.Name); later stages were not started." }
    }
    Write-Host "All stages answered and scored in $attemptDirectory. Inspect coverage, per-category scores and judge limitations before drawing conclusions."
} finally { Pop-Location }
