"""Create disposable signing material and a real minimal APK for boundary tests."""
import base64
import hashlib
import os
from pathlib import Path
import subprocess
from types import SimpleNamespace


def make_signing_fixture(root, version_code=1, version_name="test"):
    root.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ["ANDROID_HOME"])
    tools = sdk / "build-tools/35.0.0"
    environment = {
        "PATH": os.environ["PATH"], "JAVA_HOME": os.environ["JAVA_HOME"],
        "ANDROID_HOME": str(sdk), "RUNNER_TEMP": str(root),
        "ANDROID_RELEASE_KEYSTORE_PASSWORD": "disposable-test-password",
        "RELEASE_SOURCE_SHA": "a" * 40, "RELEASE_VALIDATION_RUN_ID": "123",
        "RELEASE_VALIDATION_RUN_ATTEMPT": "1", "GITHUB_RUN_ID": "456", "GITHUB_RUN_ATTEMPT": "1",
    }

    def run(arguments):
        result = subprocess.run(arguments, env=environment, capture_output=True, timeout=30, check=False)
        if result.returncode:
            raise AssertionError(f"Disposable signing fixture command failed: {arguments[0]}")
        return result

    keystore = root / "disposable.p12"
    run([
        "keytool", "-genkeypair", "-alias", "hermes-native-client-release",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-storetype", "PKCS12",
        "-dname", "CN=Disposable Test Key", "-keystore", str(keystore),
        "-storepass:env", "ANDROID_RELEASE_KEYSTORE_PASSWORD",
    ])
    certificate = run([
        "keytool", "-exportcert", "-alias", "hermes-native-client-release",
        "-keystore", str(keystore), "-storepass:env", "ANDROID_RELEASE_KEYSTORE_PASSWORD",
    ]).stdout
    fingerprint = hashlib.sha256(certificate).hexdigest()
    certificate_file = root / "certificate.sha256"
    certificate_file.write_text(fingerprint + "\n")
    environment["ANDROID_RELEASE_KEYSTORE_BASE64"] = base64.b64encode(keystore.read_bytes()).decode()
    manifest = root / "AndroidManifest.xml"
    manifest.write_text(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
        f'package="org.hermesnative.client" android:versionCode="{version_code}" android:versionName="{version_name}">'
        '<uses-sdk android:minSdkVersion="24" android:targetSdkVersion="35"/>'
        '<application android:label="Signing test"/></manifest>'
    )
    apk = root / "unsigned.apk"
    run([str(tools / "aapt2"), "link", "--manifest", str(manifest), "-I", str(sdk / "platforms/android-35/android.jar"), "-o", str(apk)])
    environment["RELEASE_UNSIGNED_SHA256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
    return SimpleNamespace(environment=environment, apk=apk, certificate_file=certificate_file, tools=tools, fingerprint=fingerprint)
