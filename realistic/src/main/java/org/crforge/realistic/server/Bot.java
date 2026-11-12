/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.realistic.bridge.BattleFrame;
import org.crforge.realistic.bridge.BattleSession;
import org.crforge.realistic.bridge.EntityView;

/**
 * The phone server's built-in opponent: a few readable rules, after the Python package's {@code
 * crforge_env.scripted.ScriptedBot}, played through the same path as the player's cards ({@link
 * BattleSession#play}, which refuses what the game would refuse and moves a drop to the nearest
 * legal tile). It sees what a player sees: the field's characters and towers, its own hand and
 * elixir. Each decision (every {@link #DECISION_TICKS} ticks, with none while a play of its own
 * waits to run), in order:
 *
 * <ol>
 *   <li><b>Spell a group</b>: a spell whose circle covers {@link #SPELL_GROUP} or more enemy troops
 *       is cast on them.
 *   <li><b>Defend</b>: an enemy troop past the river into its half: the cheapest troop or building
 *       that is not a win condition (one that can hit air for an air troop), a troop in the
 *       threat's path in front of the towers, a building in the middle of the half to pull it.
 *   <li><b>Support</b>: a win condition of its own heading for the bridge alone: the dearest other
 *       troop behind it.
 *   <li><b>Attack</b>: at {@link #ATTACK_ELIXIR} elixir, the cheapest win condition (a troop that
 *       targets buildings only) in the lane whose enemy princess tower is weaker, a tank behind the
 *       king tower, any other at the bridge; with none, the cheapest troop at the bridge.
 *   <li><b>No leak</b>: at full elixir, the cheapest troop or building behind the king tower.
 * </ol>
 */
public final class Bot {

  /** Ticks between two decisions (0.4 s). */
  static final int DECISION_TICKS = 8;

  /** Ticks before the first decision. */
  static final int FIRST_DECISION = 40;

  static final int SPELL_GROUP = 3;
  static final int ATTACK_ELIXIR = 9;

  /** A win condition with this many hit points or more is a tank (played at the back). */
  static final int TANK_HP = 2000;

  private final int side;
  private final Random random;
  private int plays;

  public Bot(int side, long seed) {
    this.side = side;
    this.random = new Random(seed);
  }

  public int side() {
    return side;
  }

  /** How many plays the bot has given. */
  public int plays() {
    return plays;
  }

  /** One hand card as the bot reads it. */
  record Card(int slot, String name, int cost, DeployCard deploy) {
    boolean spell() {
      return deploy.spell();
    }

    boolean building() {
      return !spell() && deploy.unit().building();
    }

    boolean winCondition() {
      return !spell() && deploy.unit().targetOnlyBuildings();
    }

    boolean troop() {
      return !spell() && !building();
    }

    boolean hitsAir() {
      return !spell() && deploy.unit().attacksAir();
    }

    int radius() {
      return deploy.radius() > 0 ? deploy.radius() : 2500;
    }
  }

  /**
   * Decides on a tick and gives a play, if any.
   *
   * @param session the battle
   * @param frame the battle as the screen reads it now
   * @return a short description of the play given, or null for none
   */
  public String decide(BattleSession session, BattleFrame frame) {
    int tick = session.tick();
    if (tick < FIRST_DECISION || tick % DECISION_TICKS != 0) return null;
    if (frame.ended() || session.isOver() || session.getHalted() != null) return null;
    for (int s = 0; s < 4; s++) if (session.isPending(side, s)) return null;
    BattleFrame.SideView me = null;
    for (BattleFrame.SideView sv : frame.sides()) if (sv.side() == side) me = sv;
    if (me == null) return null;
    int elixir = me.wholeElixir();
    List<Card> hand = new ArrayList<>();
    for (int slot = 0; slot < 4; slot++) {
      MatchCard mc = session.handCard(side, slot);
      DeployCard dc = session.deployCard(mc);
      if (mc == null || dc == null) continue;
      if (session.cardUnavailableReason(side, slot) != null) continue;
      hand.add(new Card(slot, mc.name(), mc.cost(), dc));
    }
    if (hand.isEmpty()) return null;
    int width = 18000;
    int height = 32000;
    List<EntityView> enemies = new ArrayList<>();
    List<EntityView> mine = new ArrayList<>();
    List<EntityView> enemyTowers = new ArrayList<>();
    for (EntityView e : frame.entities()) {
      if (!e.isCharacter() || e.hidden()) continue;
      if (e.kind() == EntityView.Kind.TOWER) {
        if (e.side() != side && e.hitPoints() > 0) enemyTowers.add(e);
        continue;
      }
      if (e.side() == side) mine.add(e);
      else if (e.kind() == EntityView.Kind.TROOP) enemies.add(e);
    }

    // 1. spell a group
    Card bestSpell = null;
    int[] bestAt = null;
    int bestCount = SPELL_GROUP - 1;
    for (Card c : hand) {
      if (!c.spell() || c.cost() > elixir) continue;
      long r2 = (long) c.radius() * c.radius();
      for (EntityView centre : enemies) {
        int count = 0;
        long sx = 0;
        long sy = 0;
        for (EntityView e : enemies) {
          long dx = e.x() - centre.x();
          long dy = e.y() - centre.y();
          if (dx * dx + dy * dy <= r2) {
            count++;
            sx += e.x();
            sy += e.y();
          }
        }
        if (count > bestCount) {
          bestCount = count;
          bestSpell = c;
          bestAt = new int[] {(int) (sx / count), (int) (sy / count)};
        }
      }
    }
    if (bestSpell != null) return play(session, bestSpell, bestAt[0], bestAt[1], "spell");

    // 2. defend: the enemy troop deepest in its half
    EntityView threat = null;
    for (EntityView e : enemies) {
      if (depth(e.y(), height) < height / 2 - 500
          && (threat == null || depth(e.y(), height) < depth(threat.y(), height))) {
        threat = e;
      }
    }
    if (threat != null) {
      Card defender = null;
      for (Card c : hand) {
        if (c.spell() || c.winCondition() || c.cost() > elixir) continue;
        if (threat.air() && !c.hitsAir()) continue;
        if (defender == null || c.cost() < defender.cost()) defender = c;
      }
      if (defender != null) {
        int threatDepth = depth(threat.y(), height);
        if (defender.building()) {
          int x = threat.x() < width / 2 ? width / 2 - 1500 : width / 2 + 1500;
          return play(session, defender, x, fromDepth(8500, height), "defend");
        }
        int d = Math.max(2500, threatDepth - 3000);
        return play(session, defender, threat.x(), fromDepth(d, height), "defend");
      }
    }

    // 3. support a win condition of its own
    for (EntityView e : mine) {
      if (e.kind() != EntityView.Kind.TROOP) continue;
      if (!isWinCondition(session, e.name())) continue;
      int d = depth(e.y(), height);
      if (d < 9000 || d > 17000) continue;
      boolean alone = true;
      for (EntityView o : mine) {
        if (o == e || o.kind() != EntityView.Kind.TROOP) continue;
        long dx = o.x() - e.x();
        long dy = o.y() - e.y();
        if (dx * dx + dy * dy < 3500L * 3500L) alone = false;
      }
      if (!alone) continue;
      Card support = null;
      for (Card c : hand) {
        if (!c.troop() || c.winCondition() || c.cost() > elixir) continue;
        if (support == null || c.cost() > support.cost()) support = c;
      }
      if (support != null) {
        return play(
            session, support, e.x(), fromDepth(Math.min(d - 2500, 14000), height), "support");
      }
    }

    // 4. attack in the weaker lane
    if (elixir >= ATTACK_ELIXIR) {
      int laneX = weakerLane(enemyTowers, width);
      Card attacker = null;
      for (Card c : hand) {
        if (!c.winCondition() || c.cost() > elixir) continue;
        if (attacker == null || c.cost() < attacker.cost()) attacker = c;
      }
      if (attacker != null) {
        boolean tank = attacker.deploy().unit().hitpoints() >= TANK_HP;
        return play(session, attacker, laneX, fromDepth(tank ? 1500 : 14000, height), "attack");
      }
      Card troop = null;
      for (Card c : hand) {
        if (!c.troop() || c.cost() > elixir) continue;
        if (troop == null || c.cost() < troop.cost()) troop = c;
      }
      if (troop != null) {
        return play(session, troop, laneX, fromDepth(14000, height), "attack");
      }
    }

    // 5. no leak at full elixir
    if (elixir >= 10) {
      Card cheap = null;
      for (Card c : hand) {
        if (c.spell() || c.cost() > elixir) continue;
        if (cheap == null || c.cost() < cheap.cost()) cheap = c;
      }
      if (cheap != null) {
        int x = width / 2 + (random.nextBoolean() ? -1500 : 1500);
        return play(session, cheap, x, fromDepth(1500, height), "cycle");
      }
    }
    return null;
  }

  private boolean isWinCondition(BattleSession session, String unitName) {
    try {
      var unit = session.getBattle().getWorld().getRecords().unit(unitName);
      return unit != null && unit.targetOnlyBuildings();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** The lane (its bridge's x) whose enemy princess tower has fewer hit points. */
  private int weakerLane(List<EntityView> towers, int width) {
    int left = Integer.MAX_VALUE;
    int right = Integer.MAX_VALUE;
    for (EntityView t : towers) {
      if (t.king()) continue;
      if (t.x() < width / 2) left = t.hitPoints();
      else right = t.hitPoints();
    }
    if (left == right) return random.nextBoolean() ? 3500 : width - 3500;
    return left < right ? 3500 : width - 3500;
  }

  /** How deep a point is into the bot's own half: 0 at its baseline. */
  private int depth(int y, int height) {
    return side == 0 ? y : height - y;
  }

  private int fromDepth(int depth, int height) {
    return side == 0 ? depth : height - depth;
  }

  private String play(BattleSession session, Card card, int x, int y, String why) {
    boolean given = session.play(side, card.slot(), x, y);
    if (!given) return null;
    plays++;
    return why + " " + card.name();
  }
}
