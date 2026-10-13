[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$InputFile,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$ModelConfig = 'src/main/resources/application-local.properties',
    [ValidateRange(1,100000)][int]$MaxModelRequests = 10000
)
$ErrorActionPreference = 'Stop'
Push-Location ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')))
try {
    New-Item -ItemType Directory -Force .memory-eval | Out-Null
    # Compile test-only harness; ordinary unit tests never invoke paid model endpoints.
    & .\mvnw.cmd -q test-compile dependency:build-classpath '-DincludeScope=test' '-Dmdep.outputFile=.memory-eval/classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Evaluation build failed.' }
    $classpath = 'target/test-classes;target/classes;' + [IO.File]::ReadAllText((Join-Path (Get-Location) '.memory-eval/classpath.txt')).Trim()
    & java -cp $classpath agent_backend.eval.MemoryBaseline $InputFile $OutputDirectory $ModelConfig "$MaxModelRequests"
    if ($LASTEXITCODE -ne 0) { throw 'Evaluation failed; inspect the output directory and do not interpret partial results as a score.' }
} finally { Pop-Location }
