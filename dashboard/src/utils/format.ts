export function fixed(value: number | undefined | null, digits = 2): string {
  return value == null || Number.isNaN(value) ? "-" : value.toFixed(digits);
}

export function vec(v: number[] | undefined | null, digits = 1): string {
  return v ? v.map((x) => x.toFixed(digits)).join("  ") : "-";
}

export function time(ts: string): string {
  const d = new Date(ts);
  return (
    d.toLocaleTimeString([], { hour12: false }) +
    "." +
    String(d.getMilliseconds()).padStart(3, "0")
  );
}

export function shortId(id: string): string {
  return id.replace(/^navigate_to-/, "");
}
