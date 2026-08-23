import { test, expect } from "@playwright/test";

// Stage 3 exit criteria, driven against the live backend started by scripts/e2e.sh.
const settle = (page) => page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
const ready = (page) => page.waitForSelector('#np-app[data-status="ready"]', { timeout: 60_000 });
const readout = (page, id) => page.locator(`[data-readout="${id}"] .mono`).textContent();
const colourPixels = (page) => page.locator("#volume canvas").evaluate((c) => {
  const d = c.getContext("2d").getImageData(0, 0, c.width, c.height).data; let n = 0;
  for (let i = 0; i < d.length; i += 4) { const r = d[i], g = d[i + 1], b = d[i + 2]; if (Math.max(r, g, b) - Math.min(r, g, b) > 40) n++; }
  return n;
});

async function login(page) {
  await page.goto("/login");
  const form = page.getByTestId("login-form");
  await form.locator("input[type=email], input[name=email]").fill("owner@example.org");
  await form.locator("input[type=password]").fill("owner-dev-password");
  await form.locator("button[type=submit], button").first().click();
  await page.waitForURL(/\/w\//);
}
test.beforeEach(async ({ page }) => {
  page.on("pageerror", (e) => { throw e; });
  await login(page); // projects are private from Stage 4 on
});

test("project overview leads to the current revision's workspace", async ({ page }) => {
  await page.goto("/w/rotman/p/sherlock");
  await ready(page);
  await expect(page.locator("h1")).toContainText("Sherlock");
  await expect(page.locator(".facts")).toContainText("n = 26");
  await expect(page.locator(".callout.warn")).toContainText("AR(1)");
  await page.screenshot({ path: "test-results/stage3-overview.png", fullPage: true });
  await page.getByRole("link", { name: "Open current revision" }).click();
  await ready(page);
  await expect(page.locator("#volume canvas")).toHaveCount(1);
  await expect(page.locator(".tree-row.measure")).toHaveCount(4);
  await expect(page.locator('.tree-row.measure[aria-pressed="true"]')).toHaveCount(2); // t and z per recommendation
  await settle(page);
  expect(await colourPixels(page)).toBeGreaterThan(5000);
  await page.screenshot({ path: "test-results/stage3-workspace.png", fullPage: true });
});

test("readouts at a URL-set cursor match the oracle", async ({ page }) => {
  await page.goto("/w/rotman/p/sherlock");
  await ready(page);
  const href = await page.getByRole("link", { name: "Open current revision" }).getAttribute("href");
  await page.goto(`${href}?c=0,-4,0`); // oracle probe: voxel (11,13,9) → t1 99.375, t -0.5, z 0
  await ready(page);
  await settle(page); await settle(page);
  expect(await readout(page, "world")).toBe("0.00, -4.00, 0.00");
  expect(await readout(page, "speech-t")).toBe("-0.50");
  expect(await readout(page, "speech-z")).toBe("0.00");
  expect(await readout(page, "t1")).toBe("99.38");
  // a second probe through the same path
  await page.goto(`${href}?c=-22,-30,-18`);
  await ready(page); await settle(page); await settle(page);
  expect(await readout(page, "speech-t")).toBe("0.00");
  expect(await readout(page, "t1")).toBe("0.00");
});

test("threshold and window are independent, reorder and presentation survive a URL round trip, reset restores", async ({ page }) => {
  await page.goto("/w/rotman/p/sherlock");
  await ready(page);
  const href = await page.getByRole("link", { name: "Open current revision" }).getAttribute("href");
  await page.goto(href);
  await ready(page); await settle(page);
  const card = page.locator('.layer-card[data-layer="speech-t"]');
  const thr = card.getByLabel("minimum |value|");
  const wmin = card.getByLabel("minimum", { exact: true });
  const wmax = card.getByLabel("maximum", { exact: true });
  await expect(thr).toHaveValue("3.10");
  await expect(wmin).toHaveValue("-8.00");
  await expect(wmax).toHaveValue("8.00");
  await expect(card.locator(".pill.accent")).toHaveText("published");

  // type like a person: select all, key the digits, commit with Enter
  await thr.click(); await thr.selectText(); await page.keyboard.type("4.5"); await page.keyboard.press("Enter");
  await expect(thr).toHaveValue("4.50");
  await expect(wmin).toHaveValue("-8.00"); // window untouched by threshold
  await wmax.click(); await wmax.selectText(); await page.keyboard.type("10"); await page.keyboard.press("Tab");
  await expect(wmax).toHaveValue("10.00");
  await expect(thr).toHaveValue("4.50"); // threshold untouched by window
  // an invalid window (min ≥ max) is refused and flagged, not silently applied
  await wmin.click(); await wmin.selectText(); await page.keyboard.type("50"); await page.keyboard.press("Enter");
  await expect(wmin).toHaveAttribute("aria-invalid", "true");
  await expect(wmax).toHaveValue("10.00");
  await expect(card.locator(".pill.warn")).toContainText("modified");
  await page.locator('.layer-card[data-layer="speech-z"] [aria-label="move layer up"]').click();
  await settle(page); await page.waitForTimeout(400); // URL sync is debounced
  const url = page.url();
  expect(url).toContain("ts4.5"); expect(url).toContain("-8,10");
  expect(url.indexOf("speech-z:")).toBeLessThan(url.indexOf("speech-t:")); // z now drawn below t (moved up the list)

  // one-sided thresholds render (Intaglio Below/Above): "positive" shows a strict subset of two-sided.
  // Measure the t layer alone: z sits underneath and would show through wherever t is hidden.
  await page.locator('.layer-card[data-layer="speech-z"] input[type=checkbox]').click();
  await card.locator(".pill.warn").click(); // back to published two-sided 3.1
  await settle(page); await settle(page);
  const twoSided = await colourPixels(page);
  await card.locator("select").first().selectOption("positive");
  await settle(page); await settle(page);
  const positive = await colourPixels(page);
  await card.locator("select").first().selectOption("negative");
  await settle(page); await settle(page);
  const negative = await colourPixels(page);
  expect(positive).toBeLessThan(twoSided);
  expect(negative).toBeLessThan(twoSided);
  expect(positive).toBeGreaterThan(0);
  await expect(card.locator(".pill.warn")).toContainText("modified");
  await expect(page.locator(".layer-card .pill.warn", { hasText: "cannot be rendered" })).toHaveCount(0);
  await card.locator("select").first().selectOption("two-sided");
  await page.locator('.layer-card[data-layer="speech-z"] input[type=checkbox]').click(); // z back on
  await settle(page);
  // re-apply the modified threshold/window so the round trip below carries non-published values
  await thr.click(); await thr.selectText(); await page.keyboard.type("4.5"); await page.keyboard.press("Enter");
  await wmax.click(); await wmax.selectText(); await page.keyboard.type("10"); await page.keyboard.press("Tab");
  await expect(thr).toHaveValue("4.50"); await expect(wmax).toHaveValue("10.00");

  // colormap change must reach the canvas: gray overlays carry no chroma
  await settle(page);
  const colourBefore = await colourPixels(page);
  await page.locator('.layer-card[data-layer="speech-t"] select').nth(1).selectOption("gray");
  await page.locator('.layer-card[data-layer="speech-z"] select').nth(1).selectOption("gray");
  await settle(page); await settle(page);
  expect(await colourPixels(page)).toBeLessThan(colourBefore / 4);

  await page.waitForTimeout(400); // URL sync is debounced
  const url2 = page.url();
  expect(url2).toContain("gray");
  await page.goto(url2); // round trip, including colormap and order, must render from the first frame
  await ready(page); await settle(page); await settle(page);
  expect(await colourPixels(page)).toBeLessThan(colourBefore / 4);
  const order = await page.locator(".layer-card").evaluateAll((els) => els.map((e) => e.dataset.layer));
  expect(order.indexOf("speech-z")).toBeLessThan(order.indexOf("speech-t"));
  await expect(page.locator('.layer-card[data-layer="speech-t"]').getByLabel("minimum |value|")).toHaveValue("4.50");
  await expect(page.locator('.layer-card[data-layer="speech-t"]').getByLabel("maximum", { exact: true })).toHaveValue("10.00");

  await page.locator('.layer-card[data-layer="speech-t"] .pill.warn').click();
  await expect(page.locator('.layer-card[data-layer="speech-t"]').getByLabel("minimum |value|")).toHaveValue("3.10");
  await page.getByRole("button", { name: "Reset view" }).click();
  await expect(page.locator(".pill.warn")).toHaveCount(0);
  // inspector tabs
  await page.getByRole("tab", { name: "Provenance" }).click();
  await expect(page.locator(".prov-node.unknown")).toHaveCount(1); // the retained unknown operation
  await page.getByRole("tab", { name: "Analysis" }).click();
  await expect(page.locator(".facts-panel")).toContainText("n = 26");
});

test("keyboard users can reach and toggle a measure; narrow layout and error states render", async ({ page }) => {
  await page.goto("/w/rotman/p/sherlock");
  await ready(page);
  const href = await page.getByRole("link", { name: "Open current revision" }).getAttribute("href");
  await page.goto(href);
  await ready(page); await settle(page);
  const effect = page.locator('.tree-row.measure[data-field="speech-effect"]');
  await effect.focus();
  await expect(effect).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(effect).toHaveAttribute("aria-pressed", "true");
  await page.keyboard.press("Tab");
  await expect(page.locator(':focus')).toHaveCount(1);

  await page.setViewportSize({ width: 900, height: 900 });
  await settle(page);
  await expect(page.locator("#volume canvas")).toHaveCount(1);
  await page.screenshot({ path: "test-results/stage3-narrow.png", fullPage: true });
  await page.setViewportSize({ width: 1280, height: 900 });

  // navigating away disposes cleanly (pageerror would throw)
  await page.locator(".topbar a.crumb").click();
  await ready(page);
  await expect(page.locator("#volume canvas")).toHaveCount(0);

  await page.goto("/w/rotman/p/nope");
  await page.waitForSelector(".error");
  await expect(page.locator(".error")).toContainText("does not exist");
  await page.screenshot({ path: "test-results/stage3-error.png" });
});
