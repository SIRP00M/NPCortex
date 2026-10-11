# Safe diagnostics: no policy changes, file deletion, or downloads.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Write-Host '=== NPCortex Phase 5 Diagnostics ==='
& (Join-Path $PSScriptRoot 'phase4-check.ps1')
Write-Host '--- Character profiles ---'
try {
    $result = Invoke-RestMethod -Uri 'http://127.0.0.1:8765/v1/characters' -TimeoutSec 5
    $result.characters | Format-Table id, name, language -AutoSize
} catch {
    Write-Host 'WARNING: backend unavailable or running old version. Restart dev-backend.ps1.' -ForegroundColor Yellow
}
Write-Host '--- Skin files (dev game dir) ---'
Get-ChildItem (Join-Path $repo 'forge-mod\run\config\npcortex\skins') -Filter '*.png' -ErrorAction SilentlyContinue |
    Select-Object Name, Length | Format-Table -AutoSize
Write-Host 'Tip: .\scripts\install-character.ps1 -Id yui'
