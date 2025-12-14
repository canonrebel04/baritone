#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE_DIR="$SCRIPT_DIR/vscode-template"
WORKSPACE_ROOT="${1:-$(cd "$SCRIPT_DIR/../.." && pwd)}"

export_repo_vscode() {
  local repo_name="$1"
  local src="$WORKSPACE_ROOT/$repo_name/.vscode"
  local dst="$TEMPLATE_DIR/$repo_name/.vscode"

  if [[ ! -d "$WORKSPACE_ROOT/$repo_name" ]]; then
    echo "skip: repo not found: $WORKSPACE_ROOT/$repo_name" >&2
    return 0
  fi

  if [[ ! -d "$src" ]]; then
    echo "skip: no .vscode in repo: $src" >&2
    return 0
  fi

  mkdir -p "$dst"
  # Replace the template with current contents
  rm -rf "$dst"/*
  cp -a "$src/." "$dst/"
  echo "exported: $repo_name <- $src" >&2
}

export_repo_vscode "baritone"
export_repo_vscode "meteor-client"

echo "Done. Templates updated under: $TEMPLATE_DIR" >&2
