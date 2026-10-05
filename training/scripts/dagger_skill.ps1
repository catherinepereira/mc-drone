# One DAgger round for the cell skill: job planner episodes flown by the scripted controller, a retrain, then
# episodes where that skill flies half the goal steps while the scripted controller labels every step, and a final
# retrain on all the skill data
param([int]$Round = 1)
$model = Split-Path $PSScriptRoot -Parent
$python = Join-Path $model ".venv\Scripts\python.exe"
$skill = Join-Path $model "checkpoints\skill.pt"
$seed = 310000 + $Round * 10000

function Collect($task, $episodes, $seed, $name, $withSkill) {
  "$(Get-Date -Format HH:mm:ss) $name x$episodes"
  $extra = if ($withSkill) { @("--skill", $skill) } else { @() }
  & $python -m drone_model.collect_skill --task $task --episodes $episodes --seed $seed --name $name --max-steps 900 @extra 2>&1 | Select-String "episode \d+0/|Traceback|Error"
}

function Train {
  "$(Get-Date -Format HH:mm:ss) training"
  & $python -u -m drone_model.train_skill 2>&1 | Select-String "^train|step \d+000 |best|Traceback|Error"
}

Collect "replicate_build" 40 $seed "replicate_build-plan-r$Round" $false
Train
Copy-Item $skill (Join-Path $model "checkpoints\skill-r$Round-bc.pt")
Collect "replicate_build" 40 ($seed + 1000) "replicate_build-dagger-r$Round" $true
Collect "harvest_crops" 20 ($seed + 2000) "harvest_crops-dagger-r$Round" $true
Train
"$(Get-Date -Format HH:mm:ss) done"
