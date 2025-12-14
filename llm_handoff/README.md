# LLM handoff bundle

This folder is a **portable context snapshot** so you can continue this workspace with a different LLM (or on a different machine) without losing the important project state.

## What’s inside

- `context.md` — human-readable current state + what’s implemented + what’s next.
- `context.json` — machine-readable snapshot for another LLM.
- `prompt.txt` — ready-to-paste prompt that tells another LLM how to resume.
- `vscode-template/` — VS Code task configs (“deploy scripts”) for Baritone + Meteor.
- `import_to_vscode.sh` — installs the VS Code configs into a fresh workspace.
- `export_from_vscode.sh` — re-captures `.vscode/` from your current workspace back into this bundle.

## Quick start (new machine / new VS Code install)

1) Copy this entire `llm_handoff/` folder to the new workspace (keeping its relative path inside `baritone/`).
2) Run:

- `bash baritone/llm_handoff/import_to_vscode.sh`

3) Open the workspace in VS Code. Tasks will appear under **Run Task…**.

## Using with another LLM

Give the other LLM:

- `context.md` (primary), and
- `context.json` (for exact identifiers / file paths / commits).

Then use the text in `prompt.txt` as the first message.
