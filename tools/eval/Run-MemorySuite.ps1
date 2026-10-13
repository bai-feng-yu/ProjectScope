[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$ModelConfig = 'src/main/resources/application-local.properties',
    [switch]$AllowPaidModel
)
$ErrorActionPreference = 'Stop'
if (-not $AllowPaidModel) { throw 'This suite makes paid model calls. Review docs/memory-baseline.md and explicitly pass -AllowPaidModel.' }
Push-Location ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')))
try {
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
        $destination = Join-Path $OutputDirectory $stage.Name
        & (Join-Path $PSScriptRoot 'Run-MemoryBaseline.ps1') -InputFile (Join-Path $stage.InputDirectory 'input.json') `
            -OutputDirectory $destination -ModelConfig $ModelConfig -MaxModelRequests $stage.Limit
        $classpath = 'target/test-classes;target/classes;' + [IO.File]::ReadAllText((Join-Path (Get-Location) '.memory-eval/classpath.txt')).Trim()
        & java -cp $classpath agent_backend.eval.MemoryScoring $stage.Name (Join-Path $stage.InputDirectory 'gold.json') $destination $ModelConfig
        if ($LASTEXITCODE -ne 0) { throw "Scoring failed for $($stage.Name); later stages were not started." }
    }
    Write-Host 'All stages answered and scored. Inspect coverage, per-category scores and judge limitations before drawing conclusions.'
} finally { Pop-Location }
