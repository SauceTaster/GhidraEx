# GhidraEx loopback gateway

This directory contains the shared native-host transport for one persistent Ghidra engine
session. It is a dependency-free Python HTTP adapter over the canonical protocol in
`integration/protocol`; it does not add a second engine API.

The listener:

- binds only literal `127.0.0.1` and uses an ephemeral port by default;
- requires a per-launch 256-bit bearer token on health, requests, and event streams;
- validates the exact `Host` authority and any browser `Origin` against an explicit allowlist;
- holds an identity-based lease on its launching host and closes the engine if that process exits
  or its PID is reused;
- bounds connections, headers, bodies, responses, deadlines, event retention, and stream life;
- returns canonical request/response envelopes at `POST /v1/requests`;
- replays a bounded canonical event ring at `GET /v1/events?after=<sequence>`; and
- exposes only process-level state at authenticated `GET /v1/health`.

Loopback is not treated as authentication. Tokens are never accepted in a URL or printed by the
normal bootstrap record. Request targets and headers are not logged.

## Run

Start a real session and put its private descriptor in a newly-created owner-only file:

```sh
python3 integration/gateway/gateway.py /path/to/binary \
  --ghidra-home /path/to/ghidra_12.1.2_PUBLIC \
  --ghidra-java-home /path/to/jdk-21 \
  --cancellation-grace 1.0 \
  --metadata-file /tmp/ghidraex-gateway.json
```

The file is created with mode `0600`, contains the base URL and bearer token, and is removed when
the gateway exits. Existing files are never overwritten. Standard output receives exactly one
prefixed bootstrap JSON record containing the URL, PID, protocol, and instance identity, but no
token. Without `--metadata-file`, the CLI still starts but deliberately does not export its token;
embedded hosts normally instantiate `GhidraGateway` and retain the generated token in memory.

The CLI captures `os.getppid()` before starting the potentially long Ghidra import. A host may
instead pass its own PID with `--parent-pid`; this is useful when a launcher wrapper sits between
the host and gateway. Linux leases use kernel process start ticks plus the boot identity, Windows
leases use process creation time, and other POSIX systems use the process creation time reported by
`ps`. The gateway only reads parent identity: it never signals or otherwise controls the parent.
Identity is checked during Ghidra startup and periodically while serving. Losing the lease closes
the listener and owned Ghidra session and exits the CLI with status 3.

There is currently no idle timeout. Gateway lifetime is controlled by the parent lease, explicit
`session.close`, process signals handled by the CLI, or an owning caller invoking `close()`.

An exact browser origin can be allowed with repeated `--origin` arguments. Wildcard and `null`
origins are rejected. Native clients should omit `Origin` and still provide `Authorization`.

## Verify

```sh
python3 -m unittest discover -s integration/gateway/tests -v
```

The suite injects a fake session and exercises canonical correlation, program-context checks,
late-client `session.ready` replay, authorization, Host/Origin policy, body/header bounds,
connection saturation, parent identity loss, private metadata, and owned shutdown without
launching Ghidra.
