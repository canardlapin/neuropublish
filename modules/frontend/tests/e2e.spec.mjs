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
  // the canvas has non-background pixels (something rendered)
  const lit = await page.locator("#volume canvas").evaluate((c) => {
    const ctx = c.getContext("2d"); const d = ctx.getImageData(0, 0, c.width, c.height).data;
    let n = 0; for (let i = 0; i < d.length; i += 4) if (d[i] + d[i + 1] + d[i + 2] > 60) n++;
    return n;
  });
  expect(lit).toBeGreaterThan(1000);
  // toggling a layer off and on does not error
  await page.locator('.layer[data-layer="speech-t"] input[type=checkbox]').click();
  await page.locator('.layer[data-layer="speech-t"] input[type=checkbox]').click();
  await expect(page.locator(".warning")).toContainText("AR(1)");
  await page.screenshot({ path: "test-results/stage1-revision.png", fullPage: true });
  expect(errors).toEqual([]);
});
