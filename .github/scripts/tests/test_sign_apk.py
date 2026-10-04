"""Sign a minimal APK with a disposable key; no production secret is read."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from signing_fixture import make_signing_fixture

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / ".github/scripts/sign-release-apk.py"


class SignApkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.directory.cleanup)
        cls.root = Path(cls.directory.name)
        fixture = make_signing_fixture(cls.root)
        cls.tools = fixture.tools
        cls.environment = fixture.environment
        cls.fingerprint = fixture.fingerprint
        cls.certificate_file = fixture.certificate_file
        cls.apk = fixture.apk

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
        self.assertEqual(1, metadata["version_code"])
        self.assertEqual("test", metadata["version_name"])
        self.assertEqual([], metadata["native_abis"])
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
            ("ANDROID_RELEASE_KEYSTORE_PASSWORD", ""),
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

    def test_empty_workflow_secret_inputs_report_the_exact_missing_material_failure(self):
        self.env.update(ANDROID_RELEASE_KEYSTORE_BASE64="", ANDROID_RELEASE_KEYSTORE_PASSWORD="")
        result = self.sign()
        self.assertEqual(1, result.returncode)
        self.assertEqual("Release signing refused: Signing material is unavailable", result.stderr.strip())
        self.assertFalse(self.output.exists())
        self.assertEqual([], list(self.root.glob("release-signing-*")))

    def test_rejects_a_nightly_apk_with_the_wrong_embedded_version(self):
        self.env["RELEASE_VERSION_CODE"] = "7002"
        result = self.sign()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.output.exists())
        self.assertIn("version", result.stderr)

    def test_rejects_emulator_native_libraries_in_a_nightly(self):
        fixture = make_signing_fixture(self.root / "emulator", 7002, "nightly-7002-aaaaaaaaaaaa")
        with zipfile.ZipFile(fixture.apk, "a") as archive:
            archive.writestr("lib/x86_64/libfixture.so", b"synthetic-ABI-fixture")
        self.apk = fixture.apk
        self.certificate_file = fixture.certificate_file
        self.env = {**fixture.environment, "RELEASE_VERSION_CODE": "7002"}
        self.env["RELEASE_UNSIGNED_SHA256"] = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        result = self.sign()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("non-ARM64", result.stderr)
        self.assertFalse(self.output.exists())

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
