# NPCortex Character Profile fallback tool (Windows PowerShell 5.1+). The in-game NPCortex Manager is the
# normal way to do this; use this script for automation, recovery or when the GUI is unavailable.
#
#   -Action Check    GET  /v1/actors/<id>/profile   -> is the profile registered? (exit 0 yes, 2 no)
#   -Action Export   same GET, writes the importable profile JSON to -OutFile
#   -Action Register POST world + actor + inline profile. DRY RUN unless -Apply is given.
#
# Credentials: the local secret is read from NPCORTEX_CPLUS_F1_TOKEN or the backend's secret file
# (NPCORTEX_CPLUS_F1_SECRET_FILE, else %LOCALAPPDATA%\NPCortex\bridge\cplus-f1.secret). It is sent only as the
# X-NPCortex-CPlus-Token header to a loopback backend, and is never printed, logged or written anywhere.
#
# Find -WorldId / -ActorId / -Dimension in the reply of "/npcortex c1 spawn <name> custom".
param(
    [ValidateSet('Check', 'Export', 'Register')][string]$Action = 'Check',
    [string]$BaseUrl = 'http://127.0.0.1:8765',
    [Parameter(Mandatory = $true)][string]$ActorId,
    [string]$WorldId = '',
    [string]$Dimension = 'minecraft:overworld',
    [string]$ProfileFile = '',
    [string]$OutFile = '',
    [switch]$Apply,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
Add-Type -AssemblyName System.Net.Http

$MaxBodyBytes = 32768
$UuidPattern = '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
$Utf8 = New-Object System.Text.UTF8Encoding($false)
$script:Secret = $null

function Fail([string]$Message, [int]$Code = 1) {
    # Never let the credential leak through an error text.
    if ($script:Secret) { $Message = $Message.Replace($script:Secret, '***') }
    [Console]::Error.WriteLine("register-npc-profile: $Message")
    exit $Code
}

function Resolve-Base([string]$Raw) {
    $uri = $null
    if (-not [Uri]::TryCreate($Raw.TrimEnd('/'), [UriKind]::Absolute, [ref]$uri)) { Fail "BaseUrl is not a valid URL." }
    if ($uri.Scheme -ne 'http' -and $uri.Scheme -ne 'https') { Fail "BaseUrl must be http(s)." }
    if ($uri.UserInfo -or ($uri.AbsolutePath -ne '/' -and $uri.AbsolutePath -ne '') -or $uri.Query -or $uri.Fragment) {
        Fail "BaseUrl must be a bare origin without credentials or path."
    }
    $hostName = $uri.Host.Trim('[', ']')
    if ($hostName -notin @('127.0.0.1', 'localhost', '::1')) {
        Fail "Refusing to send the local credential to a non-loopback host ($hostName)."
    }
    return $uri.GetLeftPart([UriPartial]::Authority)
}

function Read-Secret {
    $manual = [Environment]::GetEnvironmentVariable('NPCORTEX_CPLUS_F1_TOKEN')
    if ($manual) {
        if ($manual.Length -lt 24 -or $manual.Length -gt 512 -or $manual -match "[\r\n]") { Fail "NPCORTEX_CPLUS_F1_TOKEN is invalid." }
        return $manual
    }
    $path = [Environment]::GetEnvironmentVariable('NPCORTEX_CPLUS_F1_SECRET_FILE')
    if (-not $path) {
        $base = $env:LOCALAPPDATA
        if (-not $base) { $base = Join-Path $HOME 'AppData\Local' }
        $path = Join-Path $base 'NPCortex\bridge\cplus-f1.secret'
    }
    if (-not [IO.Path]::IsPathRooted($path)) { Fail "NPCORTEX_CPLUS_F1_SECRET_FILE must be an absolute path." }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Fail "Local secret file not found. Start the backend once so it creates it."
    }
    $info = Get-Item -LiteralPath $path
    if ($info.Length -gt 128) { Fail "Local secret file is larger than expected." }
    $value = ([IO.File]::ReadAllText($path, [Text.Encoding]::ASCII)).Trim()
    if ($value -notmatch '^[0-9a-f]{64}$') { Fail "Local secret file does not contain a valid secret." }
    return $value
}

function Send-Npc([string]$Method, [string]$Path, [byte[]]$Body) {
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromSeconds(30)
    try {
        $request = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::new($Method)), ($script:Base + $Path)
        [void]$request.Headers.TryAddWithoutValidation('X-NPCortex-CPlus-Token', $script:Secret)
        if ($null -ne $Body) {
            $content = New-Object System.Net.Http.ByteArrayContent (, $Body)
            $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/json; charset=utf-8')
            $request.Content = $content
        }
        try {
            $response = $client.SendAsync($request).GetAwaiter().GetResult()
        } catch {
            Fail "Cannot reach the backend at $($script:Base). Is it running?"
        }
        $raw = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $text = $Utf8.GetString($raw)
        $json = $null
        try { $json = $text | ConvertFrom-Json } catch { }
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Text = $text; Json = $json }
    } finally {
        $client.Dispose()
    }
}

function Describe-Error($Response) {
    $detail = ''
    if ($Response.Json -and ($Response.Json.PSObject.Properties.Name -contains 'error')) { $detail = [string]$Response.Json.error }
    if ($Response.Status -eq 403) { return "HTTP 403: the backend rejected the local credential. $detail" }
    return "HTTP $($Response.Status): $detail"
}

function Assert-Uuid([string]$Value, [string]$Label) {
    if ($Value -notmatch $UuidPattern) { Fail "$Label must be a lower-case canonical UUID." }
}

Assert-Uuid $ActorId 'ActorId'
$script:Base = Resolve-Base $BaseUrl

if ($Action -eq 'Register') {
    Assert-Uuid $WorldId 'WorldId'
    if ($Dimension -notmatch '^[a-z0-9_.-]+:[a-z0-9_./-]{1,120}$') { Fail "Dimension must look like minecraft:overworld." }
    if (-not $ProfileFile) { Fail "-ProfileFile is required for Register." }
    if (-not (Test-Path -LiteralPath $ProfileFile -PathType Leaf)) { Fail "Profile file not found." }
    $profileText = $Utf8.GetString([IO.File]::ReadAllBytes($ProfileFile)).TrimStart([char]0xFEFF).Trim()
    if (-not $profileText.StartsWith('{') -or -not $profileText.EndsWith('}')) { Fail "Profile file must contain one JSON object." }
    $parsed = $null
    try { $parsed = $profileText | ConvertFrom-Json } catch { Fail "Profile file is not valid JSON." }
    $names = @($parsed.PSObject.Properties.Name)
    if ($names -contains 'character_id' -or $names -contains 'preset') { Fail "Profile file must be a Character Profile, not a pack reference." }
    if (-not ($names -contains 'display_name')) { Fail "Profile file needs display_name." }
    $body = '{"world_id":"' + $WorldId + '","dimension_id":"' + $Dimension + '","actor_id":"' + $ActorId + '","profile":' + $profileText + '}'
    $bodyBytes = $Utf8.GetBytes($body)
    if ($bodyBytes.Length -gt $MaxBodyBytes) { Fail "Request is $($bodyBytes.Length) bytes; the backend accepts at most $MaxBodyBytes." }
    $shape = 'structured'
    if ($names -contains 'system_prompt') { $shape = 'raw' }
    Write-Host "Plan: register Character Profile '$($parsed.display_name)' ($shape, $($bodyBytes.Length) bytes)"
    Write-Host "      world=$WorldId actor=$ActorId dimension=$Dimension backend=$($script:Base)"
    if (-not $Apply) {
        Write-Host "DRY RUN: nothing was sent. Re-run with -Apply to register."
        exit 0
    }
    $script:Secret = Read-Secret
    $worldBody = '{"world_id":"' + $WorldId + '","name":"NPCortex-C1-' + $WorldId + '"}'
    $w = Send-Npc 'POST' '/v1/worlds' ($Utf8.GetBytes($worldBody))
    if ($w.Status -ne 201 -and $w.Status -ne 200) { Fail "World registration failed. $(Describe-Error $w)" }
    $a = Send-Npc 'POST' '/v1/actors' $bodyBytes
    if ($a.Status -ne 201 -and $a.Status -ne 200) { Fail "Profile registration failed (nothing was stored). $(Describe-Error $a)" }
    $e = Send-Npc 'GET' "/v1/actors/$ActorId/profile" $null
    if ($e.Status -ne 200) { Fail "Registered, but the read-back failed. $(Describe-Error $e)" }
    $bound = $a.Json.profile
    if ([string]$a.Json.actor_id -ne $ActorId -or [string]$a.Json.world_id -ne $WorldId -or [string]$a.Json.dimension_id -ne $Dimension) {
        Fail "The backend answered with a different world/actor/dimension than requested."
    }
    if ([string]$e.Json.content_sha256 -ne [string]$bound.content_sha256 -or [int]$e.Json.revision -ne [int]$bound.revision) {
        Fail "Read-back does not match what was registered."
    }
    Write-Host "REGISTERED revision $($e.Json.revision) source=$($e.Json.source) sha256=$($e.Json.content_sha256)"
    exit 0
}

$script:Secret = Read-Secret
$r = Send-Npc 'GET' "/v1/actors/$ActorId/profile" $null
if ($r.Status -eq 404) {
    Write-Host "NOT REGISTERED: this actor has no Character Profile at the backend yet."
    exit 2
}
if ($r.Status -ne 200) { Fail "Check failed. $(Describe-Error $r)" }

if ($Action -eq 'Check') {
    Write-Host "REGISTERED revision $($r.Json.revision) source=$($r.Json.source) composition=$($r.Json.composition) sha256=$($r.Json.content_sha256)"
    exit 0
}

if (-not $OutFile) { Fail "-OutFile is required for Export." }
if ((Test-Path -LiteralPath $OutFile) -and -not $Force) { Fail "OutFile already exists; use -Force to overwrite." }
$importable = $r.Json.profile | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText($OutFile, $importable, $Utf8)
Write-Host "Exported revision $($r.Json.revision) (sha256=$($r.Json.content_sha256)) to $OutFile"
exit 0
