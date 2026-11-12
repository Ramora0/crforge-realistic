/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;

/**
 * The phone server's access token. The stream carries Supercell's art, so every request, the page,
 * its WebSocket and its sounds, must name the token: as the query parameter {@code t}, or the
 * cookie the page sets. A tunnel's URL alone then gives no access.
 */
public final class Access {

  /** The query parameter and cookie that carry the token. */
  public static final String PARAMETER = "t";

  private static final String ALPHABET =
      "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

  private final String token;

  public Access(String token) {
    if (!wellFormed(token)) throw new IllegalArgumentException("a malformed token");
    this.token = token;
  }

  public String token() {
    return token;
  }

  /** A random token: 22 characters of an unambiguous alphabet (about 128 bits). */
  public static String newToken() {
    SecureRandom random = new SecureRandom();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 22; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    return sb.toString();
  }

  /** Whether a token is 8 to 128 letters, digits, '-' or '_' (safe in a URL and a cookie). */
  public static boolean wellFormed(String token) {
    if (token == null || token.length() < 8 || token.length() > 128) return false;
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      boolean ok =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '-'
              || c == '_';
      if (!ok) return false;
    }
    return true;
  }

  /** Whether a given token is this one, compared in constant time. */
  public boolean matches(String given) {
    if (given == null) return false;
    return MessageDigest.isEqual(
        given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Whether a request carries the token, in its query or its cookie header.
   *
   * @param query the request's query parameters
   * @param cookieHeader the request's {@code Cookie} header, or null
   */
  public boolean allows(Map<String, String> query, String cookieHeader) {
    if (matches(query.get(PARAMETER))) return true;
    return matches(cookie(cookieHeader, cookieName()));
  }

  /** The cookie's name. */
  public static String cookieName() {
    return "crforge_" + PARAMETER;
  }

  /** The value of one cookie of a {@code Cookie} header, or null. */
  static String cookie(String header, String name) {
    if (header == null) return null;
    for (String part : header.split(";")) {
      int eq = part.indexOf('=');
      if (eq < 0) continue;
      if (part.substring(0, eq).trim().equals(name)) return part.substring(eq + 1).trim();
    }
    return null;
  }
}
