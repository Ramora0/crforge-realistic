#!/bin/sh
# Rewrites a patch made in crforge (Ramora0/crforge, the realistic-render work in its desktop
# module) for this repository: file paths and Java packages (CONTRIBUTING.md, "Porting a patch").
#
#   tools/port/port-patch.sh < from-crforge.patch > for-here.patch
#   git am -3 for-here.patch        # or: git apply -3 for-here.patch
set -eu
here=$(cd "$(dirname "$0")" && pwd)
exec sed -E -f "$here/paths.sed" -f "$here/packages.sed"
