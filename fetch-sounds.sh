#!/usr/bin/env bash
# Downloads Minecraft's button click (the game's only menu sound, ui.button.click) from Mojang's
# asset server to sounds/click.ogg, where build.sh picks it up. It's Mojang's, so it stays out of
# the repository. Does nothing if the right file is already there.
set -euo pipefail
cd "$(dirname "$0")"

URL=https://resources.download.minecraft.net/f0/f0ca66561f832bf2f60b393837297c2692367cd5
SHA1=f0ca66561f832bf2f60b393837297c2692367cd5
FILE=sounds/click.ogg

sha1() { sha1sum "$1" | cut -d' ' -f1; }

if [[ -f "$FILE" && "$(sha1 "$FILE")" == "$SHA1" ]]; then
  echo "$FILE is already there"
  exit 0
fi

mkdir -p sounds
PART=$FILE.part
trap 'rm -f "$PART"' EXIT
curl -fsSL --retry 3 -o "$PART" "$URL"
GOT=$(sha1 "$PART")
if [[ "$GOT" != "$SHA1" ]]; then
  echo "Wrong file from $URL: sha1 $GOT, expected $SHA1" >&2
  exit 1
fi
mv "$PART" "$FILE"
echo "$FILE downloaded"
