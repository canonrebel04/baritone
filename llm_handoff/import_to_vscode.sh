#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE_DIR="$SCRIPT_DIR/vscode-template"

# Default workspace root assumes this script lives at: <workspace>/baritone/llm_handoff/import_to_vscode.sh
# so workspace root is two levels up.
WORKSPACE_ROOT="${1:-$(cd "$SCRIPT_DIR/../.." && pwd)}"

copy_repo_vscode() {
  local repo_name="$1"
  local src="$TEMPLATE_DIR/$repo_name/.vscode"
  local dst="$WORKSPACE_ROOT/$repo_name/.vscode"

  if [[ ! -d "$WORKSPACE_ROOT/$repo_name" ]]; then
    echo "skip: repo not found: $WORKSPACE_ROOT/$repo_name" >&2
    return 0
  fi

  if [[ ! -d "$src" ]]; then
    echo "skip: template not found: $src" >&2
    return 0
  fi

  mkdir -p "$dst"
  # Copy template files into the repo .vscode
  cp -a "$src/." "$dst/"
  echo "installed: $repo_name -> $dst" >&2
}

copy_repo_vscode "baritone"
copy_repo_vscode "meteor-client"

echo "Done. In VS Code, run: Command Palette -> Tasks: Run Task" >&2
