param([string]$WorldName,[switch]$RemoveFromSource)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if($WorldName){
    if($WorldName -match '[\\/]'){throw 'WorldName must be a world folder name'}
    $save=Join-Path $root ('forge-mod/run/saves/'+$WorldName)
    if(-not (Test-Path (Join-Path $save 'level.dat'))){throw 'World not found; nothing deleted'}
    $pack=Join-Path $save 'datapacks/NPCortex_Test6_Automation.zip'
    if(Test-Path $pack){Remove-Item -LiteralPath $pack -Force; Write-Host 'Removed Test6 datapack from save'}
}
if($RemoveFromSource){
    $java='forge-mod/src/main/java/io/github/sirp00m/npcortex/'
    $diag=Join-Path $root ($java+'CPlusF1DiagnosticRuntime.java')
    $main=Join-Path $root ($java+'NPCortex.java')
    $outbox=Join-Path $root ($java+'CPlusOutbox.java')
    $test5=Join-Path $root ($java+'CPlusTest5Runtime.java')
    $changes=@{}
    $registration=@'
        // TEMP Test6 scoped offline/online harness (no backend process interruption).
        cplus.then(Commands.literal("autotest6")
                .then(Commands.literal("start")
                        .then(Commands.argument("name",StringArgumentType.word())
                                .executes(ctx -> CPlusTest6Runtime.start(ctx.getSource(),
                                        StringArgumentType.getString(ctx,"name")))))
                .then(Commands.literal("status")
                        .executes(ctx -> CPlusTest6Runtime.status(ctx.getSource())))
                .then(Commands.literal("abort")
                        .executes(ctx -> CPlusTest6Runtime.abort(ctx.getSource()))));
'@
    $listener=@'
        // TEMP Test-6 controlled outage acceptance bridge.
        MinecraftForge.EVENT_BUS.addListener(CPlusTest6Runtime::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(CPlusTest6Runtime::onServerStopping);
'@
    $fault=@'
            // TEMP Test6: scoped offline fault after the durable reserve envelope exists.
            // No shared transport settings, backend process, or other NPC traffic are changed.
            CPlusTest6Fault.beforeNetwork(route,body.get("world_id").getAsString(),
                    body.get("actor_id").getAsString(),body.get("requester_id").getAsString(),
                    body.get("task_id").getAsString());
'@
    $test5Addition=@'
    static boolean isRunning(MinecraftServer server) {
        Run r=RUNS.get(server);return r!=null&&!terminal(r);
    }
'@
    $edits=@(
        @{file=$diag; block=$registration}, @{file=$main; block=$listener},
        @{file=$outbox; block=$fault}, @{file=$test5; block=$test5Addition}
    )
    # Validate every replacement BEFORE changing any source file.
    foreach($e in $edits){
        $old=[System.IO.File]::ReadAllText($e.file)
        $needle=$e.block.TrimStart([char[]]@([char]13,[char]10)).Replace("`r`n","`n")
        if(-not $old.Contains($needle)){throw "Test6 hook changed or missing in $($e.file). No source files altered."}
        $changes[$e.file]=$old.Replace($needle,'')
    }
    foreach($key in $changes.Keys){[System.IO.File]::WriteAllText($key,$changes[$key])}
    $toDelete=@(
        ($java+'CPlusTest6Runtime.java'),($java+'CPlusTest6Fault.java'),
        'forge-mod/src/test/java/io/github/sirp00m/npcortex/CPlusTest6FaultSelfTest.java',
        'integrations/datapacks/NPCortex_Test6_Automation',
        'integrations/datapacks/NPCortex_Test6_Automation.zip'
    )
    foreach($rel in $toDelete){$file=Join-Path $root $rel;if(Test-Path $file){Remove-Item -LiteralPath $file -Recurse -Force}}
    Write-Host 'Removed temporary Test6 source, hooks and datapack. Test5 remains unchanged.'
}
Write-Host 'No world save, SQLite, C2 journal, outbox records, or Git data were removed.'
