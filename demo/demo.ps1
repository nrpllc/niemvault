<#
.SYNOPSIS
  Runs the Phase 1 vertical slice as a narrated demo.

.DESCRIPTION
  Tells one story: an agency onboards a CAD source, it runs clean, and then the source changes
  underneath the mapping overnight. Nothing crashes. That is the point. Spec section 4.2 names the
  failure this platform exists to prevent as silent corruption, not visible crashes, and the whole
  demo is built to show the difference.

  Everything here runs offline against the artifacts the repository actually ships. No Docker, no
  network, no fixtures invented for the occasion.

  Deliberately ASCII only: PowerShell 5.1 reads a BOM-less script as ANSI, and a stray section
  sign turns into mojibake in front of an audience.

.EXAMPLE
  .\demo\demo.ps1
#>

param(
    [string] $Work = (Join-Path $env:TEMP "niem-demo")
)

# PowerShell 5.1 wraps every line a native command writes to stderr in an ErrorRecord, which under
# 'Stop' aborts the script even when the command succeeded. The CLI writes diagnostics to stderr by
# design, so this script must not treat that as failure.
$ErrorActionPreference = 'Continue'

$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem 'C:\Program Files\Microsoft\jdk-21*' -Directory -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}

$module  = "modules\law-enforcement\src\main\resources"
$mapping = "$module\mappings\cad-to-canonical-1.0.0.yaml"
$niem    = ".\tools\cli\build\install\niem\bin\niem.bat"

function Step($number, $title) {
    Write-Host ""
    Write-Host ("=" * 78) -ForegroundColor DarkGray
    Write-Host " $number. $title" -ForegroundColor Cyan
    Write-Host ("=" * 78) -ForegroundColor DarkGray
    Write-Host ""
}

function Note($text) {
    Write-Host "  $text" -ForegroundColor DarkGray
}

# Write-Host writes straight to the host, while a native command's stdout goes down the pipeline.
# Mixing the two puts the narration and the CLI output in the wrong order whenever the script is
# piped or captured. Routing everything through Write-Host keeps the story readable either way.
function Show($lines) {
    foreach ($line in $lines) { Write-Host $line }
}

# --- setup ------------------------------------------------------------------

if (Test-Path $Work) { Remove-Item -Recurse -Force $Work -Confirm:$false }
New-Item -ItemType Directory -Force -Path "$Work\day1", "$Work\day2", "$Work\bronze" | Out-Null
Copy-Item "$module\fixtures\incidents.csv"         "$Work\day1\"
Copy-Item "$module\fixtures\incidents-drifted.csv" "$Work\day2\"

if (-not (Test-Path $niem)) {
    Write-Host "Building the operator CLI..." -ForegroundColor DarkGray
    & .\gradlew.bat :tools:cli:installDist --console=plain -q
}

# --- 1 ----------------------------------------------------------------------

Step 1 "What is in the box"
Note "Mappings and contracts are versioned artifacts on disk, not code. An agency"
Note "changes one and redeploys the definition; the platform is not rebuilt."
Write-Host ""
Show (& $niem validate --module $module)

# --- 2 ----------------------------------------------------------------------

Step 2 "Day one: a CAD export arrives"
Note "Ten rows of a real-shaped export: names packed as 'LAST, FIRST M', one driver"
Note "licence written three ways, UNK and N/A standing in for null."
Write-Host ""
Get-Content "$Work\day1\incidents.csv" -TotalCount 4 |
    ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
Write-Host "    ..." -ForegroundColor DarkGray
Write-Host ""
Show (& $niem run --module $module --mapping $mapping --drop "$Work\day1" `
        --bronze "$Work\bronze" --tenant co.riverton.pd --out "$Work\canonical.jsonl")
Write-Host ""
Note "Ten rows became thirty canonical records: a Person, an Incident, and the"
Note "association between them, per row. Six humans, because three rows are the same"
Note "person and the licence punctuation did not fool identity resolution."
Write-Host ""
Write-Host "  One canonical Person:" -ForegroundColor DarkGray
Get-Content "$Work\canonical.jsonl" | Where-Object { $_ -match '#Person' } |
    Select-Object -First 1 | ForEach-Object { Write-Host "    $_" -ForegroundColor Gray }

# --- 3 ----------------------------------------------------------------------

Step 3 "Day two: the source changed overnight, and nobody told anyone"
Note "The vendor pushed an update. Dates of birth are now ISO. One incident timestamp"
Note "is ISO. There is a new column. Every value is still a perfectly valid string,"
Note "and a pipeline without contracts would happily produce wrong canonical data."
Write-Host ""
$dayTwo = & $niem run --module $module --mapping $mapping --drop "$Work\day2" `
    --bronze "$Work\bronze" --tenant co.riverton.pd --quarantine-out "$Work\quarantine.jsonl"
$dayTwoExit = $LASTEXITCODE
Show ($dayTwo | Where-Object { $_ -notmatch '^\{"eventId"' })
Write-Host ""
Note "Exit code $dayTwoExit : it ran, but not cleanly. A scheduled job can tell the difference."
Note "The pipeline did not halt. Rows that were fine still mapped, including the"
Note "incidents belonging to the rows whose dates of birth were rejected."

# --- 4 ----------------------------------------------------------------------

Step 4 "The event says what shape arrived. The quarantine keeps the data."
Note "Events go to logs and dashboards, so they carry a redacted shape and never a"
Note "value. The quarantined record keeps everything, and lives where bronze lives."
Write-Host ""
# Reuses day two's output. Running the pipeline again here would land a duplicate batch and make
# step 5 report three batches for what the story says is two days.
$event = $dayTwo | Where-Object { $_ -match 'CONTRACT_VIOLATION' } | Select-Object -First 1
if ($event) {
    Write-Host "  event    : " -NoNewline -ForegroundColor DarkGray
    Write-Host ($event | ConvertFrom-Json).summary -ForegroundColor Yellow
}
$quarantineFile = Get-ChildItem "$Work" -Filter "quarantine-*.jsonl" | Select-Object -First 1
$held = Get-Content $quarantineFile.FullName | Select-Object -First 1 | ConvertFrom-Json
Write-Host "  retained : " -NoNewline -ForegroundColor DarkGray
Write-Host ("DOB = " + $held.values.DOB + "   (in quarantine, never in the event)") -ForegroundColor Yellow

# --- 5 ----------------------------------------------------------------------

Step 5 "Everything is replayable from bronze"
Note "Bronze is append-only and byte-preserved. Reading it verifies content hashes,"
Note "so corruption surfaces here rather than downstream."
Write-Host ""
Show (& $niem inspect --bronze "$Work\bronze")

# --- 6 ----------------------------------------------------------------------

Step 6 "The architectural bet: one mapping, two execution modalities"
Note "Spec section 5 requires the same transformation definition to run in batch and"
Note "in streaming without modification. Criterion 7 is the test of it, and the spec"
Note "says Phase 1 is not done without it."
Write-Host ""
& .\gradlew.bat :runtime:engine:test --tests "*FlinkMappingJobTest*" --console=plain -q
if ($LASTEXITCODE -eq 0) {
    Write-Host "  criterion 7: batch and streaming produced identical canonical output. PASSED" -ForegroundColor Green
} else {
    Write-Host "  criterion 7: FAILED" -ForegroundColor Red
}

Write-Host ""
Write-Host ("=" * 78) -ForegroundColor DarkGray
Write-Host " Not yet built: the graph projection (criterion 4) and replay (criterion 6)." -ForegroundColor DarkGray
Write-Host " Both need the silver store, which needs a container runtime. See ADR 0005." -ForegroundColor DarkGray
Write-Host ("=" * 78) -ForegroundColor DarkGray
Write-Host ""
