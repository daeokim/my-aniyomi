# Personal build validation

Validated app commit: `c869a323817a30663072eeb16a5a077c9fd99b53`.
Successful private run: https://github.com/daeokim/my-aniyomi/actions/runs/39176044666

- Android `:app:assembleRelease :app:testReleaseUnitTest -Pdisable-code-shrink`: passed.
- Android JVM tests: 6 passed, 0 failures/errors/skips.
- Compiled production Java fold layout policy: 199,827 assertions passed.
- Static integration contracts: 5 passed. These are not device tests.
- Arm64 release APK signed offline with a persistent personal RSA 3072 key; APK Signature Scheme v2/v3 verification passed.
- Binary manifest checked: original application ID `xyz.jmir.tachiyomi.mi`, versionCode 13201, release (not debuggable), resizable app and reader.
- Native APK ABI set checked: arm64-v8a only.
- Signing material is excluded from Git; a private signing backup is required for future updates.

The unavailable JitPack FlexibleAdapter dependency is built from the exact pinned upstream sources under `vendor/flexible-adapter`; see its UPSTREAM.md and LICENSE. Code/resource shrinking is disabled for personal builds because the upstream Kotlin 2.4.0 toolchain produced R8 metadata compatibility warnings.

No Fold8 device/emulator installation or live source/playback test has been performed. Folding transitions, image zoom/retry, physical reading order and video playback still require device validation. Official Aniyomi uses a different signing key, so first replacement may require backup, uninstall, install and restore. No device application was installed or removed by these build steps.
