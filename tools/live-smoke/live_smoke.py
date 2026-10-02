#!/usr/bin/env python3
"""Local-only exact-APK live smoke gate for hermes-native-client.

Subcommands
-----------
preflight          Validate the designated local environment and explicit inputs.
run                Execute one disposable live smoke run and write evidence.
validate-evidence  Fail-closed check of a produced evidence file against an APK.
cleanup            Remove leftover containers and scratch state for one run.

This tool never runs in CI (it refuses CI markers) and never contacts an
external or production Gateway. Provider credentials are read from the
runtime environment and never printed.
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import secrets
import signal
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from livesmoke import evidence as evidence_mod  # noqa: E402
from livesmoke.config import (  # noqa: E402
    DESIGNATION_ENV,
    PROVIDER_KEY_ENV,
    RunInputs,
    default_paths,
    generate_run_id,
    validate_inputs,
)
from livesmoke.errors import LiveSmokeError  # noqa: E402
from livesmoke.orchestrate import LiveSmokeGate, cleanup_run  # noqa: E402
from livesmoke.proc import SubprocessRunner  # noqa: E402

EXIT_OK = 0
EXIT_CONFIG = 2
EXIT_RUN_FAILED = 3
EXIT_EVIDENCE_INVALID = 4
EXIT_CLEANUP_FAILED = 5


class _SigtermInterrupt(KeyboardInterrupt):
    """SIGTERM translated into the run-interrupt path so teardown still runs.

    Only normal TERM is covered; SIGKILL cannot be handled by any process.
    """


def _install_termination_handler():
    """Translate normal SIGTERM into the run-interrupt exception.

    The gate catches the exception, records ``run_interrupted`` and still
    attempts the bounded existing cleanup.
    """
    previous = signal.getsignal(signal.SIGTERM)

    def _handle(_signum, _frame):
        # Once termination starts, leave the bounded teardown uninterrupted.
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        raise _SigtermInterrupt()

    signal.signal(signal.SIGTERM, _handle)
    return previous


def _restore_termination_handler(previous) -> None:
    signal.signal(signal.SIGTERM, previous)


def _print(payload: dict) -> None:
    print(json.dumps(payload, indent=2, ensure_ascii=False))


def _new_run_id() -> str:
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return generate_run_id(now_utc=now, entropy=secrets.token_hex(3))


def _build_inputs(args: argparse.Namespace, *, run_id: str) -> RunInputs:
    env = dict(os.environ)
    paths = default_paths(env, run_id=run_id)
    work_root = Path(args.work_root) if args.work_root else paths["work_root"]
    retained = Path(args.retained_video_dir) if args.retained_video_dir else paths["retained_video_dir"]
    evidence_dir = Path(args.evidence_dir) if args.evidence_dir else paths["evidence_dir"]
    inputs = RunInputs(
        run_id=run_id,
        apk_path=Path(args.apk),
        expected_apk_sha256=args.expected_apk_sha256,
        hermes_revision=args.hermes_revision,
        hermes_source=Path(args.hermes_source),
        video_recipient=args.video_recipient,
        work_root=work_root,
        retained_video_dir=retained,
        evidence_dir=evidence_dir,
        tls_p12=Path(args.tls_p12),
        designation=env.get(DESIGNATION_ENV, ""),
        device_serial=args.device_serial,
        avd_name=args.avd_name,
        gateway_port=args.gateway_port,
        tls_port=args.tls_port,
        tmpfiles_conf_dir=paths["tmpfiles_conf_dir"],
    )
    validate_inputs(inputs)
    return inputs


def _add_common_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--apk", required=True, help="exact candidate APK to install")
    parser.add_argument("--expected-apk-sha256", required=True, help="approved SHA-256 of that APK")
    parser.add_argument("--hermes-revision", required=True, help="audited Hermes commit (40-hex)")
    parser.add_argument("--hermes-source", required=True, help="local git checkout containing that commit")
    parser.add_argument("--video-recipient", required=True, help="release maintainer OpenPGP fingerprint")
    parser.add_argument("--tls-p12", required=True, help="repository test TLS PKCS12 for the loopback boundary")
    parser.add_argument("--device-serial", default=None, help="owned AVD serial (required by preflight)")
    parser.add_argument("--avd-name", default=None, help="exact owned AVD name (enforced by preflight)")
    parser.add_argument("--gateway-port", type=int, default=8642)
    parser.add_argument("--tls-port", type=int, default=18443)
    parser.add_argument("--work-root", default=None)
    parser.add_argument("--retained-video-dir", default=None)
    parser.add_argument("--evidence-dir", default=None)
    parser.add_argument("--run-id", default=None)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="live-smoke", description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    preflight_parser = subparsers.add_parser("preflight", help="validate inputs and environment")
    _add_common_arguments(preflight_parser)

    run_parser = subparsers.add_parser("run", help="execute one live smoke run")
    _add_common_arguments(run_parser)

    validate_parser = subparsers.add_parser("validate-evidence", help="validate an evidence file")
    validate_parser.add_argument("--evidence", required=True)
    validate_parser.add_argument("--apk", required=True)
    validate_parser.add_argument("--expected-apk-sha256", default=None)

    cleanup_parser = subparsers.add_parser("cleanup", help="remove leftover run state")
    _add_common_arguments(cleanup_parser)

    args = parser.parse_args(argv)
    if args.command == "cleanup" and not args.run_id:
        parser.error("--run-id is required for cleanup")
    run_id = getattr(args, "run_id", None) or _new_run_id()

    try:
        if args.command == "validate-evidence":
            return _validate_evidence(args)
        inputs = _build_inputs(args, run_id=run_id)
        runner = SubprocessRunner(base_env=dict(os.environ))
        if args.command == "preflight":
            gate = LiveSmokeGate(inputs, runner=runner, env=dict(os.environ))
            checks = gate.preflight()
            _print({"command": "preflight", "run_id": run_id,
                    "ok": all(c["ok"] for c in checks), "checks": checks})
            return EXIT_OK if all(c["ok"] for c in checks) else EXIT_CONFIG
        if args.command == "run":
            gate = LiveSmokeGate(inputs, runner=runner, env=dict(os.environ), log=lambda m: print(m))
            previous_handler = _install_termination_handler()
            try:
                result = gate.run()
            finally:
                _restore_termination_handler(previous_handler)
            _print({"command": "run", "run_id": run_id, **result})
            return EXIT_OK if result["result"] == "passed" else EXIT_RUN_FAILED
        if args.command == "cleanup":
            result = cleanup_run(inputs, runner=runner, log=lambda m: print(m))
            _print({"command": "cleanup", "run_id": run_id, **result})
            return EXIT_OK
    except LiveSmokeError as exc:
        _print({"command": args.command, "run_id": run_id, "error": exc.to_dict()})
        if args.command == "cleanup":
            return EXIT_CLEANUP_FAILED
        return EXIT_CONFIG if args.command == "preflight" else EXIT_RUN_FAILED
    except ValueError as exc:
        _print({"command": args.command, "run_id": run_id, "error": {"code": "invalid_input", "message": str(exc)}})
        return EXIT_EVIDENCE_INVALID if args.command == "validate-evidence" else EXIT_CONFIG
    except KeyboardInterrupt:
        _print({"command": args.command, "run_id": run_id,
                "error": {"code": "run_interrupted", "message": "the command was interrupted"}})
        if args.command == "cleanup":
            return EXIT_CLEANUP_FAILED
        return EXIT_CONFIG if args.command == "preflight" else EXIT_RUN_FAILED
    return EXIT_OK


def _validate_evidence(args: argparse.Namespace) -> int:
    evidence = evidence_mod.load_evidence(Path(args.evidence))
    apk = Path(args.apk)
    if not apk.is_file():
        _print({"command": "validate-evidence", "valid": False,
                "problems": ["apk_missing"], "error": {"code": "apk_missing"}})
        return EXIT_EVIDENCE_INVALID
    actual = evidence_mod.sha256_file(apk)
    expected = args.expected_apk_sha256 or (evidence.get("apk") or {}).get("sha256", "")
    problems = evidence_mod.validate_evidence(
        evidence, expected_apk_sha256=expected, actual_apk_sha256=actual,
    )
    valid = not problems
    payload = {
        "command": "validate-evidence",
        "valid": valid,
        "problems": problems,
        "apk_sha256": actual,
        "evidence": str(args.evidence),
    }
    if valid:
        payload["note"] = (
            "Live smoke evidence is valid for this exact APK. It does not replace human "
            "release approval: the release maintainer must still approve the release."
        )
    _print(payload)
    return EXIT_OK if valid else EXIT_EVIDENCE_INVALID


if __name__ == "__main__":
    sys.exit(main())
