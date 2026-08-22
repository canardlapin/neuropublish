# Neuropublish UI design

`concept-mockup-2026-08-22.png` is the directional concept for the scientific
workspace, captured before any code existed. It is a mood and layout
reference, not a specification. The proper design pass happens in the
"UI design cadence" of the [implementation plan](../implementation-plan.md),
using the `/design` skill to produce editable artboards for each surface.

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

## What the mockup shows that the plan does not adopt (yet)

| Mockup element | Status |
| --- | --- |
| ZIO, Tyrian CSS, Ory Kratos/Keto in the stack panel | Stack is fixed by ADR 0003 (Cats Effect/Laminar). Ory is a candidate for the Stage 4 identity-provider spike. |
| JSON-LD "NPMP" protocol | Protocol is JSON Schema 2020-12 per ADR 0001. A JSON-LD context/export may be added post-MVP for discoverability. |
| DOI citation, license, public Explore/Collections/Library | Post-MVP; depends on public projects and the multi-workspace enablement in ADR 0004. |
| "Subjects", "Files" navigation entries | Navigation is analysis → estimand → measure; files are representations, not a primary view. Subject-level results carry a sensitivity warning. |
| Fly.io / Lightsail hosting, 3–4 week schedule | Hosting is decided before Stage 6; the plan has gates, not dates. |
| "Add Layer" from arbitrary files | Layers come from the revision's result fields; no ad-hoc upload in the viewer. |
