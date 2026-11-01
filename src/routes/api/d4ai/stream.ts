/**
 * D4AI streaming chat — Text, reasoning and vision all run on the
 * Pollinations "openai" model (free, unlimited), streamed token by token.
 * Images (base64 data URLs) are sent as image_url parts for vision.
 *
 * POST /api/d4ai/stream  { messages, images?, documentText?, documentName? }
 * Responds with NDJSON lines: {type:"token",content} … {type:"done"}
 */
import { createFileRoute } from "@tanstack/react-router";

type Msg = { role: string; content: string };
type Part = { type: "text"; text: string } | { type: "image_url"; image_url: { url: string } };

const SYSTEM =
  "You are D4AI by D4TECH — a friendly, helpful study and life companion. " +
  "Reply clearly with short paragraphs or bullets. Match the user's language (including Nigerian Pidgin when they use it). " +
  "Never start with 'Error', 'fetch failed', or raw system messages. " +
  "Vary your openings naturally (not always the same phrase). For greetings, be warm and brief. " +
  "If live research notes are attached, use those facts and answer in your own words — do not dump the notes. " +
  "For math: first state the final answer boldly, then give clear step-by-step solution. " +
  "Be detailed when helpful, but not bloated.";

function model() {
  return process.env["D4AI_POLLINATIONS_MODEL"] || "openai";
}

export const Route = createFileRoute("/api/d4ai/stream")({
  server: {
    handlers: {
      POST: async ({ request }) => {
        try {
          const body = (await request.json()) as {
            messages?: Msg[];
            images?: string[];
            documentText?: string;
            documentName?: string;
          };

          const messages = Array.isArray(body.messages) ? body.messages.slice(-16) : [];
          if (!messages.length) {
            return Response.json({ error: "No messages" }, { status: 400 });
          }

          const hasImages = Boolean(body.images?.length);
          const hasDoc = Boolean(body.documentText?.trim());

          const msgs: { role: string; content: string | Part[] }[] = messages.map((m) => ({
            role: m.role === "assistant" ? "assistant" : m.role === "system" ? "system" : "user",
            content: String(m.content || "").slice(0, 48000),
          }));

          if (hasDoc) {
            const last = msgs[msgs.length - 1]!;
            if (last.role === "user") {
              last.content =
                String(last.content) +
                `\n\n---\nContent from "${body.documentName || "document"}":\n` +
                String(body.documentText).slice(0, 16000);
            }
          }

          msgs.unshift({ role: "system", content: SYSTEM });

          // Vision: attach images to the newest user turn as image_url parts
          if (hasImages) {
            const last = msgs[msgs.length - 1]!;
            if (last.role === "user") {
              const parts: Part[] = [{ type: "text", text: String(last.content) }];
              for (const img of (body.images || []).slice(0, 4)) {
                parts.push({
                  type: "image_url",
                  image_url: { url: img.startsWith("data:") ? img : `data:image/jpeg;base64,${img}` },
                });
              }
              last.content = parts;
            }
          }

          const res = await fetch("https://text.pollinations.ai/openai", {
            method: "POST",
            headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
            body: JSON.stringify({ model: model(), messages: msgs, stream: true }),
          });

          const encoder = new TextEncoder();
          const decoder = new TextDecoder();

          const ndjson = new ReadableStream({
            async start(controller) {
              const sendToken = (t: string) => {
                if (t) controller.enqueue(encoder.encode(JSON.stringify({ type: "token", content: t }) + "\n"));
              };
              try {
                if (!res.ok || !res.body) {
                  // Non-streaming fallback — one full reply as a single token
                  const res2 = await fetch("https://text.pollinations.ai/openai", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ model: model(), messages: msgs }),
                  });
                  const raw = await res2.text();
                  let token = "";
                  try {
                    const j = JSON.parse(raw) as { choices?: { message?: { content?: string } }[] };
                    token = j.choices?.[0]?.message?.content?.trim() || "";
                  } catch {
                    token = raw.trim();
                  }
                  sendToken(token || "I could not reach my brain just now — try again in a moment.");
                  controller.enqueue(encoder.encode(JSON.stringify({ type: "done" }) + "\n"));
                  controller.close();
                  return;
                }

                controller.enqueue(
                  encoder.encode(JSON.stringify({ type: "meta", model: model() }) + "\n"),
                );
                const reader = res.body.getReader();
                let buffer = "";
                while (true) {
                  const { done, value } = await reader.read();
                  if (done) break;
                  buffer += decoder.decode(value, { stream: true });
                  const lines = buffer.split("\n");
                  buffer = lines.pop() || "";
                  for (const line of lines) {
                    const trimmed = line.trim();
                    if (!trimmed.startsWith("data:")) continue;
                    const payload = trimmed.slice(5).trim();
                    if (!payload || payload === "[DONE]") continue;
                    try {
                      const j = JSON.parse(payload) as {
                        choices?: { delta?: { content?: string }; message?: { content?: string } }[];
                      };
                      const token = j.choices?.[0]?.delta?.content || j.choices?.[0]?.message?.content || "";
                      sendToken(token);
                    } catch {
                      /* skip malformed chunk */
                    }
                  }
                }
                if (buffer.trim()) {
                  for (const line of buffer.split("\n")) {
                    const trimmed = line.trim();
                    if (!trimmed.startsWith("data:")) continue;
                    const payload = trimmed.slice(5).trim();
                    if (!payload || payload === "[DONE]") continue;
                    try {
                      const j = JSON.parse(payload) as {
                        choices?: { delta?: { content?: string }; message?: { content?: string } }[];
                      };
                      sendToken(j.choices?.[0]?.delta?.content || j.choices?.[0]?.message?.content || "");
                    } catch {
                      /* ignore */
                    }
                  }
                }
                controller.enqueue(encoder.encode(JSON.stringify({ type: "done" }) + "\n"));
                controller.close();
              } catch (e) {
                const msg = e instanceof Error ? e.message : "stream failed";
                try {
                  controller.enqueue(encoder.encode(JSON.stringify({ type: "error", error: msg }) + "\n"));
                  controller.close();
                } catch {
                  controller.error(e);
                }
              }
            },
          });

          return new Response(ndjson, {
            headers: {
              "Content-Type": "application/x-ndjson; charset=utf-8",
              "Cache-Control": "no-cache, no-transform",
              "X-Accel-Buffering": "no",
            },
          });
        } catch (e) {
          const msg = e instanceof Error ? e.message : "Failed to reach D4AI";
          return Response.json({ error: msg }, { status: 500 });
        }
      },
    },
  },
});
