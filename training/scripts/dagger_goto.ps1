# One DAgger round for the goto policy, as dagger_skill.ps1 runs the cell skill's: goto and dock planner episodes flown by the
# scripted controller, a retrain, then episodes where that policy flies half the steps while the planner labels every
# step, and a final retrain on all the goto data. -SkipPlan starts from the first retrain
param([int]$Round = 1, [switch]$SkipPlan)
$model = Split-Path $PSScriptRoot -Parent
$python = Join-Path $model ".venv\Scripts\python.exe"
$goto = Join-Path $model "checkpoints\goto.pt"
$seed = 410000 + $Round * 10000

# task, terrain, episodes, step limit
$runs = @(
  @("goto_point", "flat", 30, 600),
  @("goto_point", "rough", 30, 600),
  @("goto_point", "cave", 30, 600),
  @("find_block", "flat", 15, 1500),
  @("find_block", "rough", 15, 1500),
  @("find_block", "cave", 10, 1500),
  @("follow_mob", "flat", 10, 1500),
  @("follow_mob", "rough", 10, 1500),
  @("dock_station", "flat", 15, 600),
  @("dock_station", "rough", 15, 600),
  @("dock_station", "cave", 10, 600)
)

function Collect($withPolicy, $offset) {
  $i = 0
  foreach ($r in $runs) {
    $name = "$($r[0])-$($r[1])-" + $(if ($withPolicy) { "dagger" } else { "plan" }) + "-r$Round"
    "$(Get-Date -Format HH:mm:ss) $name x$($r[2])"
    $extra = if ($withPolicy) { @("--dagger", $goto) } else { @() }
    & $python -m drone_model.framework.collect --policy goto --task $r[0] --terrain $r[1] --episodes $r[2] --max-steps $r[3] --seed ($seed + $offset + $i * 100) --run $name @extra 2>&1 | Select-String "episode \d+/|Traceback|Error"
    $i++
  }
}

function Train {
  "$(Get-Date -Format HH:mm:ss) training"
  & $python -u -m drone_model.framework.train --policy goto 2>&1 | Select-String "^train|step \d+000 |best|Traceback|Error"
  # stop before DAgger flies a stale checkpoint
  if ($LASTEXITCODE -ne 0) { throw "goto training failed" }
}

# the checkpoint the round starts from, the first retrain overwrites it
if (Test-Path $goto) { Copy-Item $goto (Join-Path $model "checkpoints\archive\goto-before-r$Round.pt") }
if (-not $SkipPlan) { Collect $false 0 }
Train
Copy-Item $goto (Join-Path $model "checkpoints\archive\goto-r$Round-bc.pt")
Collect $true 5000
Train
"$(Get-Date -Format HH:mm:ss) done"
