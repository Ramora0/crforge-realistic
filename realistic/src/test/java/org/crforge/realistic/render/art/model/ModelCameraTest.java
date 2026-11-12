/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ModelCameraTest {

  private static final float EPS = 1e-4f;

  @Test
  void theCameraSquashesTheGroundByTheCosineOfItsTiltAndRaisesHeightsByTheSine() {
    // a metre along the ground away from the viewer (-Y) goes up the screen by cos(36.8)
    assertThat(ModelCamera.screenY(0, -1, 0)).isCloseTo(0.8007f, within(EPS));
    // a metre up goes up the screen by sin(36.8)
    assertThat(ModelCamera.screenY(0, 0, 1)).isCloseTo(0.5990f, within(EPS));
    // screen right is prefab -X
    assertThat(ModelCamera.screenX(-1, 0, 0)).isEqualTo(1f);
    // nearer the camera: the +Y side and up
    assertThat(ModelCamera.towardsCamera(0, 1, 0)).isPositive();
    assertThat(ModelCamera.towardsCamera(0, 0, 1)).isPositive();
  }

  @Test
  void theEnvironmentCameraLooksAtItsRootFromAboveThePlusYSide() {
    // arn_default: camera_root rot (-36.8, 0, 0), camera at (0, 0, 20) rot (-90, -179.9999, 0)
    float[] look = ModelCamera.forward(-90f, -179.9999f, 0f); // in the root's space
    assertThat(look[2]).isCloseTo(-1f, within(EPS)); // from +z towards the root
    float[] lookWorld = ModelCamera.rotate(look, 'x', -36.8f);
    float[] position = ModelCamera.rotate(new float[] {0, 0, 20}, 'x', -36.8f);
    float[] cam = ModelCamera.cameraPosition();
    for (int i = 0; i < 3; i++) {
      assertThat(position[i]).isCloseTo(cam[i], within(1e-3f));
      // it looks back at the origin
      assertThat(lookWorld[i]).isCloseTo(-cam[i] / ModelCamera.DISTANCE, within(1e-3f));
    }
    assertThat(cam[1]).isPositive();
  }

  @Test
  void aHeadingTurnsTheDefaultUpFacingOntoTheScreenDirection() {
    assertThat(ModelCamera.headingRadians(0, 1)).isCloseTo(0f, within(EPS));
    // facing right on screen is prefab -X: -Y turned by -90 degrees
    float right = ModelCamera.headingRadians(1, 0);
    float x = (float) Math.sin(right);
    float y = (float) -Math.cos(right);
    assertThat(ModelCamera.screenX(x, y, 0)).isCloseTo(1f, within(EPS));
    assertThat(ModelCamera.screenY(x, y, 0)).isCloseTo(0f, within(EPS));
    // facing down the screen: half a turn
    assertThat(Math.abs(ModelCamera.headingRadians(0, -1))).isCloseTo((float) Math.PI, within(EPS));
  }

  @Test
  void theLightsShineDownAndThePrefabSunCastsToTheLeft() {
    // arn_default's light: from above, screen left (+X) and the far side (-Y)
    float[] env = ModelCamera.towardsLight(-60f, -179.9999f, -41f);
    assertThat(env[2]).isPositive();
    assertThat(env[0]).isPositive();
    assertThat(env[1]).isNegative();
    // the Hero Tombstone's sun: from above and screen right (-X); a shadow p - L p.z / L.z goes
    // to +X, screen left, as real battles show the Tomb Queen's
    float[] sun = ModelCamera.towardsLight(-80.6f, 0f, -32.6f);
    assertThat(sun[2]).isGreaterThan(0.8f);
    float shadowX = -sun[0] / sun[2];
    assertThat(ModelCamera.screenX(shadowX, 0, 0)).isNegative();
    assertThat(sun[0] * sun[0] + sun[1] * sun[1] + sun[2] * sun[2]).isCloseTo(1f, within(EPS));
  }

  @Test
  void thePrefabSunCastsAShortShadowDownAndToTheLeft() {
    // read as yaw and pitch, the sun's -80.6 is its angle below the horizon
    float[] line = ModelCamera.shadowLight(-80.6f, 0f, -32.6f);
    float up = Math.abs(line[2]);
    assertThat((float) Math.toDegrees(Math.asin(up))).isCloseTo(80.6f, within(0.01f));
    // the shadow of a point 1 m up: p - L p.z / L.z, about 0.17 m out
    float sx = -line[0] / line[2];
    float sy = -line[1] / line[2];
    assertThat((float) Math.hypot(sx, sy)).isCloseTo(0.1655f, within(1e-3f));
    // to the screen's left, and towards the viewer (down the screen)
    assertThat(ModelCamera.screenX(sx, sy, 0)).isNegative();
    assertThat(ModelCamera.screenY(sx, sy, 0)).isNegative();
    // the planar shadow is the same for either sign of the line
    float[] flipped = ModelRenderer.shadowLine(null);
    assertThat(flipped[2]).isPositive();
  }

  @Test
  void theEnvironmentIsReadFromTheIndex() throws Exception {
    String json =
        """
        {"globals": {"VANILLA_3D_ACTOR_COLORS": [733581, 11077383], "VANILLA_3D_PPM": [100, 100, 100],
          "BLOB_SHADOW_SOFT_EDGE": [0.35], "BLOB_SHADOW_RADIUS_FACTOR": [0.45]},
         "lights": [{"node": "light", "rot": [-60.000004, -179.9999, -40.999992], "Intensity": 2,
           "MainLight": true, "AmbientSpecIntensity": 2.452, "Color": {"r": 255, "g": 255, "b": 255},
           "Ambient": {"r": 132, "g": 115, "b": 106}}],
         "cameras": [{"node": "camera", "chain": [{"name": "camera_root", "pos": [0, 0, 0],
           "rot": [-36.8, 0, 0]}, {"name": "camera", "pos": [0, 0, 20], "rot": [-90, -179.9999, 0]}]}]}
        """;
    Sc3dLibrary.Environment env = Sc3dLibrary.readEnvironment(new ObjectMapper().readTree(json));
    assertThat(env.tiltDegrees()).isCloseTo(ModelCamera.TILT_DEGREES, within(EPS));
    assertThat(env.duPerMetre()).isEqualTo(ModelCamera.DU_PER_METRE);
    assertThat(env.blueTeam()).isEqualTo(0x0B318D);
    assertThat(env.redTeam()).isEqualTo(0xA90707);
    assertThat(env.light().intensity()).isEqualTo(2f);
    assertThat(env.light().ambient()[0]).isCloseTo(132 / 255f, within(EPS));
    assertThat(env.light().ambientSpecular()).isCloseTo(2.452f, within(EPS));
    // an index without an environment falls back to this data version's values
    assertThat(Sc3dLibrary.readEnvironment(new ObjectMapper().readTree("{}")))
        .isEqualTo(Sc3dLibrary.Environment.DEFAULT);
  }
}
