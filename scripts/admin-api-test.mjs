import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

// Exercise the shipped API helper, without DOM startup, production sessions or network writes.
const source = await readFile(new URL("../src/main/resources/admin/app.js", import.meta.url), "utf8");
const startup = source.indexOf('$("login-form").addEventListener');
assert.ok(startup > 0);
function panel(fetch) {
  const hidden = new Map([["login", true], ["workspace", false]]);
  const context = vm.createContext({ fetch, AbortSignal, clearTimeout, Intl, document: {
    getElementById: id => ({ classList: {
      add: name => { if (name === "hidden") hidden.set(id, true); },
      remove: name => { if (name === "hidden") hidden.set(id, false); },
    } }),
  } });
  vm.runInContext(source.slice(0, startup), context);
  vm.runInContext('csrf="fixture-csrf"', context);
  return { context, hidden, api: (...args) => context.api(...args),
    csrf: () => vm.runInContext("csrf", context) };
}
const json = (status, body) => new Response(JSON.stringify(body), { status,
  headers: { "Content-Type": "application/json" } });
const failure = (status, code, message) => json(status, { success: false, error: { code, message } });

test("admin assignment includes private plans but excludes inactive and nonassignable plans", () => {
  const app = panel(async () => json(200, { success: true, data: [] }));
  const plans = [{id:"PUBLIC",enabled:true,published:true,isAssignable:true},
    {id:"PRIVATE",enabled:true,published:false,isAssignable:true},
    {id:"NO_ASSIGN",enabled:true,published:true,isAssignable:false},
    {id:"INACTIVE",enabled:false,isAssignable:true}, {id:"LEGACY",enabled:true}];
  const result = app.context.assignablePlans(plans);
  assert.deepEqual(Array.from(result, p=>p.id), ["PUBLIC", "PRIVATE", "LEGACY"]);
});

test("purchase PUT sends session credentials and CSRF header; GET does not send CSRF", async () => {
  const calls = [];
  const app = panel(async (url, options) => {
    calls.push({ url, options });
    return json(200, { success: true, data: { premiumPurchaseEnabled: false } });
  });
  const result = await app.api("premium-purchase", "PUT", { premiumPurchaseEnabled: false, premiumPurchaseUrl: null });
  assert.equal(result.premiumPurchaseEnabled, false);
  assert.equal(calls[0].url, "/admin/api/premium-purchase");
  assert.equal(calls[0].options.credentials, "same-origin");
  assert.equal(calls[0].options.headers["X-CSRF-Token"], "fixture-csrf");
  assert.equal(calls[0].options.headers["Content-Type"], "application/json");
  await app.api("premium-purchase");
  assert.equal(calls[1].options.headers["X-CSRF-Token"], undefined);
});

test("empty and HTML 403 show actionable HTTP status without rendering response content", async () => {
  for (const body of ["", "<html>private-response-marker</html>"]) {
    const app = panel(async () => new Response(body, { status: 403 }));
    await assert.rejects(app.api("premium-purchase", "PUT", {}), error => {
      assert.match(error.message, /HTTP 403/);
      assert.match(error.message, /تازه‌سازی/);
      assert.doesNotMatch(error.message, /private-response-marker|قابل خواندن نیست|<html>/);
      return true;
    });
    assert.equal(app.csrf(), "fixture-csrf");
  }
});

test("structured CSRF and Origin errors offer specific safe recovery instructions", async () => {
  for (const [code, expected] of [["CSRF_INVALID", /اعتبار امنیتی/], ["ORIGIN_INVALID", /آدرس اصلی و امن/]]) {
    const app = panel(async () => failure(403, code, "ignored internal response"));
    await assert.rejects(app.api("premium-purchase", "PUT", {}), error => {
      assert.match(error.message, expected);
      assert.match(error.message, /HTTP 403/);
      assert.doesNotMatch(error.message, /ignored internal response/);
      return true;
    });
  }
});

test("malformed envelopes and generic HTTP failures retain status and do not echo raw bodies", async () => {
  for (const [status, body] of [[403, {}], [403, null], [200, null], [200, { success: true }],
    [502, {}], [429, { success: false }]]) {
    const app = panel(async () => json(status, body));
    await assert.rejects(app.api("plans"), new RegExp(`HTTP ${status}`));
  }
});

test("existing structured validation errors remain readable", async () => {
  const app = panel(async () => failure(400, "BAD_REQUEST", "لینک HTTPS معتبر نیست"));
  await assert.rejects(app.api("premium-purchase", "PUT", {}), /لینک HTTPS معتبر نیست/);
});

test("non-JSON 401 invalidates current session and displays login", async () => {
  const app = panel(async () => new Response("", { status: 401 }));
  await assert.rejects(app.api("premium-purchase"), /HTTP 401/);
  assert.equal(app.csrf(), "");
  assert.equal(app.hidden.get("workspace"), true);
  assert.equal(app.hidden.get("login"), false);
});

test("late 401 cannot invalidate a newer session", async () => {
  let finish;
  const app = panel(() => new Promise(resolve => { finish = resolve; }));
  const request = app.api("premium-purchase");
  vm.runInContext('csrf="new-fixture-csrf"', app.context);
  finish(new Response("", { status: 401 }));
  await assert.rejects(request, /HTTP 401/);
  assert.equal(app.csrf(), "new-fixture-csrf");
  assert.equal(app.hidden.get("workspace"), false);
});
