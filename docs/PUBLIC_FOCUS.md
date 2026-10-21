# Public Keep Me Out accounts - 0.3.2

0.3.3 follow-up: setup and recovery first check the service's `public_focus_accounts` health capability. If the backend is still old, the app explains that a service update is needed before generating another recovery key. A read-only live check on 21 October confirmed the service was reachable but this capability was absent; remote publication/deployment is still pending explicit user approval after automatic approval review rejected the main push.

At the owner's request, setup now includes an explicit **Copy key** button. No recovery key is retained in app storage. Only tapping Copy puts it in the system clipboard, marked sensitive, with best-effort cleanup of that specific copy after one minute. Cleanup never erases a different clipboard item; background/OS restrictions can prevent cleanup, so the user should paste it to a safe place outside the phone and replace/clear the clipboard afterward. The in-app recovery action is labeled **Use a saved recovery key** and explains that it is for an account whose setup previously completed. Duplicate setup instructions are removed.

Public preview and release create an anonymous focus account per installation. No email is requested. The personal debug variant continues using the original device key, device secret and shared admin recovery endpoints. Public builds still contain no personal device key or fixed identity.

## Owner test

Update the existing Public Test from Studio with `publicPreview`; do not uninstall it. Keep Me Out now has an opaque rounded panel over the custom photo. Set up the account, write the LG1 recovery key outside the phone, confirm it is saved and enable this app's Accessibility service. Start a five-minute test. Confirm that the countdown continues offline, calls and WhatsApp remain available, normal breaks and pauses cannot end it, and only the saved key permits early exit. Android permission controls and uninstall remain available.

After expiry or a valid early exit, an idle account can be deleted from the tab. Local habits, wallpaper, selection and break data remain. Recovering an account with the saved key rotates device access and signs the former installation out of server access; any active deadline remains unchanged. The old installation may continue enforcing its cached countdown offline until that deadline. A separate installation without the recovery key cannot access that account. Losing app data/permissions can still defeat local enforcement; this is not tamper-proof device management.

## Server behavior

The additive `public_focus_account` table holds a random device identifier, SHA-256 digests of the high-entropy device/recovery credentials, and current lock timestamps. It also holds a persistent five-failure, ten-minute recovery cooldown shared across recovery and early exit for that account. Credentials are not logged or stored in plaintext. Ordinary state/start requests need only that installation's device credential; admin recovery is never returned by those endpoints.

Public routes: POST `/v1/public/enroll`, POST `/v1/public/recover`, POST `/v1/public/focus/state`, POST `/v1/public/focus/start`, POST `/v1/public/focus/end`, POST `/v1/public/account/delete`. Public identifiers and credentials are carried in bodies/headers rather than state URL queries. Only these exact routes bypass the legacy shared-device-key middleware; they enforce their own account authentication. JSON body, security-header and no-store safeguards still apply. Legacy routes and tables are untouched.

Start never shortens an active lock. Re-enrollment cannot change a recovery credential during an active lock. Recovery requires the saved key and changes device access without shortening the lock. Deletion requires the device credential and an inactive lock. A locked account must finish or be explicitly ended with its saved recovery key before deletion. Server deletion removes that account and its current lock record, rather than clearing local habits or analytics.

Enrollment/recovery has a persistent cap of 20 attempts per directly connected network peer per hour. Peer addresses are HMAC-hashed with the configured personal device key, or a dedicated `LOCKOUT_PUBLIC_RATE_SECRET` on a standalone public service; raw peer addresses are not retained in this rate table. The server does not trust caller-supplied forwarding headers. Behind a reverse proxy the cap can be shared by clients, so review trusted-proxy/edge limits before launch. Expired rate entries are pruned on the next setup/recovery attempt. The default capacity is 10,000 public accounts, configurable with `LOCKOUT_PUBLIC_MAX_ACCOUNTS`. These conservative defaults support initial testing, not a capacity claim.

Before creating public tables, startup makes an integrity-checked, non-overwritten SQLite snapshot at `gate.db.before-public-focus-v1.bak` in the existing persistent volume. Public code adds no new dependency and does not rotate the personal service's credentials. Deployment verification must confirm the snapshot log and preserve a disposable pre-deployment lock's exact timestamps. Never test admin brute-force throttling on the owner's personal account.

## Remaining launch work

Real-device acceptance, final hosted privacy/support/deletion pages, signed upgrades, a hosting/rate-limit review, coordinated historical personal-key rotation and store declarations/testing remain. The anonymous recovery-key account is not email login or a cross-device habit/photo backup. No Google Play approval is implied by passing local tests.
