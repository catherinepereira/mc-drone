# Records cell skill training data from the reader-based experts, one collection at a time
$model = Split-Path $PSScriptRoot -Parent
$python = Join-Path $model ".venv\Scripts\python.exe"
$runs = @(
  @{ task = "harvest_crops"; terrain = "flat"; episodes = 40; seed = 300000 },
  @{ task = "replicate_build"; terrain = "flat"; episodes = 40; seed = 301000 },
  @{ task = "dig_block"; terrain = "rough"; episodes = 30; seed = 302000 }
)
foreach ($r in $runs) {
  "$(Get-Date -Format HH:mm:ss) $($r.task) $($r.terrain) x$($r.episodes)"
  & $python -m drone_model.collect_skill --task $r.task --terrain $r.terrain --episodes $r.episodes --seed $r.seed 2>&1 | Select-String "episode \d+0/|Traceback|Error"
}
"$(Get-Date -Format HH:mm:ss) done"
