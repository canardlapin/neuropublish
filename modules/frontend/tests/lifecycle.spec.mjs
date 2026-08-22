import { test, expect } from "@playwright/test";

const CYCLES = 24; // beyond Chrome's ~16 live WebGL contexts: a leaked context shows up as a lost one

async function ready(page) {
  await page.goto("/spike.html");
  await page.waitForFunction(() => window.__spikeReady === true);
}
const report = (page) => page.evaluate(() => window.__spike.report());
const settle = (page) => page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));

test("volume pane: mount, resize, unmount releases everything exactly once", async ({ page }) => {
  await ready(page);
  await page.evaluate(() => window.__spike.mountVolume("vol"));
  await settle(page);
  let r = await report(page);
  expect(r.volume.created).toBe(1);
  expect(r.volume.resizes).toBeGreaterThanOrEqual(1); // ResizeObserver fired on mount
  expect(r.volume.frames).toBeGreaterThanOrEqual(1);
  expect(r.volume.errors).toEqual([]);

  await page.evaluate(() => window.__spike.resize("vol", 480, 300));
  await settle(page);
  r = await report(page);
  expect(r.volume.resizes).toBeGreaterThanOrEqual(2);
  const canvas = page.locator("#vol canvas");
  expect(await canvas.evaluate((c) => [c.width, c.height])).toEqual([480, 300]);

  await page.evaluate(() => window.__spike.unmount("vol"));
  await settle(page);
  r = await report(page);
  expect(r.volume.disposed).toBe(1);
  expect(r.volume.rafOutstanding).toBe(0);
  expect(r.handlesDisposed).toBe(r.handles);
  expect(await page.locator("#vol canvas").count()).toBe(0);
});

test("surface pane: 24 mount/unmount cycles leak no WebGL context or listeners", async ({ page }) => {
  await ready(page);
  const before = (await report(page)).listeners;
  for (let i = 0; i < CYCLES; i++) {
    await page.evaluate(() => window.__spike.mountSurface("surf"));
    await settle(page);
    await page.evaluate(() => window.__spike.unmount("surf"));
  }
  await settle(page);
  await page.evaluate(() => window.__spike.mountSurface("surf"));
  await settle(page);
  const r = await report(page);
  expect(r.surface.created).toBe(CYCLES + 1);
  expect(r.surface.disposed).toBe(CYCLES);
  expect(r.surface.errors).toEqual([]);
  expect(r.surface.lastContextState).toBe("Available");
  expect(r.listeners.add - r.listeners.remove).toBe(before.add - before.remove);
  await page.evaluate(() => window.__spike.unmount("surf"));
  await settle(page);
  const f = await report(page);
  expect(f.surface.disposed).toBe(CYCLES + 1);
  expect(f.handlesDisposed).toBe(f.handles);
});

test("volume pane: 24 mount/unmount cycles leave no outstanding frames", async ({ page }) => {
  await ready(page);
  for (let i = 0; i < CYCLES; i++) {
    await page.evaluate(() => window.__spike.mountVolume("vol"));
    await page.evaluate(() => window.__spike.unmount("vol")); // unmount before the frame fires
  }
  await settle(page);
  const r = await report(page);
  expect(r.volume.created).toBe(CYCLES);
  expect(r.volume.disposed).toBe(CYCLES);
  expect(r.volume.rafOutstanding).toBe(0);
  expect(r.volume.errors).toEqual([]);
});
