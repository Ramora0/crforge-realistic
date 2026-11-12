/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The phone server's settings, from the launcher's arguments: {@code --serve [--port 8765] [--lan]
 * [--token X] [--size 720x1560] [--fps 30] [--quality 70] [--location PvP_royal] [--audio-cache
 * DIR] [--render list|jpeg]}.
 *
 * @param port the TCP port of the web page, its WebSocket and its sounds
 * @param lan whether to listen on every interface (0.0.0.0) rather than on 127.0.0.1 only
 * @param token the access token every request must carry, or null to draw a random one
 * @param width the streamed frame's width in pixels
 * @param height the streamed frame's height in pixels
 * @param fps the frames per second streamed at most
 * @param quality the JPEG quality the stream starts at and never exceeds, 1 to 100
 * @param location the arena location a new battle starts on, by default
 * @param audioCache where sounds transcoded for browsers that cannot decode Ogg Vorbis are kept
 * @param render how a page is sent its picture unless it asks otherwise: {@code list} (draw lists
 *     the page draws itself with WebGL) or {@code jpeg} (frames drawn here, as JPEG)
 */
public record ServeOptions(
    int port,
    boolean lan,
    String token,
    int width,
    int height,
    int fps,
    int quality,
    String location,
    Path audioCache,
    String render) {

  /** Draw lists: the page draws the picture itself. */
  public static final String LIST = "list";

  /** JPEG frames drawn here. */
  public static final String JPEG = "jpeg";

  /** The argument that starts the phone server. */
  public static final String SERVE = "--serve";

  public static final int DEFAULT_PORT = 8765;

  /** Whether the arguments ask for the phone server. */
  public static boolean wanted(String[] args) {
    for (String a : args) if (SERVE.equals(a)) return true;
    return false;
  }

  /**
   * Reads the settings.
   *
   * @throws IllegalArgumentException for a value that cannot be read, naming it
   */
  public static ServeOptions parse(String[] args) {
    int port = integer(args, "--port", DEFAULT_PORT, 1, 65535);
    boolean lan = flag(args, "--lan");
    String token = option(args, "--token");
    if (token != null && !Access.wellFormed(token)) {
      throw new IllegalArgumentException(
          "--token takes 8 to 128 letters, digits, '-' or '_', not \"" + token + "\"");
    }
    String size = option(args, "--size");
    int width = 720;
    int height = 1560;
    if (size != null) {
      String[] wh = size.toLowerCase(java.util.Locale.ROOT).split("x");
      try {
        width = Integer.parseInt(wh[0]);
        height = Integer.parseInt(wh[1]);
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("--size takes WIDTHxHEIGHT, not " + size);
      }
      if (width < 180 || height < 320 || width > 2160 || height > 4680) {
        throw new IllegalArgumentException("--size " + size + " is out of 180x320 .. 2160x4680");
      }
    }
    int fps = integer(args, "--fps", 30, 1, 60);
    int quality = integer(args, "--quality", 70, 10, 100);
    String location = option(args, "--location");
    String cache = option(args, "--audio-cache");
    Path audioCache =
        cache != null
            ? Paths.get(cache)
            : Paths.get(System.getProperty("user.home"), ".cache", "crforge-phone", "audio");
    String render = option(args, "--render");
    if (render == null) render = LIST;
    if (!render.equals(LIST) && !render.equals(JPEG)) {
      throw new IllegalArgumentException("--render takes list or jpeg, not " + render);
    }
    return new ServeOptions(
        port, lan, token, width, height, fps, quality, location, audioCache, render);
  }

  private static boolean flag(String[] args, String name) {
    for (String a : args) if (name.equals(a)) return true;
    return false;
  }

  private static String option(String[] args, String name) {
    for (int i = 0; i < args.length; i++) {
      if (name.equals(args[i])) {
        if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
          throw new IllegalArgumentException(name + " needs a value");
        }
        return args[i + 1];
      }
    }
    return null;
  }

  private static int integer(String[] args, String name, int fallback, int min, int max) {
    String v = option(args, name);
    if (v == null) return fallback;
    int n;
    try {
      n = Integer.parseInt(v);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + " takes a number, not " + v);
    }
    if (n < min || n > max) {
      throw new IllegalArgumentException(name + " takes " + min + " to " + max + ", not " + n);
    }
    return n;
  }
}
