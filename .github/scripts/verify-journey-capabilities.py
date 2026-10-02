#!/usr/bin/env python3
"""Check journey readiness against the pinned contract fixture, without a provider."""
import json
from pathlib import Path
import sys


def main():
    if len(sys.argv) != 3:
        return 2
    try:
        required = json.loads(Path(sys.argv[1]).read_text())['response']['body']['endpoints']
        served = json.load(sys.stdin)['endpoints']
        if not isinstance(required, dict) or not required or not isinstance(served, dict) or not served:
            return 1
        allow_missing = sys.argv[2] == 'capabilities-missing-required'
        for name, endpoint in required.items():
            # Health is probed directly before this check, not a client capability.
            if name == 'health' or (allow_missing and name not in served):
                continue
            actual = served.get(name)
            if not isinstance(actual, dict) or any(actual.get(key) != endpoint[key] for key in ('method', 'path')):
                return 1
    except (OSError, ValueError, KeyError, TypeError):
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
