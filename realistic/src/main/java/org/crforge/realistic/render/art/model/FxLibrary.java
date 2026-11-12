/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * The effect side of the extraction: prefab node trees ({@link FxPrefab}), timelines ({@link
 * FxTimeline}) and materials with their base chain ({@link FxMaterial}), read through the index's
 * {@code prefabs}, {@code timelines} and {@code materials} maps and kept once read. An index
 * written before the extractor recorded timelines and effect textures yields prefabs without them.
 */
@Slf4j
public final class FxLibrary {

  private final Sc3dLibrary library;
  // the timelines write NaN for a bezier's unused control points
  private final ObjectMapper mapper =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
          .build();
  private final Map<String, Optional<FxPrefab>> prefabs = new HashMap<>();
  private final Map<String, Optional<FxTimeline>> timelines = new HashMap<>();
  private final Map<String, Optional<FxMaterial>> materials = new HashMap<>();
  private final Map<String, Optional<JsonNode>> materialJson = new HashMap<>();

  public FxLibrary(Sc3dLibrary library) {
    this.library = library;
  }

  public Sc3dLibrary library() {
    return library;
  }

  /** The prefab a row names ({@code characters} in the index), or null. */
  public FxPrefab prefabOfRow(String row) {
    JsonNode list = library.index().path("characters").path(row);
    if (!list.isArray() || list.isEmpty()) return null;
    return prefab(list.get(0).asText());
  }

  /** The prefab at that asset path, or null when the extraction lacks it. */
  public FxPrefab prefab(String path) {
    return prefabs
        .computeIfAbsent(
            path,
            p -> {
              JsonNode entry = library.index().path("prefabs").path(p);
              JsonNode json = read(entry.path("json").asText(""));
              if (json == null) return Optional.empty();
              return Optional.of(FxPrefab.parse(p, json, this));
            })
        .orElse(null);
  }

  /** The timeline at that asset path, or null. */
  public FxTimeline timeline(String path) {
    return timelines
        .computeIfAbsent(
            path,
            p -> {
              JsonNode json = read(library.index().path("timelines").path(p).asText(""));
              return Optional.ofNullable(json == null ? null : FxTimeline.parse(json));
            })
        .orElse(null);
  }

  /** The material at that asset path (its base chain merged), or null. */
  public FxMaterial material(String path) {
    if (path == null) return null;
    return materials
        .computeIfAbsent(path, p -> Optional.ofNullable(FxMaterial.read(p, this::materialJson)))
        .orElse(null);
  }

  private JsonNode materialJson(String path) {
    return materialJson
        .computeIfAbsent(
            path,
            p -> Optional.ofNullable(read(library.index().path("materials").path(p).asText(""))))
        .orElse(null);
  }

  /** The model file a model path names ({@code models.<path>.glb}), or null. */
  public Path glb(String modelPath) {
    String rel = library.index().path("models").path(modelPath).path("glb").asText("");
    if (rel.isEmpty()) return null;
    Path p = library.folder().resolve(rel);
    return Files.isRegularFile(p) ? p : null;
  }

  /** A file beside the index (a texture), or null. */
  public Path file(String rel) {
    if (rel == null || rel.isEmpty()) return null;
    Path p = library.folder().resolve(rel);
    return Files.isRegularFile(p) ? p : null;
  }

  private JsonNode read(String rel) {
    if (rel == null || rel.isEmpty()) return null;
    Path p = library.folder().resolve(rel);
    if (!Files.isRegularFile(p)) return null;
    try {
      return mapper.readTree(p.toFile());
    } catch (IOException e) {
      log.warn("Cannot read {}: {}", p, e.getMessage());
      return null;
    }
  }
}
