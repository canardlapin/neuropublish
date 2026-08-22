import { test, expect } from "@playwright/test";

// Stage 1 exit: after `npub push`, the project's latest revision shows an underlay
// plus two visible overlays in the browser. The backend and push are started by
// scripts/e2e.sh; this spec only drives the browser.
test("latest revision renders underlay + two overlays with working layer controls", async ({ page }) => {
  const errors = [];
  page.on("pageerror", (e) => errors.push(String(e)));
  await page.goto("/w/rotman/p/sherlock");
  await page.waitForSelector('#np-app[data-status="ready"]', { timeout: 60_000 });
  await expect(page.locator("h1")).toContainText("Sherlock");
  await expect(page.locator("#volume canvas")).toHaveCount(1);
  const layers = page.locator(".layer");
  await expect(layers).toHaveCount(4);
  const checked = await page.locator(".layer input[type=checkbox]").evaluateAll((els) => els.map((e) => e.checked));
  expect(checked.filter(Boolean).length).toBe(2); // t and z visible, effect and se hidden
  // overlays paint in colour (cold–hot ramp); the grayscale underlay alone has none.
  const colourPixels = () => page.locator("#volume canvas").evaluate((c) => {
    const d = c.getContext("2d").getImageData(0, 0, c.width, c.height).data;
    let n = 0;
    for (let i = 0; i < d.length; i += 4) { const r = d[i], g = d[i + 1], b = d[i + 2]; if (Math.max(r, g, b) - Math.min(r, g, b) > 40) n++; }
    return n;
  });
  const settle = () => page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
  await settle();
  const withOverlays = await colourPixels();
  expect(withOverlays).toBeGreaterThan(200);
  await page.locator('.layer[data-layer="speech-t"] input[type=checkbox]').click();
  await page.locator('.layer[data-layer="speech-z"] input[type=checkbox]').click();
  await settle();
  const withoutOverlays = await colourPixels();
  // residual colour is the crosshair and orientation labels
  expect(withoutOverlays, `with=${withOverlays} without=${withoutOverlays}`).toBeLessThan(withOverlays / 4);
  expect(withOverlays - withoutOverlays).toBeGreaterThan(5000);
  await page.locator('.layer[data-layer="speech-t"] input[type=checkbox]').click();
  await settle();
  expect(await colourPixels()).toBeGreaterThan(withoutOverlays);
  await expect(page.locator(".warning")).toContainText("AR(1)");
  await page.screenshot({ path: "test-results/stage1-revision.png", fullPage: true });
  expect(errors).toEqual([]);
});
