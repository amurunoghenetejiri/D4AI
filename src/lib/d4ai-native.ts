/**
 * Native Android bridge helpers — window.D4AI is injected by MainActivity
 * (WebView addJavascriptInterface). In the packaged app the UI routes device
 * commands, speaking and listening through this bridge; on the web preview it
 * is absent and the app falls back to browser behaviour.
 */

export function isNativeApp(): boolean {
  return typeof window !== "undefined" && !!(window as any).D4AI;
}

export function bridge(): any | null {
  return typeof window !== "undefined" ? (window as any).D4AI ?? null : null;
}

/** One outstanding ask at a time — MainActivity answers via window._d4aiAskCb. */
let pendingAsk: { resolve: (msg: string) => void; timer: number } | null = null;

/**
 * Ask the native brain (CommandRouter + Pollinations). Resolves "" when the
 * bridge is missing, errors or times out, so callers can fall back to the
 * browser brain (askD4AI).
 */
export function nativeAsk(message: string, timeoutMs = 60000): Promise<string> {
  return new Promise((resolve) => {
    const b = bridge();
    if (!b || typeof b.ask !== "function") {
      resolve("");
      return;
    }
    if (pendingAsk) {
      window.clearTimeout(pendingAsk.timer);
      const stale = pendingAsk;
      pendingAsk = null;
      (window as any)._d4aiAskCb = null;
      stale.resolve("");
    }
    let settled = false;
    const finish = (ok: boolean, msg: string) => {
      if (settled) return;
      settled = true;
      window.clearTimeout(timer);
      pendingAsk = null;
      (window as any)._d4aiAskCb = null;
      resolve(ok && msg ? String(msg) : "");
    };
    const timer = window.setTimeout(() => finish(true, ""), timeoutMs);
    pendingAsk = { resolve: (m) => finish(true, m), timer };
    (window as any)._d4aiAskCb = (ok: boolean, msg: string) => finish(ok, msg);
    try {
      b.ask(String(message));
    } catch {
      finish(false, "");
    }
  });
}

export function nativeSpeak(text: string) {
  try {
    bridge()?.speak?.(String(text));
  } catch {}
}

export function nativeListen() {
  try {
    bridge()?.listen?.();
  } catch {}
}

export function nativeStopListen() {
  try {
    bridge()?.stopListen?.();
  } catch {}
}

export function nativeWake() {
  try {
    bridge()?.wake?.();
  } catch {}
}

export function nativeSleep() {
  try {
    bridge()?.sleep?.();
  } catch {}
}

type HeardHandler = (text: string) => void;
let heardHandler: HeardHandler | null = null;

/** Register the single listener MainActivity feeds speech results into. */
export function setNativeHeardHandler(fn: HeardHandler | null) {
  heardHandler = fn;
}

/** Wire the globals the Android side eval()s into. Call once on mount. */
export function installNativeBridge() {
  if (typeof window === "undefined" || !isNativeApp()) return;
  const w = window as any;
  w.onD4AIHeard = (t: unknown) => {
    const text = String(t ?? "").trim();
    if (text && heardHandler) heardHandler(text);
  };
  w.onD4AIStatus = () => {};
}

/** StreamElements TTS — the one public TTS that works straight from a client. */
const SE_VOICES: Record<string, string> = {
  rex: "Brian",
  atlas: "Matthew",
  nova: "Amy",
  luna: "Emma",
  cipher: "Justin",
  echo: "Brian",
  kai: "Joey",
  aria: "Joanna",
  bolt: "Joey",
  sage: "Matthew",
  pixel: "Ivy",
  volt: "Matthew",
};

export function nativeTtsUrl(text: string, voiceId: string): string {
  const se = SE_VOICES[voiceId.toLowerCase()] || "Brian";
  const q = encodeURIComponent(text.slice(0, 280));
  return `https://api.streamelements.com/kappa/v2/speech?voice=${se}&text=${q}`;
}
