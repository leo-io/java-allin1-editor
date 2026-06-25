#!/usr/bin/env pwsh
# Launch the shaded jar with realtime-audio GC settings so a GC pause cannot
# exceed the ~500 ms audio buffer and underrun playback. Build first with:
#   mvn -q clean package
#
# Generational ZGC needs JDK 21+. If your JVM rejects -XX:+ZGenerational, use the
# G1 fallback line below instead.
#
# Any extra arguments (e.g. a .json path to open) are forwarded to the app.
param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $AppArgs)

$GcOpts = @('-XX:+UseZGC', '-XX:+ZGenerational', '-Xms512m', '-Xmx512m', '-XX:+AlwaysPreTouch')
# $GcOpts = @('-XX:+UseG1GC', '-XX:MaxGCPauseMillis=50', '-Xms512m', '-Xmx512m', '-XX:+AlwaysPreTouch')

$Jar = Join-Path $PSScriptRoot 'target\java-allin1-editor.jar'
& java @GcOpts -jar $Jar @AppArgs
exit $LASTEXITCODE
