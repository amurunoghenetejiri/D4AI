/** MCP test stub — the standalone D4AI app has no server-side MCP tester.
 * Servers are saved and can be wired to a backend tester later. */
export async function d4aiMcpTest(_input: unknown) {
  return {
    ok: false,
    error: "MCP test runs from backend when configured",
    tools: [] as { name: string; description?: string }[],
  };
}
