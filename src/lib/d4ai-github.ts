/** GitHub PAT verification stub — the standalone D4AI app has no server-side
 * verifier; tokens are stored locally and used directly against the GitHub API. */
export async function d4aiGithubVerify(_input: unknown) {
  return { ok: false, login: null as string | null, error: "Verify via GitHub API from settings" };
}
