/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Encodes frames read back from GL (RGBA rows, bottom row first) as JPEG. Not thread safe: one
 * encoder per thread.
 */
public final class FrameEncoder {

  private final int width;
  private final int height;
  private final BufferedImage image;
  private final byte[] bgr;
  private final ImageWriter writer;
  private final ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);

  public FrameEncoder(int width, int height) {
    this.width = width;
    this.height = height;
    this.image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
    this.bgr = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
    this.writer = ImageIO.getImageWritersByFormatName("jpeg").next();
  }

  /**
   * One frame as JPEG.
   *
   * @param rgba the GL read-back, {@code width * height * 4} bytes, the bottom row first
   * @param quality 1 to 100
   */
  public byte[] encode(byte[] rgba, int quality) {
    int w = width;
    for (int y = 0; y < height; y++) {
      int src = (height - 1 - y) * w * 4;
      int dst = y * w * 3;
      for (int x = 0; x < w; x++, src += 4, dst += 3) {
        bgr[dst] = rgba[src + 2];
        bgr[dst + 1] = rgba[src + 1];
        bgr[dst + 2] = rgba[src];
      }
    }
    out.reset();
    try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
      writer.setOutput(ios);
      ImageWriteParam p = writer.getDefaultWriteParam();
      p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      p.setCompressionQuality(Math.max(0.01f, Math.min(1f, quality / 100f)));
      writer.write(null, new IIOImage(image, null, null), p);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }
}
