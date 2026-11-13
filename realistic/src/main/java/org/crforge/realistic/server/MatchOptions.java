/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.realistic.bridge.BattleDecks;
import org.crforge.realistic.bridge.BattleSession;

/**
 * What a new battle can be started with: the decks (the example replay's two, {@link BattleDecks},
 * and a few common Ladder decks) and the arenas (each {@code PvP_*} location whose art the cache
 * holds). A deck the battle core refuses on the tables loaded is left out.
 */
@Slf4j
public final class MatchOptions {

  /** A deck: its name, its cards by row name and their slot flags. */
  public record Deck(String id, String name, List<String> cards, int[] slots) {}

  private static final List<Deck> PRESETS =
      List.of(
          new Deck("blue", "Example blue (LavaLoon)", BattleDecks.BLUE, BattleDecks.blueSlots()),
          new Deck("red", "Example red (MegaKnight)", BattleDecks.RED, BattleDecks.redSlots()),
          plain(
              "hog",
              "Hog 2.6",
              "HogRider",
              "Musketeer",
              "Cannon",
              "IceGolemite",
              "Skeletons",
              "IceSpirits",
              "Fireball",
              "Log"),
          plain(
              "giant",
              "Giant double prince",
              "Giant",
              "Prince",
              "DarkPrince",
              "Musketeer",
              "MegaMinion",
              "Arrows",
              "Fireball",
              "Zap"),
          plain(
              "golem",
              "Golem beatdown",
              "Golem",
              "BabyDragon",
              "MegaMinion",
              "AxeMan",
              "Tornado",
              "Lightning",
              "Barbarians",
              "Zap"),
          plain(
              "bait",
              "Log bait",
              "GoblinBarrel",
              "Princess",
              "GoblinGang",
              "Knight",
              "InfernoTower",
              "Rocket",
              "Log",
              "IceSpirits"),
          plain(
              "rg",
              "Royal Giant",
              "RoyalGiant",
              "Fisherman",
              "Hunter",
              "ElectroSpirit",
              "Lightning",
              "Log",
              "Skeletons",
              "Fireball"),
          heroes(
              "heroes",
              "Heroes: Knight, Bowler, evolved Giant (3D)",
              "Knight",
              "Bowler",
              "Giant",
              "Musketeer",
              "Valkyrie",
              "Skeletons",
              "Arrows",
              "Zap"),
          heroes(
              "heroes2",
              "Heroes: Dark Prince, Balloon, evolved Electro Giant (3D)",
              "DarkPrince",
              "Balloon",
              "ElectroGiant",
              "Wizard",
              "MiniPekka",
              "Archer",
              "Fireball",
              "Log"),
          plain(
              "xbow",
              "X-Bow",
              "Xbow",
              "Tesla",
              "Archer",
              "Knight",
              "IceSpirits",
              "Skeletons",
              "Fireball",
              "Log"));

  /**
   * A deck of hero forms: the first two cards in the king's two hero slots, the third in the
   * evolution slot (3D models on the phone, docs/phone-server.md).
   */
  private static Deck heroes(String id, String name, String... cards) {
    int[] slots = new int[cards.length];
    slots[0] = org.crforge.core.battle.match.MatchSide.HERO_SLOT;
    slots[1] = org.crforge.core.battle.match.MatchSide.HERO_SLOT;
    slots[2] = org.crforge.core.battle.match.MatchSide.EVOLUTION_SLOT;
    return new Deck(id, name, List.of(cards), slots);
  }

  private static Deck plain(String id, String name, String... cards) {
    return new Deck(id, name, List.of(cards), new int[cards.length]);
  }

  private final Map<String, Deck> decks = new LinkedHashMap<>();
  private final Map<String, String> locations = new LinkedHashMap<>();

  /**
   * The options of the tables loaded and an art cache.
   *
   * @param tables the game tables battles run on
   * @param artFolder the art cache's content folder, whose arena files the locations name
   */
  public MatchOptions(GameTables tables, Path artFolder) {
    for (Deck d : PRESETS) {
      try {
        BattleSession.ladder(tables, d.cards(), d.slots(), BattleDecks.RED, BattleDecks.redSlots());
        decks.put(d.id(), d);
      } catch (RuntimeException e) {
        log.info("Deck {} is left out: {}", d.name(), e.getMessage());
      }
    }
    try {
      List<String> names = new ArrayList<>();
      for (GameRow row : tables.table("locations").rows()) {
        String name = row.name();
        if (!name.startsWith("PvP_")) continue;
        var file = row.columns().get("FileName");
        if (file == null || file.asText("").isEmpty()) continue;
        if (!Files.isRegularFile(artFolder.resolve(file.asText()))) continue;
        names.add(name);
      }
      for (String n : names) locations.put(n, pretty(n));
    } catch (RuntimeException e) {
      log.warn("No locations: {}", e.getMessage());
    }
  }

  static String pretty(String location) {
    String s = location.replaceFirst("^PvP_", "").replace('_', ' ');
    return s.isEmpty() ? location : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  public Deck deck(String id, String fallback) {
    Deck d = id == null ? null : decks.get(id);
    if (d == null) d = decks.get(fallback);
    if (d == null) d = decks.values().iterator().next();
    return d;
  }

  public boolean hasLocation(String name) {
    return name != null && locations.containsKey(name);
  }

  /** The decks and locations as the page lists them. */
  public ObjectNode json(ObjectMapper json) {
    ObjectNode out = json.createObjectNode();
    ArrayNode d = out.putArray("decks");
    for (Deck deck : decks.values()) {
      ObjectNode o = d.addObject();
      o.put("id", deck.id());
      o.put("name", deck.name());
      ArrayNode cards = o.putArray("cards");
      deck.cards().forEach(cards::add);
    }
    ArrayNode l = out.putArray("locations");
    for (var e : locations.entrySet()) {
      ObjectNode o = l.addObject();
      o.put("id", e.getKey());
      o.put("name", e.getValue());
    }
    return out;
  }
}
