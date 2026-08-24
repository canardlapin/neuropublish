# Neuropublish UI design

`concept-mockup-2026-08-22.png` is the directional concept for the scientific
workspace, captured before any code existed. It is a mood and layout
reference, not a specification. The proper design pass happens in the
"UI design cadence" of the [implementation plan](../implementation-plan.md),
using editable artboards for each surface.

## What the mockup establishes (adopted)

- dark, dense scientific-application feel; the product reads as an app, not
  a file browser;
- left navigation by scientific identity (summary, analyses, provenance),
  centre viewer with volume triplanar above bilateral surfaces, right-hand
  layer inspector and dataset/provenance facts;
- per-layer card: name, measure and source, opacity, colormap, scale
  (display window), threshold, visibility — with scale and threshold shown as
  separate controls;
- a shared colour bar with threshold band marked;
- a vertical provenance chain (raw inputs → first-level → group → publication)
  beside the result;
- a "cite / export" affordance on the project.

## Workspace artboards (Stage 5 design pass complete)

The editable design source is [artboards/canvas.json](artboards/canvas.json),
with a directly inspectable HTML rendering in
[artboards/Main.dc.html](artboards/Main.dc.html). It contains the 1440 px Hybrid
workspace, the resolved design questions, component states, and a narrow
stacked layout. These are static design artifacts; the browser implementation
must still be checked against them with real result data.

The Stage 5 workspace reuses the Volume artboard's regions and tokens
(`modules/frontend/np.css`) for the Surface and Hybrid presets; no new colours
were introduced. The design pass settled these points:

- the centre is a two-column CSS grid — volume pane · 8 px divider · surface
  pane — whose proportion is `splitFraction` (volume share); the divider is a
  `role=separator` that drags and takes arrow keys, and it turns horizontal
  (panes stacked) below 900 px;
- the surface pane carries a thin toolbar (view-from · projection) above the
  canvas, matching the inspector's field styling;
- per-pane badges (bottom-left, mono) carry the link state across panes:
  "linked to vertex 1234, 0.8 mm", "no vertex within 3 mm", "cursor from
  surface vertex 1234" — the badge is a readout, not a control;
- the honest empty state ("speech · t has no surface representation") is a
  dashed card inside the pane, never a blank canvas and never a projection;
- the status bar gains per-pane readouts: volume voxel values, surface vertex
  values marked ▲ per hemisphere, and the link distance in the cursor colour;
- the preset switch is a segmented control in the top bar (Volume · Surface ·
  Hybrid), present on the presentation route too since it changes only what
  the viewer sees locally.

- the pane-local link badge stays close to the canvas while the status bar
  carries the exact coordinate and value readout;
- the result tree uses compact representation glyphs, while the expanded layer
  card names its volume/left/right realizations in text;
- one colour bar represents the shared scientific field across linked panes.

The next design task is implementation fidelity: replace synthetic viewer
stand-ins with the conformance result's volume and cortical assets, then run
wide and narrow browser screenshots against these artboards.

## What the mockup shows that the plan does not adopt (yet)

| Mockup element | Status |
| --- | --- |
| ZIO, Tyrian CSS, Ory Kratos/Keto in the stack panel | Stack is fixed by ADR 0003 (Cats Effect/Laminar). Ory is a candidate for the Stage 4 identity-provider spike. |
| JSON-LD "NPMP" protocol | Protocol is JSON Schema 2020-12 per ADR 0001. A JSON-LD context/export may be added post-MVP for discoverability. |
| DOI citation, license, public Explore/Collections/Library | Post-MVP; depends on public projects and the multi-workspace enablement in ADR 0004. |
| "Subjects", "Files" navigation entries | Navigation is analysis → estimand → measure; files are representations, not a primary view. Subject-level results carry a sensitivity warning. |
| Fly.io / Lightsail hosting, 3–4 week schedule | Hosting is decided before Stage 6; the plan has gates, not dates. |
| "Add Layer" from arbitrary files | Layers come from the revision's result fields; no ad-hoc upload in the viewer. |
