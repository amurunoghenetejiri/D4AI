/** Standalone D4AI — no parent exam shell */
export function appNavigate(_navigate: unknown, to: string) {
  if (typeof window === "undefined") return;
  if (to === "/student" || to === "/") {
    window.location.href = "/";
    return;
  }
  window.location.href = to;
}
