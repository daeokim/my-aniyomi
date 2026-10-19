"""Build the private replacement APK; never installs or uninstalls a phone application."""
import argparse
import hashlib
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk or not (Path(sdk) / 'platforms/android-36/android.jar').is_file():
        sys.exit('Android SDK 36 is required. Install it in Android Studio and set ANDROID_HOME. No APK was built.')
    if args.check:
        print('Android SDK 36 found; Gradle dependencies and Android execution still require validation.')
        return
    subprocess.run(['bash', str(ROOT / 'tools/test-personal.sh')], cwd=ROOT, check=True)
    signing = ROOT / 'personal/signing'
    signing.mkdir(exist_ok=True, mode=0o700)
    keystore, config = signing / 'personal.jks', signing / 'signing.properties'
    if keystore.exists() != config.exists():
        sys.exit('Incomplete signing files: restore the original matching keystore and properties. Do not generate a new key.')
    if not keystore.exists():
        if os.environ.get('CI', '').lower() == 'true':
            sys.exit('CI must restore the existing private signing material; a new ephemeral update key will not be generated.')
        password = secrets.token_hex(24)
        env = dict(os.environ, PERSONAL_KEY_PASSWORD=password)
        subprocess.run([
            'keytool', '-genkeypair', '-noprompt', '-keystore', str(keystore), '-alias', 'personal',
            '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
            '-dname', 'CN=Personal Dtoon',
            '-storepass:env', 'PERSONAL_KEY_PASSWORD', '-keypass:env', 'PERSONAL_KEY_PASSWORD',
        ], env=env, check=True)
        config.write_text(f'storePassword={password}\nkeyPassword={password}\nkeyAlias=personal\n')
        config.chmod(0o600)
        keystore.chmod(0o600)
    subprocess.run([
        'bash', str(ROOT / 'gradlew'), ':app:assembleRelease', ':app:testReleaseUnitTest', '-Pdisable-code-shrink', '--no-daemon',
    ], cwd=ROOT, check=True)
    apk = ROOT / 'app/build/outputs/apk/release/app-arm64-v8a-release.apk'
    if not apk.is_file():
        sys.exit('Signed arm64 APK not found; unsigned/debug artifacts are not deliverables.')
    output = ROOT / 'personal/output'
    output.mkdir(exist_ok=True)
    target = output / 'Dtoon-fold8.apk'
    shutil.copyfile(apk, target)
    (output / 'SHA256SUMS').write_text(hashlib.sha256(target.read_bytes()).hexdigest() + '  Dtoon-fold8.apk\n')
    print('Built:', target)
    print('Keep personal/signing securely: those same files are required for future private updates.')


if __name__ == '__main__':
    main()
