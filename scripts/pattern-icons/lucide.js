// Converts icon-nodes.json + tags.json into one compact {name: [d, tags]} map for chat patterns.
const fs = require("fs");
const [nodesPath, tagsPath, outPath] = process.argv.slice(2);
const nodes = JSON.parse(fs.readFileSync(nodesPath, "utf8"));
const tags = JSON.parse(fs.readFileSync(tagsPath, "utf8"));
const n = v => +(+v).toFixed(2);
function el([tag, a]) {
  switch (tag) {
    case "path": return a.d;
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
      let r = n(a.rx || a.ry || 0); r = Math.min(r, w / 2, h / 2);
      if (!r) return `M${x} ${y}h${w}v${h}h${n(-w)}z`;
      return `M${n(x + r)} ${y}h${n(w - 2 * r)}a${r} ${r} 0 0 1 ${r} ${r}v${n(h - 2 * r)}a${r} ${r} 0 0 1 ${n(-r)} ${r}h${n(-(w - 2 * r))}a${r} ${r} 0 0 1 ${n(-r)} ${n(-r)}v${n(-(h - 2 * r))}a${r} ${r} 0 0 1 ${r} ${n(-r)}z`;
    }
    default: return null;
  }
}
// Interface glyphs make poor wallpaper figures; they are left out.
const SKIP = /^(a-|align-|arrow|chevron|chevrons|text-|list|panel|square-|circle-|file-|folder-|table|layout|grid|toggle|bring-|send-to|move-|between-|book-|calendar-|clipboard-|columns|rows|wrap|ungroup|group|indent|outdent|pilcrow|heading|strikethrough|subscript|superscript|underline|italic|bold|baseline|case-|whole-word|regex|replace|remove-formatting|space|scaling|scan|fold-|unfold|unlink|link-2-off|monitor-|mouse-pointer|pointer|shield-|lock-keyhole-open|redo|undo|refresh|rotate-|repeat|shuffle|corner-|dot|ellipsis|equal|minus|plus|percent|slash|divide|sigma|pi|omega|parentheses|brackets|braces|code-xml|binary|hash|asterisk|ampersand|at-sign|tally|signpost-big|split|merge|git-|database-|server-|hard-drive|monitor|tablet-smartphone|cast|screen-share|picture-in-picture|frame|crop|square|circle|triangle|pentagon|hexagon|octagon|diamond$|rectangle|cuboid|cylinder|cone|pyramid|torus|box-select|lasso|move|expand|shrink|maximize|minimize|fullscreen|log-|import|upload|download|share|external-link|reply|forward|history|timer-|alarm-clock-|bell-|message-square-|message-circle-|mail-|phone-|user-|users-|wifi-|signal-|battery-|bluetooth-|volume|mic-off|video-off|camera-off|eye-off|ear-off|bell-off|cloud-off|wifi-off|unplug|plug-zap|power-off|toggle|sliders|settings-2|filter|funnel|sort|arrow|navigation-off|map-pin-|locate-|badge-|ticket-|tag-|bookmark-|flag-|heart-|star-|thumbs|hand-|receipt|wallet-|credit-card|banknote|coins|circle-dollar|dollar|euro|pound|japanese-yen|indian-rupee|russian-ruble|swiss-franc|bitcoin|gem$|chart|bar-chart|line-chart|pie-chart|area-chart|gantt|kanban|trending|activity|pulse|gauge|radar)/;
const out = {};
let kept = 0, skipped = 0;
for (const [name, list] of Object.entries(nodes)) {
  if (SKIP.test(name) && !/^(star|heart|circle-dot|diamond)$/.test(name)) { skipped++; continue; }
  const d = list.map(el).filter(Boolean).join("");
  if (!d || d.length > 600 || !/^[MmLlHhVvCcSsQqTtAaZz0-9eE.,\s+-]+$/.test(d)) { skipped++; continue; }
  out[name] = [d, (tags[name] || []).slice(0, 8).join(",")];
  kept++;
}
fs.writeFileSync(outPath, JSON.stringify(out));
console.log("kept", kept, "skipped", skipped, "bytes", fs.statSync(outPath).size);
