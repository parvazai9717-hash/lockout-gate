# Timer correction and owner test

Candidate: 0.3.1, version code 4. The agent prepares the existing Studio project; the owner presses Run. Do not uninstall or clear the owner's app data.

## Corrected counting

Android reports foreground changes per activity, not just per application. Previously, resuming Instagram's next activity and subsequently pausing/stopping its old activity removed Instagram from the foreground set entirely. A five-minute synthetic sequence with a screen change at two minutes reproduced a two-minute reading. The new matcher tracks activity class names separately and counts their union, so simultaneous resumed screens never double-charge.

Usage polling replays the recent two minutes rather than permanently discarding late Android events at each five-second checkpoint. Cumulative reconciliation can catch up more than one poll's usage, bounded by monotonic elapsed time for the measurement segment. Already charged time never decreases. Selecting a different blocked-app set rebases measurement without refunding usage or charging newly selected apps retroactively. Existing active breaks migrate from their latest checkpoint without clearing usage, quotas or selections.

Screen-off and visible keyguard intervals do not count. The service flushes accounting when the screen turns off and resumes its normal polling when it turns on. The notification and break-page counter read the same persisted remaining value, updated about every five seconds while awake.

## Five-minute owner check

1. Press Studio Run to update the existing preview. Ensure Instagram is selected and Usage Access/Accessibility are on.
2. Open Take a break. Keep "Record timer log on this phone" on and start a 10-minute break.
3. Use Instagram continuously for five minutes, including switching Feed/Reels/screens. Compare with a separate stopwatch.
4. After the next refresh, approximately five minutes should remain. A few seconds of polling/display difference is expected; losing several minutes is not.
5. Spend 30 seconds in an unselected app, then 30 seconds with the screen off. Those periods should not reduce the selected-app allowance.
6. Open Take a break -> Timer log -> Save log if a discrepancy remains. Choose where to save the CSV. Nothing is automatically sent anywhere.

The log retains 240 checks (about 20 minutes of continuous five-second polling). Columns: sample wall timestamp, local break number, measured usage in the current segment, total charged usage, remaining usage, event count and query duration, all durations in milliseconds. Measured usage can restart when app selections change; charged usage must remain monotonic. Logging can be switched off and cleared.

## Limits to verify

The exact report from the owner's Samsung has not yet been reproduced with its live event stream. Activity-class handling and delayed-event regressions reproduce plausible causes; passing those tests is not a completed real-phone accuracy test. Events delayed more than the two-minute replay window, an app using multiple instances of the same activity class, incomplete OEM usage events, long history gaps and service termination still need real-device checks. No frame-rate or battery improvement is claimed. The app remains a testing candidate until owner acceptance and the separate public-release requirements are completed.
