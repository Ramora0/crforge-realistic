/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

import java.util.List;
import java.util.Map;

/**
 * Plays one unit's state machine over time. The renderer asks each frame for the state the unit's
 * simulator state maps to ({@link #want}); a one-shot state ({@link #play}: Deploy, the forced
 * Custom states) plays to its end first. A state without {@code Loop} that ends goes on through its
 * transition, else to the {@code DefaultState}; a {@code Random} state picks a sub-state for each
 * pass (from the unit's id, so a replay always looks the same); a {@code Selector} plays the
 * sub-state its animator parameter selects ({@link #parameter(int, int, float)}, {@link
 * #setParameter}: the client's {@code ActionSetAnimationModifier}); a {@code Sequence} plays its
 * sub-states in turn, the last one repeating; these nest. A looping state wraps. A change of state,
 * of a playing selector's parameter, or of the whole state machine (a row swap, {@link #adopt})
 * cross-fades over the new state's {@code TransInTime}.
 */
public final class AnimPlayer {

  /** What to pose this frame: a clip at a time, cross-faded from another by {@code 1 - blend}. */
  public record Sample(
      String clip, float seconds, String fromClip, float fromSeconds, float blend) {}

  private final Asm asm;
  private final Map<String, Float> clipLengths;
  private final long seed;

  private String state;
  private float start;
  private int pass;
  private boolean oneShot;
  private String wanted;

  /** The animator parameters a {@code Selector} reads, by number (0 until set). */
  private final int[] parameters = new int[8];

  /** The pose of the player this one replaces (a row swap), taken up by the first state entered. */
  private Sample adopted;

  /**
   * The cross-fade a state entered from another state machine's pose takes when it names none
   * (GUESS: as long as the shortest {@code TransInTime} the heroes' machines use, 0.1 s).
   */
  static final float ADOPT_FADE = 0.1f;

  /** Which of a {@code Sequence} state's sub-states plays. */
  private int step;

  /** The time last asked of {@link #at}: an earlier time starts the state's next pass. */
  private float lastAt = -1f;

  /** The target a transition state stands in for (it holds while that target is asked for). */
  private String standsFor;

  private String fromClip;
  private float fromSeconds;
  private float fadeStart;
  private float fade;

  /**
   * @param asm the state machine
   * @param clipLengths each clip's length in seconds, by clip name
   * @param seed the unit's id, for the random idle picks
   */
  public AnimPlayer(Asm asm, Map<String, Float> clipLengths, long seed) {
    this.asm = asm;
    this.clipLengths = clipLengths;
    this.seed = seed;
  }

  /** Whether the state machine has the state. */
  public boolean has(String name) {
    return name != null && asm.has(name);
  }

  /** The state playing now. */
  public String state() {
    return state;
  }

  /**
   * Sets an animation parameter (the client's {@code ActionSetAnimationModifier}, e.g. {@code
   * PropState} at index 2), which the {@code Selector} states read, with no cross-fade: the ground
   * troops' {@link org.crforge.realistic.render.art.GroundTroopStates} set every parameter each
   * frame.
   */
  public void setParameter(int index, int value) {
    if (index >= 0 && index < parameters.length) parameters[index] = value;
  }

  /**
   * Sets an animator parameter ({@code ActionSetAnimationModifier}'s {@code PropState}, the Dark
   * Prince's shield); a playing {@code Selector} whose sub-state changes cross-fades to it now.
   */
  public void parameter(int number, int value, float now) {
    if (number < 0 || number >= parameters.length || parameters[number] == value) return;
    Asm.State before = state == null ? null : resolve();
    Sample s = state == null ? null : sampleNoAdvance(now);
    parameters[number] = value;
    if (state == null) return;
    Asm.State after = resolve();
    if (before != after && s != null && s.clip() != null) {
      fromClip = s.clip();
      fromSeconds = s.seconds();
      fadeStart = now;
      fade = after == null || after.transIn() <= 0f ? ADOPT_FADE : after.transIn();
    }
  }

  /** An animator parameter's value. */
  public int parameter(int number) {
    return number >= 0 && number < parameters.length ? parameters[number] : 0;
  }

  /**
   * Takes over from the player of the state machine this one replaces (the unit's row swapped to
   * another prefab of the same model, a hero's form): the first state entered starts from the old
   * pose. When it plays the very clip the old one played (the Berserker's {@code Skill} in both
   * forms), it goes on from the old clip time; else it cross-fades from the old pose, so the swap
   * shows no pop. Its animator parameters carry over too.
   */
  public void adopt(AnimPlayer old, float now) {
    if (old == null) return;
    System.arraycopy(old.parameters, 0, parameters, 0, parameters.length);
    if (old.state == null) return;
    adopted = old.sample(now);
  }

  /** Plays a one-shot state from its start now (ignored when the machine has no such state). */
  public void play(String name, float now) {
    if (!asm.has(name)) return;
    enter(name, now, true);
  }

  /** The state the unit's simulator state asks for; a one-shot plays out first. */
  public void want(String name, float now) {
    if (name == null || !asm.has(name)) name = asm.defaultState();
    wanted = name;
    if (state == null) {
      enter(name, now, false);
    } else if (!oneShot
        && !name.equals(state)
        && !name.equals(transitionTarget(state))
        && !name.equals(standsFor)) {
      enter(name, now, false);
    }
  }

  private String transitionTarget(String from) {
    return asm.after().get(from);
  }

  private void enter(String name, float now, boolean once) {
    if (state != null) {
      Sample s = sampleNoAdvance(now);
      fromClip = s.clip();
      fromSeconds = s.seconds();
    }
    Asm.State st = asm.states().get(name);
    fade = st == null ? 0f : st.transIn();
    fadeStart = now;
    state = name;
    start = now;
    pass = 0;
    step = 0;
    lastAt = -1f;
    oneShot = once;
    standsFor = null;
    if (adopted != null) {
      Sample old = adopted;
      adopted = null;
      Asm.State leaf = resolve();
      if (leaf != null && old.clip() != null && old.clip().equals(leaf.clip())) {
        // the same clip goes on where the old machine had it
        float into = old.seconds() - leaf.startFrame() / Asm.FPS;
        start = now - Math.max(0f, into) / Math.max(1e-3f, leaf.speed());
        fade = 0f;
      } else if (old.clip() != null) {
        fromClip = old.clip();
        fromSeconds = old.seconds();
        if (fade <= 0f) fade = ADOPT_FADE;
      }
    }
  }

  /** The clip and time to pose at {@code now}, advancing through ends and transitions. */
  public Sample sample(float now) {
    for (int guard = 0; guard < 16 && state != null; guard++) {
      Asm.State st = resolve();
      float length = passLength(st);
      if (length <= 0f || now - start < length) break;
      Asm.State top = asm.states().get(state);
      if (top != null && top.sequence() && step < top.subStates().size() - 1) {
        // a Sequence goes on to its next sub-state
        start += length;
        step++;
        fadeStart = start;
        fade = 0f;
        continue;
      }
      boolean loops =
          st == null
              || st.loop()
              || (top != null && top.loop())
              || (!oneShot && state.equals(wanted) && !ends());
      if (loops) {
        // a looping or idling state starts its next pass (a Random one picks again)
        int passes = (int) Math.floor((now - start) / length);
        if (picks(state, 0)) {
          start += length;
          pass++;
          continue;
        }
        start += passes * length;
        pass += passes;
        break;
      }
      String next = transitionTarget(state);
      if (next == null || !asm.has(next)) next = wanted != null ? wanted : asm.defaultState();
      if (next == null || !asm.has(next)) next = asm.defaultState();
      float end = start + length;
      String target = asm.targets().get(state);
      enter(next, end, false);
      // a transition state ("Custom2 to BasicIdle via SpawnLoop") holds while its target is asked
      if (target != null && !target.equals(next)) standsFor = target;
      // the next state takes the previous one's last pose, with no fade back in time
      fadeStart = end;
    }
    return sampleNoAdvance(now);
  }

  /** Whether the current state ends rather than repeats when the unit keeps asking for it. */
  private boolean ends() {
    return transitionTarget(state) != null;
  }

  private Sample sampleNoAdvance(float now) {
    Asm.State st = resolve();
    String clip = st == null ? null : st.clip();
    float t = 0f;
    if (st != null) {
      float length = passLength(st);
      float into = Math.max(0f, now - start) * st.speed();
      if (length > 0) into = Math.min(into, length * st.speed());
      t = st.startFrame() / Asm.FPS + into;
    }
    float blend = fade <= 0f ? 1f : Math.min(1f, (now - fadeStart) / fade);
    return new Sample(clip, t, blend < 1f ? fromClip : null, fromSeconds, blend);
  }

  /**
   * A state's clip at a time the caller chose (an attack lined up with its hit), in seconds of the
   * state's own clock; null when the machine has no such clip state.
   */
  public Sample at(String name, float secondsIntoState) {
    if (!asm.has(name)) return null;
    if (!name.equals(state)) {
      state = name;
      oneShot = false;
      fade = 0f;
      step = 0;
      lastAt = -1f;
    } else if (secondsIntoState + 1e-4f < lastAt) {
      // the time went back: the next attack, a Random state's next pick
      pass++;
    }
    lastAt = secondsIntoState;
    wanted = name;
    Asm.State st = resolve();
    if (st == null || st.clip() == null) return null;
    float len = passLength(st);
    float t = Math.max(0f, Math.min(secondsIntoState, len));
    return new Sample(st.clip(), st.startFrame() / Asm.FPS + t * st.speed(), null, 0f, 1f);
  }

  /**
   * The marker's time into the state in seconds of its clock, or -1 (e.g. {@code action_frame}).
   */
  public float marker(String name, String marker) {
    Asm.State st = resolve(name, 0);
    if (st == null || !st.markers().containsKey(marker)) return -1f;
    return Math.max(0f, st.markers().get(marker) - st.startFrame()) / Asm.FPS / st.speed();
  }

  /** Seconds one pass of a state lasts, or 0. */
  public float length(String name) {
    return passLength(resolve(name, 0));
  }

  /** The playing AnimClip: the current state, its compound states followed down to a clip. */
  private Asm.State resolve() {
    return resolve(state, 0);
  }

  /**
   * The AnimClip a state plays now: a {@code Random} state's pick for this pass, a {@code
   * Selector}'s sub-state for its parameter, a {@code Sequence}'s current step, followed down.
   */
  private Asm.State resolve(String name, int depth) {
    Asm.State st = name == null ? null : asm.states().get(name);
    if (st == null || depth > 8) return null;
    if (!st.compound()) return st;
    List<String> subs = st.subStates();
    int n = subs.size();
    if (n == 0) return null;
    int i;
    if (st.selector()) {
      i = Math.max(0, subs.indexOf(st.select(parameter(st.parameter()))));
    } else if (st.sequence()) {
      i = depth == 0 ? step : 0;
    } else {
      long h = (seed * 0x9E3779B97F4A7C15L) ^ (pass * 0xBF58476D1CE4E5B9L) ^ (depth * 0x94D049BBL);
      h ^= h >>> 31;
      i = (int) Math.floorMod(h, (long) n);
    }
    return resolve(subs.get(Math.min(i, n - 1)), depth + 1);
  }

  /** Whether a state picks a new sub-state each pass (a Random one, or one that leads to one). */
  private boolean picks(String name, int depth) {
    Asm.State st = name == null ? null : asm.states().get(name);
    if (st == null || depth > 8 || !st.compound()) return false;
    if (st.random()) return true;
    for (String sub : st.subStates()) if (picks(sub, depth + 1)) return true;
    return false;
  }

  /** Seconds one pass of the state lasts at its speed. */
  private float passLength(Asm.State st) {
    if (st == null || st.clip() == null) return 0f;
    Float full = clipLengths.get(st.clip());
    if (full == null) return 0f;
    float from = st.startFrame() / Asm.FPS;
    float to = st.endFrame() >= 0 ? Math.min(full, st.endFrame() / Asm.FPS) : full;
    float frames = Math.max(0f, to - from);
    // a hold of one frame at the end (a looped 29..30 range) still lasts that frame
    if (frames <= 0f) frames = 1f / Asm.FPS;
    return frames / Math.max(1e-3f, st.speed());
  }
}
