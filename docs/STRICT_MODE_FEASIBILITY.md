# Preserve strict protection: implementation decision

Checked 20 October 2026. The owner explicitly considers resistance to uninstalling and Accessibility deactivation essential. Do not treat a version that omits these as a completed equivalent release.

## What we can establish

1. Device Admin is available to our app; it is not exclusive to AppBlock. Its user-activated administrator registration adds Android's requirement to deactivate the administrator before normal uninstall. AppBlock documents using this permission for its uninstall protection.
2. Ordinary Device Admin does not grant a veto over its own deactivation. `DeviceAdminReceiver.onDisableRequested` supplies a warning message, not a Boolean rejection. It also does not keep a separate Accessibility service enabled. Do not ship an admin toggle and advertise either of those guarantees.
3. The existing personal preview already supplies the owner's tested Accessibility/App info/removal redirects. Keep that implementation intact while assessing the public version. It is an Accessibility implementation, not Device Admin, and has bypass limits.
4. AppBlock documents an additional Settings-blocking option. That is evidence of its advertised feature, not evidence of its internal source code, a policy exception or Google's reasoning in approving its listing.
5. Google Play expressly restricts Accessibility-based prevention of disabling/uninstalling apps/services, with parental-control and enterprise exceptions. We have not found a published timed adult self-control exception. User opt-in, a timer and recovery are necessary product safeguards; they do not by themselves establish that exception.

## Candidate design and unresolved gate

The candidate is a clearly explained, voluntary, finite Strict Mode: explicit confirmation of the end time, optional Android Device Admin activation for uninstall friction, the existing cached server countdown, emergency communication, and user-owned recovery. Do not request password reset, data wipe, camera control or other unrelated admin policies.

This candidate still needs a permitted way to resist Accessibility deactivation for the chosen interval. Device Admin alone does not supply it. Google policy clarification and an accurately declared test submission are needed before claiming the desired complete consumer Play release is feasible. Support guidance is not a guarantee of subsequent review approval.

Do not evade review with remote feature flags, switch guards on after review, mislabel adult self-control as parental/enterprise administration, or move the same interference to another permission merely to avoid the Accessibility rule. Do not automatically grant admin access or drive system activation on the owner's phone.

A genuinely managed device can use stronger device/profile-owner APIs, including uninstall policies and package suspension. Ordinary Device Admin activation does not grant those roles. Managed-device enrollment is a different product/setup requirement, not a transparent upgrade for ordinary Play users. Do not provision or reset the owner's phone as part of this investigation.

If a normal consumer Play implementation cannot retain the essential protection, report that conflict to the owner before selecting a different distribution route or weakening the feature. The working personal build remains available while this is resolved.

## Draft request for Google Play developer support

Prepared only. No message has been sent. Sending requires the owner's explicit authorization and the appropriate developer account/support access.

Subject: Policy clarification for voluntary timed self-control and uninstall protection

We are developing Lockout Gate, an Android self-control app for adults using their own personal phones. It is not an accessibility tool, parental-control app or enterprise device controller.

The user deliberately chooses and confirms a finite focus interval. During it, selected distracting apps are blocked, the end time remains visible, calls and emergency communication remain available, and recovery is explained before activation. We intend to request only permissions necessary for the advertised feature, with prominent disclosure and consent.

We would like to retain resistance to disabling the blocking service or uninstalling the app during that interval. Before submitting, please clarify:

1. May this consumer self-control app request ordinary Android Device Admin activation solely to add the system's normal uninstall protection, without password, wipe, camera or unrelated administrator policies, while leaving Android's administrator-deactivation controls available?
2. The Accessibility policy restricts preventing users disabling/uninstalling apps/services and names parental-control and enterprise exceptions. Does it permit any finite, explicitly user-initiated self-control case that restricts access to our own Accessibility deactivation or removal screens? If not, is there an approved Android API or documented product pattern for that particular requirement?
3. What declaration, demonstration and recovery evidence should accompany an accurately described test submission for a permitted implementation?

We are not relying on the presence of similar apps as approval of our implementation, and will not hide functionality from review. We can provide a demonstration of the exact proposed behavior.

## Primary references

- AppBlock permissions and timed Strict Mode: https://appblock.app/help/android/settings/
- AppBlock Settings blocking and recovery: https://appblock.app/help/android/blocking/
- Android Device Admin activation/uninstall: https://developer.android.com/work/device-admin
- Android deactivation callback: https://developer.android.com/reference/android/app/admin/DeviceAdminReceiver#onDisableRequested(android.content.Context,android.content.Intent)
- Device/profile-owner uninstall APIs: https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setUninstallBlocked(android.content.ComponentName,java.lang.String,boolean)
- Google Play Accessibility policy: https://support.google.com/googleplay/android-developer/answer/16558241
