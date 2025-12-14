# Swarm security (LAN control)

This project’s Swarm feature is designed to be safe-by-default.

## Threat model

Swarm is remote control over a client. Assume:

- Anyone on the LAN may be able to reach an open port.
- Messages can be spammed, duplicated, or malformed.
- A malicious controller may try to make the worker run arbitrary commands.

## Default behavior

Workers reject remote control by default unless you explicitly configure trust.

- `require-trusted-host`: ON by default
- `trusted-hosts`: empty by default (reject all)
- `allow-unsafe-commands`: OFF by default

## Trust model

A worker only accepts messages if the host IP/hostname is allowlisted in `trusted-hosts` when `require-trusted-host` is enabled.

Recommended values:

- Single-machine testing: add `localhost`
- LAN: add the host’s LAN IP, e.g. `192.168.1.10`

## Authentication (optional)

If `require-token` is enabled:

- Host wraps outgoing messages as `swarm|TOKEN|payload`.
- Worker rejects wrapped messages with a bad token.
- Worker rejects unwrapped messages.

## Allowlist model

By default, workers only accept a strict allowlist of actions.

- Structured protocol (`swarm2 ...`) executes allowlisted Baritone actions directly.
- Legacy text commands (`swarm ...`) are allowlisted by subcommand.

To permit arbitrary remote execution, you must explicitly enable:

- `allow-unsafe-commands`

This is intentionally dangerous and should not be used on public servers.

## Rate limiting + duplicates

Workers apply:

- A per-second rate limit (`worker-commands-per-second`).
- A best-effort duplicate/replay guard for identical payloads in a short time window.

## Operational checklist

Before enabling a worker:

1. Enable Swarm module in Worker mode.
2. Set `trusted-hosts` to the controller machine.
3. (Optional) Enable `require-token` and set a non-empty token.
4. Keep `allow-unsafe-commands` OFF.

## Troubleshooting

- “Rejected message from untrusted host”: add the host to `trusted-hosts`.
- “Rejected message without token”: either disable `require-token` or configure the same token on both sides.
- “Blocked (rate limit)”: increase `worker-commands-per-second` or reduce spam.
