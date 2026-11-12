# Maps crforge desktop's Java package names (Ramora0/crforge, branch realistic-render) to this
# repository's. Extended regular expressions: run with sed -E (BSD or GNU sed).
# Used on Java sources and on patches: see tools/port/port-patch.sh and CONTRIBUTING.md.
s/org\.crforge\.desktop\.render\.art/org.crforge.realistic.render.art/g
s/org\.crforge\.desktop\.server/org.crforge.realistic.server/g
s/org\.crforge\.desktop\.battle\.([A-Z])/org.crforge.realistic.bridge.\1/g
s/org\.crforge\.desktop\.render\.(ViewOrientation|RenderConstants)/org.crforge.realistic.bridge.\1/g
s/org\.crforge\.desktop\.replay\.(ReplayFile|ReplayPlayer)/org.crforge.realistic.bridge.\1/g
s/org\.crforge\.desktop\.DataSelection/org.crforge.realistic.bridge.DataSelection/g
s/^([+ -]?)package org\.crforge\.desktop\.(battle|replay|render);/\1package org.crforge.realistic.bridge;/
s/^([+ -]?)package org\.crforge\.desktop;/\1package org.crforge.realistic.bridge;/
