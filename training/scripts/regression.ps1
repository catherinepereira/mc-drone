# Brain regression across every job arena, in vision and scan perception, one run at a time. Reports land in reports/brain/
param([int]$Episodes = 3)
$model = Split-Path $PSScriptRoot -Parent
$python = Join-Path $model ".venv\Scripts\python.exe"

# task, size, terrain, perception
$runs = @(
  @("replicate_build", 3, "flat", "vision"),
  @("harvest_crops", 5, "flat", "vision"),
  @("harvest_crops", 5, "flat", "scan"),
  @("copy_build", 5, "flat", "vision"),
  @("copy_build", 7, "rough", "vision"),
  @("copy_build", 8, "flat", "scan"),
  @("mine_deposit", 6, "flat", "vision"),
  @("mine_deposit", 6, "rough", "vision"),
  @("mine_deposit", 6, "flat", "scan"),
  @("gather_build", 4, "flat", "vision"),
  @("gather_build", 4, "flat", "scan")
)
foreach ($r in $runs) {
  $line = & $python -m drone_model.brain --task $r[0] --size $r[1] --terrain $r[2] --perception $r[3] --episodes $Episodes 2>&1 | Select-String "^success|Traceback|Error"
  "$($r[0]) s$($r[1]) $($r[2]) $($r[3]): $line"
}
"done"
