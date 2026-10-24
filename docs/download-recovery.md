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


## Follow-up: device HTTP 403 and stale engine

The user's device screenshot confirms HTTP 403 after 174.7 MiB, together with
an engine age warning for 2025.11.12 and skipped SABR-only web formats. These are
client-side observations, separate from the cloud proxy's CONNECT 403.

The APK now bundles the official 2026.10.21 zipapp, including yt-dlp-ejs, with
SHA-256 verified against the official release sums. Startup atomically upgrades
the script in noBackupFilesDir without touching tasks. The existing Android
wrapper supplies QuickJS. Settings shows the executable's actual --version and
can fetch checksum-verified stable updates. Updates cannot replace a running
engine; failed checksums leave the installed engine untouched.

403 recovery tries different supported YouTube clients and can reselect streams
at the original video height; stream identity checks still apply. No PO-token
checks are disabled. HTTP downloads use bounded 10 MiB chunks. 429 stops the
outer retry loop instead of switching clients repeatedly.

New unit coverage: engine upgrade, failed checksum, interrupted engine file,
YouTube host scoping, 403 client changes and bounded retries. The device test
asserts the actual engine version, packaged EJS, and executes QuickJS locally.
The cloud still cannot access the test video's YouTube endpoint, so an end-to-end
successful download of that video remains unverified. The initial local run lacked JDK 17. After installing JDK 17 and the proxy CA,
21 domain/downloader tests passed locally; the Android SDK was also provisioned
for local full validation. CI independently uses JDK 17.


Earlier Actions APK certificates differ (e8c49c4: 59df3573...119c3;
4339408: 7985e7ac...d178d). The old signing key was not retained by the workflow,
so a newly generated debug key cannot update that installation. CI now also
provides a parallel ARM64 verification package, com.mediaharvester.android.enginefix,
labeled MediaHarvester Engine Fix. It does not remove or read the original app's
private data. Existing 174.7 MiB partial data remains in the original app and is
not automatically migrated to the parallel package. Do not uninstall the original
to work around signature conflicts when partial data needs to be retained.
