/** D4AI brain — Pollinations API, always branded as D4AI */

const SYSTEM = `You are D4AI — a personal AI assistant by D4TECH (Creating Solutions, Innovating Lives).
Creator: Amurun Destiny O, founder and CEO of D4TECH.
Always identify as D4AI only. Be clear, helpful, and finish every sentence.`;

export async function askD4AI(
  message: string,
  history: { role: string; content: string }[] = [],
  signal?: AbortSignal,
): Promise<string> {
  const messages = [
    { role: "system", content: SYSTEM },
    ...history.slice(-6).map((m) => ({
      role: m.role === "assistant" ? "assistant" : "user",
      content: String(m.content || "").slice(0, 800),
    })),
    { role: "user", content: message },
  ];

  try {
    const res = await fetch("https://text.pollinations.ai/openai", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ model: "openai", messages }),
      signal,
      cache: "no-store",
    });
    const raw = await res.text();
    if (res.ok && raw) {
      try {
        const json = JSON.parse(raw) as {
          choices?: { message?: { content?: string } }[];
        };
        const content = json.choices?.[0]?.message?.content?.trim();
        if (content) return content;
      } catch {
        if (raw.trim()) return raw.trim();
      }
    }
  } catch {
    /* fallback */
  }

  const prompt = encodeURIComponent(`You are D4AI. ${message}`);
  const res2 = await fetch(`https://text.pollinations.ai/${prompt}`, {
    signal,
    cache: "no-store",
  });
  const t = (await res2.text()).trim();
  if (t) return t;
  throw new Error("D4AI model unreachable. Check internet and try again.");
}
