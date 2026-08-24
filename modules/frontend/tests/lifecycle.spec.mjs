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
  // the pane hosts a real two-hemisphere model: it compiled and drew at least one frame
  expect((await report(page)).surface.frames).toBeGreaterThanOrEqual(1);
  expect((await report(page)).surface.errors).toEqual([]);
  const clear = await page.locator("#sentinel canvas").evaluate((canvas) => {
    const gl = canvas.getContext("webgl2") || canvas.getContext("webgl");
    return Array.from(gl.getParameter(gl.COLOR_CLEAR_VALUE));
  });
  expect(clear[0]).toBeCloseTo(5 / 255, 6);
  expect(clear[1]).toBeCloseTo(6 / 255, 6);
  expect(clear[2]).toBeCloseTo(10 / 255, 6);
  expect(clear[3]).toBeCloseTo(1, 6);
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

test("surface pane: the world cursor links to the nearest vertex within 3 mm, or says so; a pick returns that vertex's world point", async ({ page }) => {
  await ready(page);
  await page.evaluate(() => window.__spike.mountSurface("surf"));
  await settle(page);
  // left tetrahedron vertex 1 sits at world (-10, 0, 0): 0.5 mm off it links, 5 mm off it does not
  const near = await page.evaluate(() => window.__spike.link("surf", -10.5, 0, 0));
  expect(near.kind).toBe("linked");
  expect(near.surface).toBe("lh");
  expect(near.vertex).toBe(1);
  expect(near.distance).toBeCloseTo(0.5, 6);
  const far = await page.evaluate(() => window.__spike.link("surf", 0, 0, 0));
  expect(far.kind).toBe("out-of-range");
  expect(far.distance).toBeGreaterThan(3);
  const right = await page.evaluate(() => window.__spike.link("surf", 30.2, 0, 0));
  expect(right.kind).toBe("linked");
  expect(right.surface).toBe("rh");
  // a pick somewhere on the mesh returns a vertex whose world point links back at distance 0
  await settle(page);
  const pick = await page.evaluate(() => {
    const el = document.querySelector("#surf canvas");
    const w = el.clientWidth, h = el.clientHeight;
    for (const [fx, fy] of [[0.25, 0.5], [0.75, 0.5], [0.3, 0.4], [0.7, 0.6], [0.2, 0.6], [0.8, 0.4], [0.5, 0.5]]) {
      const p = window.__spike.pick("surf", w * fx, h * fy);
      if (p) return p;
    }
    return null;
  });
  expect(pick).not.toBeNull();
  const back = await page.evaluate((p) => window.__spike.link("surf", p.x, p.y, p.z), pick);
  expect(back.kind).toBe("linked");
  expect(back.surface).toBe(pick.surface);
  expect(back.vertex).toBe(pick.vertex);
  expect(back.distance).toBeCloseTo(0, 6);
  expect((await report(page)).surface.errors).toEqual([]);
  await page.evaluate(() => window.__spike.unmount("surf"));
  await settle(page);
  const unmounted = await page.evaluate(() => window.__spike.link("surf", 0, 0, 0));
  expect(unmounted.kind).toBe("unmounted");
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
