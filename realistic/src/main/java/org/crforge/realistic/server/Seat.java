/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Disposable;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.realistic.bridge.BattleAdapter;
import org.crforge.realistic.bridge.BattleFrame;
import org.crforge.realistic.bridge.BattleSession;
import org.crforge.realistic.bridge.LandingTiles;
import org.crforge.realistic.bridge.ViewOrientation;
import org.crforge.realistic.render.art.ArenaScene;
import org.crforge.realistic.render.art.ArtCache;
import org.crforge.realistic.render.art.ArtProjection;
import org.crforge.realistic.render.art.BattleHud;
import org.crforge.realistic.render.art.BattleSounds;
import org.crforge.realistic.render.art.EffectLayer;
import org.crforge.realistic.render.art.MatchEnd;
import org.crforge.realistic.render.art.RealisticRenderer;

/**
 * One player's seat in a battle: that side's game view drawn with the game's art (the realistic
 * renderer, its effects, the player's-view HUD, the match end), the sounds that view hears, and the
 * player's card selection and drag as the frames show them. A seat is built with its battle and
 * hears it from its first step. Everything here runs on the GL thread.
 */
final class Seat implements Disposable {

  final int side;
  final ViewOrientation view;
  final PhoneLayout layout;
  final ArtProjection proj;
  private final RealisticRenderer renderer;
  private final EffectLayer effects;
  private final BattleHud hud;
  private final MatchEnd matchEnd;
  private final BattleSounds sounds;
  private final ArenaScene arena;
  private final GameTables tables;
  private final BattleSession session;

  BattleFrame previous;
  BattleFrame current;

  /** The player's finger: the card selected and where it is held. */
  final TapInput hand;

  /** The last input applied, which the next frame carries. */
  int lastInputSeq;

  /** The renderer's batch when its draws are recorded (the draw-list stream), else null. */
  private final DrawRecorder recorder;

  /** The placement overlay's flat triangles while recording: x, y pairs, three a triangle. */
  private float[] shapeXy = new float[1024];

  private int shapeLen;

  Seat(
      int side,
      ArtCache cache,
      GameTables tables,
      BattleSession session,
      String location,
      int width,
      int height,
      List<String> names,
      DrawRecorder recorder) {
    this.side = side;
    this.recorder = recorder;
    this.tables = tables;
    this.session = session;
    var tileMap = session.getBattle().getWorld().getTileMap();
    this.view = ViewOrientation.of(tileMap, side);
    this.layout = PhoneLayout.of(width, height, tileMap.widthUnits(), tileMap.heightUnits());
    this.proj = layout.projection();
    renderer =
        recorder == null ? new RealisticRenderer(cache) : new RealisticRenderer(cache, recorder);
    arena = new ArenaScene(cache, tables, location);
    int[] sh = arena.shadow();
    renderer.setShadowColor(sh[0], sh[1], sh[2], sh[3]);
    hud = new BattleHud(cache, renderer.drawer());
    hud.setUnits(renderer);
    hud.setTables(tables);
    hud.setPlays(session.getBattle()::getPlays);
    hud.setPlayerView(true);
    hud.setPlayerNames(names);
    effects = new EffectLayer(cache);
    renderer.setEffects(effects);
    var world = session.getBattle().getWorld();
    world.addObserver(effects.observer());
    world.addObserver(renderer.observer());
    sounds = new BattleSounds(cache, tables, view, arena.locationName());
    world.addObserver(sounds);
    matchEnd = new MatchEnd(cache);
    hand = new TapInput(session, side);
  }

  /** The renderer's batch, which records the draws for the draw-list stream. */
  DrawRecorder recorder() {
    return recorder;
  }

  /**
   * Whether the next frames draw the 3D models: off while a frame is recorded for draw lists, whose
   * page gets the 3D rows' 2D stand-in (a model is an image rendered anew each frame).
   */
  void drawModels(boolean on) {
    renderer.setDrawModels(on);
  }

  String locationName() {
    return arena.locationName();
  }

  boolean ended() {
    return matchEnd.ended();
  }

  /**
   * Reads the battle after a step (or, with {@code stopped}, an outro tick after its end).
   *
   * @param frame the battle's frame of the tick; null for an outro tick
   * @param out where the sounds this view hears are added, as {seconds, ref}
   */
  void tick(BattleFrame frame, boolean stopped, List<String[]> out) {
    previous = current;
    if (stopped) {
      matchEnd.stopped();
      current = matchEnd.update(later(current), view);
    } else {
      current = matchEnd.update(frame, view);
    }
    if (previous == null) previous = current;
    renderer.setTickData(
        BattleAdapter.attackTimers(session), BattleAdapter.projectileFlights(session));
    effects.update(current, previous == current ? null : previous, tables, view);
    out.addAll(effects.drainSounds());
    out.addAll(sounds.drain());
    out.addAll(matchEnd.drainSounds());
  }

  /** The frame one tick later with nothing changed: the outro's clock. */
  private static BattleFrame later(BattleFrame f) {
    return new BattleFrame(
        f.tick() + 1,
        f.timeMs() + 50,
        f.overtime(),
        f.elixirRate(),
        f.entities(),
        f.sides(),
        f.ended(),
        f.winner(),
        f.over(),
        f.halted(),
        f.messages());
  }

  /** This side's view of the frame's sides, or null. */
  BattleFrame.SideView me() {
    if (current == null) return null;
    for (BattleFrame.SideView s : current.sides()) if (s.side() == side) return s;
    return null;
  }

  BattleFrame.SideView them() {
    if (current == null) return null;
    for (BattleFrame.SideView s : current.sides()) if (s.side() != side) return s;
    return null;
  }

  // ---------------------------------------------------------------- input

  /** A press, a move or a release of the player's finger ({@link TapInput#input}). */
  TapInput.Result input(String kind, float nx, float ny, boolean playable) {
    BattleFrame.SideView me = me();
    return hand.input(kind, layout.hit(nx, ny, view, me == null ? null : me.abilities()), playable);
  }

  TapInput.Result ability(int slot, boolean playable) {
    return hand.ability(slot, playable);
  }

  /** Drops the selection (its card was played or cycled, or the battle ended). */
  void cancelSelection() {
    hand.cancel();
  }

  // ---------------------------------------------------------------- drawing

  /**
   * Draws the view at {@code alpha} between the previous and the current tick into the bound frame
   * buffer.
   */
  void draw(
      float alpha, OrthographicCamera camera, ShapeRenderer shapes, List<String[]> soundsOut) {
    if (current == null) return;
    int w = layout.width();
    int h = layout.height();
    if (rasterizing()) {
      Gdx.gl.glClearColor(CLEAR[0], CLEAR[1], CLEAR[2], 1f);
      Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
      Gdx.gl.glEnable(GL20.GL_BLEND);
    }
    float seconds = (previous.timeMs() + (current.timeMs() - previous.timeMs()) * alpha) / 1000f;
    float[] shake = effects.shake(seconds);
    float ppdu = proj.pixelsPerDisplayUnit();
    float dx = shake == null ? 0 : shake[0] * ppdu;
    float dy = shake == null ? 0 : shake[1] * ppdu;
    camera.position.set(w / 2f - dx, h / 2f - dy, 0);
    camera.update();
    renderer.drawer().batch().setProjectionMatrix(camera.combined);
    renderer.setEffectClock(seconds);
    arena.drawGround(renderer.drawer(), proj, view, current, previous, alpha);
    drawPlacement(camera, shapes);
    renderer.drawer().begin();
    effects.drawBelow(renderer.drawer(), proj, view, seconds);
    renderer.drawer().end();
    renderer.render(current, previous, alpha, tables, view, proj, camera.combined);
    renderer.drawer().begin();
    effects.drawAbove(renderer.drawer(), proj, view, seconds);
    renderer.drawer().end();
    arena.drawOverlay(renderer.drawer(), proj, view, current, alpha);
    drawGhost(camera, shapes);
    camera.position.set(w / 2f, h / 2f, 0);
    camera.update();
    hud.setRaisedSlot(hand.selected());
    hud.draw(
        matchEnd.hud(current), matchEnd.hud(previous), alpha, view, proj, w, h, camera.combined);
    float tickSeconds = (previous.tick() + (current.tick() - previous.tick()) * alpha) * 0.05f;
    matchEnd.draw(tickSeconds, w, h, renderer.drawer(), camera.combined);
    soundsOut.addAll(effects.drainSounds());
  }

  /** The colour the frame is cleared to (the draw-list page clears to it too). */
  static final float[] CLEAR = {0.11f, 0.13f, 0.12f};

  private boolean rasterizing() {
    return recorder == null || recorder.rasterizing();
  }

  private boolean recording() {
    return recorder != null && recorder.recording();
  }

  /** A flat rectangle of the overlay (filled, as two triangles). */
  private void shapeRect(ShapeRenderer shapes, float x, float y, float w, float h) {
    if (rasterizing()) shapes.rect(x, y, w, h);
    if (recording()) {
      shapeTri(x, y, x + w, y, x + w, y + h);
      shapeTri(x + w, y + h, x, y + h, x, y);
    }
  }

  /** An ellipse of the overlay, filled or outlined, as ShapeRenderer draws it. */
  private void shapeEllipse(
      ShapeRenderer shapes, float x, float y, float w, float h, int segments, boolean filled) {
    if (rasterizing()) shapes.ellipse(x, y, w, h, segments);
    if (!recording()) return;
    float angle = 2 * com.badlogic.gdx.math.MathUtils.PI / segments;
    float cx = x + w / 2, cy = y + h / 2;
    for (int i = 0; i < segments; i++) {
      float x0 = cx + w * 0.5f * com.badlogic.gdx.math.MathUtils.cos(i * angle);
      float y0 = cy + h * 0.5f * com.badlogic.gdx.math.MathUtils.sin(i * angle);
      float x1 = cx + w * 0.5f * com.badlogic.gdx.math.MathUtils.cos((i + 1) * angle);
      float y1 = cy + h * 0.5f * com.badlogic.gdx.math.MathUtils.sin((i + 1) * angle);
      if (filled) {
        shapeTri(x0, y0, cx, cy, x1, y1);
      } else {
        // a one pixel line as a thin quad
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len == 0) continue;
        float nx = -dy / len * 0.5f, ny = dx / len * 0.5f;
        shapeTri(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny);
        shapeTri(x1 - nx, y1 - ny, x0 - nx, y0 - ny, x0 + nx, y0 + ny);
      }
    }
  }

  private void shapeTri(float x0, float y0, float x1, float y1, float x2, float y2) {
    if (shapeLen + 6 > shapeXy.length)
      shapeXy = java.util.Arrays.copyOf(shapeXy, shapeXy.length * 2);
    shapeXy[shapeLen++] = x0;
    shapeXy[shapeLen++] = y0;
    shapeXy[shapeLen++] = x1;
    shapeXy[shapeLen++] = y1;
    shapeXy[shapeLen++] = x2;
    shapeXy[shapeLen++] = y2;
  }

  /** Records the overlay's triangles gathered since the last call, in one colour. */
  private void shapeFlush(ShapeRenderer shapes, OrthographicCamera camera) {
    if (shapeLen > 0 && recording()) {
      com.badlogic.gdx.graphics.Color c = shapes.getColor();
      recorder.shapeTriangles(shapeXy, shapeLen, c.r, c.g, c.b, c.a, camera.combined);
    }
    shapeLen = 0;
  }

  /** Whether a placement is being shown: a troop or building card selected. */
  private DeployCard selectedCard() {
    if (hand.selected() < 0) return null;
    return session.deployCard(session.handCard(side, hand.selected()));
  }

  /**
   * The ground under the arena while a card is selected: the tiles it cannot be dropped on shaded
   * red (as the game shades the area a card cannot go), the tile a drop would land on lit.
   */
  private void drawPlacement(OrthographicCamera camera, ShapeRenderer shapes) {
    DeployCard card = selectedCard();
    if (card == null) return;
    int selected = hand.selected();
    PhoneLayout.Arena hover = hand.hover();
    LandingTiles.Tiles tiles = card.spell() ? null : session.landingTiles(side, selected);
    LandingTiles.Landing landing =
        hover == null ? null : session.landingAt(side, selected, hover.tileX(), hover.tileY());
    if (tiles == null && landing == null) return;
    beginShapes(camera, shapes, ShapeRenderer.ShapeType.Filled);
    if (tiles != null) {
      shapes.setColor(0.85f, 0.1f, 0.1f, 0.28f);
      for (int r = 0; r < tiles.height(); r++) {
        for (int c = 0; c < tiles.width(); c++) {
          if (!tiles.open(c, r)) tile(shapes, c, r);
        }
      }
      shapeFlush(shapes, camera);
    }
    if (landing != null && landing.targetTile() != null) {
      int[] t = landing.targetTile();
      shapes.setColor(1f, 1f, 1f, 0.35f);
      tile(shapes, t[0], t[1]);
      shapeFlush(shapes, camera);
    }
    endShapes(shapes);
  }

  private void beginShapes(
      OrthographicCamera camera, ShapeRenderer shapes, ShapeRenderer.ShapeType type) {
    shapeLen = 0;
    if (!rasterizing()) return;
    Gdx.gl.glEnable(GL20.GL_BLEND);
    Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    shapes.setProjectionMatrix(camera.combined);
    shapes.begin(type);
  }

  private void endShapes(ShapeRenderer shapes) {
    if (rasterizing()) shapes.end();
  }

  private void tile(ShapeRenderer shapes, int col, int row) {
    float x0 = proj.x(view, col * 1000f);
    float x1 = proj.x(view, (col + 1) * 1000f);
    float y0 = proj.y(view, row * 1000f);
    float y1 = proj.y(view, (row + 1) * 1000f);
    shapeRect(shapes, Math.min(x0, x1), Math.min(y0, y1), Math.abs(x1 - x0), Math.abs(y1 - y0));
  }

  /**
   * Over the units, where the held card would land: a ring per unit it places (its collision
   * radius), or a spell's circle.
   */
  private void drawGhost(OrthographicCamera camera, ShapeRenderer shapes) {
    DeployCard card = selectedCard();
    int selected = hand.selected();
    PhoneLayout.Arena hover = hand.hover();
    if (card == null || hover == null) return;
    LandingTiles.Landing landing = session.landingAt(side, selected, hover.tileX(), hover.tileY());
    if (landing == null || landing.point() == null) return;
    int[] p = landing.point();
    List<float[]> rings = new ArrayList<>();
    if (card.spell()) {
      int r = card.radius() > 0 ? card.radius() : 1000;
      rings.add(new float[] {p[0], p[1], r});
    } else {
      CardPlacement.Result preview = session.preview(side, selected, p[0], p[1]);
      if (preview != null && preview.placed()) {
        for (CardPlacement.Unit u : preview.units()) {
          rings.add(new float[] {u.x(), u.y(), Math.max(300, u.unit().collisionRadius())});
        }
      }
      if (rings.isEmpty()) rings.add(new float[] {p[0], p[1], 500});
    }
    float sx = proj.pixelsPerTile() / 1000f;
    float sy = proj.pixelsPerTileY() / 1000f;
    beginShapes(camera, shapes, ShapeRenderer.ShapeType.Filled);
    shapes.setColor(1f, 1f, 1f, 0.22f);
    for (float[] r : rings) {
      shapeEllipse(
          shapes,
          proj.x(view, r[0]) - r[2] * sx,
          proj.y(view, r[1]) - r[2] * sy,
          2 * r[2] * sx,
          2 * r[2] * sy,
          32,
          true);
    }
    shapeFlush(shapes, camera);
    endShapes(shapes);
    beginShapes(camera, shapes, ShapeRenderer.ShapeType.Line);
    shapes.setColor(1f, 1f, 1f, 0.9f);
    for (float[] r : rings) {
      shapeEllipse(
          shapes,
          proj.x(view, r[0]) - r[2] * sx,
          proj.y(view, r[1]) - r[2] * sy,
          2 * r[2] * sx,
          2 * r[2] * sy,
          32,
          false);
    }
    shapeFlush(shapes, camera);
    endShapes(shapes);
  }

  @Override
  public void dispose() {
    effects.dispose();
    matchEnd.dispose();
    renderer.dispose();
    hud.dispose();
  }
}
