"""
Records MP4s of a policy flying a task in the live mod: a third-person view from behind the drone (or its own camera),
the drone camera, depth, the arena map, and stats.
Agents see frames at the size they were trained on, the video upscales them
"""

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

import numpy as np
from mcdrone import DroneEnv
from PIL import Image, ImageDraw, ImageFont

from ..agents import Pilot, outcome
from ..paths import CHECKPOINTS, VIDEOS
from ..perception.reader import Reader

CAM_W, CAM_H = 480, 360
CHASE_W, CHASE_H = 640, 360
AGENT_W, AGENT_H = 160, 120
SIDE_W, SIDE_H = 240, 180
INSET_W, INSET_H = 160, 120
STRIP_H = 64
BG = (246, 248, 252)
INK = (31, 41, 51)
MUTED = (91, 103, 118)
ACCENT = (61, 123, 217)
MARKER = (232, 137, 43)
FPS = 20


def mask_colors(mask: np.ndarray) -> np.ndarray:
    ids = mask.astype(np.uint32)
    hue = ((ids * 2654435761) % 360).astype(np.float32)
    s, light = 0.45, 0.55
    a = s * min(light, 1 - light)

    def channel(n):
        k = (n + hue / 30) % 12
        return light - a * np.clip(np.minimum(k - 3, 9 - k), -1, 1)

    rgb = np.stack([channel(0), channel(8), channel(4)], axis=-1) * 255
    rgb[ids == 0] = (226, 234, 245)
    return rgb.astype(np.uint8)


def depth_colors(depth: np.ndarray, depth_max: float = 32.0) -> np.ndarray:
    t = np.clip(depth / depth_max, 0, 1)[..., None]
    near = np.array([31, 64, 122], np.float32)
    far = np.array([247, 249, 252], np.float32)
    return (near + (far - near) * t).astype(np.uint8)


def draw_map(draw: ImageDraw.ImageDraw, state: dict, box: tuple[int, int, int, int]) -> None:
    arena = state.get("arena")
    if not arena or "pos" not in state:
        return
    x0, y0, x1, y1 = box
    ox, _, oz = arena["origin"]
    half = arena["radius"] + 1
    scale = (x1 - x0) / (half * 2)

    def sx(x):
        return x0 + (x - (ox + 0.5 - half)) * scale

    def sz(z):
        return y0 + (z - (oz + 0.5 - half)) * scale

    draw.rectangle(box, fill=(255, 255, 255), outline=(221, 227, 236))
    for x, z, w, _h in arena.get("obstacles", []):
        draw.rectangle((sx(x), sz(z), sx(x + w), sz(z + w)), fill=MUTED)
    for p in arena.get("distractors", []):
        draw.rectangle((sx(p[0]), sz(p[2]), sx(p[0] + 1), sz(p[2] + 1)), fill=(170, 176, 186))
    for key, color in (("targets", (40, 40, 40)), ("marker", MARKER), ("goal", (46, 158, 99))):
        points = arena.get(key, [])
        points = [points] if points and isinstance(points[0], int) else points
        for p in points:
            draw.rectangle((sx(p[0]), sz(p[2]), sx(p[0] + 1), sz(p[2] + 1)), fill=color)
    for key, color in (("referenceBase", (90, 170, 180)), ("buildBase", (120, 190, 60))):
        if key in arena:
            p = arena[key]
            draw.rectangle((sx(p[0] - 1), sz(p[2] - 1), sx(p[0] + 2), sz(p[2] + 2)), outline=color, width=2)
    for key in ("sourceChest", "targetChest"):
        if key in arena:
            p = arena[key]
            draw.rectangle((sx(p[0]), sz(p[2]), sx(p[0] + 1), sz(p[2] + 1)), fill=(150, 98, 40), outline=INK)
    px, _, pz = state["pos"]
    yaw = np.radians(state.get("yaw", 0.0))
    draw.line((sx(px), sz(pz), sx(px - np.sin(yaw) * 2.5), sz(pz + np.cos(yaw) * 2.5)), fill=ACCENT, width=2)
    draw.ellipse((sx(px) - 4, sz(pz) - 4, sx(px) + 4, sz(pz) + 4), fill=ACCENT)


def compose(obs: dict, info: dict, title: str, font) -> np.ndarray:
    state = info["state"]
    episode = info["episode"] or {}
    chase = obs.get("chase")
    canvas = Image.new("RGB", frame_size(chase is not None), BG)
    main_h = CAM_H
    if chase is not None:
        # third person on the left, what the drone sees on the right at the same height, depth inset in its corner
        canvas.paste(Image.fromarray(chase).resize((CHASE_W, CHASE_H), Image.BICUBIC), (0, 0))
        canvas.paste(Image.fromarray(obs["rgb"]).resize((CAM_W, CAM_H), Image.BICUBIC), (CHASE_W, 0))
        inset = (CHASE_W + CAM_W - INSET_W - 8, CAM_H - INSET_H - 8)
        canvas.paste(Image.fromarray(depth_colors(obs["depth"])).resize((INSET_W, INSET_H)), inset)
        labels = (((CHASE_W - 110, 6), "third person"), ((CHASE_W + 8, 6), "drone camera"), ((inset[0] + 4, inset[1] + 2), "depth"))
    else:
        canvas.paste(Image.fromarray(obs["rgb"]).resize((CAM_W, CAM_H), Image.BICUBIC), (0, 0))
        canvas.paste(Image.fromarray(depth_colors(obs["depth"])).resize((SIDE_W, SIDE_H)), (CAM_W, 0))
        if "mask" in obs:
            canvas.paste(Image.fromarray(mask_colors(obs["mask"])).resize((SIDE_W, SIDE_H), Image.NEAREST), (CAM_W, SIDE_H))
        labels = (((CAM_W + 8, 4), "depth"), ((CAM_W + 8, SIDE_H + 4), "semantic mask"))
    draw = ImageDraw.Draw(canvas)
    draw_map(draw, state, (8, 8, 120, 120))
    outcome = "success" if episode.get("success") else "out of bounds" if episode.get("outOfBounds") else "timed out" if episode.get("truncated") else "running"
    line1 = f"{title}   step {episode.get('step', 0)}   reward {episode.get('totalReward', 0):.2f}   collisions {episode.get('collisions', 0)}   {outcome}"
    items = [f"{c} {n.split(':')[1]}" for n, c in state.get("inventory", []) if c > 0]
    breaking = state.get("breaking")
    extras = []
    if breaking:
        extras.append(f"mining {breaking['progress'] * 100:.0f}%")
    if state.get("container"):
        extras.append("container open")
    line2 = "inventory: " + (", ".join(items[:6]) if items else "empty") + ("   " + "   ".join(extras) if extras else "")
    draw.text((10, main_h + 10), line1, fill=INK, font=font)
    draw.text((10, main_h + 34), line2, fill=MUTED, font=font)
    for xy, text in labels:
        # dark text with a light outline reads on sky, ground, and the depth colors
        draw.text(xy, text, fill=INK, font=font, stroke_width=2, stroke_fill=BG)
    return np.asarray(canvas)


def frame_size(chase: bool) -> tuple[int, int]:
    return (CHASE_W + CAM_W if chase else CAM_W + SIDE_W), CAM_H + STRIP_H


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--task", default="navigate_to")
    parser.add_argument("--perception", choices=["reader", "mask"], default="reader", help="what experts see with, mask is the mod's ground truth")
    parser.add_argument("--skill", type=Path, default=None, help="a cell skill checkpoint to fly, aim, and fire for the expert's planner")
    parser.add_argument("--reader", type=Path, default=CHECKPOINTS / "reader.pt")
    parser.add_argument("--policy", choices=["expert", "bc", "seq"], default="expert")
    parser.add_argument("--checkpoint", type=Path, default=CHECKPOINTS / "ppo.pt")
    parser.add_argument("--episodes", type=int, default=3)
    parser.add_argument("--seed", type=int, default=300_000)
    parser.add_argument("--obstacles", type=int, default=8)
    parser.add_argument("--terrain", choices=["flat", "rough", "cave"], default="flat")
    parser.add_argument("--size", type=int, default=5, help="structure or deposit side for the copy, build, and mine arenas")
    parser.add_argument("--scan", action="store_true", help="scan perception, the server hands the planner each job box read from the world")
    parser.add_argument("--view",choices=["chase", "drone"], default="chase", help="chase films the drone from behind, drone shows its own camera")
    parser.add_argument("--tier", choices=["copper", "iron", "diamond"], default=None, help="fly a drone of this tier, it sets how fast blocks break")
    parser.add_argument("--name", default=None)
    parser.add_argument("--out", type=Path, default=VIDEOS)
    args = parser.parse_args()
    try:
        pilot = Pilot(args.policy, args.task, args.checkpoint, Reader(args.reader) if args.perception == "reader" else None, args.skill)
    except ValueError as e:
        raise SystemExit(str(e))
    env = DroneEnv(
        task=args.task,
        tools=args.policy in ("expert", "seq") or None,
        # the frames the reader and skill were trained on, upscaled for the video
        width=AGENT_W,
        height=AGENT_H,
        streams=("rgb", "depth", "mask") + (("chase",) if args.view == "chase" else ()),
        chase_size=(CHASE_W, CHASE_H),
        task_options={"obstacles": args.obstacles, "terrain": args.terrain, "size": args.size, "perception": "scan" if args.scan else "vision", **({"tier": args.tier} if args.tier else {})},
        action_pause_ms=0,
    )
    args.out.mkdir(parents=True, exist_ok=True)
    name = args.name or f"{args.task}-{args.policy if args.policy == 'expert' else args.checkpoint.stem}-o{args.obstacles}-{args.terrain}"
    path = args.out / f"{name}.mp4"
    try:
        font = ImageFont.truetype("arial.ttf", 15)
    except OSError:
        font = ImageFont.load_default()
    frame_w, frame_h = frame_size(args.view == "chase")
    ffmpeg = subprocess.Popen(
        ["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{frame_w}x{frame_h}",
         "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20", str(path)],
        stdin=subprocess.PIPE,
    )
    summary = []
    blocks = "scanned blocks" if args.scan else "block reader vision"
    try:
        for i in range(args.episodes):
            obs, info = env.reset(seed=args.seed + i)
            job = info["state"].get("job")
            agent = pilot.start(env.client.mask_ids, info)
            if args.policy != "expert":
                label = f"{args.checkpoint.stem} policy, vision only"
            elif args.skill is not None:
                label = f"planner plus learned cell skill, {blocks}"
            elif job:
                label = f"job planner, scripted flight, {blocks}"
            else:
                label = "scripted expert, explores and maps"
            tier = f"{args.tier} drone, " if args.tier else ""
            title = f"{args.task}, {tier}{label}, episode {i + 1}"
            # an arena can be done before its first step
            terminated, truncated = bool(info["episode"].get("done")), False
            while True:
                ffmpeg.stdin.write(compose(obs, info, title, font).tobytes())
                if terminated or truncated:
                    break
                obs, reward, terminated, truncated, info = env.step(agent.act(info["state"], obs))
            # hold the last frame for a second so the outcome is readable
            for _ in range(FPS):
                ffmpeg.stdin.write(compose(obs, info, title, font).tobytes())
            summary.append(outcome(info["episode"]))
            print(f"episode {i + 1}: {summary[-1]} in {info['episode']['step']} steps", flush=True)
    finally:
        env.close()
        ffmpeg.stdin.close()
        ffmpeg.wait()
    print(f"wrote {path} ({', '.join(summary)})")


if __name__ == "__main__":
    main()
