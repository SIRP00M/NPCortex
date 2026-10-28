# Builds the Full Repository Candidate ZIP and a CURRENT-FILE integrity manifest (SHA-256 per packaged file).
#   scripts\make-candidate.ps1 -Name NPCortex-Stage4-NativeBreakPickup-Candidate-20261028-r2
# Writes the ZIP and <Name>.manifest.json next to the repository folder. The same manifest is embedded in the ZIP as
# STAGE4_NATIVE_MANIFEST.json (it lists every other packaged file; it cannot list itself). The older root manifests
# (CANDIDATE_MANIFEST.json, M04_*.json) describe historical candidates and are packaged untouched.
# Excluded: build outputs, run folders, .gradle, graphify-out, g5-results, .git, __pycache__, backend\data (live database), forge-mod\logs,
# *.pyc/*.class, credential files, settings.local.json, and every SQLite file except the committed test fixtures in backend\tests\fixtures.
param([Parameter(Mandatory = $true)][string]$Name)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = Split-Path -Parent $PSScriptRoot
$zipPath = Join-Path (Split-Path -Parent $root) ($Name + '.zip')
$manPath = Join-Path (Split-Path -Parent $root) ($Name + '.manifest.json')
if ((Test-Path $zipPath) -or (Test-Path $manPath)) { throw "refusing to overwrite $zipPath / $manPath" }
$files = Get-ChildItem -Recurse -File -Force $root | Where-Object {
    $rel = $_.FullName.Substring($root.Length + 1)
    $rel -notmatch '^(forge-mod\\(build|run|run-gametest|logs|\.gradle)|\.gradle|graphify-out|g5-results|\.git|backend\\data)\\' -and
    $rel -notmatch '(^|\\)__pycache__\\' -and $rel -notmatch '\.(pyc|class)$' -and $rel -notmatch 'settings\.local\.json|\.secret$|^STAGE4_NATIVE_MANIFEST\.json$' -and
    -not ($rel -match '\.sqlite3(-wal|-shm)?$' -and $rel -notmatch '^backend\\tests\\fixtures\\')
} | Sort-Object FullName
$entries = @(foreach ($f in $files) {
    [pscustomobject]@{ path = $f.FullName.Substring($root.Length + 1).Replace('\', '/'); bytes = $f.Length; sha256 = (Get-FileHash $f.FullName -Algorithm SHA256).Hash.ToLower(); full = $f.FullName }
})
$manifest = [ordered]@{
    candidate = $Name; generated_utc = (Get-Date).ToUniversalTime().ToString('o'); file_count = $entries.Count
    note = 'Current-file integrity manifest. Historical root manifests (CANDIDATE_MANIFEST.json, M04_*.json) are not part of this check.'
    files = @($entries | ForEach-Object { [ordered]@{ path = $_.path; bytes = $_.bytes; sha256 = $_.sha256 } })
}
$json = $manifest | ConvertTo-Json -Depth 5
[IO.File]::WriteAllText($manPath, $json, (New-Object Text.UTF8Encoding $false))
$zip = [IO.Compression.ZipFile]::Open($zipPath, 'Create')
try {
    foreach ($e in $entries) { [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $e.full, 'NPCortex/' + $e.path, 'Optimal') }
    [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $manPath, 'NPCortex/STAGE4_NATIVE_MANIFEST.json', 'Optimal')
} finally { $zip.Dispose() }
Write-Host "files: $($entries.Count) + manifest | zip bytes: $((Get-Item $zipPath).Length)"
Write-Host "zip sha256: $((Get-FileHash $zipPath -Algorithm SHA256).Hash)"
Write-Host "manifest sha256: $((Get-FileHash $manPath -Algorithm SHA256).Hash)"
