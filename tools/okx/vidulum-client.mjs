/**
 * Minimal Vidulum REST client for the OKX prototype.
 *
 * Zero dependencies, like the rest of this directory. It exists so the prototype can register a
 * user, hold the JWT and make authenticated calls without repeating header plumbing at every
 * call site.
 *
 * The client is deliberately dumb about the domain: it moves JSON and surfaces errors with the
 * backend's own `ApiError` shape (`code` and `message`), because those codes are what the flow
 * reacts to - a 409 `EXCHANGE_ACCOUNT_ALREADY_CONNECTED` means something different from a 409
 * `PORTFOLIO_SPEC_SNAPSHOT_CHANGED`.
 */

export class VidulumError extends Error {
  constructor(status, body, method, path) {
    const code = body?.code ?? "UNKNOWN";
    const detail = body?.message ?? (typeof body === "string" ? body : JSON.stringify(body));
    super(`${method} ${path} -> ${status} ${code}: ${detail}`);
    this.name = "VidulumError";
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

export function createVidulumClient({ baseUrl = "http://localhost:8080", token = null,
                                      fetchImpl = globalThis.fetch, verbose = false } = {}) {
  let jwt = token;

  async function request(method, path, body) {
    const headers = { "Content-Type": "application/json" };
    if (jwt) headers.Authorization = `Bearer ${jwt}`;

    if (verbose) console.error(`-> ${method} ${path}`);
    const response = await fetchImpl(`${baseUrl}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });

    const text = await response.text();
    const parsed = text ? safeJson(text) : null;
    if (!response.ok) throw new VidulumError(response.status, parsed ?? text, method, path);
    return parsed;
  }

  return {
    get token() { return jwt; },

    /**
     * Registers a fresh user and keeps the returned JWT for every later call.
     *
     * The prototype registers rather than logs in because it is not idempotent by design: a
     * rerun against a dirty database is meant to fail loudly rather than half-work.
     */
    async register({ username, email, password }) {
      const created = await request("POST", "/api/v1/auth/register",
        { username, email, password });
      jwt = created.access_token;
      return { userId: created.user_id, token: jwt };
    },

    async authenticate({ username, password }) {
      const session = await request("POST", "/api/v1/auth/authenticate", { username, password });
      jwt = session.access_token;
      return { token: jwt };
    },

    get: (path) => request("GET", path),
    post: (path, body) => request("POST", path, body),
    put: (path, body) => request("PUT", path, body),
  };
}

function safeJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

/** Unique enough for repeated local runs, readable enough to recognise in Mongo. */
export function throwawayUser(prefix = "okx") {
  const stamp = Date.now().toString(36);
  return {
    username: `${prefix}_${stamp}`,
    email: `${prefix}_${stamp}@example.test`,
    password: "SecurePassword123!",
  };
}
