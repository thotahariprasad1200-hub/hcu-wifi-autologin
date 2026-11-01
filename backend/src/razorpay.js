// Minimal Razorpay client: only what the licensing flow needs (create a payment link).
// Key ID / secret come from Worker secrets and are never logged or returned to callers.

const API_BASE = 'https://api.razorpay.com/v1';

/**
 * Creates a standard (non-UPI-only) payment link. Standard links work in Razorpay test mode,
 * which the release test plan depends on, and still offer UPI at checkout.
 *
 * reference_id must be unique per link (Razorpay rejects reuse), so the caller passes a fresh
 * nonce and keeps its own reference_id -> install_id mapping.
 */
export async function createPaymentLink(env, fetchFn, params) {
  const response = await fetchFn(`${API_BASE}/payment_links`, {
    method: 'POST',
    headers: {
      authorization: 'Basic ' + btoa(`${env.RAZORPAY_KEY_ID}:${env.RAZORPAY_KEY_SECRET}`),
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      amount: params.amountPaise,
      currency: 'INR',
      accept_partial: false,
      reference_id: params.referenceId,
      description: params.description,
      expire_by: params.expireBy,
      notify: { sms: false, email: false },
      reminder_enable: false,
      notes: { install_id: params.installId },
    }),
    signal: AbortSignal.timeout(8000),
  });

  if (!response.ok) {
    // Deliberately drop the response body: it can echo request details and is of no use to the app.
    throw new Error(`razorpay_payment_link_failed status=${response.status}`);
  }

  const link = await response.json();
  if (typeof link.short_url !== 'string' || !link.short_url.startsWith('https://')) {
    throw new Error('razorpay_payment_link_bad_response');
  }
  return { id: link.id, shortUrl: link.short_url };
}
