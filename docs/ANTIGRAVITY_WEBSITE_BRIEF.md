# Paste into Antigravity: Lockout Gate launch website

Build a polished, accessible, mobile-first static marketing website for Lockout Gate, an Android app from Parvaz AI. Deliver source and deployment instructions. Do not deploy or change any existing VPS service without the owner's review.

The product is being prepared for closed testing. Do not claim it is already on Google Play. Primary CTA before approval: “Join the test” linking to the real test URL only when supplied; until then show “Testing opens soon” without a fake form. After launch replace it with the verified Google Play link.

Visual direction: quiet off-white backgrounds, dark ink text, violet accent matching the existing app, generous whitespace, rounded panels, crisp typography, strong contrast and restrained animation with reduced-motion support. No generic AI imagery, stock dashboards, fake testimonials or invented download counts. Use real screenshots from the approved preview. Avoid copyrighted anime imagery from the developer's personal background.

Pages:
1. Home: hero “More focus. Better habits.” Subheading: “Choose distracting apps, take intentional breaks, and keep your daily habits in one place.” Add benefit cards for app selection, measured usage breaks, habits and on-device privacy.
2. How it works: choose apps → read permission explanations → enable voluntarily → start using focus/break controls. Explain that users can always pause, disable permissions and uninstall.
3. Privacy: render docs/PRIVACY_POLICY.md as a readable permanent public page at /privacy. Confirm publisher/support details before deployment. No PDF-only policy.
4. Support: permission troubleshooting, battery-management limitations, resetting local data, and mailto:parvazai9717@gmail.com. Confirm the address first.
5. FAQ: no account; no photo verification; no remote AI; no cloud backup; no ads or payments in the free launch version; premium may arrive later through an optional update.

Privacy constraints: no analytics, tracking pixels, ad scripts, external font requests, unnecessary cookies, embedded social widgets or collection form. Use self-hosted or system fonts. A mailto support link needs no backend. All pages should be keyboard accessible with visible focus states, descriptive page titles, useful alt text and responsive layouts. Target good Lighthouse accessibility and performance, then record actual results instead of claiming them in advance.

Use the final app's logo and real screenshots. Keep screenshot paths configurable. Include metadata, Open Graph preview, sitemap, robots.txt and a useful 404 page. Do not expose the VPS IP, admin panel, API endpoints or credentials.

Content:
- Free launch period: first three months from actual public launch, no ads or payments.
- Premium is planned; do not show pricing, checkout or a paid feature as available yet.
- Describe it as a personal self-control aid, not child surveillance, medical treatment, or an impossible-to-disable lock.
- Exact data behavior must agree with the final Android build, not old Python prototype docs.

Acceptance: all navigation works, no dead/fake download button, privacy URL public without login, no unwanted network requests, readable at 320px width and 200% text zoom, and clear handoff with build/deploy commands. Ask only for the domain and real store/test links when those are needed. The site can be hosted as static files; do not add another always-on server.
