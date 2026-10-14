# Commit the verified v0.8.1-alpha source overlay onto a fresh, clean clone.
# By default this creates a LOCAL commit on a release branch. Push only with -Push.
param(
    [Parameter(Mandatory=$true)][string]$RepoRoot,
    [Parameter(Mandatory=$true)][string]$SourceRoot,
    [switch]$Push
)
$ErrorActionPreference = 'Stop'
function Invoke-Git {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$GitArgs)
    & git -C $RepoRoot @GitArgs
    if ($LASTEXITCODE -ne 0) { throw "git $($GitArgs -join ' ') failed (exit $LASTEXITCODE)" }
}
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'Git for Windows is required.' }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$SourceRoot = (Resolve-Path -LiteralPath $SourceRoot).Path
if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot '.git'))) { throw 'RepoRoot must be a Git checkout of SIRP00M/NPCortex.' }
$remote = (& git -C $RepoRoot remote get-url origin)
if ($LASTEXITCODE -ne 0 -or $remote -notmatch '(?i)(?:github\.com[:/])SIRP00M/NPCortex(?:\.git)?/?$') {
    throw "Refusing unexpected origin remote: $remote"
}
$dirty = (& git -C $RepoRoot status --porcelain --untracked-files=normal)
if ($LASTEXITCODE -ne 0 -or $dirty) { throw 'Checkout contains local changes. Use a fresh clone so NPC data / working edits cannot be overwritten.' }
Invoke-Git fetch origin main
$current = (& git -C $RepoRoot rev-parse HEAD).Trim()
$upstream = (& git -C $RepoRoot rev-parse refs/remotes/origin/main).Trim()
if ($current -ne $upstream) { throw 'Checkout HEAD differs from origin/main; update the clean checkout first.' }
$expectedBase = '1719543273f4265d5953212647fe4c2881427c2e'
if ($current -ne $expectedBase) { throw "GitHub main has moved since this bundle was prepared ($expectedBase). Review the new changes before applying this overlay." }
$branch = 'release/v0.8.1-agentic-brain'
& git -C $RepoRoot show-ref --verify --quiet "refs/heads/$branch"
if ($LASTEXITCODE -eq 0) { throw "Branch '$branch' already exists. Review it instead of overwriting." }
Invoke-Git switch -c $branch
$required = @('ReadMe.md','forge-mod/gradle.properties','forge-mod/src/main/resources/META-INF/mods.toml','backend/pyproject.toml','docs/releases/v0.8.1-alpha.md')
foreach ($rel in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $SourceRoot $rel))) { throw "Required release file missing: $rel" }
}
$srcFiles = Get-ChildItem -LiteralPath $SourceRoot -File -Recurse
$srcRoot = $SourceRoot.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
foreach ($file in $srcFiles) {
    $relative = $file.FullName.Substring($srcRoot.Length)
    if ($relative -match '(^|[\\/])\.git([\\/]|$)' -or $relative -match '(^|[\\/])(?:\.env|data|run|build|logs)([\\/]|$)') {
        throw "Unsafe path in release overlay: $relative"
    }
    if ($relative -match '(?i)\.(?:sqlite3?|db|gguf|safetensors|jar|class)$') {
        throw "Binary/runtime artifact not allowed in release overlay: $relative"
    }
    $dest = Join-Path $RepoRoot $relative
    $destDir = Split-Path -Parent $dest
    if (-not (Test-Path -LiteralPath $destDir)) { New-Item -ItemType Directory -Force -Path $destDir | Out-Null }
    Copy-Item -LiteralPath $file.FullName -Destination $dest -Force
}
Invoke-Git add -A
$changes = & git -C $RepoRoot diff --cached --name-only
if ($LASTEXITCODE -ne 0 -or -not $changes) { throw 'No changes to commit.' }
foreach ($name in $changes) {
    if ($name -match '(?i)(^|/)(?:data|run|build|logs)/|\.env$|\.(?:sqlite3?|db|gguf|safetensors|jar|class)$') {
        Invoke-Git reset
        throw "Unsafe staged file detected: $name"
    }
}
Invoke-Git diff --cached --check
Invoke-Git commit -m 'feat: release NPCortex v0.8.1-alpha agentic AI brain' -m 'Consolidate Qwen skill-selection pilot, bounded server-side agent loop, grounded observation and action feedback, task budgets, navigation and inventory verification. Preserve existing APIs, packs and memory. Update ReadMe and version metadata; document foliage navigation limitation and verified single-log test.'
Write-Host "Created local commit on branch '$branch'."
Invoke-Git log -1 --format='%h %s'
if ($Push) {
    Invoke-Git push -u origin $branch
    Write-Host 'Pushed release branch. Review changes and open a PR before merging into main.'
} else {
    Write-Host 'Not pushed. Validate build/tests, then: git push -u origin release/v0.8.1-agentic-brain'
}
