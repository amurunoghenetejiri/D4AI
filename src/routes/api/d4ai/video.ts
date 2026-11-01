/**
 * D4AI Video Studio backend.
 *
 * 1) HuggingFace Wan 2.1 text-to-video when an HF_TOKEN is configured —
 *    returns the raw mp4 stream.
 * 2) Without a token (or when HuggingFace is busy) returns { fallback: true }
 *    and the app builds the clip from 8 free Pollinations frames.
 *
 * POST /api/d4ai/video  { prompt: string }
 */
import { createFileRoute } from "@tanstack/react-router";

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function hfToken() {
  return (process.env["HF_TOKEN"] || process.env["HUGGINGFACE_TOKEN"] || "").trim();
}

function hfModel() {
  return process.env["HF_VIDEO_MODEL"] || "Wan-AI/Wan2.1-T2V-1.3B";
}

export const Route = createFileRoute("/api/d4ai/video")({
  server: {
    handlers: {
      POST: async ({ request }) => {
        try {
          const body = (await request.json()) as { prompt?: string };
          const prompt = String(body.prompt || "").trim().slice(0, 800);
          if (!prompt) {
            return Response.json({ ok: false, fallback: true, reason: "No prompt" }, { status: 400 });
          }

          const token = hfToken();
          if (!token) {
            return Response.json({
              ok: false,
              fallback: true,
              reason: "No HuggingFace token — using the free 8-frame trick.",
            });
          }

          // Wan 2.1 via the HF inference API; 503 means the model is warming up
          // and the response carries a Location header we can poll.
          let url = `https://api-inference.huggingface.co/models/${hfModel()}`;
          const deadline = Date.now() + 45_000;
          while (Date.now() < deadline) {
            const res = await fetch(url, {
              method: "POST",
              headers: {
                Authorization: `Bearer ${token}`,
                "Content-Type": "application/json",
              },
              body: JSON.stringify({ inputs: prompt }),
            });
            if (res.ok) {
              return new Response(res.body, {
                headers: {
                  "Content-Type": "video/mp4",
                  "Cache-Control": "no-store",
                },
              });
            }
            if (res.status === 503) {
              const j = (await res.json().catch(() => null)) as { estimated_time?: number } | null;
              const next = res.headers.get("location");
              if (next) url = next;
              const wait = Math.min(Math.max(Math.round((j?.estimated_time ?? 10) * 1000), 3_000), 15_000);
              await sleep(wait);
              continue;
            }
            break;
          }

          return Response.json({
            ok: false,
            fallback: true,
            reason: "HuggingFace was busy or the token was rejected.",
          });
        } catch (e) {
          return Response.json({
            ok: false,
            fallback: true,
            reason: e instanceof Error ? e.message : "failed",
          });
        }
      },
    },
  },
});
