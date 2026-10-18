# Shared by build.sh and the release script; source it, do not execute it.

# Paths that are build output or machine noise, never monero sources.
MONERO_TREE_EXCLUDES=(.git .DS_Store /build /contrib/depends/built /contrib/depends/work
    /contrib/depends/sources /contrib/depends/x86_64-linux-gnu /contrib/depends/x86_64-w64-mingw32
    /contrib/depends/aarch64-apple-darwin11)

# "<sha256>  ./<path>" for every monero source file, sorted, so two trees compare line by line.
monero_tree_manifest() {
    local prune=() e
    for e in "${MONERO_TREE_EXCLUDES[@]}"; do
        if [[ $e == /* ]]; then prune+=(-path ".$e" -o); else prune+=(-name "$e" -o); fi
    done
    (cd "$1" && find . \( "${prune[@]}" -false \) -prune -o -type f -print0 \
        | LC_ALL=C sort -z | xargs -0 shasum -a 256)
}

sha256_of() { shasum -a 256 | cut -d' ' -f1; }

# Same digest as `git ls-files -s` over the committed inputs (bitcoin-kit computeSourceHash),
# computed from the working tree so a build can attest contents that are not committed yet.
SOURCE_HASH_INPUTS=(monerokit/src/main/cpp/monerujo.cpp monerokit/src/main/cpp/monerujo.h
    monerokit/CMakeLists.txt natives/desktop)

source_hash() {
    local kit=$1 path mode
    git -C "$kit" ls-files -co --exclude-standard -- "${SOURCE_HASH_INPUTS[@]}" | LC_ALL=C sort \
        | while IFS= read -r path; do
            mode=100644; [[ -x $kit/$path ]] && mode=100755
            printf '%s %s 0\t%s\n' "$mode" "$(git -C "$kit" hash-object -- "$path")" "$path"
        done | sha256_of
}
