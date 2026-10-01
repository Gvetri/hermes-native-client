#!/usr/bin/env python3
"""Sign only inside the approved job; never log or publish signing material."""
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ALIAS = "hermes-native-client-release"


class SigningError(Exception):
    """Signing did not produce a verified artifact."""


def require(condition, message):
    if not condition:
        raise SigningError(message)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run_tool(arguments, environment):
    result = subprocess.run(arguments, env=environment, capture_output=True, timeout=120, check=False)
    require(result.returncode == 0, "Android signing tool failed; raw output is withheld")
    return result.stdout.decode("utf-8")


def sign(apk, output, certificate):
    expected_certificate = certificate.read_text().strip()
    require(re.fullmatch(r"[0-9a-f]{64}", expected_certificate), "Invalid release certificate identity")
    require(digest(apk) == os.environ["RELEASE_UNSIGNED_SHA256"], "Unsigned APK checksum changed")
    metadata = {}
    for name, variable in (
        ("source_sha", "RELEASE_SOURCE_SHA"), ("validation_run_id", "RELEASE_VALIDATION_RUN_ID"),
        ("validation_run_attempt", "RELEASE_VALIDATION_RUN_ATTEMPT"),
        ("signing_run_id", "GITHUB_RUN_ID"), ("signing_run_attempt", "GITHUB_RUN_ATTEMPT"),
    ):
        value = os.environ[variable]
        require(re.fullmatch(r"[0-9a-f]{40}" if name == "source_sha" else r"[1-9][0-9]*", value), "Invalid source metadata")
        metadata[name] = value if name == "source_sha" else int(value)
    encoded = os.environ["ANDROID_RELEASE_KEYSTORE_BASE64"]
    password = os.environ["ANDROID_RELEASE_KEYSTORE_PASSWORD"]
    require(encoded and password, "Signing material is unavailable")
    keystore = base64.b64decode(encoded, validate=True)
    require(keystore, "Signing material is unavailable")
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
    # No GitHub token or encoded keystore reaches the signing tool's environment.
    environment = {name: os.environ[name] for name in ("PATH", "JAVA_HOME")}
    environment["ANDROID_RELEASE_KEYSTORE_PASSWORD"] = password
    require(not output.exists(), "Refusing to overwrite a signing output")
    with tempfile.TemporaryDirectory(prefix="release-signing-", dir=os.environ["RUNNER_TEMP"]) as directory:
        temporary = Path(directory)
        store = temporary / "release.p12"
        with store.open("xb") as stream:
            os.chmod(store, 0o600)
            stream.write(keystore)
        aligned = temporary / "aligned.apk"
        signed = temporary / "signed.apk"
        run_tool([str(tools / "zipalign"), "-P", "16", "4", str(apk), str(aligned)], environment)
        run_tool([
            str(tools / "apksigner"), "sign", "--ks", str(store), "--ks-type", "PKCS12",
            "--ks-key-alias", ALIAS, "--ks-pass", "env:ANDROID_RELEASE_KEYSTORE_PASSWORD",
            "--key-pass", "env:ANDROID_RELEASE_KEYSTORE_PASSWORD", "--v4-signing-enabled", "false",
            "--out", str(signed), str(aligned),
        ], environment)
        verification = run_tool([str(tools / "apksigner"), "verify", "--print-certs", str(signed)], environment)
        signers = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]{64})$", verification, re.MULTILINE)
        require(signers == [expected_certificate], "APK signer does not match the pinned release identity")
        run_tool([str(tools / "zipalign"), "-c", "-P", "16", "4", str(signed)], environment)
        metadata.update(apk_sha256=digest(signed), certificate_sha256=expected_certificate)
        # Publish a small allowlist only after signature verification. The private directory is
        # outside the artifact path and is removed on both success and failure.
        output.mkdir(parents=True)
        try:
            shutil.copyfile(signed, output / "hermes-native-client.apk")
            (output / "SHA256SUMS").write_text(f"{metadata['apk_sha256']}  hermes-native-client.apk\n")
            (output / "signing-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
        except OSError:
            shutil.rmtree(output)
            raise
    print("Signed APK checksum and pinned certificate identity verified.")


def main():
    try:
        require(len(sys.argv) == 4, "Expected input APK, output directory, and pinned certificate file")
        sign(*(Path(value) for value in sys.argv[1:]))
        return 0
    except (SigningError, KeyError, ValueError, binascii.Error, OSError, subprocess.TimeoutExpired) as error:
        message = str(error) if isinstance(error, SigningError) else "Missing or invalid signing material, input, or tool"
        print(f"Release signing refused: {message}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
