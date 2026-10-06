"""
Records MP4s of a policy flying a task in the live mod: camera, depth, semantic mask, arena map, and stats.
Captures at a higher resolution than training and downsamples for learned policies
"""

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

import numpy as np
import torch
from mcdrone import DroneEnv
from PIL import Image, ImageDraw, ImageFont

from ..paths import CHECKPOINTS, SCHEMATICS, VIDEOS
from ..policies.cnn import DronePolicy, to_image
from ..policies.seq import SeqAgent
from ..perception.reader import Reader
from ..policies.skill import with_skill
from ..experts.jobs import make_planner
from ..experts.tools import make_expert

CAM_W, CAM_H = 480, 360
AGENT_W, AGENT_H = 160, 120
SIDE_W, SIDE_H = 240, 180
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
    canvas = Image.new("RGB", (CAM_W + SIDE_W, CAM_H + STRIP_H), BG)
    canvas.paste(Image.fromarray(obs["rgb"]).resize((CAM_W, CAM_H), Image.BICUBIC), (0, 0))
    canvas.paste(Image.fromarray(depth_colors(obs["depth"])).resize((SIDE_W, SIDE_H)), (CAM_W, 0))
    if "mask" in obs:
        canvas.paste(Image.fromarray(mask_colors(obs["mask"])).resize((SIDE_W, SIDE_H), Image.NEAREST), (CAM_W, SIDE_H))
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
    draw.text((10, CAM_H + 10), line1, fill=INK, font=font)
    draw.text((10, CAM_H + 34), line2, fill=MUTED, font=font)
    draw.text((CAM_W + 8, 4), "depth", fill=INK, font=font)
    draw.text((CAM_W + 8, SIDE_H + 4), "semantic mask", fill=INK, font=font)
    return np.asarray(canvas)


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
    parser.add_argument("--name", default=None)
    parser.add_argument("--out", type=Path, default=VIDEOS)
    args = parser.parse_args()
    reader = Reader(args.reader) if args.perception == "reader" else None

    model = None
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    if args.policy == "bc":
        model = DronePolicy().to(device)
        model.load_state_dict(torch.load(args.checkpoint, map_location=device, weights_only=True)["model"])
        model.eval()
    env = DroneEnv(
        task=args.task,
        tools=args.policy in ("expert", "seq") or None,
        # the frames the reader and skill were trained on, upscaled for the video
        width=AGENT_W,
        height=AGENT_H,
        streams=("rgb", "depth", "mask"),
        task_options={"obstacles": args.obstacles, "terrain": args.terrain, "size": args.size},
        action_pause_ms=0,
    )
    args.out.mkdir(parents=True, exist_ok=True)
    name = args.name or f"{args.task}-{args.policy if args.policy == 'expert' else args.checkpoint.stem}-o{args.obstacles}-{args.terrain}"
    path = args.out / f"{name}.mp4"
    try:
        font = ImageFont.truetype("arial.ttf", 15)
    except OSError:
        font = ImageFont.load_default()
    ffmpeg = subprocess.Popen(
        ["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{CAM_W + SIDE_W}x{CAM_H + STRIP_H}",
         "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20", str(path)],
        stdin=subprocess.PIPE,
    )
    summary = []
    try:
        for i in range(args.episodes):
            obs, info = env.reset(seed=args.seed + i)
            job = info["state"].get("job")
            if args.policy == "expert":
                # tasks with a job run the brain's planner for it, like drone_model.brain
                planner = make_planner(job, env.client.mask_ids, reader, SCHEMATICS) if job and reader is not None else make_expert(args.task, env.client.mask_ids, reader=reader)
                expert = with_skill(planner, args.skill)
            if args.policy == "seq":
                expert = SeqAgent(args.checkpoint, env.client.mask_ids, device)
            if args.policy != "expert":
                label = f"{args.checkpoint.stem} policy, vision only"
            elif args.skill is not None:
                label = "planner plus learned cell skill, block reader vision"
            elif job:
                label = "job planner, scripted flight, block reader vision"
            else:
                label = "scripted expert, explores and maps"
            title = f"{args.task}, {label}, episode {i + 1}"
            terminated = truncated = False
            while True:
                ffmpeg.stdin.write(compose(obs, info, title, font).tobytes())
                if terminated or truncated:
                    break
                if args.policy in ("expert", "seq"):
                    action = expert.act(info["state"], obs)
                else:
                    with torch.no_grad():
                        rgb = torch.from_numpy(np.asarray(Image.fromarray(obs["rgb"]).resize((160, 120), Image.BILINEAR)))[None].to(device)
                        depth = torch.from_numpy(np.asarray(Image.fromarray(obs["depth"]).resize((160, 120), Image.NEAREST)))[None].to(device)
                        action = model(to_image(rgb, depth), torch.from_numpy(obs["state"][:6])[None].to(device))[0].cpu().numpy()
                obs, reward, terminated, truncated, info = env.step(action)
            # hold the last frame for a second so the outcome is readable
            for _ in range(FPS):
                ffmpeg.stdin.write(compose(obs, info, title, font).tobytes())
            summary.append("success" if info["episode"].get("success") else "out of bounds" if info["episode"].get("outOfBounds") else "timeout")
            print(f"episode {i + 1}: {summary[-1]} in {info['episode']['step']} steps", flush=True)
    finally:
        env.close()
        ffmpeg.stdin.close()
        ffmpeg.wait()
    print(f"wrote {path} ({', '.join(summary)})")


if __name__ == "__main__":
    main()
