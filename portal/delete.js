'use strict';
const form = document.getElementById('delete-form');
const keyInput = document.getElementById('key');
const submit = document.getElementById('submit');
const result = document.getElementById('result');
form.addEventListener('submit', async event => {
  event.preventDefault();
  if (submit.disabled || !form.reportValidity()) return;
  const key = keyInput.value.trim();
  keyInput.value = '';
  const parts = /^LG1\.([0-9a-fA-F-]{36})\.[0-9a-f]{32}$/.exec(key);
  if (!parts) { result.textContent = 'Enter the complete saved LG1 recovery key.'; return; }
  submit.disabled = true;
  result.textContent = 'Sending your request…';
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 45000);
  try {
    const response = await fetch('/v1/public/account/delete-with-key', {
      method: 'POST', credentials: 'omit', cache: 'no-store', redirect: 'error',
      headers: {'Content-Type': 'application/json'}, signal: controller.signal,
      body: JSON.stringify({device_id: parts[1], recovery_key: key})
    });
    const body = await response.json();
    if (response.ok && body.deleted === true) {
      result.textContent = 'Your live focus account has been deleted.';
      form.hidden = true;
    } else if (response.ok && body.pending === true && Number.isSafeInteger(body.scheduled_at)) {
      result.textContent = 'Deletion is pending until the active lock finishes, currently ' +
        new Date(body.scheduled_at * 1000).toLocaleString() + '. No new lock can start. Deletion runs within about one minute after the lock ends while the service is operating.';
      form.hidden = true;
    } else {
      result.textContent = response.status === 429 ? 'Too many attempts. Please wait before trying again.' :
        response.status === 401 ? 'The key was not accepted, or this account is already deleted.' :
        'The request could not be completed. Please try again later.';
    }
  } catch (_) {
    result.textContent = 'No confirmation was received. Check your connection. If you retry and the account was already deleted, the key will no longer be accepted.';
  } finally { clearTimeout(timeout); submit.disabled = false; }
});
window.addEventListener('pagehide', () => { keyInput.value = ''; });
