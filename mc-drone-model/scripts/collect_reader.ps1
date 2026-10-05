# Records block reader training frames (rgb, depth, mask, and the state labels) across tasks and terrains,
# one collection at a time. Seeds start at 200000, clear of demo (0 and up) and evaluation (100000 and up) seeds
$model = Split-Path $PSScriptRoot -Parent
$python = Join-Path $model ".venv\Scripts\python.exe"
$runs = @(
  @{ task = "replicate_build"; terrain = "flat"; episodes = 60; seed = 200000; steps = 700 },
  @{ task = "replicate_build"; terrain = "rough"; episodes = 40; seed = 201000; steps = 700 },
  @{ task = "replicate_build"; terrain = "cave"; episodes = 40; seed = 202000; steps = 700 },
  @{ task = "harvest_crops"; terrain = "flat"; episodes = 50; seed = 203000; steps = 600 },
  @{ task = "harvest_crops"; terrain = "rough"; episodes = 30; seed = 204000; steps = 600 },
  @{ task = "dig_block"; terrain = "rough"; episodes = 20; seed = 205000; steps = 400 },
  @{ task = "dig_block"; terrain = "cave"; episodes = 20; seed = 206000; steps = 400 }
)
foreach ($r in $runs) {
  "$(Get-Date -Format HH:mm:ss) $($r.task) $($r.terrain) x$($r.episodes)"
  & $python -m drone_model.collect --task $r.task --terrain $r.terrain --episodes $r.episodes --seed $r.seed --obstacles 4 --noise 0.15 --streams "rgb,depth,mask,state" --max-steps $r.steps 2>&1 | Select-String "success rate|Traceback|Error"
}
"$(Get-Date -Format HH:mm:ss) done"
