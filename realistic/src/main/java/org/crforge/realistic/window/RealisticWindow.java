/*
 * crforge-realistic - the game's own look for crforge's battle simulator
 * SPDX-License-Identifier: Apache-2.0
 */

package org.crforge.realistic.window;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.crforge.core.battle.data.GameTables;
import org.crforge.realistic.bridge.BattleAdapter;
import org.crforge.realistic.bridge.BattleFrame;
import org.crforge.realistic.bridge.BattleSession;
import org.crforge.realistic.bridge.ReplayFile;
import org.crforge.realistic.bridge.ReplayPlayer;
import org.crforge.realistic.bridge.ViewOrientation;
import org.crforge.realistic.render.art.ArenaScene;
import org.crforge.realistic.render.art.ArtCache;
import org.crforge.realistic.render.art.ArtProjection;
import org.crforge.realistic.render.art.BattleHud;
import org.crforge.realistic.render.art.EffectLayer;
import org.crforge.realistic.render.art.MatchEnd;
import org.crforge.realistic.render.art.RealisticRenderer;
import org.crforge.realistic.server.Bot;
import org.crforge.realistic.server.MatchOptions;
import org.crforge.realistic.server.PhoneLayout;

/**
 * A desktop window with the realistic view: a replay, or a live battle against the phone server's
 * {@link Bot}, drawn as the offscreen recorder draws it (the same frame, phone shaped, into a frame
 * buffer, then scaled into the window). It replaces the realistic mode of crforge's debug
 * visualizer (its X key), without the visualizer's debug overlays.
 *
 * <p>Keys: {@code SPACE} pauses, {@code U} toggles the player's view (the bottom side's own HUD)
 * and the replay HUD of both sides, {@code F} flips the view (a replay), {@code +}/{@code -} change
 * the speed (a replay), {@code .} steps one tick while paused, {@code R} starts again, {@code ESC}
 * quits. In a live battle you play the bottom side: click a card (it lifts), then the arena where
 * it goes, or drag it there; click an ability button to use it. No sounds are played.
 */
@Slf4j
public final class RealisticWindow extends ApplicationAdapter {

  /**
   * How the window starts.
   *
   * @param width the frame's width in pixels (the window is scaled to fit the screen)
   * @param height the frame's height in pixels
   * @param bottomSide the side drawn at the bottom; in a live battle the side you play
   * @param playerView whether the HUD is the bottom side's own rather than both sides'
   * @param location the arena location row, or null for the replay's (or the default)
   * @param deck a live battle's deck for you ({@link MatchOptions} ids)
   * @param opponentDeck a live battle's deck for the bot
   */
  public record Settings(
      int width,
      int height,
      int bottomSide,
      boolean playerView,
      String location,
      String deck,
      String opponentDeck) {}

  private static final float TICK_SECONDS = 0.05f;

  /** Ticks drawn after the battle's last one, as the recorder (4.5 s: the end screen plays). */
  private static final int OUTRO_TICKS = 90;

  private final GameTables tables;
  private final ReplayFile replay;
  private final Settings settings;

  private ArtCache cache;
  private FrameBuffer fbo;
  private OrthographicCamera camera;
  private SpriteBatch screen;
  private Battle battle;
  private int bottom;
  private boolean playerView;
  private boolean paused;
  private float speed = 1f;
  private float accumulator;
  private boolean stepRequested;

  private RealisticWindow(GameTables tables, ReplayFile replay, Settings settings) {
    this.tables = tables;
    this.replay = replay;
    this.settings = settings;
    this.bottom = settings.bottomSide();
    this.playerView = settings.playerView();
  }

  /** Opens the window; returns when it closes. A null replay plays a live battle. */
  public static void launch(GameTables tables, ReplayFile replay, Settings settings) {
    if (settings.location() != null)
      System.setProperty("crforge.art.location", settings.location());
    Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
    config.setTitle(
        replay != null
            ? "crforge-realistic - " + replay.path().getFileName()
            : "crforge-realistic - you against the bot");
    // phone shaped, at most 85% of the screen's height
    int screenHeight = Lwjgl3ApplicationConfiguration.getDisplayMode().height;
    float scale = Math.min(1f, screenHeight * 0.85f / settings.height());
    config.setWindowedMode(
        Math.round(settings.width() * scale), Math.round(settings.height() * scale));
    config.setResizable(true);
    config.useVsync(true);
    config.setForegroundFPS(60);
    new Lwjgl3Application(new RealisticWindow(tables, replay, settings), config);
  }

  @Override
  public void create() {
    cache =
        ArtCache.locate()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no game art cache: extract it with crforge-art (docs/GETTING-ASSETS.md)"));
    fbo = new FrameBuffer(Pixmap.Format.RGBA8888, settings.width(), settings.height(), false);
    fbo.getColorBufferTexture()
        .setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
    camera = new OrthographicCamera(settings.width(), settings.height());
    camera.setToOrtho(false, settings.width(), settings.height());
    screen = new SpriteBatch();
    battle = new Battle();
    Gdx.input.setInputProcessor(new Keys());
    log.info(
        "{}: SPACE pause, U player's view, F flip, +/- speed, . step, R restart, ESC quit",
        replay != null ? "Replay" : "You (side " + bottom + ") against the bot");
  }

  @Override
  public void render() {
    float delta = Math.min(Gdx.graphics.getDeltaTime(), 0.25f);
    if (!paused) accumulator += delta * speed;
    if (stepRequested) {
      accumulator = TICK_SECONDS;
      stepRequested = false;
    }
    for (int steps = 0; accumulator >= TICK_SECONDS && steps < 8; steps++) {
      accumulator -= TICK_SECONDS;
      battle.tick();
    }
    if (accumulator > TICK_SECONDS) accumulator = 0;
    float alpha = Math.min(1f, accumulator / TICK_SECONDS);

    fbo.begin();
    battle.draw(alpha);
    if (smoke != null) smoke();
    fbo.end();

    Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    float[] r = frameRect();
    screen
        .getProjectionMatrix()
        .setToOrtho2D(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    screen.begin();
    screen.disableBlending();
    Texture t = fbo.getColorBufferTexture();
    screen.draw(t, r[0], r[1], r[2], r[3], 0, 0, t.getWidth(), t.getHeight(), false, true);
    screen.end();
  }

  /** Where the frame is drawn in the window: x, y (from the bottom), width, height. */
  private float[] frameRect() {
    float ww = Gdx.graphics.getWidth();
    float wh = Gdx.graphics.getHeight();
    float s = Math.min(ww / settings.width(), wh / settings.height());
    float w = settings.width() * s;
    float h = settings.height() * s;
    return new float[] {(ww - w) / 2f, (wh - h) / 2f, w, h};
  }

  /**
   * The system property of the window's smoke check: a {@code .png} the window writes its frame to
   * after {@value #SMOKE_TICKS} ticks, then closes. In a live battle it plays its first card on the
   * way, through the same clicks a player makes.
   */
  public static final String SMOKE_PROPERTY = "crforge.windowSmoke";

  private static final int SMOKE_TICKS = 200;

  private boolean smokePlayed;

  private final String smoke = System.getProperty(SMOKE_PROPERTY);

  private void smoke() {
    int tick = battle.current.tick();
    if (replay == null && tick >= 120 && !smokePlayed) {
      smokePlayed = true;
      // a click on the first card, then on the arena in front of the left princess tower
      battle.touch(true, 0.30f, 0.925f);
      battle.touch(false, 0.30f, 0.925f);
      battle.touch(true, 0.30f, 0.62f);
      battle.touch(false, 0.30f, 0.62f);
    }
    if (tick < SMOKE_TICKS) return;
    Pixmap gl = Pixmap.createFromFrameBuffer(0, 0, settings.width(), settings.height());
    // createFromFrameBuffer hands the rows top first already; the alpha is made opaque
    Pixmap shot = new Pixmap(gl.getWidth(), gl.getHeight(), Pixmap.Format.RGBA8888);
    int stride = gl.getWidth() * 4;
    byte[] row = new byte[stride];
    java.nio.ByteBuffer src = gl.getPixels();
    java.nio.ByteBuffer dst = shot.getPixels();
    for (int y = 0; y < gl.getHeight(); y++) {
      src.position(y * stride);
      src.get(row);
      for (int i = 3; i < stride; i += 4) row[i] = (byte) 255;
      dst.position(y * stride);
      dst.put(row);
    }
    src.rewind();
    dst.rewind();
    gl.dispose();
    com.badlogic.gdx.graphics.PixmapIO.writePNG(
        Gdx.files.absolute(new java.io.File(smoke).getAbsolutePath()), shot, 0, true);
    shot.dispose();
    log.info(
        "Smoke: tick {}, {} plays, frame written to {}",
        tick,
        battle.session.getBattle().getPlays().size(),
        smoke);
    Gdx.app.exit();
  }

  @Override
  public void dispose() {
    if (battle != null) battle.dispose();
    if (fbo != null) fbo.dispose();
    if (screen != null) screen.dispose();
    if (cache != null) cache.dispose();
  }

  /** One battle as the window plays it: a replay's or a live one, and its drawing. */
  private final class Battle {
    final ReplayPlayer player;
    final BattleSession session;
    final Bot bot;
    final ViewOrientation view;
    final ArtProjection proj;
    final PhoneLayout layout;
    final RealisticRenderer renderer;
    final ArenaScene arena;
    final BattleHud hud;
    final EffectLayer effects;
    final MatchEnd matchEnd;
    BattleFrame previous;
    BattleFrame current;
    int outroTicks;
    boolean over;
    int selected = -1;
    PhoneLayout.Target pressed;

    Battle() {
      List<String> names;
      if (replay != null) {
        player = new ReplayPlayer(replay, tables);
        session = player.getSession();
        if (session == null) throw new IllegalStateException("the replay cannot be played");
        bot = null;
        names = replay.header().names();
      } else {
        player = null;
        MatchOptions options = new MatchOptions(tables, cache.folder());
        MatchOptions.Deck mine = options.deck(settings.deck(), "blue");
        MatchOptions.Deck theirs = options.deck(settings.opponentDeck(), "red");
        // you play the bottom side, the bot the other
        session =
            bottom == 0
                ? BattleSession.ladder(
                    tables, mine.cards(), mine.slots(), theirs.cards(), theirs.slots())
                : BattleSession.ladder(
                    tables, theirs.cards(), theirs.slots(), mine.cards(), mine.slots());
        bot = new Bot(1 - bottom, System.nanoTime());
        names = bottom == 0 ? List.of("You", "Bot") : List.of("Bot", "You");
        log.info("Your deck: {}; the bot's: {}", mine.name(), theirs.name());
      }
      var tileMap = session.getBattle().getWorld().getTileMap();
      view = ViewOrientation.of(tileMap, bottom);
      layout =
          PhoneLayout.of(
              settings.width(), settings.height(), tileMap.widthUnits(), tileMap.heightUnits());
      proj = layout.projection();
      renderer = new RealisticRenderer(cache);
      arena =
          replay != null
              ? new ArenaScene(cache, tables, replay)
              : new ArenaScene(cache, tables, settings.location());
      int[] sh = arena.shadow();
      renderer.setShadowColor(sh[0], sh[1], sh[2], sh[3]);
      hud = new BattleHud(cache, renderer.drawer());
      hud.setUnits(renderer);
      hud.setTables(tables);
      hud.setPlays(session.getBattle()::getPlays);
      hud.setPlayerNames(names);
      effects = new EffectLayer(cache);
      renderer.setEffects(effects);
      session.getBattle().getWorld().addObserver(effects.observer());
      session.getBattle().getWorld().addObserver(renderer.observer());
      matchEnd = new MatchEnd(cache);
      read(BattleAdapter.frame(session, view::sideName));
      previous = current;
    }

    /** Reads the battle after a step (null: an outro tick after its end). */
    private void read(BattleFrame frame) {
      previous = current;
      if (frame == null) {
        matchEnd.stopped();
        current = matchEnd.update(later(current), view);
      } else {
        current = matchEnd.update(frame, view);
      }
      if (previous == null) previous = current;
      renderer.setTickData(
          BattleAdapter.attackTimers(session), BattleAdapter.projectileFlights(session));
      effects.update(current, previous == current ? null : previous, tables, view);
      // nothing plays them here
      effects.drainSounds();
      matchEnd.drainSounds();
    }

    void tick() {
      if (over) return;
      if (outroTicks > 0) {
        outro();
        return;
      }
      boolean stepped;
      if (player != null) {
        stepped = player.step();
        player.drainAreaHits();
      } else {
        bot.decide(session, BattleAdapter.frame(session));
        String selectedCard = selected < 0 ? null : cardName(selected);
        stepped = session.step();
        // the selected card left its slot (played, or the slot refilled): drop the selection
        if (selected >= 0 && !java.util.Objects.equals(selectedCard, cardName(selected))) {
          selected = -1;
        }
      }
      if (stepped) {
        read(BattleAdapter.frame(session, view::sideName));
      } else {
        selected = -1;
        outro();
        log.info("The battle is over (R starts again)");
      }
    }

    private String cardName(int slot) {
      var card = session.handCard(bottom, slot);
      return card == null ? null : card.name();
    }

    private void outro() {
      read(null);
      if (++outroTicks >= OUTRO_TICKS) over = true;
    }

    void draw(float alpha) {
      int w = settings.width();
      int h = settings.height();
      hud.setPlayerView(playerView);
      hud.setRaisedSlot(selected);
      Gdx.gl.glClearColor(0.11f, 0.13f, 0.12f, 1f);
      Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
      Gdx.gl.glEnable(GL20.GL_BLEND);
      float seconds = (previous.timeMs() + (current.timeMs() - previous.timeMs()) * alpha) / 1000f;
      // the effects' screen shake moves the arena, not the HUD
      float[] shake = effects.shake(seconds);
      float ppdu = proj.pixelsPerDisplayUnit();
      float dx = shake == null ? 0 : shake[0] * ppdu;
      float dy = shake == null ? 0 : shake[1] * ppdu;
      camera.position.set(w / 2f - dx, h / 2f - dy, 0);
      camera.update();
      renderer.drawer().batch().setProjectionMatrix(camera.combined);
      renderer.setEffectClock(seconds);
      arena.drawGround(renderer.drawer(), proj, view, current, previous, alpha);
      renderer.drawer().begin();
      effects.drawBelow(renderer.drawer(), proj, view, seconds);
      renderer.drawer().end();
      renderer.render(current, previous, alpha, tables, view, proj, camera.combined);
      renderer.drawer().begin();
      effects.drawAbove(renderer.drawer(), proj, view, seconds);
      renderer.drawer().end();
      arena.drawOverlay(renderer.drawer(), proj, view, current, alpha);
      camera.position.set(w / 2f, h / 2f, 0);
      camera.update();
      hud.draw(
          matchEnd.hud(current), matchEnd.hud(previous), alpha, view, proj, w, h, camera.combined);
      float tickSeconds = (previous.tick() + (current.tick() - previous.tick()) * alpha) * 0.05f;
      matchEnd.draw(tickSeconds, w, h, renderer.drawer(), camera.combined);
    }

    /** A press or a release at a point of the frame (0..1 across, 0..1 down). */
    void touch(boolean down, float nx, float ny) {
      if (bot == null || outroTicks > 0 || over) return;
      BattleFrame.SideView me = null;
      for (BattleFrame.SideView s : current.sides()) if (s.side() == bottom) me = s;
      PhoneLayout.Target hit = layout.hit(nx, ny, view, me == null ? null : me.abilities());
      if (down) {
        pressed = hit;
        if (hit instanceof PhoneLayout.Card c) {
          selected = selected == c.slot() ? -1 : c.slot();
          if (session.handCard(bottom, c.slot()) == null) selected = -1;
        } else if (hit instanceof PhoneLayout.Ability a) {
          if (!session.useAbility(bottom, a.slot())) refused();
        } else if (hit instanceof PhoneLayout.Arena a && selected >= 0) {
          play(a);
        }
        return;
      }
      // a drag from a card onto the arena plays it there
      if (pressed instanceof PhoneLayout.Card && hit instanceof PhoneLayout.Arena a) {
        if (selected < 0 && pressed instanceof PhoneLayout.Card c) selected = c.slot();
        play(a);
      }
      pressed = null;
    }

    private void play(PhoneLayout.Arena at) {
      int slot = selected;
      selected = -1;
      int[] p = at.tileCentre();
      if (!session.play(bottom, slot, p[0], p[1])) refused();
    }

    private void refused() {
      List<String> m = session.messages();
      log.info("Refused: {}", m.isEmpty() ? "?" : m.get(m.size() - 1));
    }

    void dispose() {
      effects.dispose();
      matchEnd.dispose();
      renderer.dispose();
      hud.dispose();
    }
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

  private void restart() {
    battle.dispose();
    battle = new Battle();
    accumulator = 0;
    paused = false;
  }

  private final class Keys extends InputAdapter {
    @Override
    public boolean keyDown(int key) {
      switch (key) {
        case Input.Keys.SPACE -> paused = !paused;
        case Input.Keys.U -> {
          playerView = !playerView;
          log.info("Player's view: {}", playerView ? "side " + bottom : "off");
        }
        case Input.Keys.F -> {
          if (replay == null) return false;
          bottom = 1 - bottom;
          restartAt(battle.current.tick());
        }
        case Input.Keys.PLUS, Input.Keys.EQUALS -> speed = Math.min(8f, speed * 2f);
        case Input.Keys.MINUS -> speed = Math.max(0.25f, speed / 2f);
        case Input.Keys.PERIOD -> {
          if (paused) stepRequested = true;
        }
        case Input.Keys.R -> restart();
        case Input.Keys.ESCAPE -> Gdx.app.exit();
        default -> {
          return false;
        }
      }
      return true;
    }

    @Override
    public boolean touchDown(int x, int y, int pointer, int button) {
      return touch(true, x, y);
    }

    @Override
    public boolean touchUp(int x, int y, int pointer, int button) {
      return touch(false, x, y);
    }

    private boolean touch(boolean down, int x, int y) {
      float[] r = frameRect();
      float nx = (x - r[0]) / r[2];
      // window y runs down from the top; the frame's rectangle is measured from the bottom
      float ny = (y - (Gdx.graphics.getHeight() - r[1] - r[3])) / r[3];
      if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return false;
      battle.touch(down, nx, ny);
      return true;
    }
  }

  /** A replay seen from the other side: the battle anew, played on to the tick it was at. */
  private void restartAt(int tick) {
    boolean wasPaused = paused;
    restart();
    while (battle.current.tick() < tick && battle.outroTicks == 0) battle.tick();
    paused = wasPaused;
  }
}
