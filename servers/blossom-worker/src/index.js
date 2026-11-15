// Blossom media server (BUD-01/02) storing blobs in a private Backblaze B2 bucket.
// Reads are public by hash. Writes need the secret path /u/<UPLOAD_TOKEN>/ AND a
// valid kind-24242 event: the app signs every blob with its own one-time key, so
// a pubkey allowlist cannot work.
import { AwsClient } from "aws4fetch";
import { schnorr } from "@noble/curves/secp256k1";
import { sha256 } from "@noble/hashes/sha256";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils";

const MAX_BYTES = 50 * 1024 * 1024;
const HASH = /^[0-9a-f]{64}$/;
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Authorization, Content-Type, Content-Length, Range, X-SHA-256, X-Content-Type, X-Content-Length",
  "Access-Control-Expose-Headers": "Content-Length, Content-Range, Accept-Ranges, X-Reason",
  "Access-Control-Max-Age": "86400",
};

const reply = (status, body, extra = {}) =>
  new Response(body, { status, headers: { ...CORS, ...extra } });
const refuse = (status, reason) => reply(status, null, { "X-Reason": reason });
const json = (obj) => reply(200, JSON.stringify(obj), { "Content-Type": "application/json" });

// Secrets pasted in the dashboard often carry a stray space or newline.
const clean = (v) => String(v || "").replace(/\s+/g, "");

// Lengths only, never values: a cut-off key shows up at once.
const keyShape = (env) =>
  `key_id=${clean(env.B2_KEY_ID).length} app_key=${clean(env.B2_APP_KEY).length} raw_app_key=${String(env.B2_APP_KEY || "").length}`;

function s3(env) {
  return new AwsClient({
    accessKeyId: clean(env.B2_KEY_ID),
    secretAccessKey: clean(env.B2_APP_KEY),
    service: "s3",
    region: env.B2_REGION,
  });
}
const objectUrl = (env, hash) => `${env.B2_ENDPOINT}/${env.B2_BUCKET}/${hash}`;

// Constant-time compare, so the token cannot be guessed byte by byte from timing.
function sameSecret(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

function tag(ev, name) {
  const t = ev.tags.find((x) => Array.isArray(x) && x[0] === name);
  return t ? t[1] : undefined;
}

// BUD-02 auth: returns null when valid, else the reason.
function checkAuth(header, action, hash) {
  if (!header || !header.startsWith("Nostr ")) return "missing auth";
  let ev;
  try {
    ev = JSON.parse(atob(header.slice(6).trim()));
  } catch {
    return "bad auth encoding";
  }
  if (!ev || ev.kind !== 24242 || !Array.isArray(ev.tags)) return "wrong auth kind";
  const now = Math.floor(Date.now() / 1000);
  if (typeof ev.created_at !== "number" || ev.created_at > now + 600) return "auth from the future";
  const exp = Number(tag(ev, "expiration"));
  if (!exp || exp < now) return "auth expired";
  if (tag(ev, "t") !== action) return "wrong auth action";
  if (!ev.tags.some((x) => x[0] === "x" && x[1] === hash)) return "auth not for this blob";
  const id = bytesToHex(sha256(new TextEncoder().encode(
    JSON.stringify([0, ev.pubkey, ev.created_at, ev.kind, ev.tags, ev.content]))));
  if (id !== ev.id) return "bad auth id";
  try {
    if (!schnorr.verify(hexToBytes(ev.sig), hexToBytes(ev.id), hexToBytes(ev.pubkey))) return "bad auth signature";
  } catch {
    return "bad auth signature";
  }
  return null;
}

async function upload(request, env) {
  const declared = Number(request.headers.get("Content-Length") || 0);
  if (declared > MAX_BYTES) return refuse(413, "blob too large");
  const body = new Uint8Array(await request.arrayBuffer());
  if (body.length === 0) return refuse(400, "empty blob");
  if (body.length > MAX_BYTES) return refuse(413, "blob too large");
  const hash = bytesToHex(new Uint8Array(await crypto.subtle.digest("SHA-256", body)));
  const why = checkAuth(request.headers.get("Authorization"), "upload", hash);
  if (why) return refuse(401, why);

  const type = request.headers.get("Content-Type") || "application/octet-stream";
  const put = await s3(env).fetch(objectUrl(env, hash), {
    method: "PUT",
    body,
    headers: { "Content-Type": type, "X-Amz-Content-Sha256": hash },
  });
  if (!put.ok) {
    console.error("b2 put", put.status, keyShape(env), await put.text());
    return refuse(502, "storage error");
  }
  return json({
    url: `${new URL(request.url).origin}/${hash}`,
    sha256: hash,
    size: body.length,
    type,
    uploaded: Math.floor(Date.now() / 1000),
  });
}

async function remove(request, env, hash) {
  const why = checkAuth(request.headers.get("Authorization"), "delete", hash);
  if (why) return refuse(401, why);
  const del = await s3(env).fetch(objectUrl(env, hash), { method: "DELETE" });
  if (!del.ok && del.status !== 404) return refuse(502, "storage error");
  return reply(200, null);
}

async function serve(request, env, hash) {
  const headers = {};
  const range = request.headers.get("Range");
  if (range) headers.Range = range;
  const res = await s3(env).fetch(objectUrl(env, hash), { method: request.method, headers });
  if (res.status === 404) return refuse(404, "not found");
  if (!res.ok && res.status !== 206) {
    console.error("b2 get", res.status, keyShape(env));
    return refuse(502, "storage error");
  }
  const out = { "Accept-Ranges": "bytes", "Cache-Control": "public, max-age=31536000, immutable" };
  for (const h of ["Content-Type", "Content-Length", "Content-Range", "ETag", "Last-Modified"]) {
    const v = res.headers.get(h);
    if (v) out[h] = v;
  }
  return reply(res.status, request.method === "HEAD" ? null : res.body, out);
}

export default {
  async fetch(request, env) {
    const m = request.method;
    if (m === "OPTIONS") return reply(204, null);
    let path = new URL(request.url).pathname;

    let writer = false;
    const priv = path.match(/^\/u\/([^/]+)(\/.*)?$/);
    if (priv) {
      if (!sameSecret(priv[1], clean(env.UPLOAD_TOKEN))) return refuse(404, "not found");
      writer = true;
      path = priv[2] || "/";
    }

    if (path === "/upload") {
      if (!writer) return refuse(401, "uploads are closed");
      if (m === "HEAD") return reply(200, null);
      if (m === "PUT") return upload(request, env);
      return refuse(405, "method not allowed");
    }

    const blob = path.match(/^\/([0-9a-f]{64})(\.[a-z0-9]{1,10})?$/);
    if (blob && HASH.test(blob[1])) {
      if (m === "GET" || m === "HEAD") return serve(request, env, blob[1]);
      if (m === "DELETE") return writer ? remove(request, env, blob[1]) : refuse(401, "deletes are closed");
      return refuse(405, "method not allowed");
    }

    if (path === "/" && (m === "GET" || m === "HEAD")) return reply(200, "Blossom server", { "Content-Type": "text/plain" });
    return refuse(404, "not found");
  },
};
