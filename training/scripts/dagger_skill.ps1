# One DAgger round for the cell skill: job planner episodes flown by the scripted controller, a retrain, then
# episodes where that skill flies half the goal steps while the scripted controller labels every step, and a final
# retrain on all the skill data
param([int]$Round = 1)
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
  @("harvest_crops", "flat", 5, 12, 1500)
)

function Collect($withSkill, $offset) {
  $i = 0
  foreach ($r in $runs) {
    $name = "$($r[0])-$($r[1])-s$($r[2])-" + $(if ($withSkill) { "dagger" } else { "plan" }) + "-r$Round"
    "$(Get-Date -Format HH:mm:ss) $name x$($r[3])"
    $extra = if ($withSkill) { @("--skill", $skill) } else { @() }
    & $python -m drone_model.collect.skill --task $r[0] --terrain $r[1] --size $r[2] --episodes $r[3] --max-steps $r[4] --seed ($seed + $offset + $i * 100) --name $name @extra 2>&1 | Select-String "episode \d+/|Traceback|Error"
    $i++
  }
}

function Train {
  "$(Get-Date -Format HH:mm:ss) training"
  & $python -u -m drone_model.train.skill 2>&1 | Select-String "^train|step \d+000 |best|Traceback|Error"
}

Collect $false 0
Train
Copy-Item $skill (Join-Path $model "checkpoints\archive\skill-r$Round-bc.pt")
Collect $true 5000
Train
"$(Get-Date -Format HH:mm:ss) done"
