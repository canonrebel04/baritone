#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE_DIR="$SCRIPT_DIR/vscode-template"

# Default workspace root assumes this script lives at: <workspace>/baritone/llm_handoff/import_to_vscode.sh
# so workspace root is two levels up.
WORKSPACE_ROOT="${1:-$(cd "$SCRIPT_DIR/../.." && pwd)}"

install_repo_vscode() {
  local repo_name="$1"
  local src_dir="$TEMPLATE_DIR/$repo_name"
  local dst_dir="$WORKSPACE_ROOT/$repo_name/.vscode"

  if [[ ! -d "$WORKSPACE_ROOT/$repo_name" ]]; then
    echo "skip: repo not found: $WORKSPACE_ROOT/$repo_name" >&2
    return 0
  fi

  if [[ ! -d "$src_dir" ]]; then
    echo "skip: template not found: $src_dir" >&2
    return 0
  fi

  mkdir -p "$dst_dir"

  local installed_any=0
  for file in tasks.json launch.json settings.json; do
    if [[ -f "$src_dir/$file" ]]; then
      cp -a "$src_dir/$file" "$dst_dir/$file"
      installed_any=1
    fi
  done

  if [[ "$installed_any" -eq 1 ]]; then
    echo "installed: $repo_name -> $dst_dir" >&2
  else
    echo "skip: no template files for $repo_name" >&2
  fi
}

install_repo_vscode "baritone"
install_repo_vscode "meteor-client"

echo "Done. In VS Code, run: Command Palette -> Tasks: Run Task" >&2
