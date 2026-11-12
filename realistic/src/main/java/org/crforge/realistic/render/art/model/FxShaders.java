/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

/**
 * The game's effect shaders ({@code assets/sc3d/shaders/vfx_mask*.shader}, {@code
 * vfx_fresnel_simple.shader}: FlatBuffers of GLSL ES 3.00 variants, as {@link UberPbr}) ported to
 * desktop GLSL 1.20, their defines turned into uniforms (DATA, line for line):
 *
 * <ul>
 *   <li>{@code vfx_mask} (8 variants: {@code DISTORTION}, {@code DISSOLVE}, {@code COLOR_AS_ALPHA};
 *       variant {@code i} has the mask {@code 7 - i}, bit 0 {@code COLOR_AS_ALPHA}, 1 {@code
 *       DISSOLVE}, 2 {@code DISTORTION}, read off what each variant samples): the colour is {@code
 *       mainColor}, times the main texture ({@code mainTex · mainColor}) or with the texture's red
 *       as its alpha ({@code COLOR_AS_ALPHA}); the alpha times the mask's selected channel raised
 *       to {@code mask_speedxy_pow_mul.z} times {@code .w}, and times the dissolve's smoothstep.
 *       Every texture's UV is {@code uv · tex_UV.xy + tex_UV.zw + speed · time}, the distortion's
 *       selected channel shifting the main and mask lookups. The output is not converted (the
 *       colours are display values).
 *   <li>{@code vfx_mask2}: the same with a second mask ({@code maskTex2}, its red mixing the
 *       dissolve out where it is 1: {@code MASK_2_DISSOVE}) and no {@code max(…, 0.001)} before the
 *       mask's power.
 *   <li>{@code vfx_fresnel_simple}: {@code mainColor} plus {@code fnl_color} times {@code pow(1 −
 *       N·V, fnl_power) · fnl_int}, both decoded from sRGB by the shader's polynomial and the sum
 *       encoded back with its square-root polynomial; alpha {@code fnl_color.w} times the vertex
 *       colour's (taken as 1).
 * </ul>
 *
 * <p>The vertex shader takes positions already placed (billboards turned to the camera on the CPU:
 * {@code vfx_mask.vert}'s {@code BILLBOARD} basis, see {@link FxRenderer}). The fragment output is
 * premultiplied for the model's image: {@code (rgb · a, a)} for an alpha-blended material, {@code
 * (rgb · a, 0)} for an additive one (blended {@code ONE, ONE_MINUS_SRC_ALPHA}, both).
 */
final class FxShaders {

  private FxShaders() {}

  static final String VERTEX =
      """
      #version 120
      attribute vec3 a_position;
      attribute vec3 a_normal;
      attribute vec2 a_texCoord0;
      uniform mat4 u_mvp;
      varying vec3 v_worldPos;
      varying vec3 v_worldNormal;
      varying vec2 v_texCoord;
      void main() {
        v_worldPos = a_position;
        v_worldNormal = a_normal;
        v_texCoord = a_texCoord0;
        gl_Position = u_mvp * vec4(a_position, 1.0);
      }
      """;

  /** {@code vfx_mask} and {@code vfx_mask2} with their defines as uniforms. */
  static final String MASK =
      """
      #version 120
      uniform sampler2D mainTex;
      uniform sampler2D maskTex;
      uniform sampler2D maskTex2;
      uniform sampler2D distortionTex;
      uniform sampler2D dissolveTex;
      uniform vec4 mainTex_UV;
      uniform vec4 maskTex_UV;
      uniform vec4 mask_speedxy_pow_mul;
      uniform vec4 mask_rgba_selector;
      uniform vec4 distortionTex_UV;
      uniform vec4 dist_speedxy_wmain_wmask;
      uniform vec4 dist_rgba_selector;
      uniform vec4 dissolveTex_UV;
      uniform vec4 disv_rgba_selector;
      uniform vec4 mainColor;
      uniform vec2 disv_speedxy;
      uniform vec2 main_speedxy;
      uniform float disv_amount;
      uniform float disv_edge_hardness;
      uniform float u_time;
      uniform vec4 u_defines; // DISTORTION, DISSOLVE, COLOR_AS_ALPHA, MASK_2_DISSOLVE
      uniform float u_mask2;  // vfx_mask2 (no floor before the mask's power)
      uniform float u_additive;
      uniform float u_alphaMul;
      varying vec2 v_texCoord;
      void main() {
        float dist = 0.0;
        if (u_defines.x > 0.5) {
          dist = dot(texture2D(distortionTex, v_texCoord * distortionTex_UV.xy + distortionTex_UV.zw
              + dist_speedxy_wmain_wmask.xy * u_time), dist_rgba_selector);
        }
        vec2 muv = v_texCoord * mainTex_UV.xy + mainTex_UV.zw + main_speedxy * u_time
            + vec2(dist * dist_speedxy_wmain_wmask.z);
        vec4 col = mainColor;
        if (u_defines.z > 0.5) {
          col.w = col.w * texture2D(mainTex, muv).x;
        } else {
          col = texture2D(mainTex, muv) * mainColor;
        }
        vec2 kuv = v_texCoord * maskTex_UV.xy + maskTex_UV.zw + mask_speedxy_pow_mul.xy * u_time
            + vec2(dist * dist_speedxy_wmain_wmask.w);
        float m = dot(texture2D(maskTex, kuv), mask_rgba_selector);
        if (u_mask2 < 0.5) m = max(m, 0.001);
        col.w = col.w * clamp(pow(m, mask_speedxy_pow_mul.z) * mask_speedxy_pow_mul.w, 0.0, 1.0);
        if (u_defines.y > 0.5) {
          float d = dot(texture2D(dissolveTex, v_texCoord * dissolveTex_UV.xy + dissolveTex_UV.zw
              + disv_speedxy * u_time), disv_rgba_selector);
          float s = smoothstep(disv_edge_hardness, 1.0 - disv_edge_hardness,
              clamp(d + 1.0 - disv_amount * 2.0, 0.0, 1.0));
          if (u_defines.w > 0.5) s = mix(s, 1.0, texture2D(maskTex2, v_texCoord).x);
          col.w = col.w * s;
        }
        // an additive draw keeps an alpha over 1 (the game's HDR target), a blended one clamps it
        float a = max(col.w * u_alphaMul, 0.0);
        if (u_additive < 0.5) a = min(a, 1.0);
        vec3 rgb = max(col.xyz, vec3(0.0)) * a;
        gl_FragColor = vec4(rgb, u_additive > 0.5 ? 0.0 : a);
      }
      """;

  /** {@code vfx_fresnel_simple}. */
  static final String FRESNEL =
      """
      #version 120
      uniform vec4 mainColor;
      uniform vec4 fnl_color;
      uniform float fnl_power;
      uniform float fnl_int;
      uniform vec4 u_cameraPosition;
      uniform float u_additive;
      uniform float u_alphaMul;
      varying vec3 v_worldPos;
      varying vec3 v_worldNormal;
      varying vec2 v_texCoord;
      vec3 lin(vec3 c) { return c * 0.012522878 + c * 0.6821711 * c + c * 0.305306 * c * c; }
      void main() {
        vec3 V = normalize(u_cameraPosition.xyz - v_worldPos);
        float f = exp2(fnl_power * log2(max(1.0 - max(dot(normalize(v_worldNormal), V), 0.0),
            0.0001))) * fnl_int;
        vec3 s1 = sqrt(max(lin(mainColor.xyz) + lin(fnl_color.xyz) * f, vec3(0.0)));
        vec3 s2 = sqrt(s1);
        vec3 c = s1 * 0.5851224 + s2 * 0.7831404 - sqrt(s2) * 0.3682627;
        float a = clamp(fnl_color.w * u_alphaMul, 0.0, 1.0);
        gl_FragColor = vec4(max(c, vec3(0.0)) * a, u_additive > 0.5 ? 0.0 : a);
      }
      """;
}
