param([Parameter(Mandatory=$true)][int]$JavaProcessId,
      [Parameter(Mandatory=$true)][string]$Output,
      [ValidateRange(10,600)][int]$DurationSeconds=120)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $Output){throw 'Output exists; preserve previous measurement'}
$process=Get-Process -Id $JavaProcessId
if($process.ProcessName -notmatch '^javaw?$'){throw 'Select the Minecraft Java PID, not another process'}
$started=$process.StartTime
$clock=[Diagnostics.Stopwatch]::StartNew()
$previousSeconds=$process.TotalProcessorTime.TotalSeconds
$previousTime=0.0
$samples=[Collections.Generic.List[object]]::new()
while($clock.Elapsed.TotalSeconds -lt $DurationSeconds){
    Start-Sleep -Seconds 1
    $process=Get-Process -Id $JavaProcessId
    if($process.StartTime -ne $started){throw 'PID was reused; measurement HOLD'}
    $elapsed=$clock.Elapsed.TotalSeconds
    $cpuSeconds=$process.TotalProcessorTime.TotalSeconds
    $cpuPercent=100*($cpuSeconds-$previousSeconds)/($elapsed-$previousTime)/[Environment]::ProcessorCount
    $samples.Add([pscustomobject]@{seconds=$elapsed;cpu_percent_machine_capacity=$cpuPercent;
        working_set_bytes=$process.WorkingSet64;private_memory_bytes=$process.PrivateMemorySize64})
    $previousSeconds=$cpuSeconds;$previousTime=$elapsed
}
$hardware=[ordered]@{
    cpu=@(Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors)
    physical_memory_bytes=(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
    gpu=@(Get-CimInstance Win32_VideoController | Select-Object Name,DriverVersion)
}
[ordered]@{status='MEASURED_NOT_ACCEPTED';pid=$JavaProcessId;process_start=$started.ToUniversalTime().ToString('o');
    duration_seconds=$clock.Elapsed.TotalSeconds;hardware=$hardware;samples=$samples;
    note='Read-only process metrics; operator must correlate this PID and interval with the Forge measurement UUID.'} |
    ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Output -Encoding utf8
Write-Host 'Process samples written. No performance threshold or gameplay PASS inferred.'
