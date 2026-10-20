# Security follow-up: 20 October 2026

The owner reports the combined Samsung protection update works. Preserve the tested preview. This follow-up changes server defenses, not Android enforcement, quotas, permanent selections, habits or focus countdowns. It does not install anything on the phone.

## Changes and checks

- Every device/admin authentication path rejects empty, whitespace-only and documented example keys. Legacy daily-budget endpoints previously accepted example credentials when environment variables were absent. Key comparisons use constant-time comparison. Correct configured clients retain their existing protocol and credentials.
- The legacy photo verifier reads at most its 20 MiB limit plus one byte into memory, rejects oversize content before verification, and runs its synchronous provider request in a worker thread. This bounds the application read; it is not a reverse-proxy upload/disk/rate limit. The current Android app does not call photo verification.
- Provider exceptions return a generic rejection rather than raw exception/provider text, which could contain private service details. The server remains fail-closed on verification failure.
- The Docker image includes the shared authentication module. Signing stores, SQLite sidecars/snapshots and local proof uploads are excluded from future Git additions. Ignoring files cannot remove a secret already present in history.
- Updated FastAPI from 0.115.6 to 0.143.5, explicitly pinned Starlette 1.9.0, and updated python-multipart from 0.0.20 to 0.0.32. The old requirements scan reported advisories in Starlette and python-multipart; the new scan reports no known vulnerabilities across its 37 resolved packages. This was a Python 3.11/Windows requirements scan, not a Linux container/base-image audit or proof that every advisory was exploitable in this application. Dependency resolution and `pip check` pass.
- 28 server tests pass, including 13 new credential/configuration, legacy compatibility, exception-redaction, bounded-read and multipart API cases. Photo verification is stubbed in tests; no real provider requests or personal photos are used. Existing focus expiry, admin throttling, device isolation and backup checks pass. Android source is unchanged by this security follow-up; the preceding 45 JVM tests, lint and builds remain the Android baseline. Deprecation warnings remain for startup hooks and the test client's httpx compatibility.

## Timed protection and AppBlock

The owner's AppBlock example is supported by AppBlock's documentation: Strict Mode can restrict removal, and Device Admin permission provides its uninstall protection. Its documentation separately describes Accessibility-based blocking of device settings. We have not inspected AppBlock's source, Play declarations or review decisions.

Android Device Admin is a separate explicitly activated permission; Android requires its deactivation before uninstall. That alone does not establish a way to prohibit its own deactivation or guarantee Play acceptance. Do not silently add or activate it on the owner's phone.

Google Play's Accessibility policy expressly restricts using that API to prevent disabling/uninstalling apps/services, with parental-control and enterprise exceptions. We found no documented timed self-control exception. An existing competitor's listing does not establish approval of our particular implementation. Equally, do not describe all timed uninstall protection as technically impossible or categorically prohibited across all Android APIs.

The tested personal preview still contains its narrow Home redirects. The existing optimized release omits them. Therefore the current release is not equivalent to the owner's requested strict behavior and must not be submitted as though this issue were settled. Keep timed Strict Mode implementation and its Play review requirements as an explicit unresolved product/release decision; do not remove the preview protection or claim approval.

Sources checked 20 October 2026:
- https://appblock.app/help/android/settings/
- https://appblock.app/help/android/troubleshoot/
- https://developer.android.com/work/device-admin
- https://support.google.com/googleplay/android-developer/answer/16558241
- https://fastapi.tiangolo.com/release-notes/

## Remaining public-release work

- Replace the personal shared master-key/master-admin setup with user-owned registration, recovery and deletion. A key shipped in an APK is extractable. Rotate the historically exposed device key with a coordinated phone update, preserving active locks and existing data.
- Review/retire legacy APIs before exposing this server to public users, including their old device-secret fallback, upload/rate limits and photo-break concurrency. The current phone does not use these routes; this patch preserves compatibility rather than quietly removing them.
- Resolve the strict-mode implementation above, publish accurate disclosures/privacy/support pages, finish device/upgrade testing, create the owner's Play account and signing identity, and complete the Console's testing/declaration requirements. Build success is not release approval.
- Deploy server changes only with the existing volume, environment and database preserved. Do not rotate working credentials or change unrelated VPS services as a side effect.

This revision is prepared and tested locally; the new server image has not yet been built/deployed on EasyPanel. Its production Linux dependencies/base image and post-deployment checks remain pending. No new phone installation is needed for these server-only changes.
