#!/usr/bin/env pwsh
param(
    [switch] $Recursive,
    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)]
    [string[]] $Paths
)

$ErrorActionPreference = 'Stop'

$Jar = Join-Path $PSScriptRoot 'target\java-allin1-editor.jar'
if (-not (Test-Path -LiteralPath $Jar)) {
    Write-Host "target\java-allin1-editor.jar not found; building it first..."
    & mvn -q package
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

$ConverterArgs = @()
if ($Recursive) {
    $ConverterArgs += '--recursive'
}
$ConverterArgs += $Paths

& java -cp $Jar com.audioeditor.tools.LegacyJsonConverter @ConverterArgs
exit $LASTEXITCODE
