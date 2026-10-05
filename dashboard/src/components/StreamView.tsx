import { useEffect, useRef, useState } from "react";
import { maskName, type MaskIds, type Observation } from "../protocol";

type Stream = "rgb" | "depth" | "mask";

interface Props {
  obs: Observation | null;
  stream: Stream;
  maskIds: MaskIds | null;
  depthMax: number;
  label: string;
}

// mask ids get a stable color from a hash, sky stays near the page background
function maskColor(id: number): [number, number, number] {
  if (id === 0) return [226, 234, 245];
  const h = ((id * 2654435761) >>> 0) % 360;
  const s = 0.45;
  const l = 0.55;
  const k = (n: number) => (n + h / 30) % 12;
  const a = s * Math.min(l, 1 - l);
  const f = (n: number) =>
    l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1)));
  return [f(0) * 255, f(8) * 255, f(4) * 255];
}

// near is dark accent blue, far fades to white
function depthColor(d: number, max: number): [number, number, number] {
  const t = Math.min(1, Math.max(0, d / max));
  return [31 + (247 - 31) * t, 64 + (249 - 64) * t, 122 + (252 - 122) * t];
}

export function StreamView({ obs, stream, maskIds, depthMax, label }: Props) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [hover, setHover] = useState<string | null>(null);

  useEffect(() => {
    const el = canvas.current;
    if (!el || !obs) return;
    const { width, height } = obs;
    if (!width || !height) return;
    el.width = width;
    el.height = height;
    const ctx = el.getContext("2d");
    if (!ctx) return;
    const img = ctx.createImageData(width, height);
    const px = img.data;
    for (let i = 0; i < width * height; i++) {
      let rgb: [number, number, number] = [240, 243, 248];
      if (stream === "rgb" && obs.rgb)
        rgb = [obs.rgb[i * 3], obs.rgb[i * 3 + 1], obs.rgb[i * 3 + 2]];
      else if (stream === "depth" && obs.depth)
        rgb = depthColor(obs.depth[i], depthMax);
      else if (stream === "mask" && obs.mask) rgb = maskColor(obs.mask[i]);
      px[i * 4] = rgb[0];
      px[i * 4 + 1] = rgb[1];
      px[i * 4 + 2] = rgb[2];
      px[i * 4 + 3] = 255;
    }
    ctx.putImageData(img, 0, 0);
  }, [obs, stream, depthMax]);

  const available =
    obs &&
    (stream === "rgb" ? obs.rgb : stream === "depth" ? obs.depth : obs.mask);

  function onMove(e: React.MouseEvent<HTMLCanvasElement>) {
    if (!obs) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const x = Math.floor(((e.clientX - rect.left) / rect.width) * obs.width);
    const y = Math.floor(((e.clientY - rect.top) / rect.height) * obs.height);
    const i = y * obs.width + x;
    const parts: string[] = [`${x}, ${y}`];
    if (obs.depth) parts.push(`${obs.depth[i].toFixed(2)} blocks`);
    if (obs.mask) parts.push(maskName(obs.mask[i], maskIds));
    setHover(parts.join(", "));
  }

  return (
    <figure className="flex flex-col gap-1.5">
      <figcaption className="flex items-baseline justify-between text-xs">
        <span className="text-text-muted font-medium tracking-wide uppercase">
          {label}
        </span>
        <span className="text-text-dim truncate font-mono">{hover ?? ""}</span>
      </figcaption>
      <div className="border-border bg-sunken relative overflow-hidden rounded-md border">
        <canvas
          ref={canvas}
          className="pixelated block aspect-[4/3] w-full"
          onMouseMove={onMove}
          onMouseLeave={() => setHover(null)}
        />
        {!available && (
          <div className="text-text-dim absolute inset-0 flex items-center justify-center text-sm">
            {obs ? "stream off" : "waiting for frames"}
          </div>
        )}
      </div>
    </figure>
  );
}
