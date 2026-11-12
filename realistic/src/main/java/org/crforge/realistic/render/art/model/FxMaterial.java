/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An {@code .rmat} material with its base materials merged (the extractor's {@code
 * sc3d/materials/*.json}): the shader the chain names ({@code uber_pbr}, {@code vfx_mask}, {@code
 * vfx_mask2}, {@code vfx_fresnel_simple}), its parameters (a material's own over its base's, DATA),
 * its textures (PNG paths) and its blend: {@code 6} additive, else alpha blended (INFERRED: 6 on
 * every {@code *_add} material, 5 on the others).
 */
public record FxMaterial(
    String path,
    String shader,
    Map<String, float[]> params,
    Map<String, Boolean> flags,
    Map<String, String> textures,
    int blend,
    int order) {

  /** Additive (the {@code *_add} materials) rather than alpha blended. */
  public boolean additive() {
    return blend == 6;
  }

  /** A parameter's values (a float is one value), or the fallback. */
  public float[] param(String name, float... fallback) {
    float[] v = params.get(name);
    return v != null ? v : fallback;
  }

  public boolean flag(String name) {
    return Boolean.TRUE.equals(flags.get(name));
  }

  /** A copy whose parameters the caller may change (a timeline's values). */
  public FxMaterial animated() {
    Map<String, float[]> p = new HashMap<>();
    params.forEach((k, v) -> p.put(k, v.clone()));
    return new FxMaterial(path, shader, p, flags, textures, blend, order);
  }

  /**
   * Reads the material at {@code path} through {@code lookup} (a material path to its JSON, null
   * when not extracted), its base chain first.
   */
  public static FxMaterial read(String path, java.util.function.Function<String, JsonNode> lookup) {
    Map<String, float[]> params = new LinkedHashMap<>();
    Map<String, Boolean> flags = new LinkedHashMap<>();
    Map<String, String> textures = new LinkedHashMap<>();
    String shader = null;
    int blend = 0;
    int order = 0;
    java.util.List<JsonNode> chain = new java.util.ArrayList<>();
    String p = path;
    for (int guard = 0; p != null && guard < 8; guard++) {
      JsonNode m = lookup.apply(p);
      if (m == null) break;
      chain.add(0, m);
      p = m.path("base").isTextual() ? m.path("base").asText() : null;
    }
    if (chain.isEmpty()) return null;
    for (JsonNode m : chain) {
      if (m.path("shader").isTextual()) shader = m.path("shader").asText();
      if (m.has("blend")) blend = m.path("blend").asInt();
      if (m.has("order")) order = m.path("order").asInt();
      var it = m.path("params").fields();
      while (it.hasNext()) {
        var e = it.next();
        JsonNode v = e.getValue();
        if (v.isBoolean()) {
          flags.put(e.getKey(), v.asBoolean());
        } else if (v.isNumber()) {
          params.put(e.getKey(), new float[] {(float) v.asDouble()});
        } else if (v.isArray()) {
          float[] f = new float[v.size()];
          for (int i = 0; i < f.length; i++) f[i] = (float) v.get(i).asDouble();
          params.put(e.getKey(), f);
        } else if (v.isTextual() && v.asText().isEmpty()) {
          textures.remove(e.getKey());
        }
      }
      var tx = m.path("textures").fields();
      while (tx.hasNext()) {
        var e = tx.next();
        textures.put(e.getKey(), e.getValue().asText());
      }
    }
    return new FxMaterial(
        path, shader == null ? "uber_pbr" : shader, params, flags, textures, blend, order);
  }

  /**
   * Sets a timeline property ({@code <param>} or {@code <param>.x|y|z|w}) on this material's
   * parameters (an animated copy).
   */
  public void set(String property, float value) {
    int dot = property.lastIndexOf('.');
    String name = property;
    int comp = 0;
    if (dot > 0 && property.length() - dot == 2 && "xyzw".indexOf(property.charAt(dot + 1)) >= 0) {
      name = property.substring(0, dot);
      comp = "xyzw".indexOf(property.charAt(dot + 1));
    }
    float[] v = params.get(name);
    if (v == null || v.length <= comp) {
      float[] n = new float[Math.max(comp + 1, v == null ? 1 : v.length)];
      if (v != null) System.arraycopy(v, 0, n, 0, v.length);
      v = n;
      params.put(name, v);
    }
    v[comp] = value;
  }
}
