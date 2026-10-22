# 0.3.5 security and reliability review

Local candidate, versionCode 8. Prepared 21-22 October 2026. Publication, backend deployment and device acceptance are separate steps.

## Changes

- Server validation failures return a generic 422 response, preventing malformed requests from echoing submitted recovery credentials in framework diagnostics. Four regression cases cover public enrollment/recovery, legacy focus and malformed JSON; no-store remains enforced.
- Container startup disables routine Uvicorn access logs, which otherwise include peer addresses and legacy identity query strings. Startup/error diagnostics remain. This takes effect only after deployment; existing logs and reverse-proxy retention are unaffected.
- Android sends the legacy installation-secret header only to personal focus routes. Health and public requests do not carry that extra credential. HTTPS configuration and disabled redirects remain; a 45-second overall call deadline bounds stalled calls.
- Starting a lock disables account enrollment, recovery and deletion until the request completes. Start rechecks active state and Accessibility availability.
- The lock screen avoids overlapping state refreshes, repeated early-exit submissions and duplicate Home navigation when timer/network completion coincide. Coroutine cancellation is propagated normally.
- Bursts of preference changes schedule one status redraw rather than repeated immediate redraws and permission queries. No allowance or timer arithmetic changed.

## Verification

- 95 JVM/Robolectric tests passed in personal debug and 95 in publicPreview: 190 executions, zero failed or skipped.
- 73 server tests passed with disposable databases. Existing framework deprecation warnings remain (145 warnings).
- Android lint: zero errors; 173 debug and 171 publicPreview warnings remain. These include hardcoded text, localization, layout/overdraw, resource, preference and dependency-update suggestions. This is not a warning-free release or an Android dependency vulnerability certification.
- Debug APK, publicPreview APK, optimized release AAB and debug instrumentation APK built successfully. Instrumentation was compiled, not installed/run on the owner's phone.
- Text-encoding check passed across 141 files. Git whitespace check passed.
- pip-audit found no known vulnerabilities in 37 resolved server packages at scan time. This is a known-vulnerability check, not proof of absence of vulnerabilities.
- Built public APK and release bundle were scanned for the configured personal device key and fixed identity: neither was found. Build flags and compiled Accessibility metadata preserve personal-only guards; public builds cannot retrieve window content.
- Hashes of the three previously preserved personal recovery artifacts still match.

## Review coverage and limits

Reviewed focus enrollment/recovery/deletion and credential storage, server authentication and transport bounds, SQLite transactions/allowance logic, Android lifecycle/network handling, all three main feature areas, Accessibility/usage accounting, break/pause rules, habit storage, wallpaper handling, diagnostics, manifest/build boundaries and website disclosures. Existing tests cover clock changes, offline focus timing, usage accounting and allowance behavior. No production account, real recovery key or real monthly allowance was used for new tests.

This is a source and automated-test review. It does not prove every OEM/device behavior, smooth frame timing, production capacity or store acceptance. No production penetration/load test, Android dependency advisory scan, signed-upgrade device test or live deployment test of 0.3.5 was performed.

## Required before a public release

1. Finalize retention and deletion. Migration snapshots have no automatic age expiry in reviewed code. Inspect container/proxy logs and all backups. Seven-day logs and thirty-day backups are proposals, not implemented promises. Test restore and deletion handling before pruning data.
2. Complete secure off-device deletion ownership verification. Account ID/setup date are insufficient proof. Current in-app deletion is disabled during active locks; a discoverable request path during those locks needs product/compliance resolution without silently bypassing the owner's lock rules.
3. Review public enrollment/recovery limits behind EasyPanel. The 20/hour directly connected peer limit may be shared by many clients behind a reverse proxy. Do not trust arbitrary forwarded IP headers or remove limits as a shortcut.
4. Rotate the historical personal shared device key through a coordinated private-phone update before broader release. Public builds do not contain that key. Rotating it independently would break the owner's existing personal app.
5. Run device acceptance: all three tabs, natural expiry and immediate restart, offline/reboot timing, calls/WhatsApp, permission changes, large text, wallpaper readability, battery behavior and signed upgrades. Use disposable accounts for quota/recovery/deletion tests.
6. Finalize consistent website and in-app privacy disclosures, Data safety and Accessibility declarations, signing, developer verification and the account's testing requirements. The website is now https://lockout-gate.netlify.app/; the older D: website review is superseded by the current OneDrive v0.3.4 site. Its current FAQ/copy controls worked in browser checks, but privacy retention and deletion details remain unfinished.

The three tabs, two local monthly pauses, two server monthly early exits, one-way Permanent Mode, cached offline countdown, wallpapers, habits and separate personal/public packages remain intact. This candidate does not add a schema migration or change the allowance rules.
