# Licensing backend (Cloudflare Worker + D1)

Takes payments for the Wi-Fi Auto-Login one-time unlock and tells the app whether an install is
paid. Free tier only, no runtime dependencies, no secrets in this repo.

```
app  --GET /pay-->  Worker --creates--> Razorpay payment link  <-- buyer pays (UPI)
app  --GET /status-> Worker <--webhook payment_link.paid-- Razorpay   (HMAC verified, idempotent)
```

## Deploy (one time)

```bash
cd backend
npm install                                   # installs wrangler (dev tool only)
npx wrangler login
npx wrangler d1 create wifi-licenses          # copy the printed database_id into wrangler.toml
npx wrangler d1 execute wifi-licenses --remote --file=schema.sql
npx wrangler secret put RAZORPAY_KEY_ID           # paste at the prompt; never commit or chat these
npx wrangler secret put RAZORPAY_KEY_SECRET
npx wrangler secret put RAZORPAY_WEBHOOK_SECRET   # you choose this; paste the same value in Razorpay
npx wrangler secret put ADMIN_SECRET              # 24+ random characters; the admin route refuses weaker
npx wrangler deploy                               # prints https://<name>.<you>.workers.dev
```

Start with Razorpay **test-mode** keys. Switch to live keys only after the end-to-end test passes.

### Razorpay dashboard -> Webhooks

* URL: `https://<your-worker>.workers.dev/webhooks/razorpay`
* Secret: the same value you gave `RAZORPAY_WEBHOOK_SECRET`
* Events to subscribe to: **`payment_link.paid`** and **`refund.processed`**

> **Correction to the PRD/SOP:** both documents say to subscribe to `payment.refunded`. Razorpay has
> no such event (see its [webhook event list](https://razorpay.com/docs/webhooks/all)); refunds are
> reported as `refund.created`, `refund.processed`, `refund.failed` and `refund.speed_changed`.
> This backend acts on `refund.processed`. Subscribing to `payment.refunded` would silently never
> revoke a refunded purchase.

## Routes

| Route | Purpose |
|---|---|
| `GET /status?device=ID` | `{status: trial\|paid\|revoked, amount_paise}`. Strongly consistent, never cached. |
| `GET /pay?device=ID` | `{status:"paid"}` or `{status:"link", url, amount_paise, expires_at}`. Reuses an unexpired link. |
| `POST /restore {device, phone}` | `{result: restored\|not_found\|limit_reached}`. One self-service move per payment. |
| `POST /webhooks/razorpay` | `payment_link.paid` unlocks, `refund.processed` (full refund) revokes. |
| `POST /admin/reset` | `Authorization: Bearer ADMIN_SECRET`. Actions below. |

Admin actions (JSON body):

```
{"action":"unbind_payment","payment_id":"pay_xxx"}                       # buyer gets another restore
{"action":"force_unlock","install_id":"<uuid>","payment_id":"pay_xxx"}   # missed webhook / support case
{"action":"revoke","install_id":"<uuid>"}
```

```bash
curl -sS https://<worker>/admin/reset -H "Authorization: Bearer $ADMIN_SECRET" \
  -H 'content-type: application/json' -d '{"action":"unbind_payment","payment_id":"pay_xxx"}'
```

## Where this differs from the PRD (and why)

1. **`reference_id` is a per-link nonce, not the install ID.** Razorpay requires `reference_id` to be
   unique per payment link and rejects reuse
   ([create-link docs](https://www.razorpay.com/docs/api/payments/payment-links/create-standard)).
   With the install ID as reference, the second link for any install (expired link, repurchase after
   refund) would fail. The `payment_links` table maps nonce -> install ID; `notes.install_id` is a
   fallback.
2. **Refund event is `refund.processed`**, not `payment.refunded` (see above). Only a *full* refund
   revokes; a partial refund leaves the unlock in place.
3. **Restore looks the phone number up in our own ledger**, filled from the signature-verified
   webhook, instead of calling the Razorpay API again. Same result, one less secret use, no extra
   network hop.
4. **The rebind budget lives on `payments`, not `devices`.** The PRD limit is "1 rebind per payment";
   keeping the counter on the payment makes that true regardless of which install holds it.
5. **Rate limits are per phone number and per IP, not only per install ID.** A caller can mint
   unlimited install IDs, so a per-install limit alone does not slow down guessing phone numbers.
   IP limits are deliberately generous because a whole campus shares one NAT address.
6. **Standard payment links, not "UPI links".** UPI-only links work in live mode only, which would
   make the test-mode rehearsal in the test plan impossible. Standard links still offer UPI.

## Known limits

* **Restore is protected only by knowing the buyer's phone number.** Anyone who knows it could use
  the one self-service move to take over the unlock (the buyer would then need you to run
  `unbind_payment`). Rate limits make bulk guessing impractical, and the stake is one Rs 50 licence,
  but it is a real property. A second factor (for example the last 4 characters of the payment ID
  from the receipt) is the natural hardening if abuse ever appears.
* `not_found` vs `limit_reached` reveals whether a number has bought. Low sensitivity; rate limited.
* A partial refund does not revoke. Use `/admin/reset` `revoke` if you want it to.

## Tests

```bash
npm test      # Node 22+, no installs needed; runs the Worker against real SQLite semantics
```

The suite covers signature checking (forged, missing, wrong secret), idempotent replay, refund-before-
payment ordering, rebind races, rate limits and admin auth. The Razorpay API is faked, so this proves
the logic, not Razorpay's behaviour: run one end-to-end **test-mode** payment before going live.
