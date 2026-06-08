#!/usr/bin/env bash
set -euo pipefail

REPO="${TINA_TOOLCHAIN_RELEASE_REPO:-${GITHUB_REPOSITORY:-wuxianggujun/TinaIDE}}"
TAG="${TINA_TOOLCHAIN_RELEASE_TAG:-toolchain-v0.2.4}"

if [ "$#" -eq 0 ]; then
  set -- arm64 x86_64
fi

read_prop() {
  local file="$1"
  local key="$2"
  sed -n "s/^${key}=//p" "$file" | head -n 1 | tr -d '\r'
}

expected_sha() {
  local sha_file="$1"
  local asset_name="$2"
  tr -d '\r' < "$sha_file" | awk -v asset="$asset_name" '$2 == asset { print $1; exit }'
}

restore_flavor() {
  local flavor="$1"
  local spec="app/src/${flavor}/assets/tina-toolchain/current.properties"

  if [ ! -f "$spec" ]; then
    echo "ERROR: missing tina-toolchain spec: $spec" >&2
    exit 1
  fi

  local archive
  archive="$(read_prop "$spec" "full")"
  if [ -z "$archive" ]; then
    archive="$(read_prop "$spec" "base")"
  fi
  if [ -z "$archive" ]; then
    echo "ERROR: $spec must define full or base." >&2
    exit 1
  fi

  local sha_name
  sha_name="$(read_prop "$spec" "sha256")"
  if [ -z "$sha_name" ]; then
    echo "ERROR: $spec must define sha256." >&2
    exit 1
  fi

  local asset_dir
  asset_dir="$(dirname "$spec")"
  local target="${asset_dir}/${archive}"
  local sha_file="${asset_dir}/${sha_name}"

  if [ ! -f "$sha_file" ]; then
    echo "ERROR: missing sha256 file: $sha_file" >&2
    exit 1
  fi

  local expected
  expected="$(expected_sha "$sha_file" "$archive")"
  if [ -z "$expected" ]; then
    echo "ERROR: $sha_file has no sha256 entry for $archive." >&2
    exit 1
  fi

  if [ -f "$target" ]; then
    local current
    current="$(sha256sum "$target" | awk '{ print $1 }')"
    if [ "$current" = "$expected" ]; then
      echo "tina-toolchain asset already valid: $target"
      return
    fi
  fi

  local url="https://github.com/${REPO}/releases/download/${TAG}/${archive}"
  local tmp="${target}.download"

  echo "Downloading tina-toolchain asset for ${flavor}: ${archive}"
  curl -fL \
    --retry 4 \
    --retry-all-errors \
    --connect-timeout 30 \
    --max-time 900 \
    -o "$tmp" \
    "$url"

  local actual
  actual="$(sha256sum "$tmp" | awk '{ print $1 }')"
  if [ "$actual" != "$expected" ]; then
    rm -f "$tmp"
    echo "ERROR: sha256 mismatch for $archive" >&2
    echo "expected: $expected" >&2
    echo "actual:   $actual" >&2
    exit 1
  fi

  mv "$tmp" "$target"
  ls -lh "$target"
}

for flavor in "$@"; do
  restore_flavor "$flavor"
done
