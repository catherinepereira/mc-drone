# Records block reader training frames (rgb, depth, mask, and the state labels) across tasks and terrains, one collection at a time
# Seeds start at 200000, clear of demo (0 and up) and evaluation (100000 and up) seeds
# -Set structures records only the larger copy, build, mine, and gather arenas, -Set mobs the hunt and patrol arenas, and
# -Set find the find_block arenas, the last two flown with the mod's mask so the experts see what the reader can't yet.
# -Tasks narrows a set to some of its tasks, such as follow_mob
param([ValidateSet("all", "base", "structures", "mobs", "find")][string]$Set = "all", [string]$Tasks = "")
$model = Split-Path $PSScriptRoot -Parent
$python = Join-Path $model ".venv\Scripts\python.exe"
$base = @(
  @{ task = "replicate_build"; terrain = "flat"; episodes = 60; seed = 200000; steps = 700; size = 5 },
  @{ task = "replicate_build"; terrain = "rough"; episodes = 40; seed = 201000; steps = 700; size = 5 },
  @{ task = "replicate_build"; terrain = "cave"; episodes = 40; seed = 202000; steps = 700; size = 5 },
  @{ task = "harvest_crops"; terrain = "flat"; episodes = 50; seed = 203000; steps = 600; size = 5 },
  @{ task = "harvest_crops"; terrain = "rough"; episodes = 30; seed = 204000; steps = 600; size = 5 },
  @{ task = "dig_block"; terrain = "rough"; episodes = 20; seed = 205000; steps = 400; size = 5 },
  @{ task = "dig_block"; terrain = "cave"; episodes = 20; seed = 206000; steps = 400; size = 5 }
)
$structures = @(
  @{ task = "copy_build"; terrain = "flat"; episodes = 14; seed = 207000; steps = 1500; size = 8 },
  @{ task = "copy_build"; terrain = "rough"; episodes = 14; seed = 208000; steps = 1500; size = 7 },
  @{ task = "schematic_build"; terrain = "flat"; episodes = 8; seed = 209000; steps = 1200; size = 6 },
  @{ task = "gather_build"; terrain = "flat"; episodes = 8; seed = 210000; steps = 1500; size = 5 },
  @{ task = "mine_deposit"; terrain = "rough"; episodes = 8; seed = 211000; steps = 1500; size = 6 }
)
$mobs = @(
  @{ task = "hunt_mobs"; terrain = "flat"; episodes = 40; seed = 212000; steps = 1500; size = 5; perception = "mask" },
  @{ task = "hunt_mobs"; terrain = "rough"; episodes = 30; seed = 213000; steps = 1500; size = 5; perception = "mask" },
  @{ task = "hunt_mobs"; terrain = "cave"; episodes = 30; seed = 214000; steps = 1500; size = 5; perception = "mask" },
  @{ task = "patrol_area"; terrain = "flat"; episodes = 15; seed = 215000; steps = 800; size = 5; perception = "mask" },
  @{ task = "patrol_area"; terrain = "rough"; episodes = 15; seed = 216000; steps = 800; size = 5; perception = "mask" },
  @{ task = "follow_mob"; terrain = "flat"; episodes = 15; seed = 220000; steps = 600; size = 5; perception = "mask" },
  @{ task = "follow_mob"; terrain = "rough"; episodes = 15; seed = 221000; steps = 600; size = 5; perception = "mask" },
  # hunts after the small animals, so the reader sees them up close
  @{ task = "hunt_mobs"; terrain = "flat"; episodes = 20; seed = 225000; steps = 1500; size = 5; perception = "mask"; prey = "chicken" },
  @{ task = "hunt_mobs"; terrain = "rough"; episodes = 15; seed = 226000; steps = 1500; size = 5; perception = "mask"; prey = "chicken" },
  @{ task = "hunt_mobs"; terrain = "flat"; episodes = 15; seed = 227000; steps = 1500; size = 5; perception = "mask"; prey = "sheep,pig" },
  @{ task = "hunt_mobs"; terrain = "rough"; episodes = 15; seed = 228000; steps = 1500; size = 5; perception = "mask"; prey = "sheep,pig,cow" }
)
# a lone target among decoys from the same block list
$find = @(
  @{ task = "find_block"; terrain = "flat"; episodes = 40; seed = 217000; steps = 600; size = 5; perception = "mask" },
  @{ task = "find_block"; terrain = "rough"; episodes = 40; seed = 218000; steps = 600; size = 5; perception = "mask" },
  @{ task = "find_block"; terrain = "cave"; episodes = 30; seed = 219000; steps = 600; size = 5; perception = "mask" }
)
$runs = switch ($Set) { "base" { $base } "structures" { $structures } "mobs" { $mobs } "find" { $find } default { $base + $structures + $mobs + $find } }
if ($Tasks) {
  $keep = $Tasks.Split(",")
  $runs = $runs | Where-Object { $keep -contains $_.task }
}
foreach ($r in $runs) {
  "$(Get-Date -Format HH:mm:ss) $($r.task) $($r.terrain) x$($r.episodes)"
  $perception = if ($r.perception) { $r.perception } else { "reader" }
  $extra = if ($r.prey) { @("--prey", $r.prey) } else { @() }
  & $python -m drone_model.collect.demos --task $r.task --terrain $r.terrain --size $r.size --episodes $r.episodes --seed $r.seed --obstacles 4 --noise 0.15 --streams "rgb,depth,mask,state" --max-steps $r.steps --perception $perception @extra 2>&1 | Select-String "success rate|Traceback|Error"
}
"$(Get-Date -Format HH:mm:ss) done"
