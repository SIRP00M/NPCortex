# Bootstrap ONLY the Gradle wrapper files from the official Forge recommended MDK.
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$forgeDir = Join-Path $repoRoot 'forge-mod'
$version = '1.20.1-47.4.10'
$url = "https://maven.minecraftforge.net/net/minecraftforge/forge/$version/forge-$version-mdk.zip"
$sha1 = '31133abd261aa4d23672d1820db06ccec80326ad'
$tmp = Join-Path $env:TEMP ('npcortex-forge-mdk-' + [guid]::NewGuid().ToString('N'))
$zip = "$tmp.zip"
try {
    Write-Host "Downloading official Forge MDK: $version"
    Invoke-WebRequest -Uri $url -OutFile $zip
    $actual = (Get-FileHash -Path $zip -Algorithm SHA1).Hash.ToLowerInvariant()
    if ($actual -ne $sha1) { throw "SHA1 mismatch. Expected $sha1, got $actual. Aborting." }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    foreach ($relative in @('gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties')) {
        $sourceFile = Join-Path $tmp $relative
        if (-not (Test-Path $sourceFile)) { throw "MDK missing wrapper file: $relative" }
        $targetFile = Join-Path $forgeDir $relative
        New-Item -ItemType Directory -Force -Path (Split-Path $targetFile) | Out-Null
        Copy-Item $sourceFile $targetFile -Force
    }
    Write-Host 'Official Gradle wrapper copied into forge-mod.'
    Write-Host 'Next: cd forge-mod; .\gradlew.bat runClient'
}
finally {
    if (Test-Path $zip) { Remove-Item $zip -Force }
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
}
