/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

/**
 * The game's {@code uber_pbr} shader ({@code assets/sc3d/shaders/uber_pbr.frag.shader}) ported to
 * desktop GLSL 1.20. The file is a FlatBuffer holding 256 GLSL ES 3.00 variants, one per set of its
 * eight defines (variant {@code i} has the define mask {@code 255 - i}; bit 0 {@code CLIP_PLANE}, 1
 * {@code FRESNEL}, 2 {@code LOD1}, 3 {@code NEW_UBER}, 4 {@code RIMLIGHT}, 5 {@code SHADOWMAP}, 6
 * {@code SHADOWMAP_SHADOW_SAMPLERS}, 7 {@code TINT}). This is the {@code TINT | CLIP_PLANE |
 * NEW_UBER} variant (DATA, line for line): GGX specular masked by the team texture's red channel, a
 * Lambert diffuse, the scene ambient, an equirectangular reflection lookup ({@code reflectioinTex},
 * mip by roughness, times the ambient's w), the team colour multiplied in where the team texture's
 * blue channel says, emission where its green says, the object's colour multiply and add, and the
 * output encoded to sRGB with the shader's own polynomial. Which variant the battle uses is
 * INFERRED: the material sets {@code enablNewUber}, the prefab enables its clipping plane, the team
 * colour needs {@code TINT}; {@code LOD1} (a cheaper path without the normal map), {@code FRESNEL}
 * ({@code fr_weight} 0 here) and {@code RIMLIGHT} (no rim parameters here) are left out, and so is
 * the shadow map (no other 3D object casts on it).
 */
final class UberPbr {

  private UberPbr() {}

  static final String VERTEX =
      """
      #version 120
      attribute vec3 a_position;
      attribute vec3 a_normal;
      attribute vec4 a_tangent;
      attribute vec2 a_texCoord0;
      uniform mat4 u_mvp;
      varying vec3 v_worldPos;
      varying vec3 v_worldNormal;
      varying vec4 v_tangent;
      varying vec2 v_texCoord;
      void main() {
        v_worldPos = a_position;
        v_worldNormal = normalize(a_normal);
        v_tangent = vec4(normalize(a_tangent.xyz), a_tangent.w);
        v_texCoord = a_texCoord0;
        gl_Position = u_mvp * vec4(a_position, 1.0);
      }
      """;

  /** The fragment shader; {@code LOD} is replaced by the reflection lookup available. */
  static final String FRAGMENT =
      """
      #version 120
      EXTENSION
      uniform sampler2D mraTex;
      uniform sampler2D normalTex;
      uniform sampler2D diffuseTex;
      uniform sampler2D teamColorTex;
      uniform sampler2D reflectioinTex;
      uniform float metalness;
      uniform float roughness;
      uniform float emissiveStrength;
      uniform vec4 u_lightDirectionTime;
      uniform vec4 u_cameraPosition;
      uniform vec4 u_dirLightColor;
      uniform vec4 u_sceneAmbientColor;
      uniform vec4 u_color;
      uniform vec4 u_clipPlane;
      uniform vec4 color_mul;
      uniform vec4 color_add;
      uniform float u_srgbDiffuse;
      uniform vec4 u_fresnel;
      uniform float u_fresnelIor;
      uniform vec4 u_rim;
      uniform vec3 u_rimParams;
      uniform vec3 u_rimDir;
      varying vec3 v_worldPos;
      varying vec3 v_worldNormal;
      varying vec4 v_tangent;
      varying vec2 v_texCoord;

      vec3 toLinear(vec3 c) {
        vec3 lo = c / 12.92;
        vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
        return mix(hi, lo, vec3(lessThanEqual(c, vec3(0.04045))));
      }

      void main() {
        // the extractor's ORM texture is the game's MRA with red and blue swapped
        vec4 orm = texture2D(mraTex, v_texCoord);
        float metal = clamp(metalness * orm.b, 0.0, 1.0);
        float rough = clamp(roughness * orm.g, 0.0, 1.0);
        float ao = orm.r;
        vec3 V = normalize(u_cameraPosition.xyz - v_worldPos);
        // the extractor's normal map holds the game's (x, y) and the rebuilt z
        vec2 nxy = texture2D(normalTex, v_texCoord).xy * 2.0 - vec2(1.0);
        float nz2 = 1.0 - dot(nxy, nxy);
        float nz = nz2 > 0.0 ? sqrt(nz2) : 0.0;
        vec3 N = normalize(v_tangent.xyz * nxy.x
            + cross(v_worldNormal, v_tangent.xyz) * v_tangent.w * nxy.y + v_worldNormal * nz);
        vec3 L = u_lightDirectionTime.xyz;
        float NdL = max(dot(N, L), 0.0);
        float NdH = max(dot(N, normalize(V + L)), 0.0);
        float NdV = max(dot(V, N), 0.0);
        vec4 diff = texture2D(diffuseTex, v_texCoord);
        vec3 base = u_srgbDiffuse > 0.5 ? toLinear(diff.rgb) : diff.rgb;
        float alpha = diff.a;
        vec4 team = texture2D(teamColorTex, v_texCoord);
        vec3 albedo = mix(base, base * u_color.rgb, vec3(team.z));
        vec3 F0 = mix(vec3(0.04), albedo, vec3(metal));
        float r2 = rough * rough;
        float a2 = r2 * r2;
        float d = ((NdH * a2) - NdH) * NdH + 1.0;
        float D = a2 / ((3.1415925 * d) * d);
        float rk = max(rough, 0.0001);
        float k = (rk * rk) * 0.7978846;
        float gl = NdL / max(NdL * (1.0 - k) + k, 0.0001);
        float gv = NdV / max(NdV * (1.0 - k) + k, 0.0001);
        float om = 1.0 - rough;
        float f5 = 1.0 - NdV;
        f5 = f5 * f5 * f5 * f5 * f5;
        vec3 F = F0 + (vec3(clamp(om, 0.0, F0.x), clamp(om, 0.0, F0.y), clamp(om, 0.0, F0.z)) - F0) * f5;
        vec3 kD = vec3(1.0) - F;
        float kM = 1.0 - metal;
        vec3 R = reflect(V, N);
        vec2 ruv = vec2(
            clamp(1.0 - ((atan(R.y, -R.x) + 3.1415925) * 0.15915495), 0.0, 1.0),
            clamp((atan(R.z, sqrt(R.x * R.x + R.y * R.y)) + 1.5707963) * 0.31830990, 0.0, 1.0));
        float lod = clamp(rough * 4.0, 0.0, 3.99) * 3.33;
        vec3 refl = LOD.xyz;
        vec3 c = ((albedo * kD * kM + F * (((team.x * (gl * gv)) * D) / ((4.0 * NdV) * NdL + 0.0001)))
                * NdL) * (u_dirLightColor.xyz * ao)
            + (u_sceneAmbientColor.xyz * ao) * albedo * kD * kM
            + ((refl * (team.x * ao)) * F) * u_sceneAmbientColor.w;
        c = mix(c, albedo * emissiveStrength, vec3(team.y));
        GLOW
        if (v_worldPos.z < u_clipPlane.w) discard;
        float a = alpha * color_mul.w;
        vec3 s1 = sqrt(max(c * color_mul.xyz + color_add.xyz, vec3(0.0)));
        vec3 s2 = sqrt(s1);
        vec3 outc = s1 * 0.5851224 + s2 * 0.7831404 - sqrt(s2) * 0.3682627;
        // premultiplied: the image is composited over the arena by the 2D batch
        gl_FragColor = vec4(clamp(outc, 0.0, 1.0) * a, a);
      }
      """;

  /**
   * The {@code FRESNEL} and {@code RIMLIGHT} variants' terms (DATA: {@code uber_pbr.frag} variant
   * 100, {@code TINT | CLIP_PLANE | NEW_UBER | FRESNEL | RIMLIGHT}, against variant 118 without
   * them), off unless a material turns them on: {@code u_fresnel} is {@code fresnelColor ·
   * fr_colorMul} and {@code fr_weight} (w below 0: no {@code FRESNEL}), {@code u_fresnelIor} {@code
   * fr_IOR}; {@code u_rim} {@code rimLightColor · rl_colorMul} and {@code rl_weight}, {@code
   * u_rimParams} the light's direction ({@code rimLightDir}, a view-space direction turned to world
   * space by the inverse view, as the shader's {@code u_invView} does) packed with {@code rl_pow}
   * and {@code rl_lowTolerance} in the uniforms below. The colour multiply is the shader's own when
   * its {@code scroll_disable_uniform_opt} constant is set, else (INFERRED) done before upload. A
   * zero direction (a timeline's) leaves the rim's facing term out (INFERRED: GLSL's normalize of
   * zero is undefined; the term then reads as 0 on common GPUs).
   */
  static final String GLOW =
      """
        if (u_fresnel.w >= 0.0) {
          float cth = abs(NdV);
          float g2 = u_fresnelIor * u_fresnelIor - 1.0 + cth * cth;
          float fr = 1.0;
          if (g2 > 0.0) {
            float g = sqrt(g2);
            float gm = g - cth;
            float gp = g + cth;
            float q = gm / gp;
            float q2 = (cth * gp - 1.0) / (cth * gm + 1.0);
            fr = 0.5 * q * q * (1.0 + q2 * q2);
          }
          float fw = fr * u_fresnel.w;
          c = mix(c, u_fresnel.xyz, vec3(fw));
          alpha = clamp(alpha + fw, 0.05, 1.0);
        }
        if (u_rim.w != 0.0) {
          float rd = length(u_rimDir);
          float facing = rd > 0.0001 ? max(dot(u_rimDir / rd, N), 0.0) : 0.0;
          c += u_rim.xyz * (pow(smoothstep(u_rimParams.y, 1.0, (1.0 - NdV) * (1.0 - facing)),
              u_rimParams.x) * ((u_rim.w * team.x) * ao));
        }
      """;

  /**
   * Sets the glow uniforms: {@code glow} is {fresnel rgb (times its multiply), fr_weight, fr_IOR,
   * rim rgb (times its multiply), rl_weight, rl_pow, rl_lowTolerance, rim direction xyz (world)},
   * or null for neither.
   */
  static void setGlow(com.badlogic.gdx.graphics.glutils.ShaderProgram p, float[] glow) {
    if (glow == null) {
      p.setUniformf("u_fresnel", 0f, 0f, 0f, -1f);
      p.setUniformf("u_rim", 0f, 0f, 0f, 0f);
      return;
    }
    p.setUniformf("u_fresnel", glow[0], glow[1], glow[2], glow[3]);
    p.setUniformf("u_fresnelIor", glow[4]);
    p.setUniformf("u_rim", glow[5], glow[6], glow[7], glow[8]);
    p.setUniformf("u_rimParams", glow[9], glow[10], 0f);
    p.setUniformf("u_rimDir", glow[11], glow[12], glow[13]);
  }

  /** The fragment shader with {@code textureLod} through {@code GL_ARB_shader_texture_lod}. */
  static String fragment(boolean lod) {
    return FRAGMENT
        .replace("GLOW", GLOW)
        .replace("EXTENSION", lod ? "#extension GL_ARB_shader_texture_lod : require" : "")
        .replace(
            "LOD.xyz",
            lod
                ? "texture2DLod(reflectioinTex, ruv, lod).xyz"
                : "texture2D(reflectioinTex, ruv, lod - 2.0).xyz");
  }

  /**
   * The planar shadow ({@code planar_shadow.vert}): each vertex slid along the light onto the
   * ground ({@code p - L * p.z / L.z}), the parts below the clipping plane left out ({@code
   * planar_shadow.frag}); the mask is then blurred and tinted as {@code shadow_2d_composite} does.
   */
  static final String SHADOW_VERTEX =
      """
      #version 120
      attribute vec3 a_position;
      uniform mat4 u_mvp;
      uniform vec4 u_lightDirectionTime;
      varying float v_z;
      void main() {
        vec3 p = a_position;
        vec3 q = abs(u_lightDirectionTime.z) < 0.001 ? p
            : p - u_lightDirectionTime.xyz * (p.z / u_lightDirectionTime.z);
        v_z = p.z;
        gl_Position = u_mvp * vec4(q.xy, 0.0, 1.0);
      }
      """;

  static final String SHADOW_FRAGMENT =
      """
      #version 120
      uniform vec4 u_clipPlane;
      varying float v_z;
      void main() {
        float s = step(u_clipPlane.w, v_z);
        gl_FragColor = vec4(s, 0.0, 0.0, s);
      }
      """;

  /**
   * {@code shadow_2d_composite}: four taps of the mask at {@code ±u_offset}, summed and capped at
   * 1, times the shadow colour ({@code u_c}, here the batch's premultiplied vertex colour).
   */
  static final String COMPOSITE_VERTEX =
      """
      attribute vec4 a_position; attribute vec4 a_color; attribute vec2 a_texCoord0;
      uniform mat4 u_projTrans; varying vec4 v_color; varying vec2 v_texCoords;
      void main() { v_color = a_color; v_color.a = v_color.a * (255.0/254.0);
        v_texCoords = a_texCoord0; gl_Position = u_projTrans * a_position; }
      """;

  static final String COMPOSITE_FRAGMENT =
      """
      #ifdef GL_ES
      precision mediump float;
      #endif
      varying vec4 v_color; varying vec2 v_texCoords; uniform sampler2D u_texture;
      uniform vec2 u_offset;
      void main() {
        float s = texture2D(u_texture, v_texCoords + u_offset).r
            + texture2D(u_texture, v_texCoords + vec2(-u_offset.x, u_offset.y)).r
            + texture2D(u_texture, v_texCoords - u_offset).r
            + texture2D(u_texture, v_texCoords + vec2(u_offset.x, -u_offset.y)).r;
        gl_FragColor = v_color * min(s, 1.0);
      }
      """;
}
