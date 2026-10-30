import { useEffect, useState } from "react";

const LOGO = "/d4ai-avatar.png";

/**
 * D4AI launch splash — shows the D4AI robot logo (app icon + logo),
 * fades out after a short beat, then unmounts. Also hides the native
 * Capacitor splash on Android so the branded splash is seamless.
 */
export function D4AiSplash() {
  const [phase, setPhase] = useState<"show" | "fade" | "gone">("show");

  useEffect(() => {
    (async () => {
      try {
        const { Capacitor } = await import("@capacitor/core");
        if (Capacitor.isNativePlatform()) {
          try {
            const { SplashScreen } = await import("@capacitor/splash-screen");
            await SplashScreen.hide();
          } catch {}
          try {
            const { StatusBar, Style } = await import("@capacitor/status-bar");
            await StatusBar.setBackgroundColor({ color: "#0b1a3a" });
            await StatusBar.setStyle({ style: Style.Dark });
          } catch {}
        }
      } catch {
        /* web — native splash not in play */
      }
    })();

    const fade = window.setTimeout(() => setPhase("fade"), 1800);
    const gone = window.setTimeout(() => setPhase("gone"), 2450);
    return () => {
      window.clearTimeout(fade);
      window.clearTimeout(gone);
    };
  }, []);

  if (phase === "gone") return null;

  return (
    <div
      aria-hidden={phase === "fade"}
      className="fixed inset-0 z-[999] flex flex-col items-center justify-center overflow-hidden d4ai-splash"
      style={{
        background:
          "radial-gradient(ellipse at 50% 32%, #0b1a3a 0%, #050b18 52%, #020617 100%)",
        opacity: phase === "fade" ? 0 : 1,
        pointerEvents: phase === "fade" ? "none" : "auto",
      }}
    >
      <style>{SPLASH_CSS}</style>
      <div className="relative grid h-44 w-44 place-items-center">
        <span className="d4ai-ring absolute inset-0 rounded-full border-2 border-cyan-400/45" />
        <span
          className="d4ai-ring absolute inset-0 rounded-full border-2 border-fuchsia-400/30"
          style={{ animationDelay: "0.7s" }}
        />
        <div className="absolute -inset-8 rounded-full bg-cyan-500/20 blur-3xl" />
        <img
          src={LOGO}
          alt="D4AI"
          className="d4ai-splash-logo relative h-32 w-32 rounded-full object-cover shadow-[0_0_70px_rgba(34,211,238,0.45)] ring-2 ring-cyan-400/60"
        />
      </div>
      <h1 className="d4ai-title-grad mt-6 text-4xl font-extrabold tracking-wide">D4AI</h1>
      <p className="mt-2 text-[11px] font-semibold tracking-[0.42em] text-slate-300">D4TECH</p>
      <p className="mt-2 text-[13px] text-cyan-200/80">Creating Solutions. Innovating Lives.</p>
      <div className="mt-8 h-1 w-40 overflow-hidden rounded-full bg-white/10">
        <div className="d4ai-load-bar h-full w-1/2 rounded-full bg-gradient-to-r from-cyan-400 to-fuchsia-500" />
      </div>
    </div>
  );
}

const SPLASH_CSS = `
@keyframes d4ai-ring{0%{transform:scale(.85);opacity:.75}100%{transform:scale(1.55);opacity:0}}
.d4ai-ring{animation:d4ai-ring 1.8s ease-out infinite}
@keyframes d4ai-splash-pop{0%{transform:scale(.82);opacity:0}60%{transform:scale(1.05)}100%{transform:scale(1);opacity:1}}
.d4ai-splash-logo{animation:d4ai-splash-pop .7s cubic-bezier(.2,.9,.3,1.3) both}
@keyframes d4ai-load{0%{transform:translateX(-110%)}100%{transform:translateX(240%)}}
.d4ai-load-bar{animation:d4ai-load 1.4s ease-in-out infinite}
.d4ai-title-grad{background:linear-gradient(90deg,#67e8f9 0%,#3b82f6 45%,#c084fc 100%);-webkit-background-clip:text;background-clip:text;color:transparent}
`;
