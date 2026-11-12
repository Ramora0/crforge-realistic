/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RigTest {

  /**
   * A glb as the extractor writes one: a zup_to_yup root (dropped), one joint that a clip lifts 1
   * along z over a second, one triangle skinned to it.
   */
  private static byte[] glb() {
    ByteBuffer bin = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
    float[] pos = {0, 0, 0, 1, 0, 0, 0, 1, 0};
    for (float f : pos) bin.putFloat(f); // 0..36
    for (int i = 0; i < 3; i++)
      bin.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0); // 36..48
    for (int i = 0; i < 3; i++) bin.putFloat(1).putFloat(0).putFloat(0).putFloat(0); // 48..96
    float[] ibm = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    for (float f : ibm) bin.putFloat(f); // 96..160
    bin.putFloat(0).putFloat(1); // times 160..168
    bin.putFloat(0).putFloat(0).putFloat(0).putFloat(0).putFloat(0).putFloat(1); // 168..192
    bin.putShort((short) 0).putShort((short) 1).putShort((short) 2).putShort((short) 0); // 192..200
    String json =
        """
        {"asset": {"version": "2.0"},
         "nodes": [{"name": "zup_to_yup", "rotation": [-0.7071068, 0, 0, 0.7071068], "children": [1]},
                   {"name": "Root"}, {"name": "mesh", "mesh": 0, "skin": 0}],
         "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "JOINTS_0": 1, "WEIGHTS_0": 2},
                                     "indices": 6}]}],
         "skins": [{"joints": [1], "inverseBindMatrices": 3}],
         "animations": [{"name": "lift", "channels": [{"sampler": 0,
            "target": {"node": 1, "path": "translation"}}],
            "samplers": [{"input": 4, "output": 5, "interpolation": "LINEAR"}]}],
         "buffers": [{"byteLength": 256}],
         "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36},
                         {"buffer": 0, "byteOffset": 36, "byteLength": 12},
                         {"buffer": 0, "byteOffset": 48, "byteLength": 48},
                         {"buffer": 0, "byteOffset": 96, "byteLength": 64},
                         {"buffer": 0, "byteOffset": 160, "byteLength": 8},
                         {"buffer": 0, "byteOffset": 168, "byteLength": 24},
                         {"buffer": 0, "byteOffset": 192, "byteLength": 6}],
         "accessors": [{"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
                       {"bufferView": 1, "componentType": 5121, "count": 3, "type": "VEC4"},
                       {"bufferView": 2, "componentType": 5126, "count": 3, "type": "VEC4"},
                       {"bufferView": 3, "componentType": 5126, "count": 1, "type": "MAT4"},
                       {"bufferView": 4, "componentType": 5126, "count": 2, "type": "SCALAR"},
                       {"bufferView": 5, "componentType": 5126, "count": 2, "type": "VEC3"},
                       {"bufferView": 6, "componentType": 5123, "count": 3, "type": "SCALAR"}]}
        """;
    byte[] j = json.getBytes(StandardCharsets.UTF_8);
    int jl = (j.length + 3) & ~3;
    ByteBuffer out = ByteBuffer.allocate(12 + 8 + jl + 8 + 256).order(ByteOrder.LITTLE_ENDIAN);
    out.putInt(0x46546C67).putInt(2).putInt(out.capacity());
    out.putInt(jl).putInt(0x4E4F534A).put(j);
    for (int i = j.length; i < jl; i++) out.put((byte) ' ');
    out.putInt(256).putInt(0x004E4942).put(bin.array());
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    b.writeBytes(out.array());
    return b.toByteArray();
  }

  @Test
  void aClipPosesTheSkinInPrefabSpaceWithoutTheViewerRoot() {
    Glb g = Glb.parse(glb());
    assertThat(g.parent(1)).isEqualTo(-1); // zup_to_yup is dropped
    assertThat(g.clips()).containsKey("lift");
    assertThat(g.clips().get("lift").length()).isEqualTo(1f);
    Rig rig = new Rig(g);
    float[] pos = new float[9];
    rig.pose(g.clips().get("lift"), 0.5f, null, 0f, 1f);
    rig.skin(0, pos, null, null);
    assertThat(pos[2]).isCloseTo(0.5f, within(1e-5f));
    assertThat(pos[3]).isCloseTo(1f, within(1e-5f));
    assertThat(pos[5]).isCloseTo(0.5f, within(1e-5f));
    // cross-faded half way from the rest pose
    rig.pose(g.clips().get("lift"), 1f, null, 0f, 1f);
    rig.skin(0, pos, null, null);
    assertThat(pos[2]).isCloseTo(1f, within(1e-5f));
    rig.pose(g.clips().get("lift"), 1f, g.clips().get("lift"), 0f, 0.5f);
    rig.skin(0, pos, null, null);
    assertThat(pos[2]).isCloseTo(0.5f, within(1e-5f));
  }

  @Test
  void aTrsMatrixRotatesScalesAndMoves() {
    float[] m = new float[16];
    float s = (float) Math.sqrt(0.5);
    // 90 degrees about z, scale 2, at (1, 2, 3)
    Rig.trs(new float[] {1, 2, 3}, 0, new float[] {0, 0, s, s}, 0, new float[] {2, 2, 2}, 0, m);
    float[] p = new float[3];
    Rig.transformPoint(m, new float[] {1, 0, 0}, 0, p, 0);
    assertThat(p[0]).isCloseTo(1f, within(1e-5f));
    assertThat(p[1]).isCloseTo(4f, within(1e-5f));
    assertThat(p[2]).isCloseTo(3f, within(1e-5f));
  }
}
