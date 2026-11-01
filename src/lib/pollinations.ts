/** D4AI brain — Pollinations "openai" model for text, reasoning and vision. */

const SYSTEM = `You are D4AI — a personal AI assistant by D4TECH (Creating Solutions, Innovating Lives).
Creator: Amurun Destiny O, founder and CEO of D4TECH.
Always identify as D4AI only. Be clear, helpful, and finish every sentence.`;

type PollinationsPart =
  | { type: "text"; text: string }
  | { type: "image_url"; image_url: { url: string } };

export async function askD4AI(
  message: string,
  history: { role: string; content: string }[] = [],
  signal?: AbortSignal,
  images?: string[],
): Promise<string> {
  // Vision: the newest user turn becomes text + image_url parts
  let last: { role: string; content: string | PollinationsPart[] } = {
    role: "user",
    content: message,
  };
  if (images?.length) {
    const parts: PollinationsPart[] = [{ type: "text", text: message }];
    for (const img of images.slice(0, 3)) {
      parts.push({
        type: "image_url",
        image_url: { url: img.startsWith("data:") ? img : `data:image/jpeg;base64,${img}` },
      });
    }
    last = { role: "user", content: parts };
  }

  const messages = [
    { role: "system", content: SYSTEM },
    ...history.slice(-6).map((m) => ({
      role: m.role === "assistant" ? "assistant" : "user",
      content: String(m.content || "").slice(0, 800),
    })),
    last,
  ];

  try {
    const res = await fetch("https://text.pollinations.ai/openai", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ model: "openai", messages }),
      signal: signal ?? null,
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
        if (raw.trim() && !raw.trim().startsWith("<")) return raw.trim();
      }
    }
  } catch {
    /* fallback */
  }

  const prompt = encodeURIComponent(`You are D4AI. ${message}`);
  const res2 = await fetch(`https://text.pollinations.ai/${prompt}`, {
    signal: signal ?? null,
    cache: "no-store",
  });
  const t = (await res2.text()).trim();
  if (t && !t.startsWith("<")) return t;
  throw new Error("D4AI model unreachable. Check internet and try again.");
}
