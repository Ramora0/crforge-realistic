# Play from your phone

The phone server plays crforge battles in a phone's browser, drawn with the game's own art, HUD and sounds ([realistic rendering](realistic-rendering.md)). The battle, the bot and the renderer run on your computer. By default the phone draws the picture itself: the server sends it what the renderer draws, as a list of textured polygons a frame, and the parts of the game's textures they use, and the page draws them with WebGL at the screen's own resolution and sends your taps back ([drawn on the phone](#the-picture-drawn-on-the-phone)). That needs well under 1 Mbit/s once the art is in. The older video stream (JPEG frames drawn on the computer) is still there as an option. You play the bottom side against a built-in bot, or against a friend on a second phone.

It needs the art cache of [Getting the assets](GETTING-ASSETS.md) (extract it with `--audio` for the sounds) and `ffmpeg` on the `PATH` for iPhones that cannot decode Ogg Vorbis.

## Starting it

```bash
./gradlew :realistic:run -Pcrforge.assetSource=cdn --args="--serve"
#   [--port 8765] [--lan] [--token X] [--size 720x1560] [--fps 30] [--quality 70]
#   [--location PvP_royal] [--audio-cache DIR] [--render list|jpeg]
```

It prints the URLs to open, with the access token:

```
crforge phone server on port 8765 (token 7hQk...)
  this Mac:    http://127.0.0.1:8765/?t=7hQk...
```

Open the URL, tap **Tap to play** (this tap also starts the sound), and play:

- **Drag** a card from your hand onto the arena, or **tap** a card (it lifts) and then tap where it goes. Tap the lifted card again to put it back.
- While you hold a card over the arena, the tiles it cannot go on are shaded red, the tile it lands on is lit, and a ring marks each unit it would place (a spell's circle for a spell). A drop where the card cannot land goes to the nearest legal tile, as in the game; a play the game refuses (not enough elixir) shows the reason.
- For the first seconds of a battle the game's start cooldown refuses every play, as in the game; the page says so.
- A champion's or hero's **ability button** appears above the hand while it is on the field; tap it.
- **Menu**: a new battle (bot or friend, your deck, the bot's deck, the arena), the picture setting (**Drawn on this phone**, the default, or video: **Sharp**, **Balanced**, **Light**), and the stats line (frame rate, frame size, round trip, tap-to-frame time; drawn here, the time a frame takes to draw).
- The first time, **Tap to play** waits until the art the first frame needs is in ("Loading the art: 2.1 MB"): 3.5 MB, a few seconds on Wi-Fi.

| Option | Default | Meaning |
|---|---|---|
| `--port` | 8765 | the one port for the page, its WebSocket and its sounds |
| `--lan` | off | listen on every interface (0.0.0.0) rather than on 127.0.0.1 only |
| `--token` | random | the access token (8 to 128 letters, digits, `-`, `_`) |
| `--size` | 720x1560 | the frame the view is laid out in, phone shaped (19.5:9); drawn on the phone it is only the coordinate space, scaled to the screen |
| `--fps` | 30 | frames a second at most |
| `--quality` | 70 | the video's JPEG quality: where it starts, and never exceeds |
| `--render` | list | a page's picture unless it chooses otherwise in the menu: `list` (drawn on the phone) or `jpeg` (video) |
| `--location` | PvP_royal | the arena of the first battle |
| `--audio-cache` | `~/.cache/crforge-phone/audio` | where sounds transcoded to AAC are kept |

Stop the server with Ctrl+C.

## Reaching it from the phone

The server listens on 127.0.0.1 only unless you pass `--lan`. Every request must carry the token: in the URL (`?t=...`) or in the cookie the page sets. A URL without it, a tunnel's included, gets `401`.

**Same Wi-Fi (simplest).** Start with `--lan`; it prints the URL with your computer's LAN address (`http://192.168.x.y:8765/?t=...`). Open it on the phone. Anyone on that Wi-Fi who learns the URL can play; the stream is plain HTTP, so use a network you trust.

**Tailscale (recommended away from home).** With [Tailscale](https://tailscale.com) on the computer and the phone, keep the default 127.0.0.1 binding and run `tailscale serve 8765`. It prints `https://<computer>.<tailnet>.ts.net/`; open that with `/?t=<token>` on the phone. Only your own devices reach it, over WireGuard, with HTTPS.

**A public tunnel.** Only when you need it, and stop it when you are done: the URL is public and only the token protects the stream.

- ngrok: `ngrok http 8765`, or with a second lock, `ngrok http 8765 --basic-auth "me:a-long-password"`. Open `https://<id>.ngrok-free.app/?t=<token>`.
- cloudflared: `cloudflared tunnel --url http://localhost:8765`. Open `https://<words>.trycloudflare.com/?t=<token>`.

Either way the page switches to `wss://` itself. Drawn on the phone, a battle needs well under 1 Mbit/s once the art is in, so a tunnel or a phone network is fine. Video is about 30 Mbit/s sharp (see below), more than most tunnels and phone networks carry: there choose **Balanced** or **Light**.

## Playing a friend

Choose **Menu > New battle > Opponent: A friend on another phone**. Your friend opens the same URL; the first page to join plays side 0, the second side 1, and each sees the battle from their own side with their own hand. The battle starts when both have tapped to play. Add `&seat=0` or `&seat=1` to a URL to pick a side.

## The bot

The bot plays side 1 through the same path as your cards (it is refused what you would be refused) and sees what a player sees: the field, its own hand and elixir. Every 0.4 s, with no play of its own waiting, it takes the first of:

1. **Spell a group**: a spell whose circle covers three or more of your troops is cast on them.
2. **Defend**: a troop of yours past the river: its cheapest troop or building that is not a win condition (one that hits air against an air troop), a troop in the threat's path in front of its towers, a building in the middle of its half to pull it.
3. **Support**: its win condition heading for the bridge alone gets its dearest other troop behind it.
4. **Attack** at 9 elixir in the lane whose princess tower of yours is weaker: its cheapest win condition (a troop that targets buildings only), a tank (2000 hit points or more) behind its king tower, any other at the bridge; with none, its cheapest troop at the bridge.
5. **No leak**: at 10 elixir, its cheapest troop or building behind its king tower.

It is a sparring partner, not a strong player. The decks list the example replay's two decks (the default: you play the blue deck, the bot the red one) and a few common Ladder decks.

## How it works

`realistic/src/main/java/org/crforge/realistic/server`:

| Class | Role |
|---|---|
| `PhoneServer` | the hidden-window LibGDX app: steps the battle at 20 ticks a second in real time, draws each seat's view at the frame rate (recorded as a draw list, or into a frame buffer read back for the JPEG thread, or both) and sends each page its picture; the routes; the seats, the battle's phases (waiting, running, outro, over), the dry run and the pages' state |
| `DrawRecorder` | the renderer's batch, recording each of its draws (polygons, texture region, colours, blending, shader and its uniforms) as it draws, or instead of drawing |
| `DrawFrame`, `DrawDefs` | a frame's draw list; the texture regions and GL states it refers to by number |
| `DrawCodec` | a draw list coded as the changes from the last one a page was sent, deflated; and its decoder (the page's in Java, for the tests) |
| `TextureStore` | the parts of the textures the draw lists use, packed into 2048 pages as patches |
| `Seat` | one side's view: the realistic renderer, effects, player's-view HUD (`BattleHud.setPlayerView`), match end and sounds of that side, and the placement overlay |
| `TapInput` | the finger: select, drag, tap to play, ability buttons, through `BattleSession` |
| `PhoneLayout` | the recorder's phone framing; maps a tap to a hand card (`BattleHud.handSlotBox`), an ability button (`BattleHud.abilityButtonBox`) or an arena point (the inverse of `ArtProjection`) |
| `WebServer` | a small HTTP/1.1 and WebSocket server on one port, one virtual thread a connection; a slow page drops frames instead of queueing them |
| `Access` | the token: drawn from `SecureRandom`, compared in constant time |
| `AudioCatalog` | the art cache's `audio/index.json` in a compact form for the page, and its samples as Ogg or AAC (transcoded once by ffmpeg) |
| `BattleSchedule` | the music and sounds the game times itself (`client_globals` SOUNDS_PVP, MUSICS_PVP), as `crforge_art/audio.py` does for recordings |
| `Bot` | the built-in opponent |
| `FrameEncoder` | GL read-back to JPEG (ImageIO), for video |

`realistic/src/main/resources/org/crforge/realistic/server/index.html` is the page: plain JavaScript, no build step.

**Video frames.** Each WebSocket binary message is a 20-byte header (type, frame number, tick, the last input applied, the encode time, the JPEG quality, the capture time in server milliseconds) and the JPEG. The page decodes each frame as it arrives and acknowledges it, then shows it on a steady clock: at its capture time plus a playout delay. That delay is the lowest latency of the last 4 s plus a margin covering the jitter (the 97th percentile of how much later frames arrived, 25-300 ms); it grows quickly after a late frame and shrinks slowly. Sounds are held back by the same margin so they stay in step with the picture. The server sends a page at most eight frames ahead of its acknowledgements and skips it otherwise, so a slow link gets fewer frames, not later ones. While a page's window stays full the quality drops by 5 (down to 30), and it climbs back 3 s after the skips stop. Positions are interpolated between ticks, as in the recorder.

**Draw-list frames** go through the same playout buffer and acknowledgements; see below.

**Taps.** The page sends `down`, `move` and `up` in normalized frame coordinates (0..1 across, 0..1 down). The server maps them through the same layout it draws with, so a tap on a card is a tap on that card at any frame size.

**Sounds.** The server sends the sound references its view's effect layer, battle sounds and match end start (`soundbanks/<bank>/<event>`) as they happen, and the battle music's changes. The page plays them with Web Audio the way `audio.py` mixes a recording: each layer picks a choice by weight, with its pitch and volume and their random spread, its delay and loop; the event's cooldown and instance limit hold; footsteps, wing flaps and voice lines are thinned and a stack of one event is turned down. The battle music loops its event's tracks from the start jingle's end, switches at 60 s and 30 s and at overtime, and stops at the end, where the horn and the win, lose or draw jingle play. The countdown and the time warnings come from the client tables.

## The picture drawn on the phone

The server keeps all the logic: the battle, the bot, the taps, the HUD and the effects. Its renderer runs as for video, but draws into a recording batch (`DrawRecorder`): everything the realistic view, its effects, the HUD, the fonts and the placement overlay draw goes through one `PolygonSpriteBatch`, and each of its flushes (a GL draw call) is recorded as polygons. Nothing reads pixels back and there is no frame buffer compositing, so a frame is fully described by what it draws. The page is told what to draw where and draws it with WebGL (2, else 1), at its own resolution (a 402x874 iPhone screen at 3x: 1206x2622 pixels), with the server's shader maths: libgdx's default, ScDrawer's and EffectDrawer's added colour, HudArt's clamp and grey, the shadows' silhouette, and the same blending.

**What a frame is.** Measured over a whole battle of two bots (`:realistic:phoneRenderBench`, 5,658 frames at 30 fps): 569 polygons and 2,705 vertices a frame on average, 1,285 and 6,456 at most, in 218 of the renderer's draw calls. A polygon is a region of a texture (its texture coordinates, interned and sent once, with its triangulation: a strip, as ScDrawer cuts shapes; a quad, as glyphs are; or triangles), a GL state (interned too), its vertex colours (one or one a vertex) and its vertex positions in 1/16 of a frame pixel. Polygons wholly off the frame are dropped.

**Coding.** A frame is sent as the changes from the last frame that page was sent (`DrawCodec`): runs of polygons unchanged, polygons moved (vertex 0's move, then any other vertex's own), recoloured or restated, and new ones (shape repeated from the region's last one where it is the same), then deflated. A frame costs 1.5 KB on average (1.2 KB median, 2.9 KB at the 90th percentile, 9.4 KB at most): 0.36 Mbit/s at 30 fps. Recording a frame takes 0.6 ms on the GL thread and coding it 0.1-0.2 ms on the seat's encoder thread; the server neither rasterizes nor reads back nor compresses images, so a draw-list battle costs it less than video.

**Textures.** The game's atlases are large (a battle touches 87 of them, 153 MB as PNG and 890 MB of GPU memory with mipmaps) but a battle draws about a tenth of them: the HUD a few cards of the UI atlases, a unit the animations it plays. So `TextureStore` packs the parts the draw lists use, as they are first drawn, into 2048x2048 pages: each region's rectangle of its atlas, with a margin of 4 texels for filtering and mipmaps, copied 1:1 (the region's texture coordinates are moved to the page). A page is sent a patch (a PNG of that rectangle) before the first frame that draws it, in order on the WebSocket. Art is sent with straight alpha, as the atlas holds it, and premultiplied by the page on upload, as `ArtCache` premultiplies it; the pages are mipmapped. Font glyph pages, made at run time, are read back from GL and sent as they are held. A first battle downloads 3.5 MB before its first frame (the arena, towers and HUD) and about 20-24 MB over the whole battle as units first appear, into 5-8 pages (100-170 MB of GPU memory). A second battle with the same decks and arena needs none: the pages persist for the server's life, until they pass 6 pages, when the next battle starts a fresh set and the page frees the old ones.

**Sent ahead.** A unit's first appearance would hold the stream up while its art arrives, so when a battle is set up the server plays a dry run of it offscreen: the two decks played by two bots for two minutes, drawn into the recorder every third tick and never shown. The patches its frames use (1,240 for the default decks) are sent ahead of need: as fast as the link takes them while the battle waits to start, then at most 1.2 Mbit/s and only while the page acknowledges frames at once and nothing is queued.

**3D units.** With a 3D extraction (`--sc3d`, [realistic rendering §10](realistic-rendering.md#10-3d-assets-sc3d)) the desktop and video draw the hero forms, evolutions and their 3D effects as models. A model reaches the batch as an image the renderer draws into one shared frame buffer, anew each frame, which a draw list could only carry as a fresh texture every frame (a few hundred KB a unit a frame, far over the stream's budget). So while a frame is recorded for draw lists the renderer leaves the models out (`RealisticRenderer.setDrawModels`): the 3D rows, their 3D projectiles and effects are drawn with their 2D stand-in, their bars at the 2D place, as without an extraction; the forms, mounts and effects are still followed from the core's ticks, so the models pick up where they are when the page leaves. The dry run sends that 2D art ahead too. A JPEG page watching the same frame as a draw-list page sees the same 2D stand-in. Without the fallback the page drew the first model image ever read back (the frame buffer read once, its pixels kept as a texture) for every model: the Mega Minion hero showed as pieces of the Hero Tombstone. Measured with the blue example deck's two hero forms (`:realistic:phoneRenderBench --args="OUT 900,1800,2700 720x1560 --sc3d=DIR"`, add `--models` for the old behaviour): 0.36 Mbit/s, 1.5 KB a frame, 2,042 patches, parity 0.32 levels mean difference (0.30-0.35% of pixels off by more than 8), as before 3D; with the models, 9,692 regions instead of 2,095 and parity 0.97-2.2 levels (1-2.3% off). A first battle on a fresh server through `badlink.py` (5 Mbit/s, 20 ms, stalls of 300-1,300 ms) showed 19.8 fps, 22.4 MB in 92 s (frames 3.1 MB, 1.6 KB each; art 19.2 MB); on a plain link 27.2 fps, frames 1.8 KB each, steady 0.55-1.0 Mbit/s.

**Protocol.** On the same WebSocket as video, as binary messages in order (never replaced, unlike video frames):

| Type (first byte) | Message |
|---|---|
| 2 | a draw-list frame: the video frame's 20-byte header (type, frame, tick, the last input applied, the coding time, flags: 1 key frame, capture time), then the deflated payload (`DrawCodec`'s format) |
| 3 | a texture patch: type, flags (1: plain, else art: premultiply and mipmap), page, the page's width and height, the patch's x and y in it (24 bytes), then the PNG |

and a text message `{"type":"reset"}` when the page is to drop its pages and definitions (a fresh texture store). The page asks for its picture with `{"type":"picture","mode":"list"|"jpeg",...}`; a page that reconnects is sent a key frame and the patches its frames need again.

**Measured** with the page in headless Chromium at an iPhone's size (`tools/phone-render/e2e.py`), a first battle on a fresh server, cards played every 4 s:

| Link | Picture | Tap to play ready | Shown, battle average (last two thirds) | Longest gap in a 2 s window: worst, median | Received in 2 min |
|---|---|---|---|---|---|
| 5 Mbit/s, 20 ms (`badlink.py`) | drawn | 6.0 s | 25.5 fps (28.9) | 1,866 ms, 83 ms | 26.7 MB: frames 5.4 MB (1.7 KB each), art 21.1 MB |
| 5 Mbit/s, 20 ms, stalls of 300-1,300 ms every 2-6 s | drawn | 7.9 s | 20.8 fps (24.2) | 2,383 ms, 267 ms | 23.9 MB: frames 4.5 MB, art 19.2 MB |
| the same | video, Sharp | at once | 6.3 fps (6.6) | 1,320 ms, 434 ms | 59.5 MB (75 KB frames) |
| 50 Mbit/s, 5 ms, WebKit | drawn | 3.5 s | 25.6 fps (28.5) | 2,164 ms, 67 ms | 24.2 MB |

Once a battle's art is in, the stream is 0.3-0.9 Mbit/s at a steady 30 fps (gaps of 34-51 ms); the first battle with new decks averages about 1.5 Mbit/s as its art streams in, and its worst moments are a unit's first appearance on a slow link. With stalls, the link's own freezes (a fifth of the time here) are what is left: video, at 75 KB a frame, cannot keep up at all. The page draws a frame in 0.1-0.3 ms of its own time (headless Chromium; WebKit 1-2 ms) and decodes it in 0.3-0.6 ms. (Chromium's network emulation through CDP did not hold the WebSocket to its latency, so the bad links are made with the proxy.)

**Pixel parity.** `tools/phone-render/parity.py` replays the bench's parity frames (a key frame and the patches made so far) in the page's own player and compares them with the server's frame buffer at the same size: mid-battle frames with units, effects and the HUD differ by 0.24-0.33 levels on average (of 255), with 0.14-0.33% of the pixels off by more than 8, in Chromium and WebKit alike: polygon edges rasterized a hair differently, a thin clock sweep, mipmaps made from a page rather than the whole atlas.

## iPhone notes

- Safari before 18.4 cannot decode Ogg Vorbis. The page tries one Ogg sample and, if it does not decode, asks for AAC (`.m4a`), which the server transcodes with ffmpeg on the first request and keeps in `--audio-cache`, outside the art cache and the repository. The first battle on an iPhone has a few silent first sounds while they are transcoded (each takes about 50 ms).
- The sound starts with the **Tap to play** tap, as iOS requires. The page asks iOS (16.4 and later) to play as media, so the ring/silent switch does not mute it; on older iOS, turn the silent switch off.
- **Add to Home Screen** opens it full screen, without Safari's bars. The saved URL keeps the token.
- The page asks to keep the screen awake while you play. When the phone locks or you switch apps, iOS drops the page's connection after a moment; a battle against the bot then pauses until the page reconnects, which it does when you come back.
- Pinch zoom, double-tap zoom and scrolling are off; taps have no 300 ms delay.
- Drawn on the phone needs WebGL and `DecompressionStream` (iOS 16.4 and later); a browser without them gets video. If iOS drops the page's GL context (memory, the app in the background), the page reconnects and is sent its textures again.

## Performance

Drawn on the phone, see [above](#the-picture-drawn-on-the-phone). Video, measured on an M5 MacBook with a headless test client on the same machine (so the network adds nothing), against the bot:

| Frame | Picture | Frame rate | JPEG per frame | Stream | Tap to frame (median / p90) |
|---|---|---|---|---|---|
| 720x1560 | quality 70 (default) | 29.6 fps | 139 KB | about 33 Mbit/s | 25-29 / 40 ms |
| 720x1560 | Balanced (55, 24 fps) | 23.6 fps | 109 KB | about 21 Mbit/s | 27 / 54 ms |
| 720x1560 | Light (40, 15 fps) | 15.1 fps | 89 KB | about 11 Mbit/s | 34 / 60 ms |
| 540x1170 | quality 70 | 30.0 fps | 89 KB | about 21 Mbit/s | 18 / 27 ms |
| 540x1170 | Balanced | 24.0 fps | 65 KB | about 12.5 Mbit/s | 13 / 33 ms |
| 540x1170 | Light | 15.1 fps | 55 KB | about 6.6 Mbit/s | 33 / 64 ms |

A frame takes 1-2 ms to draw (9 ms for the first ones, while the art loads), 1.5-2 ms to read back and 6-8 ms to encode, on its own thread. A tap is applied within a millisecond of its arrival; "tap to frame" is the time from sending a tap to receiving the first frame that shows it. On Wi-Fi add the network's round trip (a few ms) and the phone's decode (about 5-15 ms). A card's play then runs one second after the tap, as in the game. A friend battle draws two views; each costs the same. The server uses about half a core while a battle streams.

For a tunnel or a phone network, start the server with `--size 540x1170` and pick **Balanced** or **Light** in the menu.

## Limitations

- Drawn on the phone, the first battle with new decks streams its art (3.5 MB before the start, about 20 MB more over the battle; PNG, lossless). On a slow link a unit the dry run did not draw (a direction, an animation) can hold the stream up for a moment the first time it appears. Lossy WebP would cut the art to about a third, at the cost of an encoder the JVM lacks (`cwebp`) and exact parity.
- A Wi-Fi stall longer than the playout buffer (300 ms at most) still freezes the picture for its length, as with video; the draw lists only make the stream small enough not to cause stalls of its own.
- Video frames are JPEG. A sharp 30 fps stream needs a fast network.
- No emotes, no king tower activation animation before the first hit, no intro banners: the battle starts one second after both players tap.
- No placement ghost of the unit's own sprite: rings mark where the units land.
- The bot is a handful of rules; there is no difficulty setting.
- Drawn on the phone, 3D units show their 2D stand-in (see *3D units* above); only video shows the models.
- One battle at a time per server; extra pages watch side 0 (or side 1 in a friend battle) and can play for it.
- The art is Supercell's: the stream is for you and your friends, under its Fan Content Policy. Never leave a public tunnel open.
