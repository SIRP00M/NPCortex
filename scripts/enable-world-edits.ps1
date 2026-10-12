# Explicitly enable world interactions in the existing development instance config.
# Existing TOML settings override the new v0.8.2 default value.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$config = Join-Path $root 'forge-mod\run\config\npcortex-common.toml'
if (!(Test-Path -LiteralPath $config)) {
    throw "No config at $config. Run the Minecraft dev client once, close it, then retry."
}
$text = [IO.File]::ReadAllText($config)
if ($text -notmatch '(?m)^\s*enable_world_edits\s*=\s*(?:true|false)\s*$') {
    throw "No enable_world_edits key in $config. Verify the Minecraft instance and mod config."
}
Copy-Item -LiteralPath $config -Destination "$config.bak" -Force
$text = [regex]::Replace($text, '(?m)^(\s*enable_world_edits\s*=\s*)(?:true|false)(\s*)$', '${1}true${2}')
[IO.File]::WriteAllText($config, $text)
Write-Host 'World edits enabled for this development instance. Close/restart Minecraft to reload. (Singleplayer + cheats still required.)' -ForegroundColor Green
