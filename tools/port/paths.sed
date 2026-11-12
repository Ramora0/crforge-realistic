# Maps crforge desktop's file paths to this repository's (a patch's diff --git, ---, +++ lines).
# Extended regular expressions: run with sed -E (BSD or GNU sed).
s#desktop/src/(main|test)/java/org/crforge/desktop/render/art/#realistic/src/\1/java/org/crforge/realistic/render/art/#g
s#desktop/src/(main|test)/java/org/crforge/desktop/server/#realistic/src/\1/java/org/crforge/realistic/server/#g
s#desktop/src/main/resources/org/crforge/desktop/server/#realistic/src/main/resources/org/crforge/realistic/server/#g
s#desktop/src/(main|test)/java/org/crforge/desktop/battle/([A-Za-z]+\.java)#realistic/src/\1/java/org/crforge/realistic/bridge/\2#g
s#desktop/src/(main|test)/java/org/crforge/desktop/render/(ViewOrientation|RenderConstants)(Test)?\.java#realistic/src/\1/java/org/crforge/realistic/bridge/\2\3.java#g
s#desktop/src/(main|test)/java/org/crforge/desktop/replay/(ReplayFile|ReplayPlayer)(Test)?\.java#realistic/src/\1/java/org/crforge/realistic/bridge/\2\3.java#g
s#desktop/src/(main|test)/java/org/crforge/desktop/DataSelection(Test)?\.java#realistic/src/\1/java/org/crforge/realistic/bridge/DataSelection\2.java#g
