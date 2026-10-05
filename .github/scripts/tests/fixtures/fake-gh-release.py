#!/usr/bin/env python3
"""Filesystem-backed stand-in for the gh release commands; used only by local tests."""
import hashlib
import json
from pathlib import Path
import shutil
import sys

root = Path.home() / "release-fixture"
root.mkdir(exist_ok=True)
state = root / "release.json"
arguments = sys.argv[1:]
payload = None
if arguments[0] == "api":
    assert arguments == ["api", "--method", "POST", "repos/Gvetri/hermes-native-client/releases", "--input", "-"]
    payload = json.load(sys.stdin)
    assert set(payload) == {"tag_name", "target_commitish", "name", "body", "draft", "prerelease", "make_latest"}
    assert payload["draft"] is True and payload["prerelease"] is True and payload["make_latest"] == "false"
    operation, tag = "create", payload["tag_name"]
else:
    assert arguments[0] == "release"
    operation, tag = arguments[1:3]
    assert arguments[arguments.index("--repo") + 1] == "Gvetri/hermes-native-client"
assert "--clobber" not in arguments
with (root / "operations").open("a") as log:
    log.write(operation + "\n")


def value(flag):
    return arguments[arguments.index(flag) + 1]


if operation == "create":
    assert not state.exists()
    if payload is None:
        assert "--draft" in arguments and "--prerelease" in arguments and "--latest=false" in arguments
    release = {
        "id": 1, "tag_name": tag, "target_commitish": payload["target_commitish"] if payload else value("--target"),
        "name": payload["name"] if payload else value("--title"),
        "body": payload["body"] if payload else Path(value("--notes-file")).read_text(),
        "draft": True, "prerelease": True, "assets": [],
        "html_url": f"https://github.com/Gvetri/hermes-native-client/releases/tag/{tag}",
    }
    state.write_text(json.dumps(release))
    if payload is not None:
        response = {**release, "id": 2} if (root / "wrong-create-id").exists() else release
        print(json.dumps(response))
    else:
        print(release["html_url"])
elif operation == "upload":
    release = json.loads(state.read_text())
    assert release["draft"] is True
    store = root / "assets"
    store.mkdir()
    for filename in arguments[3:arguments.index("--repo")]:
        source = Path(filename)
        shutil.copyfile(source, store / source.name)
        release["assets"].append({
            "id": len(release["assets"]) + 1, "name": source.name, "size": source.stat().st_size,
            "state": "uploaded", "digest": "sha256:" + hashlib.sha256(source.read_bytes()).hexdigest(),
            "download_count": 0,
        })
        state.write_text(json.dumps(release))
        if (root / "fail-upload").exists():
            raise SystemExit(7)
elif operation == "download":
    destination = Path(value("--dir"))
    store = root / "nightly-assets" if tag.startswith("nightly-") and (root / "nightly-assets").exists() else root / "assets"
    for source in store.iterdir():
        shutil.copyfile(source, destination / source.name)
    # Downloads change provider statistics, not the artifact identity.
    if store.name == "nightly-assets":
        counter = root / "nightly-downloads"
        counter.write_text(str((int(counter.read_text()) if counter.exists() else 0) + 1))
    else:
        release = json.loads(state.read_text())
        for asset in release["assets"]:
            asset["download_count"] += 1
        state.write_text(json.dumps(release))
    if (root / "corrupt-download").exists() or (tag.startswith("stable-beta-") and (root / "corrupt-stable-download").exists()):
        (destination / "hermes-native-client.apk").write_bytes(b"corrupt-fixture")
elif operation == "edit":
    assert "--draft=false" in arguments and "--prerelease" in arguments and "--latest=false" in arguments
    release = json.loads(state.read_text())
    release.update(draft=False, prerelease=True, name=value("--title"))
    state.write_text(json.dumps(release))
else:
    raise SystemExit("Unexpected gh fixture operation")
