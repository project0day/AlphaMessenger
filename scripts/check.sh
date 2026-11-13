#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "=== AlphaMessenger Code Quality Check ==="

cd "$PROJECT_ROOT/rust-core"

echo "Formatting..."
cargo fmt --check

echo "Clippy..."
cargo clippy --all-targets -- -D warnings

echo "Tests..."
cargo test

echo "All checks passed!"
