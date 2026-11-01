// Wi-Fi Auto-Login licensing backend: Cloudflare Worker + D1 + Razorpay.
//
// Routes (PRD section 11.2):
//   GET  /status?device=ID        -> {status: trial|paid|revoked, amount_paise}
//   GET  /pay?device=ID           -> {status:"paid"} or {status:"link", url, ...}
//   POST /restore {device,phone}  -> {result: restored|not_found|limit_reached}
//   POST /webhooks/razorpay       -> Razorpay events (HMAC verified, idempotent)
//   POST /admin/reset             -> seller-only overrides (Bearer ADMIN_SECRET)
//
// Secrets (Worker secrets, never in git): RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET,
// RAZORPAY_WEBHOOK_SECRET, ADMIN_SECRET.  Settings (wrangler.toml vars): PRICE_PAISE.
//
// Design rule: every state change is ONE db.batch() (a transaction in D1) whose statements are
// guarded by table state, not by "did the previous statement change a row", so a redelivered
// webhook, a refund that arrives before its payment, or a rebind can never re-grant an unlock.

import {
  json, isInstallId, normalisePhone, hmacSha256Hex, constantTimeEqual, secretsMatch,
  randomHex, withinLimit, purgeExpiredLimits,
} from './util.js';
import { createPaymentLink } from './razorpay.js';

const MAX_REBINDS = 1;                 // self-service moves per payment (PRD 10.4)
const LINK_TTL_SECONDS = 24 * 3600;    // Razorpay minimum is 15 minutes
const LINK_REUSE_MIN_SECONDS = 30 * 60;
const HOUR = 3600;
const MAX_WEBHOOK_BYTES = 256 * 1024;
const MAX_JSON_BYTES = 4096;

function log(event, fields = {}) {
  // Structured, PII-free: never log phone numbers, keys, secrets or signatures.
  console.log(JSON.stringify({ event, ...fields }));
}

function priceFor(env) {
  const value = Number.parseInt(env.PRICE_PAISE ?? '5000', 10);
  return Number.isInteger(value) && value >= 100 ? value : 5000;
}

function clientIp(request) {
  return request.headers.get('cf-connecting-ip') || 'unknown';
}

async function readJson(request, maxBytes) {
  const declared = Number(request.headers.get('content-length') ?? 0);
  if (declared > maxBytes) return null;
  const text = await request.text();
  if (text.length > maxBytes) return null;
  try {
    const value = JSON.parse(text);
    return value && typeof value === 'object' ? value : null;
  } catch {
    return null;
  }
}

function tooManyRequests() {
  return json({ error: 'rate_limited' }, 429, { 'retry-after': String(HOUR) });
}

// Upsert a device as paid, but only while the payment is still bound to that install and not refunded.
function grantStatement(db, installId, paymentId, nowIso) {
  return db
    .prepare(
      `INSERT INTO devices (install_id, status, paid_at, payment_id)
       SELECT ?, 'paid', ?, ?
       WHERE EXISTS (SELECT 1 FROM payments WHERE payment_id = ? AND install_id = ? AND refunded = 0)
       ON CONFLICT(install_id) DO UPDATE SET
         status = 'paid', paid_at = excluded.paid_at, payment_id = excluded.payment_id`)
    .bind(installId, nowIso, paymentId, paymentId, installId);
}

// ---------------------------------------------------------------- GET /status

async function getStatus(request, env) {
  const device = new URL(request.url).searchParams.get('device');
  if (!isInstallId(device)) return json({ error: 'invalid_device' }, 400);
  const row = await env.DB.prepare('SELECT status FROM devices WHERE install_id = ?').bind(device).first();
  return json({ status: row?.status ?? 'trial', amount_paise: priceFor(env) });
}

// ---------------------------------------------------------------- GET /pay

async function getPay(request, env, nowMs, fetchFn) {
  const device = new URL(request.url).searchParams.get('device');
  if (!isInstallId(device)) return json({ error: 'invalid_device' }, 400);

  const db = env.DB;
  const nowSec = Math.floor(nowMs / 1000);
  // Per-IP limits are generous: a whole campus can share one NAT address after portal login.
  if (!(await withinLimit(db, 'pay-device', device, 10, HOUR, nowSec))
      || !(await withinLimit(db, 'pay-ip', clientIp(request), 300, HOUR, nowSec))) {
    return tooManyRequests();
  }

  const price = priceFor(env);
  const deviceRow = await db.prepare('SELECT status FROM devices WHERE install_id = ?').bind(device).first();
  if (deviceRow?.status === 'paid') return json({ status: 'paid' });

  const existing = await db
    .prepare(
      `SELECT short_url, expires_at FROM payment_links
       WHERE install_id = ? AND amount = ? AND expires_at > ?
       ORDER BY expires_at DESC LIMIT 1`)
    .bind(device, price, nowSec + LINK_REUSE_MIN_SECONDS)
    .first();
  if (existing) {
    return json({ status: 'link', url: existing.short_url, amount_paise: price, expires_at: existing.expires_at });
  }

  const referenceId = `wl_${randomHex(12)}`;          // 27 chars; Razorpay allows 40 and requires uniqueness
  const expiresAt = nowSec + LINK_TTL_SECONDS;
  let link;
  try {
    link = await createPaymentLink(env, fetchFn, {
      referenceId,
      installId: device,
      amountPaise: price,
      expireBy: expiresAt,
      description: env.PRODUCT_DESCRIPTION || 'Wi-Fi Auto-Login - one-time unlock',
    });
  } catch (err) {
    log('pay_link_failed', { reason: String(err.message || err) });
    return json({ error: 'payment_provider_unavailable' }, 502);
  }

  await db
    .prepare(
      `INSERT INTO payment_links (reference_id, link_id, install_id, short_url, amount, expires_at, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`)
    .bind(referenceId, link.id ?? null, device, link.shortUrl, price, expiresAt, new Date(nowMs).toISOString())
    .run();

  return json({ status: 'link', url: link.shortUrl, amount_paise: price, expires_at: expiresAt });
}

// ---------------------------------------------------------------- POST /webhooks/razorpay

async function postWebhook(request, env, nowMs) {
  if (!env.RAZORPAY_WEBHOOK_SECRET) return json({ error: 'not_configured' }, 503);

  if (Number(request.headers.get('content-length') ?? 0) > MAX_WEBHOOK_BYTES) {
    return json({ error: 'too_large' }, 413);
  }
  // The signature covers the RAW body, so it is read as text and verified before any parsing.
  const raw = await request.text();
  if (raw.length > MAX_WEBHOOK_BYTES) return json({ error: 'too_large' }, 413);

  const provided = (request.headers.get('x-razorpay-signature') || '').toLowerCase();
  const expected = await hmacSha256Hex(env.RAZORPAY_WEBHOOK_SECRET, raw);
  if (!provided || !constantTimeEqual(provided, expected)) {
    log('webhook_bad_signature');
    return json({ error: 'bad_signature' }, 401);
  }

  let event;
  try {
    event = JSON.parse(raw);
  } catch {
    return json({ error: 'bad_json' }, 400);
  }

  const eventId = request.headers.get('x-razorpay-event-id') || null;
  const nowIso = new Date(nowMs).toISOString();

  switch (event?.event) {
    case 'payment_link.paid':
      return json(await applyPaid(env.DB, event, eventId, nowIso));
    case 'refund.processed':
      return json(await applyRefund(env.DB, event, nowIso));
    default:
      // Unsubscribed or irrelevant events are acknowledged so Razorpay stops retrying them.
      return json({ ok: true, result: 'ignored' });
  }
}

async function applyPaid(db, event, eventId, nowIso) {
  const link = event.payload?.payment_link?.entity;
  const payment = event.payload?.payment?.entity;
  if (!link || !payment || payment.status !== 'captured' || link.status !== 'paid' || !payment.id) {
    return { ok: true, result: 'ignored' };
  }

  // Our own reference_id -> install mapping is the source of truth; notes are only a fallback.
  const mapped = await db
    .prepare('SELECT install_id, amount FROM payment_links WHERE reference_id = ?')
    .bind(link.reference_id ?? '')
    .first();
  const installId = mapped?.install_id
    ?? (isInstallId(link.notes?.install_id) ? link.notes.install_id : null);
  if (!installId) {
    log('webhook_unknown_link', { payment_id: payment.id });
    return { ok: true, result: 'unknown_link' };   // 200: retrying cannot help; seller can force-unlock
  }

  const expectedAmount = mapped?.amount ?? 0;
  if (!Number.isInteger(payment.amount) || payment.amount < expectedAmount) {
    log('webhook_amount_too_low', { payment_id: payment.id });
    return { ok: true, result: 'amount_mismatch' };
  }

  const results = await db.batch([
    db.prepare(
      `INSERT OR IGNORE INTO payments
         (payment_id, link_ref, install_id, phone, amount, event_id, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`)
      .bind(payment.id, link.reference_id ?? null, installId, normalisePhone(payment.contact ?? ''),
            payment.amount, eventId, nowIso),
    grantStatement(db, installId, payment.id, nowIso),
    db.prepare('UPDATE payment_links SET expires_at = 0 WHERE reference_id = ?').bind(link.reference_id ?? ''),
  ]);

  const created = results[0].meta?.changes === 1;
  log('webhook_paid', { payment_id: payment.id, new: created });
  return { ok: true, result: created ? 'unlocked' : 'duplicate' };
}

async function applyRefund(db, event, nowIso) {
  const refund = event.payload?.refund?.entity;
  const payment = event.payload?.payment?.entity;
  const paymentId = refund?.payment_id ?? payment?.id;
  if (!paymentId) return { ok: true, result: 'ignored' };

  // Only a FULL refund revokes. Prefer Razorpay's own cumulative figure; fall back to our ledger.
  let full;
  if (payment && Number.isInteger(payment.amount_refunded) && Number.isInteger(payment.amount)) {
    full = payment.amount_refunded >= payment.amount;
  } else {
    const stored = await db.prepare('SELECT amount FROM payments WHERE payment_id = ?').bind(paymentId).first();
    full = Boolean(stored?.amount) && Number.isInteger(refund?.amount) && refund.amount >= stored.amount;
  }
  if (!full) {
    log('webhook_partial_refund', { payment_id: paymentId });
    return { ok: true, result: 'partial_refund_ignored' };
  }

  // If the refund is processed before the payment webhook arrives, this inserts a tombstone:
  // the later payment_link.paid insert is ignored, so a refunded payment can never unlock.
  await db.batch([
    db.prepare(
      `INSERT INTO payments (payment_id, created_at, refunded, refunded_at) VALUES (?, ?, 1, ?)
       ON CONFLICT(payment_id) DO UPDATE SET
         refunded = 1, refunded_at = COALESCE(payments.refunded_at, excluded.refunded_at)`)
      .bind(paymentId, nowIso, nowIso),
    db.prepare(`UPDATE devices SET status = 'revoked' WHERE payment_id = ? AND status = 'paid'`).bind(paymentId),
  ]);
  log('webhook_refund', { payment_id: paymentId });
  return { ok: true, result: 'revoked' };
}

// ---------------------------------------------------------------- POST /restore

async function postRestore(request, env, nowMs) {
  const body = await readJson(request, MAX_JSON_BYTES);
  const device = body?.device;
  const phone = normalisePhone(body?.phone);
  if (!isInstallId(device) || !phone) return json({ error: 'invalid_request' }, 400);

  const db = env.DB;
  const nowSec = Math.floor(nowMs / 1000);
  // The per-phone limit is the real brute-force defence: a caller can mint unlimited install IDs.
  if (!(await withinLimit(db, 'restore-phone', phone, 5, HOUR, nowSec))
      || !(await withinLimit(db, 'restore-device', device, 10, HOUR, nowSec))
      || !(await withinLimit(db, 'restore-ip', clientIp(request), 60, HOUR, nowSec))) {
    return tooManyRequests();
  }

  const nowIso = new Date(nowMs).toISOString();
  const payment = await db
    .prepare(
      `SELECT payment_id, install_id, rebinds_used FROM payments
       WHERE phone = ? AND refunded = 0 AND install_id IS NOT NULL
       ORDER BY created_at DESC LIMIT 1`)
    .bind(phone)
    .first();
  if (!payment) return json({ result: 'not_found' });

  if (payment.install_id === device) {
    await grantStatement(db, device, payment.payment_id, nowIso).run();   // heal a missing/revoked row
    return json({ result: 'restored' });
  }
  if (payment.rebinds_used >= MAX_REBINDS) return json({ result: 'limit_reached' });

  // Compare-and-swap: the first UPDATE only matches while the payment is still bound to the old
  // install with budget left, so two racing restores cannot both win.
  const results = await db.batch([
    db.prepare(
      `UPDATE payments SET install_id = ?, rebinds_used = rebinds_used + 1
       WHERE payment_id = ? AND install_id = ? AND rebinds_used < ? AND refunded = 0`)
      .bind(device, payment.payment_id, payment.install_id, MAX_REBINDS),
    db.prepare(
      `UPDATE devices SET status = 'revoked'
       WHERE install_id = ? AND payment_id = ?
         AND EXISTS (SELECT 1 FROM payments WHERE payment_id = ? AND install_id = ?)`)
      .bind(payment.install_id, payment.payment_id, payment.payment_id, device),
    grantStatement(db, device, payment.payment_id, nowIso),
  ]);

  if (results[0].meta?.changes === 1) {
    log('restore_rebound', { payment_id: payment.payment_id });
    return json({ result: 'restored' });
  }
  const current = await db
    .prepare('SELECT install_id FROM payments WHERE payment_id = ?').bind(payment.payment_id).first();
  return json({ result: current?.install_id === device ? 'restored' : 'limit_reached' });
}

// ---------------------------------------------------------------- POST /admin/reset

async function postAdminReset(request, env, nowMs) {
  const secret = env.ADMIN_SECRET;
  if (!secret || secret.length < 24) return json({ error: 'admin_disabled' }, 503);   // fail closed

  const db = env.DB;
  const nowSec = Math.floor(nowMs / 1000);
  if (!(await withinLimit(db, 'admin-ip', clientIp(request), 20, HOUR, nowSec))) return tooManyRequests();

  const bearer = (request.headers.get('authorization') || '').replace(/^Bearer\s+/i, '');
  if (!(await secretsMatch(bearer, secret))) return json({ error: 'unauthorized' }, 401);

  const body = await readJson(request, MAX_JSON_BYTES);
  const nowIso = new Date(nowMs).toISOString();

  switch (body?.action) {
    case 'unbind_payment': {            // give the buyer a fresh self-service restore
      if (typeof body.payment_id !== 'string') return json({ error: 'invalid_request' }, 400);
      const res = await db.prepare('UPDATE payments SET rebinds_used = 0 WHERE payment_id = ?')
        .bind(body.payment_id).run();
      return json({ ok: true, changed: res.meta?.changes ?? 0 });
    }
    case 'force_unlock': {              // "I paid but it did not unlock", or a missed webhook
      if (!isInstallId(body.install_id) || typeof body.payment_id !== 'string') {
        return json({ error: 'invalid_request' }, 400);
      }
      const refunded = await db.prepare('SELECT refunded FROM payments WHERE payment_id = ?')
        .bind(body.payment_id).first();
      if (refunded?.refunded === 1) return json({ error: 'payment_refunded' }, 409);
      const prior = await db.prepare('SELECT install_id FROM payments WHERE payment_id = ?')
        .bind(body.payment_id).first();
      await db.batch([
        db.prepare(
          `INSERT OR IGNORE INTO payments (payment_id, link_ref, install_id, created_at)
           VALUES (?, 'manual', ?, ?)`).bind(body.payment_id, body.install_id, nowIso),
        db.prepare('UPDATE payments SET install_id = ? WHERE payment_id = ? AND refunded = 0')
          .bind(body.install_id, body.payment_id),
        db.prepare(
          `UPDATE devices SET status = 'revoked'
           WHERE install_id = ? AND payment_id = ? AND install_id <> ?`)
          .bind(prior?.install_id ?? '', body.payment_id, body.install_id),
        grantStatement(db, body.install_id, body.payment_id, nowIso),
      ]);
      log('admin_force_unlock', { payment_id: body.payment_id });
      return json({ ok: true });
    }
    case 'revoke': {
      if (!isInstallId(body.install_id)) return json({ error: 'invalid_request' }, 400);
      await db.prepare(
        `INSERT INTO devices (install_id, status) VALUES (?, 'revoked')
         ON CONFLICT(install_id) DO UPDATE SET status = 'revoked'`).bind(body.install_id).run();
      log('admin_revoke');
      return json({ ok: true });
    }
    default:
      return json({ error: 'unknown_action' }, 400);
  }
}

// ---------------------------------------------------------------- router

const ROUTES = {
  '/status': { GET: getStatus },
  '/pay': { GET: getPay },
  '/restore': { POST: postRestore },
  '/webhooks/razorpay': { POST: postWebhook },
  '/admin/reset': { POST: postAdminReset },
};

export function createApp({ now = () => Date.now(), fetchFn = (...args) => fetch(...args) } = {}) {
  return async function handle(request, env, ctx) {
    try {
      const { pathname } = new URL(request.url);
      const route = ROUTES[pathname];
      if (!route) return json({ error: 'not_found' }, 404);
      const handler = route[request.method];
      if (!handler) return json({ error: 'method_not_allowed' }, 405, { allow: Object.keys(route).join(', ') });

      const nowMs = now();
      // Opportunistic cleanup of expired rate-limit rows (about 1 request in 50).
      if (ctx?.waitUntil && Math.random() < 0.02) {
        ctx.waitUntil(purgeExpiredLimits(env.DB, Math.floor(nowMs / 1000)));
      }
      return await handler(request, env, nowMs, fetchFn);
    } catch (err) {
      // A 500 on the webhook route makes Razorpay retry, which is exactly what we want for a DB error.
      log('unhandled_error', { message: String(err?.message || err) });
      return json({ error: 'internal' }, 500);
    }
  };
}

const app = createApp();
export default { fetch: (request, env, ctx) => app(request, env, ctx) };
