import type { ReactNode } from "react";

export function Card({
  title,
  actions,
  children,
  className = "",
}: {
  title?: string;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section
      className={`border-border bg-card rounded-lg border p-4 shadow-[0_1px_2px_rgba(31,41,51,0.04)] ${className}`}
    >
      {(title || actions) && (
        <header className="mb-3 flex items-center justify-between gap-2">
          {title && (
            <h2 className="text-text-primary text-sm font-semibold">{title}</h2>
          )}
          {actions}
        </header>
      )}
      {children}
    </section>
  );
}

type Tone = "neutral" | "accent" | "green" | "red" | "amber";

const PILL: Record<Tone, string> = {
  neutral: "bg-sunken text-text-muted",
  accent: "bg-accent-light text-accent",
  green: "bg-green-light text-green",
  red: "bg-red-light text-red",
  amber: "bg-amber-light text-amber",
};

export function Pill({
  tone = "neutral",
  children,
}: {
  tone?: Tone;
  children: ReactNode;
}) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ${PILL[tone]}`}
    >
      {children}
    </span>
  );
}

const BUTTON = {
  primary: "bg-accent text-white hover:bg-accent/90 disabled:bg-accent/40",
  secondary:
    "border border-border bg-card text-text-primary hover:bg-sunken disabled:text-text-dim",
  danger:
    "border border-red/30 bg-card text-red hover:bg-red-light disabled:text-text-dim",
};

export function Button({
  variant = "secondary",
  className = "",
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: keyof typeof BUTTON;
}) {
  return (
    <button
      {...props}
      className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors disabled:cursor-not-allowed ${BUTTON[variant]} ${className}`}
    />
  );
}

export function Field({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}) {
  return (
    <label className="text-text-muted flex flex-col gap-1 text-xs">
      {label}
      {children}
    </label>
  );
}

export const inputClass =
  "rounded-md border border-border bg-card px-2 py-1.5 text-sm text-text-primary outline-none focus:border-accent";

export function Row({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}) {
  return (
    <div className="flex items-baseline justify-between gap-4 py-1 text-sm">
      <span className="text-text-muted">{label}</span>
      <span className="text-text-primary truncate text-right font-mono">
        {children}
      </span>
    </div>
  );
}
