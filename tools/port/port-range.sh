#!/bin/sh
# Ports a range of crforge commits (Ramora0/crforge, the realistic-render work) into this
# repository as commits of its own, keeping their authors and messages (CONTRIBUTING.md):
#
#   tools/port/port-range.sh /path/to/crforge 7f6d6bcf..fix/polish3d          # applies them
#   tools/port/port-range.sh /path/to/crforge 7f6d6bcf..fix/polish3d --check  # only checks
#
# Only the parts this repository holds are taken: the realistic renderer, the server, their
# resources and tests, the bridge classes, tools/game-art, tools/phone-render and the two docs.
# A commit's changes to other crforge files (DesktopLauncher, BattleRenderer, ...) are left out
# and listed, to be redone by hand in RealisticLauncher if they matter here.
set -eu
repo=$1
range=$2
mode=${3:-apply}
here=$(cd "$(dirname "$0")" && pwd)
top=$(cd "$here/../.." && pwd)
D=desktop/src
B=org/crforge/desktop
paths="$D/main/java/$B/render/art $D/test/java/$B/render/art $D/main/java/$B/server $D/test/java/$B/server
 $D/main/resources/$B/server
 $D/main/java/$B/battle/ActionMeter.java $D/main/java/$B/battle/AreaHitLog.java
 $D/main/java/$B/battle/BattleAdapter.java $D/main/java/$B/battle/BattleDecks.java
 $D/main/java/$B/battle/BattleFrame.java $D/main/java/$B/battle/BattleSession.java
 $D/main/java/$B/battle/EntityView.java $D/main/java/$B/battle/LandingTiles.java
 $D/main/java/$B/battle/UnitRole.java $D/main/java/$B/battle/UnitStatus.java
 $D/main/java/$B/render/RenderConstants.java $D/main/java/$B/render/ViewOrientation.java
 $D/main/java/$B/replay/ReplayFile.java $D/main/java/$B/replay/ReplayPlayer.java
 $D/main/java/$B/DataSelection.java
 tools/game-art tools/phone-render docs/realistic-rendering.md docs/phone-server.md"
left=""
for f in $(git -C "$repo" diff --name-only "$range"); do
  kept=no
  for p in $paths; do
    case "$f" in "$p" | "$p"/*) kept=yes ;; esac
  done
  [ $kept = no ] && left="$left  $f
"
done
if [ -n "$left" ]; then
  printf 'Left out of %s (not in this repository):\n%s' "$range" "$left" >&2
fi
patch=$(mktemp)
# shellcheck disable=SC2086
git -C "$repo" format-patch --stdout "$range" -- $paths | "$here/port-patch.sh" > "$patch"
cd "$top"
if [ "$mode" = "--check" ]; then
  git apply --check "$patch" && echo "the range applies cleanly" >&2
else
  git am -3 "$patch"
fi
rm -f "$patch"
