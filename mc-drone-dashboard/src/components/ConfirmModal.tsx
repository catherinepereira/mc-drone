import { useEffect } from "react";
import { Button } from "./ui";

interface Props {
  open: boolean;
  title: string;
  body: string;
  confirmLabel: string;
  onResolve: (ok: boolean) => void;
}

export function ConfirmModal({
  open,
  title,
  body,
  confirmLabel,
  onResolve,
}: Props) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onResolve(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onResolve]);

  if (!open) return null;
  return (
    <div
      className="bg-text-primary/20 fixed inset-0 z-50 flex items-center justify-center p-4 backdrop-blur-sm"
      onClick={() => onResolve(false)}
    >
      <div
        className="border-border bg-card w-full max-w-sm rounded-lg border p-5 shadow-lg"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-base font-semibold">{title}</h2>
        <p className="text-text-muted mt-2 text-sm">{body}</p>
        <div className="mt-5 flex justify-end gap-2">
          <Button onClick={() => onResolve(false)}>Cancel</Button>
          <Button variant="danger" onClick={() => onResolve(true)}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}
