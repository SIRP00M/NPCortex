param(
    [string]$WorldName,
    [switch]$RemoveFromSource
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if($RemoveFromSource -and (Test-Path (Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex/CPlusM03Runtime.java'))){
    throw 'Remove the M03 source harness first; it uses the Test5 target finder. No files changed.'
}
if($WorldName){
    if($WorldName -match '[\\/]'){throw 'WorldName must be a world-folder name'}
    $save=Join-Path $root ('forge-mod/run/saves/'+$WorldName)
    if(-not (Test-Path (Join-Path $save 'level.dat'))){throw 'World not found; no deletion performed'}
    $target=Join-Path $save 'datapacks/NPCortex_Test5_Automation.zip'
    if(Test-Path $target){Remove-Item -LiteralPath $target -Force; Write-Host "Removed datapack from $WorldName"}
}
if($RemoveFromSource){
    $java='forge-mod/src/main/java/io/github/sirp00m/npcortex/'
    $diag=Join-Path $root ($java+'CPlusF1DiagnosticRuntime.java')
    $main=Join-Path $root ($java+'NPCortex.java')
    $g3=Join-Path $root ($java+'CPlusE3G3Runtime.java')
    $s=[System.IO.File]::ReadAllText($diag)
    $block=@'
        // TEMP Test-5 operator-only datapack bridge. Remove with scripts/remove-test5-harness.ps1.
        cplus.then(Commands.literal("autotest5")
                .then(Commands.literal("start")
                        .then(Commands.argument("name",StringArgumentType.word())
                                .executes(ctx -> CPlusTest5Runtime.start(ctx.getSource(),
                                        StringArgumentType.getString(ctx,"name")))))
                .then(Commands.literal("status")
                        .executes(ctx -> CPlusTest5Runtime.status(ctx.getSource())))
                .then(Commands.literal("abort")
                        .executes(ctx -> CPlusTest5Runtime.abort(ctx.getSource()))));
'@
    $block=$block.TrimStart([char[]]@([char]13,[char]10)).Replace("`r`n","`n")
    if(-not $s.Contains($block)){throw 'Registration block changed: stop; no source removals performed'}
    $s2=[System.IO.File]::ReadAllText($main)
    $listener=@'
        // TEMP Test-5 acceptance harness only.
        MinecraftForge.EVENT_BUS.addListener(CPlusTest5Runtime::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(CPlusTest5Runtime::onServerStopping);
'@
    $listener=$listener.TrimStart([char[]]@([char]13,[char]10)).Replace("`r`n","`n")
    if(-not $s2.Contains($listener)){throw 'Listener block changed: stop; no source removals performed'}
    $s3=[System.IO.File]::ReadAllText($g3)
    $hook=@'
                // TEMP Test-5 observability hook AFTER strict identity + ACK validation.
                CPlusTest5Runtime.onTerminalAck(server,actor,str(admitted,"task_id"),expected);
'@
    $hook=$hook.TrimStart([char[]]@([char]13,[char]10)).Replace("`r`n","`n")
    if(-not $s3.Contains($hook)){throw 'ACK hook changed: stop; no source removals performed'}
    # All replacements are validated before writing files. No persistent world data touched.
    [System.IO.File]::WriteAllText($diag,$s.Replace($block,''))
    [System.IO.File]::WriteAllText($main,$s2.Replace($listener,''))
    [System.IO.File]::WriteAllText($g3,$s3.Replace($hook,''))
    $toDelete=@(
        ($java+'CPlusTest5Runtime.java'),($java+'CPlusTest5Decision.java'),
        'forge-mod/src/test/java/io/github/sirp00m/npcortex/CPlusTest5DecisionSelfTest.java',
        'integrations/datapacks/NPCortex_Test5_Automation.zip',
        'integrations/datapacks/NPCortex_Test5_Automation'
    )
    foreach($relative in $toDelete){$file=Join-Path $root $relative; if(Test-Path $file){Remove-Item -LiteralPath $file -Recurse -Force}}
    Write-Host 'Removed TEMP Test5 Java bridge and bundled datapack from source. Rebuild Forge now.'
}
Write-Host 'No SQLite, outbox, actor, world save or task data were deleted.'
