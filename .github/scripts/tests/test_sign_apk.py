"""Sign a minimal APK with a disposable key; no production secret is read."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / ".github/scripts/sign-release-apk.py"


class SignApkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.directory.cleanup)
        cls.root = Path(cls.directory.name)
        sdk = Path(os.environ["ANDROID_HOME"])
        cls.tools = sdk / "build-tools/35.0.0"
        cls.environment = {
            "PATH": os.environ["PATH"], "JAVA_HOME": os.environ["JAVA_HOME"],
            "ANDROID_HOME": str(sdk), "RUNNER_TEMP": str(cls.root),
            "ANDROID_RELEASE_KEYSTORE_PASSWORD": "disposable-test-password",
            "RELEASE_SOURCE_SHA": "a" * 40, "RELEASE_VALIDATION_RUN_ID": "123",
            "RELEASE_VALIDATION_RUN_ATTEMPT": "1", "GITHUB_RUN_ID": "456", "GITHUB_RUN_ATTEMPT": "1",
        }
        cls.keystore = cls.root / "disposable.p12"
        cls.run_tool([
            "keytool", "-genkeypair", "-alias", "hermes-native-client-release",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-storetype", "PKCS12",
            "-dname", "CN=Disposable Test Key", "-keystore", str(cls.keystore),
            "-storepass:env", "ANDROID_RELEASE_KEYSTORE_PASSWORD",
        ])
        certificate = cls.run_tool([
            "keytool", "-exportcert", "-alias", "hermes-native-client-release",
            "-keystore", str(cls.keystore), "-storepass:env", "ANDROID_RELEASE_KEYSTORE_PASSWORD",
        ]).stdout
        cls.fingerprint = hashlib.sha256(certificate).hexdigest()
        cls.certificate_file = cls.root / "certificate.sha256"
        cls.certificate_file.write_text(cls.fingerprint + "\n")
        cls.environment["ANDROID_RELEASE_KEYSTORE_BASE64"] = base64.b64encode(cls.keystore.read_bytes()).decode()
        manifest = cls.root / "AndroidManifest.xml"
        manifest.write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="org.hermesnative.client" android:versionCode="1" android:versionName="test">'
            '<uses-sdk android:minSdkVersion="24" android:targetSdkVersion="35"/>'
            '<application android:label="Signing test"/></manifest>'
        )
        cls.apk = cls.root / "unsigned.apk"
        cls.run_tool([
            str(cls.tools / "aapt2"), "link", "--manifest", str(manifest),
            "-I", str(sdk / "platforms/android-35/android.jar"), "-o", str(cls.apk),
        ])
        cls.environment["RELEASE_UNSIGNED_SHA256"] = hashlib.sha256(cls.apk.read_bytes()).hexdigest()

    @classmethod
    def run_tool(cls, arguments):
        result = subprocess.run(arguments, env=cls.environment, capture_output=True, check=False)
        if result.returncode:
            raise AssertionError(f"Disposable signing fixture command failed: {arguments[0]}")
        return result

    def setUp(self):
        self.output = self.root / self._testMethodName
        self.env = dict(self.environment)

    def sign(self):
        return subprocess.run(
            ["python3", str(SCRIPT), str(self.apk), str(self.output), str(self.certificate_file)],
            cwd=ROOT, env=self.env, capture_output=True, text=True, check=False,
        )

    def test_signs_and_verifies_the_expected_identity_without_retaining_secrets(self):
        result = self.sign()
        self.assertEqual(0, result.returncode, result.stderr)
        artifact = self.output / "hermes-native-client.apk"
        verified = self.run_tool([str(self.tools / "apksigner"), "verify", "--print-certs", str(artifact)])
        self.assertIn(self.fingerprint, verified.stdout.decode())
        metadata = json.loads((self.output / "signing-metadata.json").read_text())
        self.assertEqual(self.fingerprint, metadata["certificate_sha256"])
        self.assertEqual("a" * 40, metadata["source_sha"])
        self.assertEqual(hashlib.sha256(artifact.read_bytes()).hexdigest(), metadata["apk_sha256"])
        self.assertEqual(
            {"hermes-native-client.apk", "SHA256SUMS", "signing-metadata.json"},
            {entry.name for entry in self.output.iterdir()},
        )
        for secret in (self.env["ANDROID_RELEASE_KEYSTORE_PASSWORD"], self.env["ANDROID_RELEASE_KEYSTORE_BASE64"]):
            self.assertNotIn(secret, result.stdout + result.stderr + json.dumps(metadata))
        self.assertEqual([], list(self.root.glob("release-signing-*")))

    def test_missing_material_bad_password_and_modified_apk_fail_without_output(self):
        cases = (
            ("ANDROID_RELEASE_KEYSTORE_BASE64", ""),
            ("ANDROID_RELEASE_KEYSTORE_BASE64", "not-base64"),
            ("ANDROID_RELEASE_KEYSTORE_PASSWORD", "wrong-disposable-password"),
            ("RELEASE_UNSIGNED_SHA256", "0" * 64),
        )
        for name, value in cases:
            with self.subTest(name=name, value_type=type(value).__name__):
                self.env = dict(self.environment)
                self.env[name] = value
                result = self.sign()
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.output.exists())
                self.assertEqual([], list(self.root.glob("release-signing-*")))
                self.assertNotIn(self.environment["ANDROID_RELEASE_KEYSTORE_BASE64"], result.stdout + result.stderr)
                self.assertNotIn(self.environment["ANDROID_RELEASE_KEYSTORE_PASSWORD"], result.stdout + result.stderr)

    def test_wrong_certificate_is_rejected_before_any_artifact_is_published(self):
        certificate = self.certificate_file
        try:
            self.certificate_file = self.root / "wrong-certificate.sha256"
            self.certificate_file.write_text("0" * 64 + "\n")
            result = self.sign()
            self.assertNotEqual(0, result.returncode)
            self.assertIn("does not match", result.stderr)
            self.assertFalse(self.output.exists())
            self.assertEqual([], list(self.root.glob("release-signing-*")))
        finally:
            self.certificate_file = certificate


if __name__ == "__main__":
    unittest.main()
