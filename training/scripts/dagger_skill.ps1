# One DAgger round for the cell skill through the framework: job planner episodes flown by the scripted controller, a
# retrain, then episodes where that skill flies half the goal steps while the planner labels every step, and a final
# retrain on all the skill data. -Tasks narrows the round to some of the runs, such as hunt_mobs,patrol_area
param([int]$Round = 1, [string]$Tasks = "")
$model = Split-Path $PSScriptRoot -Parent
$python = Join-Path $model ".venv\Scripts\python.exe"
$skill = Join-Path $model "checkpoints\skill.pt"
$seed = 310000 + $Round * 10000

# task, terrain, size, episodes, step limit
$runs = @(
  @("replicate_build", "flat", 3, 20, 900),
  @("copy_build", "flat", 5, 12, 4000),
  @("copy_build", "rough", 5, 12, 4000),
  @("mine_deposit", "flat", 5, 10, 4000),
  @("mine_deposit", "rough", 5, 10, 4000),
  @("gather_build", "flat", 4, 10, 6000),
  @("harvest_crops", "flat", 5, 12, 1500),
  @("hunt_mobs", "flat", 5, 20, 3000),
  @("hunt_mobs", "rough", 5, 15, 3000),
  @("hunt_mobs", "cave", 5, 10, 3000),
  @("patrol_area", "flat", 5, 10, 1500),
  @("patrol_area", "rough", 5, 10, 1500)
)
if ($Tasks) {
  $keep = $Tasks.Split(",")
  $runs = $runs | Where-Object { $keep -contains $_[0] }
}

function Collect($withSkill, $offset) {
  $i = 0
  foreach ($r in $runs) {
    $name = "$($r[0])-$($r[1])-s$($r[2])-" + $(if ($withSkill) { "dagger" } else { "plan" }) + "-r$Round"
    "$(Get-Date -Format HH:mm:ss) $name x$($r[3])"
    $extra = if ($withSkill) { @("--dagger", $skill) } else { @() }
    & $python -m drone_model.framework.collect --policy skill --task $r[0] --terrain $r[1] --size $r[2] --episodes $r[3] --max-steps $r[4] --seed ($seed + $offset + $i * 100) --run $name @extra 2>&1 | Select-String "episode \d+/|Traceback|Error"
    $i++
  }
}

function Train {
  "$(Get-Date -Format HH:mm:ss) training"
  & $python -u -m drone_model.framework.train --policy skill 2>&1 | Select-String "^train|step \d+000 |best|Traceback|Error"
}

Collect $false 0
Train
Copy-Item $skill (Join-Path $model "checkpoints\archive\skill-r$Round-bc.pt")
Collect $true 5000
Train
"$(Get-Date -Format HH:mm:ss) done"
