/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import java.util.List;
import org.crforge.realistic.bridge.BattleSession;

/**
 * The player's finger on the phone, as the game's hand works: a tap on a card selects it (a second
 * tap deselects it), a drag from a card onto the arena plays it where it is released, and a tap on
 * the arena plays the selected card there; a tap on an ability button uses it. While a selected
 * card is held over the arena, {@link #hover} says where. Plays and abilities go through the
 * session, which refuses what the game would refuse (not enough elixir, a tile the card cannot land
 * on) and moves a drop to the tile the game's own placement search finds.
 */
final class TapInput {

  /**
   * What an input came to: {@code none}, {@code hover}, {@code selected}, {@code deselected},
   * {@code played}, {@code ability} or {@code refused} (with the reason).
   */
  record Result(String what, String message) {

    static final Result NONE = new Result("none", null);
  }

  private final BattleSession session;
  private final int side;
  private int selected = -1;
  private PhoneLayout.Arena hover;
  private PhoneLayout.Target pressed;
  private boolean pressedSelected;

  TapInput(BattleSession session, int side) {
    this.session = session;
    this.side = side;
  }

  /** The hand slot selected, or -1. */
  int selected() {
    return selected;
  }

  /** Where the selected card is held over the arena, or null. */
  PhoneLayout.Arena hover() {
    return hover;
  }

  /**
   * A press, a move or a release on a target.
   *
   * @param kind "down", "move" or "up"
   * @param hit what the finger is on
   * @param playable whether the battle runs (a play is refused while it does not)
   */
  Result input(String kind, PhoneLayout.Target hit, boolean playable) {
    switch (kind) {
      case "down" -> {
        pressed = hit;
        if (hit instanceof PhoneLayout.Card c) {
          if (session.handCard(side, c.slot()) == null) {
            pressed = null;
            return Result.NONE;
          }
          pressedSelected = selected == c.slot();
          selected = c.slot();
          hover = null;
          return new Result("selected", null);
        }
        if (hit instanceof PhoneLayout.Ability a) {
          pressed = null;
          return ability(a.slot(), playable);
        }
        if (hit instanceof PhoneLayout.Arena a && selected >= 0) {
          hover = a;
          return new Result("hover", null);
        }
        return Result.NONE;
      }
      case "move" -> {
        if (pressed == null || selected < 0) return Result.NONE;
        hover = hit instanceof PhoneLayout.Arena a ? a : null;
        return hover == null ? Result.NONE : new Result("hover", null);
      }
      case "up" -> {
        PhoneLayout.Target from = pressed;
        pressed = null;
        hover = null;
        if (from == null) return Result.NONE;
        if (hit instanceof PhoneLayout.Arena a && selected >= 0) return play(a, playable);
        if (from instanceof PhoneLayout.Card c
            && hit instanceof PhoneLayout.Card c2
            && c.slot() == c2.slot()
            && pressedSelected) {
          selected = -1;
          return new Result("deselected", null);
        }
        return Result.NONE;
      }
      default -> {
        return Result.NONE;
      }
    }
  }

  private Result play(PhoneLayout.Arena at, boolean playable) {
    int slot = selected;
    selected = -1;
    if (!playable) return new Result("refused", "the battle is not running");
    int[] p = at.tileCentre();
    if (session.play(side, slot, p[0], p[1])) return new Result("played", null);
    return new Result("refused", lastMessage());
  }

  /** A tap on a champion slot's ability button. */
  Result ability(int slot, boolean playable) {
    if (!playable) return new Result("refused", "the battle is not running");
    if (session.useAbility(side, slot)) return new Result("ability", null);
    return new Result("refused", lastMessage());
  }

  /** Drops the selection and any press. */
  void cancel() {
    selected = -1;
    hover = null;
    pressed = null;
  }

  /** The session's latest message without its tick and side, as a toast shows it. */
  private String lastMessage() {
    List<String> m = session.messages();
    if (m.isEmpty()) return "refused";
    String s = m.get(m.size() - 1).replaceFirst("^\\[\\d+\\] ", "");
    return s.replaceFirst("^(blue|red): ", "");
  }
}
