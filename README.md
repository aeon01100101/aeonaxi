# AeonAxi — Fusion Matter Earth Map

Android app (`com.aeonaxi.app`, min Android 8.0) showing **FusionFall's Fusion Matter**
(Planet Fusion's lime-green goo) consuming Earth over October 1–31.

## Detail (v1.1.0)
- **Real borders:** 180 countries (Natural Earth geometry via world.geo.json),
  drawn and labelled as you zoom.
- **All 3,221 US counties** (Census FIPS GeoJSON) appear on deep zoom, with
  state labels at medium zoom and county names at deep zoom.
- **150+ cities** worldwide with dots and zoom-scaled name labels.
- **Zoom / pan:** pinch, double-tap, + / − buttons, drag to pan, ⌂ resets to
  the whole Earth. The scale bar rescales with zoom (24,901-mile basis).
- **Tap to identify:** city, county + state, country, or ocean — with its
  Fusion Matter status for the current day.
- Map data lives in `app/src/main/assets/`; original downloads in `data-src/`.

- **Normal land / ocean** = normal colours
- **Lime green (#3DFF1A)** = corrupted zones, fusion-matter oceans, fusion-matter
  scars, future zones, and erased districts (lime disc with an X, 14 cities)
- **Real-time LIVE mode (default):** opens synced to the real calendar date —
  in October it animates from Oct 1 up to today, then re-checks the date every
  30 seconds and rolls over at midnight on its own. **GO LIVE** returns to it
  after scrubbing. **Play 31 days** runs the full simulation; slider + Day
  buttons scrub manually.
- **Scale:** full map width at the equator = Earth's circumference,
  **24,901 miles**, with a 0 / 2,500 / 5,000-mile scale bar on the map.
  Surface area derived from that circumference: ~197.4M sq mi.
  Coverage: Oct 1 = 6.2% (~12.2M sq mi) → Oct 31 = 71.4% (~140.9M sq mi).
- One dated event line per day (Planet Fusion's arrival, zone formations,
  district erasures).

Fully offline, no dependencies, no permissions. Signed with AeonAxi's
permanent keystore (`signing/`, cert SHA-256 8626e5ce…) so future updates
install over the top.

Fan-made, unofficial. FusionFall © Cartoon Network.

## State scarcity scores (v1.2.0)
Per-state **0–10 scarcity score** (0 = stable, 10 = collapse-tier), recomputed
for the selected day from seven factors, exactly as specified:
`Fusion_density` (share of the state's land inside Fusion Matter, sampled on a
grid over its real county geometry and calibrated to the planet-wide coverage
curve), `Corridor_access` (open / partial / collapsed — density + fusion-scar
crossings), `Refinery_status` (online / isolated / shutdown for real refining
states; other states inherit national fuel stress), `Import_dependency`
(dimensional-import reliance multiplier), `Population_pressure` (surviving-zone
population), `Agriculture_loss` (own farm loss + national ag collapse,
food-tier), `Fuel_distribution` (hazard-zone routing transport multiplier).
Points: 3.0 + 1.5 + 1.2 + 1.0 + 1.3 + 1.2 + 0.8 = 10. In-app: **⚖ Scarcity
list** (worst-first, tap a state for the full 7-factor breakdown) and
**Scarcity tint** (states coloured green→red by score on the map).
