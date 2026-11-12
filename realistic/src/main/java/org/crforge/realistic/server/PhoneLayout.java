/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.util.GameUnits;
import org.crforge.realistic.bridge.BattleFrame;
import org.crforge.realistic.bridge.ViewOrientation;
import org.crforge.realistic.render.art.ArtProjection;
import org.crforge.realistic.render.art.BattleHud;

/**
 * Where things are on a phone-framed frame, as the offscreen recorder frames it: the arena fills
 * the width between the HUD bars at the top (13.5% of the height) and the bottom (12.5%). It maps a
 * tap, given in the frame's normalized coordinates (0..1 across, 0..1 down from the top), to a card
 * of the bottom hand or a point of the arena.
 *
 * @param width the frame's width in pixels
 * @param height the frame's height in pixels
 * @param pixelsPerTile pixels per tile across the arena
 * @param originX the arena's left edge in pixels
 * @param originY the bottom player's baseline in pixels (y up)
 * @param widthUnits the arena's width in game units
 * @param heightUnits the arena's length in game units
 */
public record PhoneLayout(
    int width,
    int height,
    float pixelsPerTile,
    float originX,
    float originY,
    int widthUnits,
    int heightUnits) {

  /** The layout of a frame, for an arena of that size (the recorder's framing). */
  public static PhoneLayout of(int width, int height, int widthUnits, int heightUnits) {
    float pixelsPerTile = width / 18.6f;
    float arenaHeight =
        heightUnits
            / 1000f
            * pixelsPerTile
            * ArtProjection.DU_PER_TILE_Y
            / ArtProjection.DU_PER_TILE_X;
    float originX = (width - widthUnits / 1000f * pixelsPerTile) / 2f;
    float bottomHud = height * 0.125f;
    float topHud = height * 0.135f;
    float originY = bottomHud + (height - topHud - bottomHud - arenaHeight) / 2f;
    return new PhoneLayout(width, height, pixelsPerTile, originX, originY, widthUnits, heightUnits);
  }

  public ArtProjection projection() {
    return new ArtProjection(pixelsPerTile, originX, originY, widthUnits, heightUnits);
  }

  private float pixelsPerTileY() {
    return pixelsPerTile * ArtProjection.DU_PER_TILE_Y / ArtProjection.DU_PER_TILE_X;
  }

  /** What a tap is on. */
  public sealed interface Target permits Card, Ability, Arena, Nothing {}

  /** A champion slot's ability button of the bottom side. */
  public record Ability(int slot) implements Target {}

  /** A card of the bottom hand. */
  public record Card(int slot) implements Target {}

  /**
   * A point of the arena, in the battle's own game units (the view's mirroring undone), and the
   * tile it is in.
   */
  public record Arena(int x, int y, int tileX, int tileY) implements Target {

    /** The centre of the tile, as the screen sends a play. */
    public int[] tileCentre() {
      return new int[] {GameUnits.tileCenter(tileX), GameUnits.tileCenter(tileY)};
    }
  }

  /** Neither: the HUD around the arena. */
  public record Nothing() implements Target {}

  /**
   * What a tap is on.
   *
   * @param nx the tap across the frame, 0 at the left edge, 1 at the right
   * @param ny the tap down the frame, 0 at the top edge, 1 at the bottom
   * @param view the view the frame is drawn in
   * @param abilities the bottom side's champion slots, null entries for none (may be null)
   */
  public Target hit(
      float nx, float ny, ViewOrientation view, List<BattleFrame.AbilityView> abilities) {
    float px = nx * width;
    float py = (1f - ny) * height;
    if (abilities != null) {
      // the buttons shown stand from the right, as the HUD draws them
      List<Integer> shown = new ArrayList<>();
      for (int slot = 0; slot < abilities.size(); slot++) {
        if (BattleHud.abilityVisible(abilities.get(slot))) shown.add(slot);
      }
      for (int i = 0; i < shown.size(); i++) {
        float[] box = BattleHud.abilityButtonBox(i, shown.size(), width);
        if (px >= box[0] && px < box[2] && py >= box[1] && py < box[3]) {
          return new Ability(shown.get(i));
        }
      }
    }
    for (int slot = 0; slot < 4; slot++) {
      float[] box = BattleHud.handSlotBox(slot, width);
      if (px >= box[0] && px < box[2] && py >= box[1] && py < box[3]) return new Card(slot);
    }
    float drawnX = (px - originX) / pixelsPerTile * 1000f;
    float drawnY = (py - originY) / pixelsPerTileY() * 1000f;
    // a little slack around the arena: a finger dropping a card at the edge still drops it
    float slack = 500f;
    if (drawnX < -slack
        || drawnX > widthUnits + slack
        || drawnY < -slack
        || drawnY > heightUnits + slack) {
      return new Nothing();
    }
    // the view mirrors positions; a mirror is its own inverse
    int gx = clamp(Math.round(view.x(drawnX)), 0, widthUnits - 1);
    int gy = clamp(Math.round(view.y(drawnY)), 0, heightUnits - 1);
    int tile = GameUnits.UNITS_PER_TILE;
    return new Arena(gx, gy, gx / tile, gy / tile);
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }
}
