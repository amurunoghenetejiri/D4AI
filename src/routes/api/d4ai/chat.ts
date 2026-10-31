import { createFileRoute } from "@tanstack/react-router";

const SYSTEM = `You are D4AI by D4TECH. Creator: Amurun Destiny O. Always identify as D4AI.`;

async function askPollinations(message: string): Promise<string> {
  const body = JSON.stringify({
    model: "openai",
    messages: [
      { role: "system", content: SYSTEM },
      { role: "user", content: message },
    ],
  });
  const res = await fetch("https://text.pollinations.ai/openai", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body,
  });
  const raw = await res.text();
  try {
    const json = JSON.parse(raw) as { choices?: { message?: { content?: string } }[] };
    const c = json.choices?.[0]?.message?.content?.trim();
    if (c) return c;
  } catch {}
  if (raw.trim()) return raw.trim();
  throw new Error("empty");
}

export const Route = createFileRoute("/api/d4ai/chat")({
  server: {
    handlers: {
      POST: async ({ request }) => {
        try {
          const body = (await request.json()) as { message?: string };
          const message = String(body.message || "").trim();
          if (!message) return Response.json({ ok: false, error: "No message" }, { status: 400 });
          const answer = await askPollinations(message);
          return Response.json({ ok: true, answer, sources: [] });
        } catch (e) {
          return Response.json(
            { ok: false, error: e instanceof Error ? e.message : "failed" },
            { status: 502 },
          );
        }
      },
    },
  },
});
