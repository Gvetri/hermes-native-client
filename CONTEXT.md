# Hermes Native Client

This context covers the concepts used by the native client when connecting to a Hermes Gateway and working with conversation history and agent executions.

## Language

**Gateway**:
An existing service that provides the supported Hermes client capabilities.
_Avoid_: local runtime, desktop app

**Gateway connection**:
The client's configured access to a Gateway.
_Avoid_: account, profile

**Session**:
A Gateway-managed conversation with an ordered history of messages.
_Avoid_: chat, thread

**Message**:
An entry in a Session's history; its role or content may be unavailable.
_Avoid_: transcript item

**Run**:
An agent execution started with input for a Session.
_Avoid_: task, job
