// Adds the decorative Tabler outline icons to pattern_icons.json (names already there win).
const fs = require("fs");
const [nodesPath, metaPath, libPath] = process.argv.slice(2);
const nodes = JSON.parse(fs.readFileSync(nodesPath, "utf8"));
const meta = JSON.parse(fs.readFileSync(metaPath, "utf8"));
const lib = JSON.parse(fs.readFileSync(libPath, "utf8"));
const n = v => +(+v).toFixed(2);
function el([tag, a]) {
  switch (tag) {
    case "path": return /^M0 0h24v24H0z$/i.test(a.d) ? "" : a.d;
    case "circle": { const cx = n(a.cx), cy = n(a.cy), r = n(a.r); return `M${n(cx - r)} ${cy}a${r} ${r} 0 1 0 ${n(2 * r)} 0a${r} ${r} 0 1 0 ${n(-2 * r)} 0z`; }
    case "ellipse": { const cx = n(a.cx), cy = n(a.cy), rx = n(a.rx), ry = n(a.ry); return `M${n(cx - rx)} ${cy}a${rx} ${ry} 0 1 0 ${n(2 * rx)} 0a${rx} ${ry} 0 1 0 ${n(-2 * rx)} 0z`; }
    case "line": return `M${n(a.x1)} ${n(a.y1)}L${n(a.x2)} ${n(a.y2)}`;
    case "polyline": case "polygon": {
      const p = String(a.points).trim().split(/[\s,]+/).map(Number);
      let d = `M${n(p[0])} ${n(p[1])}`;
      for (let i = 2; i + 1 < p.length; i += 2) d += `L${n(p[i])} ${n(p[i + 1])}`;
      return tag === "polygon" ? d + "z" : d;
    }
    case "rect": {
      const x = n(a.x || 0), y = n(a.y || 0), w = n(a.width), h = n(a.height);
      let r = Math.min(n(a.rx || a.ry || 0), w / 2, h / 2);
      if (!r) return `M${x} ${y}h${w}v${h}h${n(-w)}z`;
      return `M${n(x + r)} ${y}h${n(w - 2 * r)}a${r} ${r} 0 0 1 ${r} ${r}v${n(h - 2 * r)}a${r} ${r} 0 0 1 ${n(-r)} ${r}h${n(-(w - 2 * r))}a${r} ${r} 0 0 1 ${n(-r)} ${n(-r)}v${n(-(h - 2 * r))}a${r} ${r} 0 0 1 ${r} ${n(-r)}z`;
    }
    default: return null;
  }
}
const KEEP = new Set(["Food", "Animals", "Nature", "Weather", "Vehicles", "Sport", "Health", "Mood", "Zodiac", "Games", "Buildings",
  "Photography", "Media", "Devices", "Computers", "Map", "Communication", "Gestures", "E-commerce", "Symbols", ""]);
// Logos, interface glyphs and slashed "off" variants make no wallpaper.
const SKIP = /(^brand-|-off$|^arrow|^chevron|^square-|^circle-|^text-|^list|^layout|^table|^file-|^folder-|^letter-|^number-|^math|^align|^border|^box-|^device-.*-(cog|code|search|plus|minus|check|x|question|exclamation|pause|up|down|share|star|heart|bolt|dollar|pin|cancel)$|-(plus|minus|check|x|question|exclamation|cog|code|search|share|up|down|bolt|dollar|pin|cancel|pause|star|heart|lock|edit|spark)$|^ad-|^api|^app-|^badge|^ban|^barcode|^baseline|^bleach|^blockquote|^bold|^brackets|^braces|^clear|^click|^clipboard|^code|^column|^command|^container|^copy|^crop|^cursor|^dots|^drag|^focus|^grid|^hash|^h-\d|^indent|^italic|^keyframe|^logout|^login|^menu|^point|^radius|^selector|^separator|^settings|^shadow|^slash|^sort|^spacing|^strikethrough|^subtask|^switch|^tab|^tallymark|^tilde|^underline|^vector|^wash|^window|^zoom)/;
let added = 0;
for (const [name, list] of Object.entries(nodes)) {
  const m = meta[name];
  if (!m || !KEEP.has(m.category || "") || SKIP.test(name) || lib[name]) continue;
  const d = list.map(el).filter(Boolean).join("");
  if (!d || d.length > 600 || !/^[MmLlHhVvCcSsQqTtAaZz0-9eE.,\s+-]+$/.test(d)) continue;
  lib[name] = [d, (m.tags || []).map(String).slice(0, 8).join(",")];
  added++;
}
// Company and console logos from either set are trademarks, not figures.
const LOGOS = /^(chrome|chromium|codepen|codesandbox|dribbble|facebook|figma|framer|github|gitlab|instagram|linkedin|pocket|slack|trello|twitch|twitter|youtube|device-nintendo|steam|fish-christianity)$|^(playstation|xbox)-/;
for (const k of Object.keys(lib)) if (LOGOS.test(k)) delete lib[k];
fs.writeFileSync(libPath, JSON.stringify(lib));
console.log("added", added, "total", Object.keys(lib).length, "bytes", fs.statSync(libPath).size);
