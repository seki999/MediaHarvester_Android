# Download recovery investigation

The website path unconditionally deleted its private task directory and requested
`--no-continue`. This explains lost progress after pause/retry. Cancellation already
deleted task data, but the UI did not warn the user. All website exceptions were
replaced by a generic message, so the reported interruption at 41.3 MiB cannot be
attributed to HTTP 403/429, timeout, or Android service termination without device logs.

The download now preserves `.part` and `.ytdl`, uses bounded fragment/HTTP retries,
refreshes extraction on transient failures, and aborts on missing fragments.
Fragment identities include selected format, codec, size, protocol and the exact
fragment list. Changed or unverifiable manifests and malformed journals isolate
old data in `.incompatible-*` under the task directory. Explicit cancellation
removes it. Signed fragment URL changes can therefore cause a conservative restart;
this is deliberate until stronger server validators are available. Output streams are decoded with FFmpeg error checking before publication. This is
not a cryptographic source-integrity guarantee. Full decoding costs time and power
and is subject to the existing 30 minute FFmpeg timeout.

Existing HTTP tests cover disconnect/reconnect, cancellation, validator changes,
malformed ranges, and simulated metadata commit crashes. Room instrumentation tests
cover database reopen and recovery. New ResumeGuard unit tests cover retained part
and journal files, changed manifests, and legacy unvalidated partial files.

Local validation attempted:
- Gradle default cache: blocked by unwritable `/home/agent/.gradle`.
- Retried all requested tasks with `GRADLE_USER_HOME=/workspace/.gradle`:
  Gradle distribution download failed with connection refused, before compilation.
- `git diff --check` passed.

No Android SDK or emulator was found in the standard SDK locations. The supplied
YouTube video has not been downloaded end to end or tested on an Android device.
CI is responsible for unit tests, lint, debug APK, and emulator instrumentation.
