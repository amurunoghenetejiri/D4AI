/** Minimal router shim so D4AI runs without TanStack Start */
export function useNavigate() {
  return (opts: { to?: string } | string) => {
    const to = typeof opts === "string" ? opts : opts?.to || "/";
    if (typeof window !== "undefined") window.location.href = to;
  };
}
