# R8 rules for the release build.
#
# Deliberately almost empty. This app has no reflection, no serialisation, no dependency injection
# and no third-party runtime libraries beyond AndroidX and Compose - all of which ship their own
# consumer rules. Every `-keep` added here is a hole in the shrinker, so each one needs a reason.

# Keep stack traces readable. There is no crash reporting in this app on purpose
# (docs/adr/0007-minimal-permission-set.md), so the only way anyone ever sees a stack trace is
# `adb logcat` on their own device - which makes line numbers the difference between a diagnosable
# report and "it stopped working".
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Nothing here is entered by name from outside the app: MainActivity and MonitoringService are named
# in the manifest, which R8 reads, and the service is not exported. No other entry points exist.
