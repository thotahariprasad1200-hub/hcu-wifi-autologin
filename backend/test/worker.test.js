import test from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/index.js';
import { hmacSha256Hex, normalisePhone, isInstallId } from '../src/util.js';
import { D1Shim } from './d1-shim.js';

const WEBHOOK_SECRET = 'whsec_test_secret_value';
const ADMIN_SECRET = 'admin-secret-for-tests-0123456789';

function harness(envOverrides = {}) {
  const db = new D1Shim();
  let clock = Date.UTC(2026, 10, 1, 12, 0, 0);
  const razorpayCalls = [];
  let linkCounter = 0;
  const fetchFn = async (url, init) => {
    linkCounter += 1;
    razorpayCalls.push({ url, headers: init.headers, body: JSON.parse(init.body) });
    return new Response(
      JSON.stringify({ id: `plink_test${linkCounter}`, short_url: `https://rzp.io/i/test${linkCounter}` }),
      { status: 200 });
  };
  const app = createApp({ now: () => clock, fetchFn });
  const env = {
    DB: db,
    RAZORPAY_KEY_ID: 'rzp_test_key',
    RAZORPAY_KEY_SECRET: 'rzp_test_secret',
    RAZORPAY_WEBHOOK_SECRET: WEBHOOK_SECRET,
    ADMIN_SECRET,
    PRICE_PAISE: '5000',
    ...envOverrides,
  };
  const call = (path, init = {}) => app(new Request(`https://license.example${path}`, init), env);
  return {
    db, env, call, razorpayCalls,
    advance: (ms) => { clock += ms; },
  };
}

const newDevice = () => crypto.randomUUID();
const post = (body, headers = {}) => ({
  method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(body),
});

function paidEvent({ paymentId, referenceId, installId, amount = 5000, contact = '+919876543210', status = 'captured' }) {
  return {
    event: 'payment_link.paid',
    payload: {
      payment: { entity: { id: paymentId, amount, status, contact, email: null } },
      payment_link: { entity: { id: 'plink_x', reference_id: referenceId, status: 'paid', amount, amount_paid: amount, notes: { install_id: installId } } },
    },
  };
}

function refundEvent({ paymentId, amount = 5000, paymentAmount = 5000, refundedSoFar = amount }) {
  return {
    event: 'refund.processed',
    payload: {
      refund: { entity: { id: 'rfnd_1', payment_id: paymentId, amount, status: 'processed' } },
      payment: { entity: { id: paymentId, amount: paymentAmount, amount_refunded: refundedSoFar } },
    },
  };
}

async function sendWebhook(h, event, { eventId = `evt_${Math.random()}`, signature } = {}) {
  const raw = JSON.stringify(event);
  const sig = signature ?? await hmacSha256Hex(WEBHOOK_SECRET, raw);
  return h.call('/webhooks/razorpay', {
    method: 'POST',
    headers: { 'x-razorpay-signature': sig, 'x-razorpay-event-id': eventId, 'content-type': 'application/json' },
    body: raw,
  });
}

// Full happy path: ask for a link, then deliver the paid webhook for it.
async function buy(h, device, { paymentId = `pay_${Math.random().toString(36).slice(2, 10)}`, contact } = {}) {
  const res = await h.call(`/pay?device=${device}`);
  assert.equal(res.status, 200);
  const referenceId = h.razorpayCalls.at(-1).body.reference_id;
  const hook = await sendWebhook(h, paidEvent({ paymentId, referenceId, installId: device, contact }));
  assert.equal(hook.status, 200);
  return { paymentId, referenceId };
}

const statusOf = async (h, device) => (await (await h.call(`/status?device=${device}`)).json()).status;

// ---------------------------------------------------------------- validation helpers

test('install IDs must be UUID v4; phones must be Indian mobile numbers', () => {
  assert.ok(isInstallId(crypto.randomUUID()));
  assert.ok(!isInstallId('not-a-uuid'));
  assert.ok(!isInstallId("00000000-0000-0000-0000-000000000000' OR 1=1 --"));
  assert.equal(normalisePhone('+91 98765 43210'), '9876543210');
  assert.equal(normalisePhone('09876543210'), '9876543210');
  assert.equal(normalisePhone('919876543210'), '9876543210');
  assert.equal(normalisePhone('12345'), null);
  assert.equal(normalisePhone('5876543210'), null);   // Indian mobiles start 6-9
});

// ---------------------------------------------------------------- /status

test('/status: unknown install is trial; bad IDs are rejected; no row is created by reading', async () => {
  const h = harness();
  const device = newDevice();
  const res = await h.call(`/status?device=${device}`);
  assert.deepEqual(await res.json(), { status: 'trial', amount_paise: 5000 });
  assert.equal(res.headers.get('cache-control'), 'no-store');
  assert.equal((await h.call('/status?device=nope')).status, 400);
  assert.equal(h.db.rows('SELECT * FROM devices').length, 0);
});

test('unknown routes and wrong methods', async () => {
  const h = harness();
  assert.equal((await h.call('/nope')).status, 404);
  assert.equal((await h.call('/status', { method: 'POST' })).status, 405);
});

// ---------------------------------------------------------------- /pay

test('/pay creates a Razorpay link with a unique reference_id, correct amount and basic auth', async () => {
  const h = harness();
  const device = newDevice();
  const res = await h.call(`/pay?device=${device}`);
  const body = await res.json();
  assert.equal(body.status, 'link');
  assert.equal(body.url, 'https://rzp.io/i/test1');
  assert.equal(body.amount_paise, 5000);

  const call = h.razorpayCalls[0];
  assert.equal(call.url, 'https://api.razorpay.com/v1/payment_links');
  assert.equal(call.headers.authorization, 'Basic ' + btoa('rzp_test_key:rzp_test_secret'));
  assert.equal(call.body.amount, 5000);
  assert.equal(call.body.currency, 'INR');
  assert.equal(call.body.accept_partial, false);
  assert.equal(call.body.notes.install_id, device);
  assert.ok(call.body.reference_id.length <= 40, 'Razorpay allows at most 40 characters');
  assert.notEqual(call.body.reference_id, device, 'reference_id must be a per-link nonce, not the install ID');
  assert.ok(call.body.expire_by - Math.floor(Date.now() / 1000) > 0);
});

test('/pay reuses an unexpired link, but issues a NEW reference_id once it is about to expire', async () => {
  const h = harness();
  const device = newDevice();
  await h.call(`/pay?device=${device}`);
  const again = await (await h.call(`/pay?device=${device}`)).json();
  assert.equal(again.url, 'https://rzp.io/i/test1');
  assert.equal(h.razorpayCalls.length, 1, 'second request must reuse the stored link');

  h.advance(23.75 * 3600 * 1000);   // 15 minutes left: below the 30-minute reuse floor
  const fresh = await (await h.call(`/pay?device=${device}`)).json();
  assert.equal(fresh.url, 'https://rzp.io/i/test2');
  assert.equal(h.razorpayCalls.length, 2);
  assert.notEqual(h.razorpayCalls[0].body.reference_id, h.razorpayCalls[1].body.reference_id,
    'Razorpay rejects a repeated reference_id');
});

test('/pay says paid for an unlocked install and never creates a link', async () => {
  const h = harness();
  const device = newDevice();
  await buy(h, device);
  const callsBefore = h.razorpayCalls.length;
  assert.deepEqual(await (await h.call(`/pay?device=${device}`)).json(), { status: 'paid' });
  assert.equal(h.razorpayCalls.length, callsBefore);
});

test('/pay returns 502 when Razorpay is down, and stores nothing', async () => {
  const h = harness();
  const failing = createApp({ now: () => Date.now(), fetchFn: async () => new Response('boom', { status: 500 }) });
  const res = await failing(new Request(`https://license.example/pay?device=${newDevice()}`), h.env);
  assert.equal(res.status, 502);
  assert.equal(h.db.rows('SELECT * FROM payment_links').length, 0);
});

test('/pay is rate limited per install', async () => {
  const h = harness();
  const device = newDevice();
  for (let i = 0; i < 10; i++) assert.equal((await h.call(`/pay?device=${device}`)).status, 200);
  assert.equal((await h.call(`/pay?device=${device}`)).status, 429);
});

// ---------------------------------------------------------------- webhook: signature

test('webhook: bad or missing signature -> 401 and no state change', async () => {
  const h = harness();
  const device = newDevice();
  await h.call(`/pay?device=${device}`);
  const referenceId = h.razorpayCalls[0].body.reference_id;
  const event = paidEvent({ paymentId: 'pay_forged', referenceId, installId: device });

  assert.equal((await sendWebhook(h, event, { signature: 'deadbeef'.repeat(8) })).status, 401);
  assert.equal((await h.call('/webhooks/razorpay', { method: 'POST', body: JSON.stringify(event) })).status, 401);
  assert.equal(h.db.rows('SELECT * FROM payments').length, 0);
  assert.equal(await statusOf(h, device), 'trial');
});

test('webhook: a signature made with the WRONG secret is rejected', async () => {
  const h = harness();
  const device = newDevice();
  await h.call(`/pay?device=${device}`);
  const event = paidEvent({ paymentId: 'pay_1', referenceId: h.razorpayCalls[0].body.reference_id, installId: device });
  const wrong = await hmacSha256Hex('some-other-secret', JSON.stringify(event));
  assert.equal((await sendWebhook(h, event, { signature: wrong })).status, 401);
});

test('webhook: 503 when the webhook secret is not configured (fails closed)', async () => {
  const h = harness({ RAZORPAY_WEBHOOK_SECRET: undefined });
  assert.equal((await h.call('/webhooks/razorpay', { method: 'POST', body: '{}' })).status, 503);
});

// ---------------------------------------------------------------- webhook: paid / idempotency

test('webhook paid: unlocks, normalises the phone, retires the link', async () => {
  const h = harness();
  const device = newDevice();
  const { paymentId, referenceId } = await buy(h, device, { paymentId: 'pay_abc' });
  assert.equal(await statusOf(h, device), 'paid');
  const [payment] = h.db.rows('SELECT * FROM payments WHERE payment_id = ?', paymentId);
  assert.equal(payment.phone, '9876543210');
  assert.equal(payment.install_id, device);
  assert.equal(payment.amount, 5000);
  assert.equal(h.db.rows('SELECT expires_at FROM payment_links WHERE reference_id = ?', referenceId)[0].expires_at, 0);
});

test('webhook paid: replaying the SAME delivery twice (and a retry with a new event id) unlocks exactly once', async () => {
  const h = harness();
  const device = newDevice();
  await h.call(`/pay?device=${device}`);
  const referenceId = h.razorpayCalls[0].body.reference_id;
  const event = paidEvent({ paymentId: 'pay_dup', referenceId, installId: device });

  const first = await (await sendWebhook(h, event, { eventId: 'evt_A' })).json();
  const replay = await (await sendWebhook(h, event, { eventId: 'evt_A' })).json();
  const retryNewId = await (await sendWebhook(h, event, { eventId: 'evt_B' })).json();

  assert.equal(first.result, 'unlocked');
  assert.equal(replay.result, 'duplicate');
  assert.equal(retryNewId.result, 'duplicate');
  assert.equal(h.db.rows('SELECT * FROM payments').length, 1);
  assert.equal(h.db.rows("SELECT * FROM devices WHERE status = 'paid'").length, 1);
});

test('webhook paid: ignores uncaptured payments, too-small amounts, and unknown links (all acknowledged with 200)', async () => {
  const h = harness();
  const device = newDevice();
  await h.call(`/pay?device=${device}`);
  const referenceId = h.razorpayCalls[0].body.reference_id;

  const failed = await sendWebhook(h, paidEvent({ paymentId: 'pay_f', referenceId, installId: device, status: 'failed' }));
  const small = await sendWebhook(h, paidEvent({ paymentId: 'pay_s', referenceId, installId: device, amount: 100 }));
  const stranger = await sendWebhook(h, paidEvent({ paymentId: 'pay_u', referenceId: 'wl_unknown', installId: 'not-a-uuid' }));

  assert.equal(failed.status, 200);
  assert.equal((await small.json()).result, 'amount_mismatch');
  assert.equal((await stranger.json()).result, 'unknown_link');
  assert.equal(h.db.rows('SELECT * FROM payments').length, 0);
  assert.equal(await statusOf(h, device), 'trial');
});

test('webhook: unrelated events are acknowledged and ignored', async () => {
  const h = harness();
  const res = await sendWebhook(h, { event: 'payment.captured', payload: {} });
  assert.equal(res.status, 200);
  assert.equal((await res.json()).result, 'ignored');
});

test('webhook: a database failure returns 500 so Razorpay retries, and nothing is half-written', async () => {
  const h = harness();
  const device = newDevice();
  await h.call(`/pay?device=${device}`);
  const referenceId = h.razorpayCalls[0].body.reference_id;
  const event = paidEvent({ paymentId: 'pay_retry', referenceId, installId: device });

  h.db.failNextBatch = true;
  assert.equal((await sendWebhook(h, event, { eventId: 'evt_R' })).status, 500);
  assert.equal(await statusOf(h, device), 'trial');

  // Razorpay retries the same delivery and this time it succeeds.
  assert.equal((await sendWebhook(h, event, { eventId: 'evt_R' })).status, 200);
  assert.equal(await statusOf(h, device), 'paid');
});

// ---------------------------------------------------------------- webhook: refunds

test('refund.processed revokes; replaying the paid event afterwards must NOT re-unlock', async () => {
  const h = harness();
  const device = newDevice();
  const { paymentId, referenceId } = await buy(h, device, { paymentId: 'pay_ref' });
  assert.equal(await statusOf(h, device), 'paid');

  assert.equal((await sendWebhook(h, refundEvent({ paymentId }))).status, 200);
  assert.equal(await statusOf(h, device), 'revoked');

  await sendWebhook(h, paidEvent({ paymentId, referenceId, installId: device }), { eventId: 'evt_late_replay' });
  assert.equal(await statusOf(h, device), 'revoked', 'a late redelivery must not undo a refund');
});

test('refund arriving BEFORE its payment webhook leaves a tombstone: the payment never unlocks', async () => {
  const h = harness();
  const device = newDevice();
  await h.call(`/pay?device=${device}`);
  const referenceId = h.razorpayCalls[0].body.reference_id;

  await sendWebhook(h, refundEvent({ paymentId: 'pay_early' }));
  await sendWebhook(h, paidEvent({ paymentId: 'pay_early', referenceId, installId: device }));
  assert.equal(await statusOf(h, device), 'trial');
});

test('a partial refund does not revoke', async () => {
  const h = harness();
  const device = newDevice();
  const { paymentId } = await buy(h, device);
  const res = await sendWebhook(h, refundEvent({ paymentId, amount: 1000, refundedSoFar: 1000 }));
  assert.equal((await res.json()).result, 'partial_refund_ignored');
  assert.equal(await statusOf(h, device), 'paid');
});

test('after a refund the buyer can purchase again and gets a fresh (not the used) link', async () => {
  const h = harness();
  const device = newDevice();
  const { paymentId } = await buy(h, device);
  await sendWebhook(h, refundEvent({ paymentId }));
  const callsBefore = h.razorpayCalls.length;

  const again = await (await h.call(`/pay?device=${device}`)).json();
  assert.equal(again.status, 'link');
  assert.equal(h.razorpayCalls.length, callsBefore + 1, 'the paid link was retired, so a new one is created');
  await buy(h, device);
  assert.equal(await statusOf(h, device), 'paid');
});

// ---------------------------------------------------------------- /restore

test('restore: unknown number -> not_found; invalid input -> 400', async () => {
  const h = harness();
  assert.deepEqual(await (await h.call('/restore', post({ device: newDevice(), phone: '9123456780' }))).json(), { result: 'not_found' });
  assert.equal((await h.call('/restore', post({ device: 'bad', phone: '9123456780' }))).status, 400);
  assert.equal((await h.call('/restore', post({ device: newDevice(), phone: '123' }))).status, 400);
  assert.equal((await h.call('/restore', { method: 'POST', body: 'not json' })).status, 400);
});

test('restore: moves the unlock to a new install once; the old install is revoked; a second move is blocked', async () => {
  const h = harness();
  const oldPhone = newDevice();
  const newPhone = newDevice();
  const thirdPhone = newDevice();
  await buy(h, oldPhone, { contact: '+919876543210' });

  const moved = await (await h.call('/restore', post({ device: newPhone, phone: '98765 43210' }))).json();
  assert.equal(moved.result, 'restored');
  assert.equal(await statusOf(h, newPhone), 'paid');
  assert.equal(await statusOf(h, oldPhone), 'revoked', 'exactly one active device per payment');

  const blocked = await (await h.call('/restore', post({ device: thirdPhone, phone: '9876543210' }))).json();
  assert.equal(blocked.result, 'limit_reached');
  assert.equal(await statusOf(h, thirdPhone), 'trial');
  assert.equal(await statusOf(h, newPhone), 'paid', 'a blocked restore must not disturb the current device');
});

test('restore: the already-bound install gets restored without spending the rebind budget', async () => {
  const h = harness();
  const device = newDevice();
  await buy(h, device);
  assert.equal((await (await h.call('/restore', post({ device, phone: '9876543210' }))).json()).result, 'restored');
  assert.equal(h.db.rows('SELECT rebinds_used FROM payments')[0].rebinds_used, 0);
});

test('restore: a refunded payment cannot be restored', async () => {
  const h = harness();
  const device = newDevice();
  const { paymentId } = await buy(h, device);
  await sendWebhook(h, refundEvent({ paymentId }));
  const res = await (await h.call('/restore', post({ device: newDevice(), phone: '9876543210' }))).json();
  assert.equal(res.result, 'not_found');
});

test('restore: two racing restores from different installs -> exactly one wins', async () => {
  const h = harness();
  const original = newDevice();
  await buy(h, original);
  const a = newDevice();
  const b = newDevice();
  const results = await Promise.all([
    h.call('/restore', post({ device: a, phone: '9876543210' })).then((r) => r.json()),
    h.call('/restore', post({ device: b, phone: '9876543210' })).then((r) => r.json()),
  ]);
  assert.deepEqual(results.map((r) => r.result).sort(), ['limit_reached', 'restored']);
  const paidDevices = h.db.rows("SELECT install_id FROM devices WHERE status = 'paid'");
  assert.equal(paidDevices.length, 1);
});

test('restore: per-phone rate limit defeats guessing with freshly minted install IDs', async () => {
  const h = harness();
  const statuses = [];
  for (let i = 0; i < 7; i++) {
    statuses.push((await h.call('/restore', post({ device: newDevice(), phone: '9000000001' }))).status);
  }
  assert.deepEqual(statuses, [200, 200, 200, 200, 200, 429, 429]);
});

// ---------------------------------------------------------------- /admin/reset

const admin = (body, secret = ADMIN_SECRET) =>
  post(body, secret === null ? {} : { authorization: `Bearer ${secret}` });

test('admin: rejects missing/wrong secret, and is disabled entirely when the secret is weak or unset', async () => {
  const h = harness();
  assert.equal((await h.call('/admin/reset', admin({ action: 'revoke', install_id: newDevice() }, null))).status, 401);
  assert.equal((await h.call('/admin/reset', admin({ action: 'revoke', install_id: newDevice() }, 'wrong'))).status, 401);
  assert.equal((await harness({ ADMIN_SECRET: 'short' }).call('/admin/reset', admin({}, 'short'))).status, 503);
  assert.equal((await harness({ ADMIN_SECRET: undefined }).call('/admin/reset', admin({}, ''))).status, 503);
});

test('admin: unbind_payment lets a buyer who used their one restore move again', async () => {
  const h = harness();
  const first = newDevice(), second = newDevice(), third = newDevice();
  const { paymentId } = await buy(h, first);
  await h.call('/restore', post({ device: second, phone: '9876543210' }));
  assert.equal((await (await h.call('/restore', post({ device: third, phone: '9876543210' }))).json()).result, 'limit_reached');

  const reset = await h.call('/admin/reset', admin({ action: 'unbind_payment', payment_id: paymentId }));
  assert.equal((await reset.json()).changed, 1);
  assert.equal((await (await h.call('/restore', post({ device: third, phone: '9876543210' }))).json()).result, 'restored');
  assert.equal(await statusOf(h, third), 'paid');
  assert.equal(await statusOf(h, second), 'revoked');
});

test('admin: force_unlock handles a missed webhook, but refuses a refunded payment', async () => {
  const h = harness();
  const device = newDevice();
  const res = await h.call('/admin/reset', admin({ action: 'force_unlock', install_id: device, payment_id: 'pay_manual' }));
  assert.equal(res.status, 200);
  assert.equal(await statusOf(h, device), 'paid');

  await sendWebhook(h, refundEvent({ paymentId: 'pay_manual', paymentAmount: 5000 }));
  // Manual rows have no stored amount, but the refund payload carries Razorpay's own totals.
  assert.equal(await statusOf(h, device), 'revoked');
  const again = await h.call('/admin/reset', admin({ action: 'force_unlock', install_id: device, payment_id: 'pay_manual' }));
  assert.equal(again.status, 409);
});

test('admin: revoke works and validates input', async () => {
  const h = harness();
  const device = newDevice();
  await buy(h, device);
  assert.equal((await h.call('/admin/reset', admin({ action: 'revoke', install_id: device }))).status, 200);
  assert.equal(await statusOf(h, device), 'revoked');
  assert.equal((await h.call('/admin/reset', admin({ action: 'revoke', install_id: 'x' }))).status, 400);
  assert.equal((await h.call('/admin/reset', admin({ action: 'nuke' }))).status, 400);
});

test('admin: repeated wrong-secret attempts are rate limited', async () => {
  const h = harness();
  let last;
  for (let i = 0; i < 22; i++) last = (await h.call('/admin/reset', admin({}, 'guess' + i))).status;
  assert.equal(last, 429);
});

// ---------------------------------------------------------------- privacy

test('the ledger stores only install ID, payment ID, amount and the 10-digit phone', async () => {
  const h = harness();
  await buy(h, newDevice());
  const columns = h.db.rows('PRAGMA table_info(payments)').map((c) => c.name).sort();
  assert.deepEqual(columns, ['amount', 'created_at', 'event_id', 'install_id', 'link_ref', 'payment_id',
    'phone', 'rebinds_used', 'refunded', 'refunded_at']);
  const [row] = h.db.rows('SELECT phone FROM payments');
  assert.match(row.phone, /^\d{10}$/);
});
