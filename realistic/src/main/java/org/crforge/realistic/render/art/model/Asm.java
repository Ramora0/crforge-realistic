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
 * A model's animation state machine ({@code AssetASMData}, the extractor's {@code
 * sc3d/asm/*.json}): named states that play a clip ({@code AnimClip}: its clip, frame range, {@code
 * BaseSpeed}, {@code Loop}, {@code TransInTime} and marker events) or pick one of their sub-states
 * ({@code Random}; {@code Selector}: the one whose {@code SwitchValues} entry equals the value of
 * the animator parameter numbered {@code Parameter}, 0 when the state names none) or play them one
 * after the other ({@code Sequence}), the {@code DefaultState} and the transitions that route the
 * end of one state through another ({@code after}: source to the state that plays next; {@code
 * targets}: source to its target). The client asks for states by name, its numeric animation states
 * mapped through {@code sc3d/vanilla/animation_states_remapping} (see {@link AnimStates}).
 */
public record Asm(
    Map<String, State> states,
    String defaultState,
    Map<String, String> after,
    Map<String, String> targets) {

  /** Frames per second of the clips' frame numbers (every battle clip is 30 fps in the index). */
  public static final float FPS = 30f;

  /**
   * A state: an {@code AnimClip} names {@code clip} (the clip file's base name, as the model's glTF
   * animations are named), a {@code Random}, {@code Selector} or {@code Sequence} lists {@code
   * subStates}; a {@code Selector} picks the one whose {@code switchValues} entry equals its {@code
   * parameter}'s value; a {@code Sequence} plays its sub-states one after the other.
   */
  public record State(
      String name,
      String type,
      String clip,
      int startFrame,
      int endFrame,
      float speed,
      boolean loop,
      float transIn,
      List<String> subStates,
      Map<String, Float> markers,
      int parameter,
      List<Integer> switchValues) {

    public State(
        String name,
        String type,
        String clip,
        int startFrame,
        int endFrame,
        float speed,
        boolean loop,
        float transIn,
        List<String> subStates,
        Map<String, Float> markers) {
      this(
          name,
          type,
          clip,
          startFrame,
          endFrame,
          speed,
          loop,
          transIn,
          subStates,
          markers,
          0,
          List.of());
    }

    public boolean random() {
      return "Random".equals(type);
    }

    /** Whether the state picks its sub-state by an animator parameter ({@code Selector}). */
    public boolean selector() {
      return "Selector".equals(type);
    }

    /** Whether the state plays its sub-states one after the other ({@code Sequence}). */
    public boolean sequence() {
      return "Sequence".equals(type);
    }

    /** Whether the state picks, switches among or steps through sub-states, not a clip. */
    public boolean compound() {
      return random() || selector() || sequence();
    }

    /**
     * The sub-state a {@code Selector} plays for the parameter's value: the one whose switch value
     * it is, else the first; null for another state.
     */
    public String select(int value) {
      if (!selector() || subStates.isEmpty()) return null;
      for (int i = 0; i < subStates.size() && i < switchValues.size(); i++) {
        if (switchValues.get(i) == value) return subStates.get(i);
      }
      return subStates.get(0);
    }
  }

  /** Reads the state machine JSON. */
  public static Asm parse(JsonNode json) {
    Map<String, State> states = new LinkedHashMap<>();
    for (JsonNode s : json.path("States")) {
      String anim = s.path("Animation").asText("");
      int bar = anim.indexOf('|');
      if (bar >= 0) anim = anim.substring(0, bar).trim();
      String clip = anim.isEmpty() ? null : anim.substring(anim.lastIndexOf('/') + 1);
      List<String> subs = new ArrayList<>();
      for (JsonNode sub : s.path("SubStates")) subs.add(sub.asText());
      List<Integer> switches = new ArrayList<>();
      for (JsonNode v : s.path("SwitchValues")) switches.add(v.asInt());
      Map<String, Float> markers = new LinkedHashMap<>();
      for (JsonNode ev : s.path("Events")) {
        if ("Marker".equals(ev.path("Event").asText())) {
          markers.put(ev.path("Name").asText(), (float) ev.path("Frame").asDouble(0));
        }
      }
      String name = s.path("Name").asText();
      states.put(
          name,
          new State(
              name,
              s.path("Type").asText("AnimClip"),
              clip,
              s.path("StartFrame").asInt(0),
              s.has("EndFrame") ? s.path("EndFrame").asInt() : -1,
              (float) s.path("BaseSpeed").asDouble(1.0),
              s.path("Loop").asBoolean(false),
              (float) s.path("TransInTime").asDouble(0.0),
              List.copyOf(subs),
              Map.copyOf(markers),
              s.path("Parameter").asInt(0),
              List.copyOf(switches)));
    }
    // a transition "Source -> Target via Transition": when Source ends, Transition plays
    Map<String, String> after = new LinkedHashMap<>();
    Map<String, String> targets = new LinkedHashMap<>();
    for (JsonNode t : json.path("Transitions")) {
      String via = t.path("TransitionState").asText("");
      String target = t.path("TargetState").asText("");
      after.put(t.path("SourceState").asText(), via.isEmpty() ? target : via);
      targets.put(t.path("SourceState").asText(), target);
    }
    String def = json.path("DefaultState").asText("");
    if (def.isEmpty() && !states.isEmpty()) def = states.keySet().iterator().next();
    return new Asm(Map.copyOf(states), def, Map.copyOf(after), Map.copyOf(targets));
  }

  public boolean has(String state) {
    return states.containsKey(state);
  }
}
