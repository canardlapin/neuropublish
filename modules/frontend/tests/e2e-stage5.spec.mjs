import { test, expect } from "@playwright/test";

// Stage 5 exit criteria ("Surface and hybrid display"), end to end, against the backend started by
// scripts/e2e.sh. Assumes the reference bundle carries left/right surfaces (ids
// `lh-pial-surface` / `rh-pial-surface`, on the assets `lh-pial` / `rh-pial` — the two spellings
// differ deliberately, so a check that confuses a surface id with its asset fails here)
// and surface representations of the `speech-t` / `speech-z` fields; `speech-effect` and
// `speech-se` stay volume-only (the honest empty state). Selectors are by stable test ids and
// `data-*` attributes, not by text, except for the link-state messages the product definition names.
const settle = (page) => page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
const ready = (page) => page.waitForSelector('#np-app[data-status="ready"]', { timeout: 60_000 });

async function login(page) {
  await page.goto("/login");
  await page.getByTestId("login-form").locator('input[type=email], input[name=email]').fill("owner@example.org");
  await page.getByTestId("login-form").locator('input[type=password]').fill("owner-dev-password");
  await page.getByTestId("login-form").locator('button[type=submit], button').first().click();
  await page.waitForURL(/\/w\//);
}
async function openWorkspace(page) {
  await page.goto("/w/rotman/p/sherlock");
  await ready(page);
  const href = await page.getByRole("link", { name: "Open current revision" }).getAttribute("href");
  await page.goto(href);
  await ready(page); await settle(page);
  return href;
}
const layerIds = (page) => page.locator(".layer-card").evaluateAll((els) => els.map((e) => e.dataset.layer));
const layerState = (page) =>
  page.locator(".layer-card").evaluateAll((els) =>
    els.map((e) => ({
      id: e.dataset.layer,
      visible: e.querySelector("input[type=checkbox]").checked,
      mode: e.querySelector("select").value,
      reps: e.querySelector('[data-testid="layer-representations"]').textContent,
    })));
const worldReadout = async (page) => {
  const t = await page.locator('[data-readout="world"] .mono').textContent();
  return t.split(",").map((s) => parseFloat(s));
};
const query = (page) => new URL(page.url()).searchParams;
// the page coalesces replaceState (250 ms); wait before reading the URL back
const urlSettled = (page) => page.waitForTimeout(400);

test("Volume → Surface → Hybrid keeps the result identity: same layer ids, order, and display state", async ({ page }) => {
  await login(page);
  await openWorkspace(page);
  const before = await layerState(page);
  const ids = await layerIds(page);
  expect(ids).toContain("speech-t");

  await page.getByTestId("preset-surface").click(); await settle(page);
  expect(await page.locator('.workspace-page').getAttribute("data-preset")).toBe("surface");
  expect(await page.locator('.pane[data-pane="surface"]').count()).toBe(1);
  expect(await page.locator('.pane[data-pane="volume"]').count()).toBe(0);
  expect(await layerState(page)).toEqual(before);
  await urlSettled(page);
  expect(query(page).get("p")).toBe("surface");

  await page.getByTestId("preset-hybrid").click(); await settle(page);
  expect(await page.locator('.pane[data-pane="surface"]').count()).toBe(1);
  expect(await page.locator('.pane[data-pane="volume"]').count()).toBe(1);
  expect(await page.locator('.pane[data-pane="volume"] canvas').count()).toBe(1);
  expect(await page.locator('.pane[data-pane="surface"] canvas').count()).toBe(1);
  expect(await layerState(page)).toEqual(before);
  expect(await layerIds(page)).toEqual(ids);

  // the surface field is drawn in both panes; a volume-only field says so on its card
  const t = before.find((l) => l.id === "speech-t");
  expect(t.reps).toMatch(/volume/);
  expect(t.reps).toMatch(/left surface/);
  expect(t.reps).toMatch(/right surface/);
  const effect = before.find((l) => l.id === "speech-effect");
  expect(effect.reps).toMatch(/volume/);
  expect(effect.reps).not.toMatch(/surface/);
  await page.screenshot({ path: "test-results/stage5-hybrid.png", fullPage: true });
});

test("wide workspace is a bounded scientific instrument with internal navigation and inspector scroll", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await login(page);
  await openWorkspace(page);
  await page.getByTestId("preset-hybrid").click(); await settle(page);

  const geometry = await page.evaluate(() => {
    const page = document.querySelector(".workspace-page");
    const centre = document.querySelector(".centre");
    const inspector = document.querySelector(".inspector");
    const navigator = document.querySelector(".navigator");
    return {
      viewport: window.innerHeight,
      document: document.documentElement.scrollHeight,
      page: page.getBoundingClientRect().height,
      centre: centre.getBoundingClientRect().height,
      inspectorClient: inspector.clientHeight,
      inspectorScroll: inspector.scrollHeight,
      navigatorClient: navigator.clientHeight,
    };
  });
  expect(geometry.page).toBeCloseTo(geometry.viewport, 0);
  expect(geometry.document).toBeLessThanOrEqual(geometry.viewport + 1);
  expect(geometry.centre).toBeGreaterThan(480);
  expect(geometry.inspectorScroll).toBeGreaterThan(geometry.inspectorClient);
  expect(geometry.navigatorClient).toBeCloseTo(geometry.centre, 0);
  await page.screenshot({ path: "test-results/stage5-artboard-wide.png" });
});

test("linked cursor: a surface pick moves the volume cursor to the vertex; a volume pick reports the link distance", async ({ page }) => {
  await login(page);
  await openWorkspace(page);
  await page.getByTestId("preset-hybrid").click(); await settle(page);
  const surface = page.locator('.pane[data-pane="surface"] .surface-canvas');
  const box = await surface.boundingBox();

  // scan a few points of the surface pane until one hits a mesh (the bilateral layout centres each
  // hemisphere in its half, so the quarter points are on the mesh for any sane geometry)
  let picked = false;
  for (const [fx, fy] of [[0.25, 0.5], [0.75, 0.5], [0.3, 0.45], [0.7, 0.55], [0.25, 0.4], [0.75, 0.6]]) {
    await surface.click({ position: { x: box.width * fx, y: box.height * fy } });
    await settle(page);
    if (await page.getByTestId("volume-link").count()) { picked = true; break; }
  }
  expect(picked).toBe(true);
  // the status bar names the vertex and the volume pane says the cursor came from it
  await expect(page.getByTestId("volume-link")).toContainText(/vertex \d+/);
  const world = await worldReadout(page);
  expect(world.length).toBe(3);
  // the picked vertex lies on one of the two icospheres (centres x = ∓30 mm, r = 25 mm)
  const r = Math.min(
    Math.hypot(world[0] + 30, world[1], world[2]),
    Math.hypot(world[0] - 30, world[1], world[2]));
  expect(Math.abs(r - 25)).toBeLessThan(0.6);
  // the URL cursor is the vertex's world position: the round trip is exact to the readout precision
  await urlSettled(page);
  const c = query(page).get("c").split(",").map(parseFloat);
  // (the status bar prints 2 decimals below 100 and none above; the URL keeps 4)
  for (let i = 0; i < 3; i++) expect(Math.abs(c[i] - world[i])).toBeLessThan(Math.abs(c[i]) >= 100 ? 0.51 : 0.006);
  // surface vertex values are read out per hemisphere beside the volume voxel value
  await expect(page.locator('.readout-layer[data-readout="speech-t"][data-pane="surface"]')).toHaveCount(1);
  // (the volume voxel readout is only present where the vertex falls inside the volume grid; the
  // synthetic icospheres — r = 25 mm at x = ∓30 — mostly lie outside the 32 × 32 × 24 mm fixture
  // volume, so only the world coordinate is asserted on the volume side)
  await expect(page.locator('.readout-layer[data-readout="world"]')).toHaveCount(1);

  // now a pick in the volume pane: the surface pane reports the link explicitly
  const volume = page.locator('.pane[data-pane="volume"]');
  const vb = await volume.boundingBox();
  await volume.click({ position: { x: vb.width * 0.5, y: vb.height * 0.5 } });
  await settle(page);
  const link = page.getByTestId("surface-link");
  await expect(link).toHaveCount(1);
  await expect(link).toContainText(/linked to vertex \d+, \d+\.\d mm|no vertex within 3 mm/);
  await expect(page.locator('[data-readout="link"]')).toContainText(/linked to vertex|no vertex within/);
  await page.screenshot({ path: "test-results/stage5-linked-cursor.png", fullPage: true });
});

// Julia oracle (modules/conformance/fixtures/julia/oracle.json): probe vertices of the synthetic
// icosphere surfaces (642 vertices, hemispheres at x ∓ 30 mm, r = 25 mm) and the vertex-field values.
const ORACLE = {
  lh: { 321: { world: [-36.60206985473633, 7.531472206115723, 22.906105041503906], t: 4.581221103668213, z: 2.7569239139556885 } },
  rh: { 641: { world: [52.824562072753906, 9.9901762008667, 2.0580894947052], t: 0.4116179049015045, z: 3.2978386878967285 } },
};
const readoutValue = (page, id, pane) =>
  page.locator(`.readout-layer[data-readout="${id}"][data-pane="${pane}"] .mono`).textContent().then(parseFloat);

test("a URL cursor at an oracle vertex links at 0 mm and reads the oracle's vertex values; 5 mm off the sphere does not link", async ({ page }) => {
  await login(page);
  const href = await openWorkspace(page);
  const lh = ORACLE.lh[321];
  await page.goto(`${href}?p=hybrid&c=${lh.world.map((v) => v.toFixed(4)).join(",")}`);
  await ready(page); await settle(page);
  await expect(page.getByTestId("surface-link")).toContainText(/linked to vertex 321, 0\.0 mm/);
  await expect(page.locator('[data-readout="link"]')).toContainText(/linked to vertex 321/);
  expect(await readoutValue(page, "speech-t", "surface")).toBeCloseTo(lh.t, 2);
  expect(await readoutValue(page, "speech-z", "surface")).toBeCloseTo(lh.z, 2);
  // right hemisphere vertex 641, by the same route (Surface preset this time)
  const rh = ORACLE.rh[641];
  await page.goto(`${href}?p=surface&c=${rh.world.map((v) => v.toFixed(4)).join(",")}`);
  await ready(page); await settle(page);
  await expect(page.locator('[data-readout="link"]')).toContainText(/linked to vertex 641, 0\.0 mm/);
  expect(await readoutValue(page, "speech-t", "surface")).toBeCloseTo(rh.t, 2);
  expect(await readoutValue(page, "speech-z", "surface")).toBeCloseTo(rh.z, 2);
  // the origin is 5 mm from the nearest sphere (centres x = ∓30, r = 25): outside the 3 mm radius
  await page.goto(`${href}?p=hybrid&c=0,0,0`);
  await ready(page); await settle(page);
  await expect(page.getByTestId("surface-link")).toContainText(/no vertex within 3 mm/);
  await expect(page.locator('.readout-layer[data-readout="speech-t"][data-pane="surface"]')).toHaveCount(0);
  await page.screenshot({ path: "test-results/stage5-oracle-link.png", fullPage: true });
});

test("a visible field without a surface representation is an honest empty state, never a projection", async ({ page }) => {
  await login(page);
  await openWorkspace(page);
  // show the effect layer (volume only) and hide the surface-backed ones
  await page.locator('.layer-card[data-layer="speech-effect"] input[type=checkbox]').check();
  await page.locator('.layer-card[data-layer="speech-t"] input[type=checkbox]').uncheck();
  await page.locator('.layer-card[data-layer="speech-z"] input[type=checkbox]').uncheck();
  await page.getByTestId("preset-surface").click(); await settle(page);
  const empty = page.getByTestId("surface-empty");
  await expect(empty.locator('[data-field="speech-effect"]')).toHaveCount(1);
  await expect(empty.locator('[data-field="speech-effect"]')).toContainText(/has no surface representation/);
  await expect(empty.locator('[data-field="speech-t"]')).toHaveCount(0);
  // the geometry is still there: the pane keeps a live canvas (surfaces are drawn bare, not projected)
  expect(await page.locator('.pane[data-pane="surface"] canvas').count()).toBe(1);
  // showing the t layer again removes the notice
  await page.locator('.layer-card[data-layer="speech-effect"] input[type=checkbox]').uncheck();
  await page.locator('.layer-card[data-layer="speech-t"] input[type=checkbox]').check();
  await settle(page);
  await expect(page.getByTestId("surface-empty")).toHaveCount(0);
  await page.screenshot({ path: "test-results/stage5-surface-empty.png", fullPage: true });
});

test("layout proportions and the surface camera survive a URL and a saved-view round trip", async ({ page }) => {
  await login(page);
  const href = await openWorkspace(page);
  await page.getByTestId("preset-hybrid").click();
  const splitter = page.getByTestId("hybrid-splitter");
  await splitter.focus();
  for (let i = 0; i < 5; i++) await page.keyboard.press("ArrowLeft");
  await page.getByTestId("surface-viewpoint").selectOption("dorsal");
  await page.getByTestId("surface-projection").selectOption("orthographic");
  await page.waitForTimeout(400); // replaceState is coalesced
  const q = query(page);
  expect(q.get("p")).toBe("hybrid");
  expect(q.get("sc")).toBe("dorsal,orthographic");
  expect(parseFloat(q.get("sf"))).toBeCloseTo(0.4, 2);
  const nowBefore = await splitter.getAttribute("aria-valuenow");

  // URL round trip
  await page.goto(page.url()); await ready(page); await settle(page);
  expect(await page.locator('.workspace-page').getAttribute("data-preset")).toBe("hybrid");
  expect(await page.getByTestId("hybrid-splitter").getAttribute("aria-valuenow")).toBe(nowBefore);
  expect(await page.getByTestId("surface-viewpoint").inputValue()).toBe("dorsal");
  expect(await page.getByTestId("surface-projection").inputValue()).toBe("orthographic");

  // saved-view round trip
  await page.getByTestId("save-view").click();
  await page.getByTestId("view-name").fill("Dorsal hybrid");
  await page.getByTestId("view-name").press("Enter");
  await page.waitForURL(/\/v\//); await ready(page); await settle(page);
  expect(await page.locator('.workspace-page').getAttribute("data-preset")).toBe("hybrid");
  expect(await page.getByTestId("hybrid-splitter").getAttribute("aria-valuenow")).toBe(nowBefore);
  expect(await page.getByTestId("surface-viewpoint").inputValue()).toBe("dorsal");

  // presentation route shows the same preset and camera, read-only
  await page.getByTestId("share-view").click();
  await page.getByTestId("create-link").click();
  const link = await page.getByTestId("share-link").inputValue();
  await page.context().clearCookies();
  await page.goto(link); await ready(page); await settle(page);
  await expect(page.getByTestId("readonly-bar")).toBeVisible();
  expect(await page.locator('.workspace-page').getAttribute("data-preset")).toBe("hybrid");
  expect(await page.getByTestId("surface-viewpoint").inputValue()).toBe("dorsal");
  expect(await page.locator('.pane[data-pane="surface"] canvas').count()).toBe(1);
  expect(await page.locator('.pane[data-pane="volume"] canvas').count()).toBe(1);
  // the other two presets also work on the presentation route
  await page.getByTestId("preset-surface").click(); await settle(page);
  expect(await page.locator('.pane[data-pane="volume"]').count()).toBe(0);
  await page.getByTestId("preset-volume").click(); await settle(page);
  expect(await page.locator('.pane[data-pane="surface"]').count()).toBe(0);
  expect(await page.locator('.pane[data-pane="volume"] canvas').count()).toBe(1);
  await page.screenshot({ path: "test-results/stage5-presentation.png", fullPage: true });
  void href;
});

test("panes are disposed on preset switches and navigation: no leaked canvas, no lost context on the survivor", async ({ page }) => {
  await login(page);
  await openWorkspace(page);
  for (let i = 0; i < 6; i++) {
    await page.getByTestId("preset-surface").click(); await settle(page);
    await page.getByTestId("preset-hybrid").click(); await settle(page);
    await page.getByTestId("preset-volume").click(); await settle(page);
  }
  expect(await page.locator("canvas").count()).toBe(1);
  await page.getByTestId("preset-hybrid").click(); await settle(page);
  expect(await page.locator("canvas").count()).toBe(2);
  // a WebGL context that survived the cycles is still live
  const lost = await page.locator('.pane[data-pane="surface"] canvas').evaluate((c) => {
    const gl = c.getContext("webgl2") || c.getContext("webgl");
    return gl ? gl.isContextLost() : "no-context";
  });
  expect(lost).toBe(false);
  // navigating away unmounts everything
  await page.goto("/w/rotman/p/sherlock"); await ready(page);
  expect(await page.locator("canvas").count()).toBe(0);
});

test("narrow layout: Hybrid stacks the panes and the divider still works by keyboard", async ({ page }) => {
  await page.setViewportSize({ width: 820, height: 1100 });
  await login(page);
  await openWorkspace(page);
  await page.getByTestId("preset-hybrid").click(); await settle(page);
  const v = await page.locator('.pane[data-pane="volume"]').boundingBox();
  const s = await page.locator('.pane[data-pane="surface"]').boundingBox();
  expect(s.y).toBeGreaterThan(v.y + v.height - 1); // stacked, not side by side
  const splitter = page.getByTestId("hybrid-splitter");
  await splitter.focus();
  const before = await splitter.getAttribute("aria-valuenow");
  await page.keyboard.press("ArrowRight");
  expect(await splitter.getAttribute("aria-valuenow")).not.toBe(before);
  await page.screenshot({ path: "test-results/stage5-narrow-hybrid.png", fullPage: true });
  await page.setViewportSize({ width: 640, height: 1100 });
  await page.getByTestId("preset-surface").click(); await settle(page);
  await page.screenshot({ path: "test-results/stage5-narrow-surface.png", fullPage: true });
});

// ---------------------------------------------------------------------------
// Review fixes (2026-08-23)
// ---------------------------------------------------------------------------

/** Distance from one synthetic hemisphere's centre: the icospheres are r = 25 mm at x = ∓30 mm. */
const radiusFrom = (world, centreX) => Math.hypot(world[0] - centreX, world[1], world[2]);

test("a pick in the right half of the surface pane lands on the right hemisphere and moves the volume cursor", async ({ page }) => {
  await login(page);
  await openWorkspace(page);
  await page.getByTestId("preset-hybrid").click(); await settle(page);
  const surface = page.locator('.pane[data-pane="surface"] .surface-canvas');
  const box = await surface.boundingBox();

  // scan the right half only: the bilateral layout puts the right hemisphere in the right slot
  let world = null;
  for (const [fx, fy] of [[0.75, 0.5], [0.8, 0.45], [0.7, 0.55], [0.78, 0.6]]) {
    await surface.click({ position: { x: box.width * fx, y: box.height * fy } });
    await settle(page);
    if (await page.getByTestId("volume-link").count()) { world = await worldReadout(page); break; }
  }
  expect(world).not.toBeNull();
  // hand-derived, not read back from the oracle: the vertex is on the +30 mm sphere of radius 25
  expect(Math.abs(radiusFrom(world, 30) - 25)).toBeLessThan(0.6);
  expect(radiusFrom(world, -30)).toBeGreaterThan(25.6);
  // the volume pane took the cursor from the surface pick, and the status bar names the vertex
  await expect(page.getByTestId("volume-link")).toContainText(/vertex \d+/);
  await expect(page.locator('.readout-layer[data-readout="speech-t"][data-pane="surface"]')).toHaveCount(1);
  // the shared cursor is in the URL at the picked world position
  await urlSettled(page);
  const c = query(page).get("c").split(",").map(parseFloat);
  expect(Math.abs(radiusFrom(c, 30) - 25)).toBeLessThan(0.6);
  await page.screenshot({ path: "test-results/stage5-right-hemisphere-pick.png", fullPage: true });
});

test("a threshold above every value hides the surface overlay; resetting the layer brings it back", async ({ page }) => {
  await login(page);
  await openWorkspace(page);
  // one visible surface-backed layer, so the pane's pixels answer for that layer alone
  await page.locator('.layer-card[data-layer="speech-z"] input[type=checkbox]').uncheck();
  await page.getByTestId("preset-surface").click(); await settle(page);
  const canvas = page.locator('.pane[data-pane="surface"] canvas');
  const card = page.locator('.layer-card[data-layer="speech-t"]');
  await settle(page); await settle(page);
  const drawn = await canvas.screenshot();

  // |t| < 99 everywhere on the synthetic fields, so a two-sided 99 threshold hides all of it
  await card.locator("select").first().selectOption("two-sided");
  const min = card.locator('label.field', { hasText: "minimum |value|" }).locator("input");
  await min.fill("99");
  await min.press("Enter");
  await settle(page); await settle(page);
  const hidden = await canvas.screenshot();
  expect(hidden.equals(drawn)).toBe(false);
  // the geometry is still drawn: the pane is not blank, it is unpainted by this layer
  expect(await page.locator('.pane[data-pane="surface"] canvas').count()).toBe(1);
  await expect(page.getByTestId("surface-error")).toHaveCount(0);

  // "modified · reset" returns the published display and the values reappear, pixel for pixel
  await card.locator("button.reset").click();
  await settle(page); await settle(page);
  const restored = await canvas.screenshot();
  expect(restored.equals(drawn)).toBe(true);
  await page.screenshot({ path: "test-results/stage5-threshold-hides-surface.png", fullPage: true });
});

test("the layer card states per-hemisphere absence and whether a projection receipt was declared", async ({ page }) => {
  await login(page);
  await openWorkspace(page);
  // the reference bundle publishes speech-t on both hemispheres, with a declared derivation
  const t = page.locator('.layer-card[data-layer="speech-t"]');
  await expect(t.getByTestId("layer-representations")).toContainText("left surface");
  await expect(t.getByTestId("layer-representations")).toContainText("right surface");
  await expect(t.getByTestId("layer-representations")).not.toContainText("only");
  await expect(t.getByTestId("layer-derivation")).toContainText("project-to-surface");
  // a volume-only field claims no surface at all, and carries no surface-derivation line
  const effect = page.locator('.layer-card[data-layer="speech-effect"]');
  await expect(effect.getByTestId("layer-representations")).not.toContainText("surface");
  await expect(effect.getByTestId("layer-derivation")).toHaveCount(0);
});
