#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE_DIR="$SCRIPT_DIR/vscode-template"
WORKSPACE_ROOT="${1:-$(cd "$SCRIPT_DIR/../.." && pwd)}"

export_repo_vscode() {
  local repo_name="$1"
  local src_dir="$WORKSPACE_ROOT/$repo_name/.vscode"
  local dst_dir="$TEMPLATE_DIR/$repo_name"

  if [[ ! -d "$WORKSPACE_ROOT/$repo_name" ]]; then
    echo "skip: repo not found: $WORKSPACE_ROOT/$repo_name" >&2
    return 0
  fi

  if [[ ! -d "$src_dir" ]]; then
    echo "skip: no .vscode in repo: $src_dir" >&2
    return 0
  fi

  mkdir -p "$dst_dir"

  local exported_any=0
  for file in tasks.json launch.json settings.json; do
    if [[ -f "$src_dir/$file" ]]; then
      cp -a "$src_dir/$file" "$dst_dir/$file"
      exported_any=1
    fi
  done

  if [[ "$exported_any" -eq 1 ]]; then
    echo "exported: $repo_name <- $src_dir" >&2
  else
    echo "skip: no tasks/launch/settings in $src_dir" >&2
  fi
}

export_repo_vscode "baritone"
export_repo_vscode "meteor-client"

echo "Done. Templates updated under: $TEMPLATE_DIR" >&2
