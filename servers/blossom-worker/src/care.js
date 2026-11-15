// Scheduled care of the network around Alpha, kept apart from the request path:
//  - the keeper: every blob the owner's phone listed (/u/<token>/keep) stays in
//    the bucket; a missing one is copied in from the mirrors that had it;
//  - the health map: which public relays and media servers answer, need a login
//    or payment, and keep what they were given; signed with HEALTH_KEY and
//    published as a Nostr event, which the app reads anonymously from relays.
// The free plan allows 50 outside requests per run: each part stays under it.
import { schnorr } from "@noble/curves/secp256k1";
import { sha256 } from "@noble/hashes/sha256";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils";

const HASH = /^[0-9a-f]{64}$/;
const KEEP = "_keep/list.json";
const KEEP_STATE = "_keep/state.json";
const HEALTH = "_health/latest.json";

export const RELAYS = [
  "wss://relay.primal.net", "wss://nostr.oxtr.dev", "wss://relay.nostr.net", "wss://nostr.data.haus",
  "wss://nostr-01.yakihonne.com", "wss://relay.damus.io", "wss://nos.lol", "wss://purplerelay.com",
  "wss://temp.iris.to", "wss://vault.iris.to", "wss://offchain.pub", "wss://relay.snort.social",
  "wss://nostr.mom", "wss://relay.nostr.band", "wss://nostr.wine",
];
export const MEDIA = [
  "https://blossom.primal.net", "https://nostr.download", "https://blossom.data.haus", "https://blossom.ditto.pub",
  "https://blossom.nmail.li", "https://blossom.jumble.social", "https://blossom.yakihonne.com",
];
// Where the signed map goes.
const PUBLISH_TO = ["wss://relay.primal.net", "wss://nostr.oxtr.dev", "wss://relay.nostr.net", "wss://nostr.data.haus"];

async function getJson(store, key, fallback) {
  const r = await store.get(key);
  if (!r.ok) return fallback;
  try { return await r.json(); } catch { return fallback; }
}

/** POST /u/<token>/keep: {items:[{sha, urls:[...]}]} merged into the list. */
export async function addToKeep(request, store) {
  let body;
  try { body = await request.json(); } catch { return { status: 400, reason: "bad json" }; }
  const items = Array.isArray(body?.items) ? body.items.slice(0, 20000) : [];
  const list = await getJson(store, KEEP, {});
  let added = 0;
  for (const it of items) {
    const sha = String(it?.sha || "").toLowerCase();
    if (!HASH.test(sha)) continue;
    const urls = (Array.isArray(it.urls) ? it.urls : []).map(String)
      .filter((u) => u.startsWith("https://") && u.length < 300 && u.includes("/" + sha)).slice(0, 8);
    if (!list[sha]) added++;
    list[sha] = Array.from(new Set([...(list[sha] || []), ...urls])).slice(0, 8);
  }
  const put = await store.put(KEEP, JSON.stringify(list));
  if (!put.ok) return { status: 502, reason: "storage error" };
  return { status: 200, body: { added, total: Object.keys(list).length } };
}

/**
 * One keeper round. Once an hour the bucket is listed (1000 names a request)
 * against the list and what it lacks is kept in the state; every round then
 * copies the next of those in from their mirrors, within the run's request budget.
 */
export async function keepRound(store) {
  let budget = 40;
  const state = await getJson(store, KEEP_STATE, { copied: 0 });
  budget--;
  const now = Math.floor(Date.now() / 1000);
  let list = null;
  if (!Array.isArray(state.queue) || !state.queue.length || now - (state.listedAt || 0) > 3600) {
    list = await getJson(store, KEEP, {});
    budget--;
    const all = Object.keys(list);
    if (!all.length) return;
    const have = new Set();
    let token = null, complete = false;
    while (budget > 12) {
      const page = await store.list(token);
      budget--;
      if (!page) break;
      page.keys.forEach((k) => have.add(k));
      if (!page.next) { complete = true; break; }
      token = page.next;
    }
    if (!complete) return; // too big to list in one run: try again next round
    const missing = all.filter((sha) => !have.has(sha));
    state.queue = missing.slice(0, 6000).map((sha) => [sha, list[sha] || []]);
    state.missing = missing.length;
    state.total = all.length;
    state.listedAt = now;
    state.lost = [];
  }
  const queue = state.queue;
  const failed = [];
  while (queue.length && budget > 6) {
    const [sha, urls, from = 0] = queue.shift();
    // Any Blossom server answers by hash: the public ones are asked after the listed mirrors,
    // two a round; a blob none of them gives back goes to the end to try the next two.
    const all = Array.from(new Set([...urls, ...MEDIA.map((b) => `${b}/${sha}`)]));
    const sources = all.slice(from, from + 2);
    let saved = false;
    for (const url of sources) {
      if (budget < 5) break;
      // A mirror may redirect to its CDN, and every hop counts against the run's limit.
      budget -= 3;
      try {
        const res = await fetch(url);
        if (!res.ok) continue;
        const bytes = new Uint8Array(await res.arrayBuffer());
        // A mirror that hands back other bytes is not trusted.
        if (bytesToHex(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))) !== sha) continue;
        budget--;
        saved = (await store.putBlob(sha, bytes)).ok;
        if (saved) break;
      } catch { /* the next mirror */ }
    }
    if (saved) { state.copied = (state.copied || 0) + 1; state.missing = Math.max(0, (state.missing || 1) - 1); }
    else if (from + 2 < all.length) queue.push([sha, urls, from + 2]);
    else failed.push(sha);
  }
  // Lost: no listed mirror and no public server gave it back.
  state.lost = Array.from(new Set([...(state.lost || []), ...failed])).slice(-500);
  state.at = now;
  await store.put(KEEP_STATE, JSON.stringify(state));
}

/** Opens a relay, asks for one event, and times the answer. */
async function probeRelay(url) {
  const t0 = Date.now();
  try {
    const res = await fetch(url.replace(/^wss:/, "https:"), { headers: { Upgrade: "websocket" } });
    const ws = res.webSocket;
    // 401/403 here means the relay shuts out the prober's network, not its users: unknown, not down.
    if (!ws) return { u: url, ok: res.status === 401 || res.status === 403 ? null : false, why: `http ${res.status}` };
    ws.accept();
    const out = await new Promise((resolve) => {
      const done = (r) => { try { ws.close(); } catch {} resolve(r); };
      const timer = setTimeout(() => done({ ok: false, why: "timeout" }), 6000);
      ws.addEventListener("message", (e) => {
        let m;
        try { m = JSON.parse(e.data); } catch { return; }
        if (m[0] === "EOSE" || m[0] === "EVENT") { clearTimeout(timer); done({ ok: true }); }
        else if (m[0] === "CLOSED" || m[0] === "NOTICE") {
          clearTimeout(timer);
          const why = String(m[2] ?? m[1] ?? "").slice(0, 60);
          done({ ok: false, why, auth: /auth/i.test(why), paid: /pay|paid|subscri/i.test(why) });
        }
      });
      ws.addEventListener("close", () => { clearTimeout(timer); resolve({ ok: false, why: "closed" }); });
      ws.send(JSON.stringify(["REQ", "h", { kinds: [1], limit: 1 }]));
    });
    return { u: url, ms: Date.now() - t0, ...out };
  } catch (e) {
    return { u: url, ok: false, why: String(e?.message || e).slice(0, 60) };
  }
}

/** A media server answers, and still holds blobs it was given (checked on the keeper's list). */
async function probeMedia(base, list) {
  const t0 = Date.now();
  let ok = false;
  try { ok = (await fetch(base + "/", { method: "HEAD" })).status < 500; } catch {}
  const held = Object.entries(list).filter(([, urls]) => urls.some((u) => u.startsWith(base + "/"))).slice(0, 2);
  let checked = 0, kept = 0;
  for (const [sha] of held) {
    checked++;
    try { if ((await fetch(`${base}/${sha}`, { method: "HEAD" })).ok) kept++; } catch {}
  }
  return { u: base, ok, ms: Date.now() - t0, checked, kept };
}

function signEvent(secretHex, content, tags) {
  const pubkey = bytesToHex(schnorr.getPublicKey(hexToBytes(secretHex)));
  const created_at = Math.floor(Date.now() / 1000);
  const id = bytesToHex(sha256(new TextEncoder().encode(JSON.stringify([0, pubkey, created_at, 30078, tags, content]))));
  const sig = bytesToHex(schnorr.sign(hexToBytes(id), hexToBytes(secretHex)));
  return { id, pubkey, created_at, kind: 30078, tags, content, sig };
}

async function publish(url, event) {
  try {
    const res = await fetch(url.replace(/^wss:/, "https:"), { headers: { Upgrade: "websocket" } });
    const ws = res.webSocket;
    if (!ws) return false;
    ws.accept();
    return await new Promise((resolve) => {
      const timer = setTimeout(() => { try { ws.close(); } catch {} resolve(false); }, 6000);
      ws.addEventListener("message", (e) => {
        try {
          const m = JSON.parse(e.data);
          if (m[0] === "OK" && m[1] === event.id) { clearTimeout(timer); try { ws.close(); } catch {} resolve(!!m[2]); }
        } catch {}
      });
      ws.send(JSON.stringify(["EVENT", event]));
    });
  } catch { return false; }
}

/** One health round: probes, the signed map published, a copy kept for /health. */
export async function healthRound(store, env) {
  const list = await getJson(store, KEEP, {});
  const relays = await Promise.all(RELAYS.map(probeRelay));
  const media = await Promise.all(MEDIA.map((m) => probeMedia(m, list)));
  const keep = await getJson(store, KEEP_STATE, {});
  const map = { v: 1, at: Math.floor(Date.now() / 1000), relays, media, archive: { total: keep.total || 0, missing: keep.missing ?? null, copied: keep.copied || 0, lost: (keep.lost || []).length } };
  const content = JSON.stringify(map);
  await store.put(HEALTH, content);
  const key = String(env.HEALTH_KEY || "").replace(/\s+/g, "");
  if (!/^[0-9a-f]{64}$/.test(key)) return;
  const event = signEvent(key, content, [["d", "alpha-health"], ["t", "alpha-health"]]);
  await Promise.all(PUBLISH_TO.map((u) => publish(u, event)));
}

export async function readHealth(store) {
  const r = await store.get(HEALTH);
  return r.ok ? await r.text() : null;
}
