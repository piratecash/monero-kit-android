#!/usr/bin/env bash
# Packs natives/desktop/prebuilt into release assets and uploads them to a draft release of <tag>.
# Runs where the natives were built (it re-reads the monero tree); publishing the draft stays manual,
# after natives-smoke.yml passed on it.
# Usage: scripts/release-natives.sh [--dry-run] <tag>
set -euo pipefail

KIT=$(cd "$(dirname "$0")/.." && pwd)
source "$KIT/natives/desktop/lib.sh"
PREBUILT=$KIT/natives/desktop/prebuilt
MONERO=${MONERO_SRC:-$HOME/Work/xmrwallet/external-libs/monero}
TARGETS=(linux-x64 macos-arm64 windows-x64)

DRY_RUN=false
if [[ ${1:-} == --dry-run ]]; then DRY_RUN=true; shift; fi
TAG=${1:?usage: $0 [--dry-run] <tag>}

fail() { echo "release-natives: $*" >&2; exit 1; }

library() {
    case $1 in
        linux-x64) echo libmonerujo.so ;;
        macos-arm64) echo libmonerujo.dylib ;;
        windows-x64) echo monerujo.dll ;;
    esac
}

# Debug info as build.sh splits it off; the file inside the dSYM bundle carries the DWARF.
symbols() {
    case $1 in
        macos-arm64) echo "$(library "$1").dSYM" ;;
        *) echo "$(library "$1").debug" ;;
    esac
}

dwarf_file() {
    case $1 in
        macos-arm64) echo "$(symbols "$1")/Contents/Resources/DWARF/$(library "$1")" ;;
        *) symbols "$1" ;;
    esac
}

# Words `file -b` must print for the library of each target.
expected_kind() {
    case $1 in
        linux-x64) echo 'ELF 64-bit|x86-64' ;;
        macos-arm64) echo 'Mach-O 64-bit|arm64' ;;
        windows-x64) echo 'PE32+|x86-64' ;;
    esac
}

check_target() {
    local target=$1 dir=$PREBUILT/$1 lib kind word words
    lib=$dir/$(library "$target")
    [[ -s $lib ]] || fail "$target: $lib is missing or empty; run natives/desktop/build.sh $target"
    [[ -s $dir/$(dwarf_file "$target") ]] ||
        fail "$target: debug symbols $dir/$(symbols "$target") are missing; run natives/desktop/build.sh $target"
    [[ -s $dir/SOURCE.sha256 ]] || fail "$target: $dir/SOURCE.sha256 is missing; run natives/desktop/build.sh $target"
    kind=$(file -b "$lib")
    IFS='|' read -r -a words <<< "$(expected_kind "$target")"
    for word in "${words[@]}"; do
        [[ $kind == *"$word"* ]] || fail "$target: $lib is '$kind', expected $(expected_kind "$target")"
    done
    [[ $(< "$dir/SOURCE.sha256") == "$CHECKOUT" ]] ||
        fail "$target: built from other sources ($(< "$dir/SOURCE.sha256")) than this checkout ($CHECKOUT); rebuild it"
}

check_monero_tree() {
    [[ -d $MONERO ]] || fail "monero tree not found at $MONERO; set MONERO_SRC"
    local current committed
    current=$(monero_tree_manifest "$MONERO" | sha256_of)
    committed=$(< "$KIT/natives/desktop/MONERO_TREE.sha256")
    [[ $current == "$committed" ]] || fail "the monero tree changed since the natives were built:" \
        "MONERO_TREE.sha256 $committed, $MONERO now $current; rebuild the natives and release a new tag"
}

# Archives keep the prebuilt/ layout, so unpacking into natives/desktop/prebuilt/ restores it.
pack() {
    local archive=$1
    shift
    tar --no-xattrs --no-mac-metadata --uid 0 --gid 0 --uname root --gname root -cf - -C "$PREBUILT" "$@" |
        gzip -n -9 > "$OUT/$archive"
}

pack_target() {
    local target=$1 archive="monerokit-desktop-$1.tar.gz" expected
    pack "$archive" "$target/$(library "$target")" "$target/SOURCE.sha256"
    pack "monerokit-desktop-symbols-$target.tar.gz" "$target/$(symbols "$target")"
    expected=$(printf '%s\n' "$target/$(library "$target")" "$target/SOURCE.sha256" | LC_ALL=C sort)
    [[ $(tar -tzf "$OUT/$archive" | LC_ALL=C sort) == "$expected" ]] ||
        fail "$archive does not hold exactly: ${expected//$'\n'/, }"
}

local_digests() {
    local asset
    for asset in "${ASSETS[@]}"; do
        printf '%s sha256:%s\n' "$asset" "$(shasum -a 256 "$OUT/$asset" | cut -d' ' -f1)"
    done | LC_ALL=C sort
}

remote_digests() {
    gh release view "$TAG" --json assets --jq '.assets[] | "\(.name) \(.digest)"' | LC_ALL=C sort
}

release_state() {
    local out
    if out=$(gh release view "$TAG" --json isDraft --jq .isDraft 2>&1); then
        echo "$out"
    elif [[ $out == *"release not found"* ]]; then
        echo absent
    else
        fail "gh release view $TAG: $out"
    fi
}

check_tag() {
    git -C "$KIT" rev-parse -q --verify "refs/tags/$TAG" > /dev/null || fail "tag $TAG does not exist; create and push it"
    [[ $(git -C "$KIT" rev-parse "$TAG^{commit}") == $(git -C "$KIT" rev-parse HEAD) ]] ||
        fail "HEAD is not $TAG: the natives are attested against the checkout"
    [[ -z $(git -C "$KIT" status --porcelain -- "${SOURCE_HASH_INPUTS[@]}") ]] ||
        fail "uncommitted changes in ${SOURCE_HASH_INPUTS[*]}: $TAG would not match the natives"
}

cd "$KIT"
CHECKOUT=$(source_hash "$KIT")
for target in "${TARGETS[@]}"; do check_target "$target"; done
check_monero_tree

OUT=$(mktemp -d "${TMPDIR:-/tmp}/monerokit-natives-$TAG.XXXXXX")
for target in "${TARGETS[@]}"; do pack_target "$target"; done
echo "$CHECKOUT" > "$OUT/SOURCE.sha256"
(cd "$OUT" && shasum -a 256 monerokit-desktop-*.tar.gz SOURCE.sha256 > natives.sha256)
ASSETS=()
for asset in "$OUT"/*.tar.gz "$OUT/SOURCE.sha256" "$OUT/natives.sha256"; do ASSETS+=("$(basename "$asset")"); done
echo "packed in $OUT:"
cat "$OUT/natives.sha256"

state=$(release_state)
case $state in
    false)
        # Public bytes may already be resolved by consumers: only an identical rerun is accepted.
        if diff -u <(local_digests) <(remote_digests); then
            echo "$TAG is already published with exactly these natives"
            exit 0
        fi
        fail "$TAG is already published with other natives; release them under a new tag" ;;
    true | absent) ;;
    *) fail "unexpected release state of $TAG: $state" ;;
esac

if $DRY_RUN; then
    echo "dry run: release $TAG is ${state/true/a draft}; would check the tag, then upload ${ASSETS[*]}"
    exit 0
fi

check_tag
# A draft keeps JitPack away from the tag until natives-smoke.yml has passed on these assets.
if [[ $state == absent ]]; then
    gh release create "$TAG" --draft --verify-tag --title "$TAG" \
        --notes "Desktop natives for linux-x64, macos-arm64 and windows-x64, with their debug symbols."
fi
# Stay in the checkout: gh resolves the repository from the working directory's git remote.
gh release upload "$TAG" --clobber "${ASSETS[@]/#/$OUT/}"
diff -u <(local_digests) <(remote_digests) || fail "the draft of $TAG does not carry exactly the local assets"
echo "draft $TAG is ready: run natives-smoke.yml for it, then: gh release edit $TAG --draft=false"
