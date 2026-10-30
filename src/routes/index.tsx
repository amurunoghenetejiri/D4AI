import { createFileRoute } from "@tanstack/react-router";
import { ClientOnly } from "@tanstack/react-router";
import { lazy, Suspense } from "react";
import { D4AiSplash } from "@/components/d4ai/D4AiSplash";

// Lazy so the browser-only app module never enters the SSR graph.
const D4AiApp = lazy(() => import("@/components/d4ai/D4AiApp"));

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "D4AI — AI Assistant by D4TECH" },
      {
        name: "description",
        content:
          "D4AI — your AI buddy by D4TECH. Chat, 13 personality voices, voice calls, camera and files. Creating Solutions, Innovating Lives.",
      },
      { property: "og:title", content: "D4AI — AI Assistant by D4TECH" },
      {
        property: "og:description",
        content:
          "Chat with D4AI, talk by voice, pick from 13 personality voices. Built by D4TECH — Creating Solutions, Innovating Lives.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
      { name: "twitter:title", content: "D4AI — AI Assistant by D4TECH" },
      {
        name: "twitter:description",
        content:
          "Chat with D4AI, talk by voice, pick from 13 personality voices. Built by D4TECH.",
      },
      { name: "theme-color", content: "#0b1a3a" },
    ],
    links: [
      { rel: "icon", type: "image/png", href: "/favicon.png" },
      { rel: "apple-touch-icon", href: "/apple-touch-icon.png" },
      { rel: "manifest", href: "/manifest.webmanifest" },
    ],
  }),
  component: Index,
});

function Index() {
  return (
    <>
      <D4AiSplash />
      <ClientOnly fallback={null}>
        <Suspense fallback={null}>
          <D4AiApp />
        </Suspense>
      </ClientOnly>
    </>
  );
}
