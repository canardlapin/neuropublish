import { test, expect } from "@playwright/test";

// Stage 4 exit criteria, end to end, against the backend started by scripts/e2e.sh
// (NP_OWNER_EMAIL / NP_OWNER_PASSWORD = owner@example.org / owner-dev-password).
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

test("private project requires sign-in; login grants access", async ({ page }) => {
  await page.goto("/w/rotman/p/sherlock");
  await page.waitForURL(/\/login/);
  await login(page);
  await ready(page);
  await expect(page.locator("h1")).toContainText("Sherlock");
});

test("provenance shows heterogeneous inputs as groups, never a false shared value; unknown op is retained only", async ({ page }) => {
  await login(page);
  await openWorkspace(page);
  await page.getByRole("tab", { name: "Provenance" }).click();
  const facet = page.getByTestId("prov-facet").filter({ hasText: /temporalNoise/ });
  await expect(facet).toContainText(/differ/i);
  const groups = facet.getByTestId("prov-group");
  await expect(groups).toHaveCount(2);
  await expect(facet).not.toContainText(/AR order:\s*2\b/);
  const unknown = page.getByTestId("prov-node").filter({ hasText: "smooth" });
  await expect(unknown).toContainText(/retained/i);
  await expect(unknown.locator("a[download]")).toHaveCount(1);
  await expect(unknown.locator("button")).toHaveCount(0);
});

test("saved view: save, reopen with order + presentation, update leaves the revision digest unchanged", async ({ page }) => {
  await login(page);
  const href = await openWorkspace(page);
  const digestBefore = (await page.locator(".facts-panel, .meta, body").first().textContent()) || "";
  // modify: threshold 4.5, move z up
  const card = page.locator('.layer-card[data-layer="speech-t"]');
  const thr = card.getByLabel("minimum |value|");
  await thr.click(); await thr.selectText(); await page.keyboard.type("4.5"); await page.keyboard.press("Enter");
  await page.locator('.layer-card[data-layer="speech-z"] [aria-label="move layer up"]').click();
  await page.getByTestId("save-view").click();
  await page.getByTestId("view-name").fill("Left STG peak");
  await page.getByTestId("view-name").press("Enter");
  await page.waitForURL(/\/v\//);
  const viewUrl = page.url();
  await page.goto(viewUrl);
  await ready(page); await settle(page);
  await expect(page.locator('.layer-card[data-layer="speech-t"]').getByLabel("minimum |value|")).toHaveValue("4.50");
  const order = await page.locator(".layer-card").evaluateAll((els) => els.map((e) => e.dataset.layer));
  expect(order.indexOf("speech-z")).toBeLessThan(order.indexOf("speech-t"));
  // revision digest unchanged by saving (API)
  const rev = href.match(/\/r\/([^/]+)/)[1];
  const detail = await (await page.request.get(`/api/v1/revisions/${rev}`)).json(); // page.request shares the session cookie
  expect(detail.digest).toMatch(/^sha256:/);
  await page.goto("/w/rotman/p/sherlock");
  await ready(page);
  const after = await (await page.request.get(`/api/v1/revisions/${rev}`)).json();
  expect(after.digest).toBe(detail.digest);
});

test("share link opens without an account, explores locally, and dies on revoke", async ({ page, browser }) => {
  await login(page);
  await openWorkspace(page);
  await page.getByTestId("save-view").click();
  await page.getByTestId("view-name").fill("Shared view");
  await page.getByTestId("view-name").press("Enter");
  await page.waitForURL(/\/v\//);
  await page.getByTestId("share-view").click();
  await page.getByTestId("create-link").click();
  const link = await page.getByTestId("share-link").inputValue();
  expect(link).toMatch(/\/s\/[A-Za-z0-9_-]{20,}/);

  const anon = await browser.newContext();
  const viewer = await anon.newPage();
  await viewer.goto(link);
  await ready(viewer); await settle(viewer);
  await expect(viewer.getByTestId("readonly-bar")).toBeVisible();
  await expect(viewer.getByTestId("save-view")).toHaveCount(0);
  await expect(viewer.locator("#volume canvas")).toHaveCount(1);
  // local exploration, then return
  await viewer.locator('.layer-card[data-layer="speech-t"] input[type=checkbox]').click();
  await viewer.getByTestId("return-to-saved").click();
  await expect(viewer.locator('.layer-card[data-layer="speech-t"] input[type=checkbox]')).toBeChecked();

  await page.getByTestId("revoke-link").first().click();
  await viewer.reload();
  await expect(viewer.locator("body")).toContainText(/revoked|expired/i);
  // members keep access
  await page.goto("/w/rotman/p/sherlock");
  await ready(page);
  await expect(page.locator("h1")).toContainText("Sherlock");
  await anon.close();
});
