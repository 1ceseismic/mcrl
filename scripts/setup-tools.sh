#!/usr/bin/env bash
# Builds the converter's tools from pinned upstream sources and installs them where the converter looks:
# umodel (UE Viewer with umodel.patch) and vgmstream-cli.
#
# usage: scripts/setup-tools.sh [--prefix DIR]
#   --prefix DIR   install into DIR (default: ${XDG_DATA_HOME:-~/.local/share}/mcrl/tools); sources go to DIR/src
set -euo pipefail

UMODEL_REPO=https://github.com/gildor2/UEViewer
UMODEL_COMMIT=a0bfb468d42be831b126632fd8a0ae6b3614f981
VGMSTREAM_REPO=https://github.com/vgmstream/vgmstream
VGMSTREAM_COMMIT=db77f98f405a45ad08df98aa444cae3848f95e6c

PATCH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/umodel.patch"
prefix="${XDG_DATA_HOME:-$HOME/.local/share}/mcrl/tools"

die() { echo "setup-tools: $*" >&2; exit 1; }
usage() { sed -n '5,6p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2; exit 2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --prefix) [ $# -ge 2 ] && [ -n "$2" ] || usage; prefix=$2; shift 2 ;;
    *) usage ;;
  esac
done
mkdir -p "$prefix"
prefix="$(cd "$prefix" && pwd)"
src="$prefix/src"

missing=""
for c in git make perl gcc g++ cmake pkg-config; do
  command -v "$c" >/dev/null || missing="$missing $c"
done
if command -v pkg-config >/dev/null; then
  for p in sdl2 zlib libpng gl vorbisfile ogg; do
    pkg-config --exists "$p" || missing="$missing $p"
  done
fi
[ -z "$missing" ] || die "missing:$missing (install them, with their development headers)"

# step <log> <command...>: runs the command into the log; on failure shows the log's tail.
step() {
  local log=$1; shift
  "$@" > "$log" 2>&1 || { tail -n 30 "$log" >&2; die "failed: $* (log: $log)"; }
}

# checkout <dir> <repo> <commit>: a clean tree of the repo at the commit.
checkout() {
  local dir=$1 repo=$2 commit=$3
  [ -d "$dir/.git" ] || git clone -q --filter=blob:none --no-checkout "$repo" "$dir"
  git -C "$dir" cat-file -e "$commit^{commit}" 2>/dev/null || git -C "$dir" fetch -q origin
  git -C "$dir" -c advice.detachedHead=false checkout -q -f "$commit"
}

echo "setup-tools: UE Viewer $UMODEL_COMMIT into $prefix"
checkout "$src/UEViewer" "$UMODEL_REPO" "$UMODEL_COMMIT"
git -C "$src/UEViewer" apply "$PATCH"
# build.sh's default output name would replace upstream's tracked 32-bit umodel.
step "$src/umodel-build.log" env -C "$src/UEViewer" ./build.sh --exe umodel_linux64
install -m 755 "$src/UEViewer/umodel_linux64" "$prefix/umodel"

echo "setup-tools: vgmstream $VGMSTREAM_COMMIT into $prefix/vgmstream"
checkout "$src/vgmstream" "$VGMSTREAM_REPO" "$VGMSTREAM_COMMIT"
step "$src/vgmstream-configure.log" cmake -S "$src/vgmstream" -B "$src/vgmstream/build" -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_CLI=ON -DBUILD_V123=OFF -DBUILD_AUDACIOUS=OFF -DUSE_VORBIS=ON -DUSE_MPEG=OFF -DUSE_FFMPEG=OFF \
  -DUSE_G719=OFF -DUSE_CELT=OFF -DUSE_SPEEX=OFF -DUSE_ATRAC9=OFF -DUSE_G7221=OFF -DFETCHCONTENT_FULLY_DISCONNECTED=ON
grep -q 'Vorbis: ON' "$src/vgmstream-configure.log" || die "vgmstream was configured without Vorbis (log: $src/vgmstream-configure.log)"
step "$src/vgmstream-build.log" cmake --build "$src/vgmstream/build" --target vgmstream_cli -j 4
install -D -m 755 "$src/vgmstream/build/cli/vgmstream-cli" "$prefix/vgmstream/vgmstream-cli"

printf 'repo: %s\ncommit: %s\npatch: umodel.patch, sha256 %s\n' "$UMODEL_REPO" "$UMODEL_COMMIT" \
  "$(sha256sum "$PATCH" | cut -d' ' -f1)" > "$prefix/umodel.VERSION"
printf 'repo: %s\ncommit: %s\n' "$VGMSTREAM_REPO" "$VGMSTREAM_COMMIT" > "$prefix/vgmstream/VERSION"
echo "setup-tools: installed $prefix/umodel and $prefix/vgmstream/vgmstream-cli"
