#!/bin/sh
# Download + SHA256-verify the pinned OCR models into $1 (default /opt/ocr-models).
# Used at image-build time so models are baked in and the runtime needs no network egress.
# Fails the build on any checksum mismatch — the slice-1b provenance gate (decision #105).
set -eu

DEST="${1:-/opt/ocr-models}"
LOCK="$(dirname "$0")/models.lock"
mkdir -p "$DEST"

# Read non-comment, non-blank lines: "<file> <sha256> <url>"
grep -vE '^\s*(#|$)' "$LOCK" | while read -r file sha url; do
    out="$DEST/$file"
    echo "Fetching $file ..."
    # -fSL: fail on HTTP error, follow redirects (ModelScope 302s to a CDN).
    curl -fSL --retry 3 --retry-delay 2 -o "$out" "$url"
    echo "$sha  $out" | sha256sum -c -
done

echo "All models verified against models.lock."
