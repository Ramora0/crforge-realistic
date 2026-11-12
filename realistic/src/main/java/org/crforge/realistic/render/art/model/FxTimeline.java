/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A prefab timeline ({@code AssetPrefabTimeline}, the extractor's {@code sc3d/timelines/*.json}):
 * per node (a path below the node the timeline plays on, {@code ""} for that node), curves of named
 * properties and timed events (docs/realistic-rendering.md §10.4).
 *
 * <p>The format (DATA: every key of the battle timelines reads so): {@code anims.nodes[i]} is the
 * node of {@code anims.data[i]}, whose {@code anims} are {@code {id, value._v.timelineValue.keys}}
 * and whose {@code events} are {@code {Time, Effect | MarkerName}}. A key covers {@code s..e}
 * seconds (both 0 when left out) and holds by its type {@code t}: none (0) the constant {@code c},
 * 1 a line from {@code lf} to {@code lt}, 2 a cubic-bezier easing {@code b = [x1, y1, x2, y2, from,
 * to]} (as CSS {@code cubic-bezier}, INFERRED: the control points lie in 0..1 and the ends are the
 * values); every number left out is 0. Before the first key the curve holds the first key's start
 * value and after the last key its end value (INFERRED).
 *
 * <p>Property ids: {@code z} (position z), {@code scl_x|y|z}, {@code pitch|yaw|roll} (degrees, read
 * as the node's x, z and y rotations: INFERRED), {@code visible}, {@code
 * Model.mat0.<param>[.x|y|z|w]} (the model's material) and {@code MaterialEffect.mat.<param>[...]}
 * (the node's material asset).
 */
public final class FxTimeline {

  /** One key: its span, type and values. */
  record Key(float s, float e, int type, float c, float from, float to, float[] bezier) {

    float value(float t) {
      return switch (type) {
        case 1 -> from + (to - from) * fraction(t);
        case 2 -> from + (to - from) * ease(bezier, fraction(t));
        default -> c;
      };
    }

    float start() {
      return type == 0 ? c : from;
    }

    float end() {
      return type == 0 ? c : to;
    }

    private float fraction(float t) {
      if (e <= s) return 1f;
      return Math.max(0f, Math.min(1f, (t - s) / (e - s)));
    }
  }

  /** A curve: its keys in time order. */
  public record Curve(List<Key> keys) {

    /** The curve's value at {@code t} seconds. */
    public float at(float t) {
      if (keys.isEmpty()) return 0f;
      Key first = keys.get(0);
      if (t <= first.s()) return first.start();
      for (Key k : keys) {
        if (t <= k.e()) return t < k.s() ? k.start() : k.value(t);
      }
      return keys.get(keys.size() - 1).end();
    }
  }

  /** An event: its time and the prefab it spawns (null for a marker). */
  public record Event(float time, String effect) {}

  /** A node's curves by property id and its events. */
  public record Track(Map<String, Curve> curves, List<Event> events) {}

  private final Map<String, Track> tracks;
  private final float total;

  private FxTimeline(Map<String, Track> tracks, float total) {
    this.tracks = tracks;
    this.total = total;
  }

  /** The tracks by node path ({@code ""} for the node the timeline plays on). */
  public Map<String, Track> tracks() {
    return tracks;
  }

  /** The timeline's length, seconds ({@code total_time}). */
  public float total() {
    return total;
  }

  public static FxTimeline parse(JsonNode json) {
    Map<String, Track> tracks = new LinkedHashMap<>();
    JsonNode nodes = json.path("anims").path("nodes");
    JsonNode data = json.path("anims").path("data");
    for (int i = 0; i < data.size(); i++) {
      String node = nodes.path(i).asText("");
      Map<String, Curve> curves = new LinkedHashMap<>();
      for (JsonNode a : data.get(i).path("anims")) {
        List<Key> keys = new ArrayList<>();
        for (JsonNode k : a.path("value").path("_v").path("timelineValue").path("keys")) {
          keys.add(key(k));
        }
        curves.put(a.path("id").asText(""), new Curve(keys));
      }
      List<Event> events = new ArrayList<>();
      for (JsonNode ev : data.get(i).path("events")) {
        String effect = ev.path("Effect").asText(null);
        if (effect != null) effect = effect.split("\\|")[0].trim();
        events.add(new Event((float) ev.path("Time").asDouble(0), effect));
      }
      Track prev = tracks.get(node);
      if (prev != null) {
        prev.curves().putAll(curves);
        prev.events().addAll(events);
      } else {
        tracks.put(node, new Track(curves, events));
      }
    }
    return new FxTimeline(tracks, (float) json.path("total_time").asDouble(0));
  }

  static Key key(JsonNode k) {
    int type = k.path("t").asInt(0);
    float[] b = null;
    float from = f(k, "lf");
    float to = f(k, "lt");
    if (type == 2) {
      b = new float[4];
      JsonNode bn = k.path("b");
      for (int i = 0; i < 4; i++) b[i] = number(bn.path(i), Float.NaN);
      from = number(bn.path(4), 0f);
      to = number(bn.path(5), 0f);
    }
    return new Key(f(k, "s"), f(k, "e"), type, f(k, "c"), from, to, b);
  }

  private static float f(JsonNode k, String name) {
    return number(k.path(name), 0f);
  }

  /** A number, or the fallback for one left out or not a number ({@code NaN} in the files). */
  private static float number(JsonNode n, float fallback) {
    if (n == null || !n.isNumber()) return fallback;
    double v = n.asDouble();
    return Double.isNaN(v) ? fallback : (float) v;
  }

  /**
   * The cubic-bezier easing {@code (0,0) (x1,y1) (x2,y2) (1,1)} at {@code x}: y where the curve's x
   * is {@code x} (bisection on the parameter; a missing y control, NaN in the files, is the line).
   */
  static float ease(float[] b, float x) {
    if (x <= 0f) return 0f;
    if (x >= 1f) return 1f;
    float x1 = Float.isNaN(b[0]) ? 1f / 3f : b[0];
    float x2 = Float.isNaN(b[2]) ? 2f / 3f : b[2];
    float y1 = Float.isNaN(b[1]) ? x1 : b[1];
    float y2 = Float.isNaN(b[3]) ? x2 : b[3];
    float lo = 0f, hi = 1f, u = x;
    for (int i = 0; i < 30; i++) {
      u = (lo + hi) / 2f;
      float bx = bez(x1, x2, u);
      if (bx < x) lo = u;
      else hi = u;
    }
    return bez(y1, y2, u);
  }

  private static float bez(float p1, float p2, float u) {
    float v = 1f - u;
    return 3f * v * v * u * p1 + 3f * v * u * u * p2 + u * u * u;
  }
}
