Builds `android/app/src/main/assets/pattern_icons.json`, the figures of chat background patterns.

    npm pack lucide-static@0.460.0 && npm pack @tabler/icons@3.24.0   # or fetch the files from a CDN
    node lucide.js <lucide>/icon-nodes.json <lucide>/tags.json pattern_icons.json
    node tabler.js <tabler>/tabler-nodes-outline.json <tabler>/icons.json pattern_icons.json

Interface glyphs, slashed variants and logos are left out. Licenses: Lucide ISC, Tabler MIT (see THIRD_PARTY_LICENSES.md).
