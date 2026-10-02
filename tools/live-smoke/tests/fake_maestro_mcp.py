"""Deterministic fake of the `maestro mcp` stdio server for tests.

It speaks MCP JSON-RPC over newline-delimited JSON (the framing the installed
Maestro 2.6.1 uses) and implements only the tools the gate needs. Scripted
inspect_screen payloads come from FAKE_MAESTRO_SCRIPT (a JSON file); every
tool call is appended to FAKE_MAESTRO_CALL_LOG (JSON lines).
"""

import json
import os
import sys


def emit(payload):
    sys.stdout.write(json.dumps(payload) + "\n")
    sys.stdout.flush()


def read_script():
    path = os.environ.get("FAKE_MAESTRO_SCRIPT")
    if not path or not os.path.exists(path):
        return {}
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def append_log(entry):
    path = os.environ.get("FAKE_MAESTRO_CALL_LOG")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(entry) + "\n")


TOOLS = [
    "list_devices",
    "take_screenshot",
    "run",
    "inspect_screen",
    "cheat_sheet",
    "open_maestro_viewer",
    "list_cloud_devices",
    "run_on_cloud",
    "get_cloud_run_status",
]


def tool_call_result(name, arguments, script):
    if name == "list_devices":
        text = script.get("list_devices_text", json.dumps([{"device_id": "emulator-5554", "state": "online"}]))
    elif name == "inspect_screen":
        seq = script.get("inspect_screen", ["<hierarchy />"])
        index = script.get("_inspect_index", 0)
        text = seq[index] if index < len(seq) else seq[-1] if seq else "<hierarchy />"
        script["_inspect_index"] = index + 1
    elif name == "run":
        text = script.get("run_text", "flow completed")
    elif name == "take_screenshot":
        text = script.get("screenshot_text", "screenshot.png")
    else:
        text = "unsupported"
    return {"content": [{"type": "text", "text": text}], "isError": False}


def main():
    if os.environ.get('FAKE_MAESTRO_NOISY'):
        sys.stderr.write('synthetic diagnostic\n' * 10000)
        sys.stderr.flush()
    script = read_script()
    mute = os.environ.get("FAKE_MAESTRO_MUTE") == "1"
    while True:
        line = sys.stdin.readline()
        if not line:
            return 0
        line = line.strip()
        if not line:
            continue
        if mute:
            continue
        try:
            msg = json.loads(line)
        except ValueError:
            continue
        method = msg.get("method")
        mid = msg.get("id")
        if method == "initialize":
            emit({
                "jsonrpc": "2.0",
                "id": mid,
                "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {"tools": {"listChanged": True}},
                    "serverInfo": {"name": "maestro-fake", "version": "1.0.0", "home": os.environ.get("HOME"), "analytics_disabled": os.environ.get("MAESTRO_CLI_NO_ANALYTICS")},
                },
            })
        elif method == "tools/list":
            emit({
                "jsonrpc": "2.0",
                "id": mid,
                "result": {
                    "tools": [
                        {"name": name, "description": "fake", "inputSchema": {"type": "object", "properties": {}}}
                        for name in TOOLS
                    ]
                },
            })
        elif method == "tools/call":
            params = msg.get("params") or {}
            name = params.get("name", "")
            arguments = params.get("arguments") or {}
            append_log({"tool": name, "arguments": arguments})
            if name not in TOOLS:
                emit({"jsonrpc": "2.0", "id": mid,
                      "result": {"content": [{"type": "text", "text": "unknown tool"}], "isError": True}})
            else:
                emit({"jsonrpc": "2.0", "id": mid, "result": tool_call_result(name, arguments, script)})
        elif mid is not None:
            emit({"jsonrpc": "2.0", "id": mid,
                  "error": {"code": -32601, "message": "method not found: %s" % method}})


if __name__ == "__main__":
    sys.exit(main())
