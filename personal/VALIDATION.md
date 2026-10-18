# Validation record — 2026-10-19 KST

Base: `aniyomiorg/aniyomi@97414446b8a95994c72dd33c41c971a89d4d25b8`.

## Executed checks

`bash tools/test-personal.sh`

- Production `FoldLayoutPolicy.java` compiled with the JDK 17 compiler and executed.
- 199,827 assertions passed, including 500 randomized three-chapter scenarios with both pairing alignments.
- Five Python static integration contract tests passed.

`python -m py_compile tools/build-personal.py personal/tests/test_contracts.py`

- Passed; build helper and integration-check Python syntax is valid.

`git diff --check`

- Passed.

## Blocked checks

`./gradlew --version`

- Gradle 8.13 could not download: `java.net.SocketException: Network is unreachable`.

`python tools/build-personal.py --check`

- Android SDK 36 is not installed in this execution environment.
- No APK was generated. Full Kotlin/Compose compilation, Android JVM unit tests, signing, installation, backup restoration and Fold8 device testing remain unverified.

## External state

- No GitHub repository was created and no app source was published.
- Automatic approval review rejected creation of a new private repository because the request did not explicitly authorize that external action.
- The separate `my-mangayomi` repository and its earlier PR were not changed by this app task.
