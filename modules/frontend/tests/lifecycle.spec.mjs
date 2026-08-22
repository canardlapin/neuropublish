import { test, expect } from "@playwright/test";

// Chrome keeps roughly 16 WebGL contexts live and loses the OLDEST when more are created.
// A sentinel pane mounted first therefore detects any leaked context from later cycles.
const CYCLES = 24;

async function ready(page) {
  await page.goto("/spike.html");
  await page.waitForFunction(() => window.__spikeReady === true);
}
const report = (page) => page.evaluate(() => window.__spike.report());
const settle = (page) => page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));

test("volume pane: mount, resize, unmount releases everything exactly once", async ({ page }) => {
  await ready(page);
  const dpr = await page.evaluate(() => window.devicePixelRatio);
  await page.evaluate(() => window.__spike.mountVolume("vol"));
  await settle(page);
  let r = await report(page);
  expect(r.volume.created).toBe(1);
  expect(r.volume.resizes).toBeGreaterThanOrEqual(1);
  expect(r.volume.frames).toBeGreaterThanOrEqual(1);
  expect(r.volume.errors).toEqual([]);

  await page.evaluate(() => window.__spike.resize("vol", 480, 300));
  await settle(page);
  r = await report(page);
  expect(r.volume.resizes).toBeGreaterThanOrEqual(2);
  const size = await page.locator("#vol canvas").evaluate((c) => [c.width, c.height]);
  expect(size).toEqual([Math.round(480 * dpr), Math.round(300 * dpr)]);

  await page.evaluate(() => window.__spike.unmount("vol"));
  await settle(page);
  r = await report(page);
  expect(r.volume.disposed).toBe(1);
  expect(r.volume.rafOutstanding).toBe(0);
  expect(r.disposedHandles).toBe(1);
  expect(r.liveHandles).toBe(0);
  expect(await page.locator("#vol canvas").count()).toBe(0);
});

test("surface panes: a mounted sentinel survives 24 mount/unmount cycles with no lost context or leaked listener", async ({ page }) => {
  await ready(page);
  await page.evaluate(() => window.__spike.mountSurface("sentinel"));
  await settle(page);
  expect(await page.evaluate(() => window.__spike.contextState("sentinel"))).toBe("Available");
  const before = (await report(page)).listeners;

  for (let i = 0; i < CYCLES; i++) {
    await page.evaluate(() => window.__spike.mountSurface("surf"));
    await settle(page);
    await page.evaluate(() => window.__spike.unmount("surf"));
  }
  await settle(page);
  const r = await report(page);
  expect(r.surface.created).toBe(CYCLES + 1);
  expect(r.surface.disposed).toBe(CYCLES);
  expect(r.surface.errors).toEqual([]);
  expect(r.surface.contextLost).toBe(0);
  expect(await page.evaluate(() => window.__spike.contextState("sentinel"))).toBe("Available");
  expect(r.listeners.add - r.listeners.remove).toBe(before.add - before.remove);

  await page.evaluate(() => window.__spike.unmount("sentinel"));
  await settle(page);
  const f = await report(page);
  expect(f.surface.disposed).toBe(CYCLES + 1);
  expect(f.disposedHandles).toBe(CYCLES + 1);
  expect(f.liveHandles).toBe(0);
});

test("volume pane: 24 mount/unmount cycles leave no outstanding frames", async ({ page }) => {
  await ready(page);
  for (let i = 0; i < CYCLES; i++) {
    await page.evaluate(() => window.__spike.mountVolume("vol"));
    await page.evaluate(() => window.__spike.unmount("vol"));
  }
  await settle(page);
  const r = await report(page);
  expect(r.volume.created).toBe(CYCLES);
  expect(r.volume.disposed).toBe(CYCLES);
  expect(r.volume.rafOutstanding).toBe(0);
  expect(r.volume.errors).toEqual([]);
});
