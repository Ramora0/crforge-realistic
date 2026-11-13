/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import com.badlogic.gdx.math.Matrix4;
import java.util.Set;
import java.util.function.IntConsumer;
import org.crforge.realistic.render.art.model.AnimPlayer;
import org.crforge.realistic.render.art.model.ModelRenderer;
import org.crforge.realistic.render.art.model.Sc3dLibrary;

/**
 * A seat's 3D models for the draw lists (docs/phone-server.md, "3D models on the phone"): each
 * model draw of the renderer becomes a {@link ModelInstance} at its place in the recorded frame,
 * its rectangles and ground point mapped through the batch's matrices to frame subpixels as the
 * recorder maps polygons. While the frame is also drawn here (a JPEG page or a parity frame) the
 * model is rendered as before and its image is left out of the recording. GL thread.
 */
final class ModelRecorder implements ModelRenderer.Remote {

  private final DrawRecorder recorder;
  private final ModelAssets assets;
  private final Sc3dLibrary library;
  private boolean on = true;

  /** Told each model ref a recorded draw uses (the dry run's preload list). */
  IntConsumer refUsed;

  /**
   * @param library the seat's renderer's extraction (whose models it has read)
   */
  ModelRecorder(DrawRecorder recorder, ModelAssets assets, Sc3dLibrary library) {
    this.recorder = recorder;
    this.assets = assets;
    this.library = library;
  }

  /** Whether recorded frames carry the models (else the renderer draws its 2D stand-in). */
  void setOn(boolean on) {
    this.on = on;
  }

  boolean on() {
    return on;
  }

  @Override
  public boolean draw(ModelRenderer.RemoteDraw d) {
    if (!on || !recorder.recording()) return true;
    recorder.model(instance(d));
    return recorder.rasterizing();
  }

  @Override
  public void compositing(boolean on) {
    if (this.on && recorder.recording()) recorder.mute(on);
  }

  private ModelInstance instance(ModelRenderer.RemoteDraw d) {
    Matrix4 m = recorder.worldToClip();
    float[] v = m.val;
    float sx = recorder.frameWidth() * 0.5f * DrawFrame.SUBPIXELS;
    float sy = recorder.frameHeight() * 0.5f * DrawFrame.SUBPIXELS;
    ModelInstance mi = new ModelInstance();
    mi.shadow = d.shadow();
    int n = d.parts().size();
    mi.parts(n);
    for (int p = 0; p < n; p++) {
      ModelRenderer.RemotePart part = d.parts().get(p);
      Set<String> effects = part.effects();
      mi.refs[p] = assets.ref(part.ref(), effects, library.glb(part.ref().glb()));
      if (refUsed != null) refUsed.accept(mi.refs[p]);
      mi.host[p] = part.host();
      mi.hostNode[p] = part.host() >= 0 ? part.hostNode() : -1;
      AnimPlayer.Sample s = part.sample();
      if (s != null) {
        mi.clip[p] = assets.clip(part.ref().glb(), s.clip());
        mi.time[p] = Math.round(s.seconds() * ModelInstance.TIME_SCALE);
        mi.fromClip[p] = s.blend() < 1f ? assets.clip(part.ref().glb(), s.fromClip()) : 0;
        mi.fromTime[p] =
            mi.fromClip[p] == 0 ? 0 : Math.round(s.fromSeconds() * ModelInstance.TIME_SCALE);
        float b = mi.fromClip[p] == 0 ? 1f : Math.max(0f, Math.min(1f, s.blend()));
        mi.blend[p] = Math.round(b * 65535f);
      } else {
        mi.blend[p] = 65535;
      }
      mi.glows[p] = part.glows();
    }
    float gx = d.x();
    float gy = d.y() + d.lift();
    mi.x = Math.round((v[Matrix4.M00] * gx + v[Matrix4.M01] * gy + v[Matrix4.M03] + 1f) * sx);
    mi.y = Math.round((v[Matrix4.M10] * gx + v[Matrix4.M11] * gy + v[Matrix4.M13] + 1f) * sy);
    // frame pixels per metre: the batch's scale (no rotation in the battle camera)
    mi.kx = v[Matrix4.M00] * sx / DrawFrame.SUBPIXELS * d.unitsPerMetre();
    mi.ky = v[Matrix4.M11] * sy / DrawFrame.SUBPIXELS * d.unitsPerMetre();
    mi.heading = d.heading();
    mi.blue = d.blue();
    mi.clipPlane = d.clipPlane();
    mi.colorMul = d.colorMul().clone();
    mi.colorAdd = new float[] {d.colorAdd()[0], d.colorAdd()[1], d.colorAdd()[2]};
    mi.light = d.light().clone();
    mi.rect = rect(d.bounds(), v, sx, sy);
    mi.drawn = rect(d.drawn(), v, sx, sy);
    if (d.tint() != null) mi.tint = d.tint().clone();
    for (ModelRenderer.RemoteEffect e : d.effects()) {
      Sc3dLibrary.ModelRef ref = new Sc3dLibrary.ModelRef(e.glb(), IDENTITY, null, false);
      int id = assets.ref(ref, Set.of(), library.glb(e.glb()));
      if (refUsed != null) refUsed.accept(id);
      String[] tex = new String[e.textures().length];
      for (int i = 0; i < tex.length; i++) tex[i] = assets.fileTexture(e.textures()[i]);
      mi.fx.add(
          new ModelInstance.Fx(
              id, e.matrix(), e.shader(), e.additive(), e.billboard(), e.uniforms(), tex));
    }
    if (!mi.fx.isEmpty()) mi.fxTime = Math.round(d.effectTime() * ModelInstance.TIME_SCALE);
    return mi;
  }

  private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

  private static int[] rect(float[] b, float[] v, float sx, float sy) {
    float x0 = (v[Matrix4.M00] * b[0] + v[Matrix4.M01] * b[1] + v[Matrix4.M03] + 1f) * sx;
    float y0 = (v[Matrix4.M10] * b[0] + v[Matrix4.M11] * b[1] + v[Matrix4.M13] + 1f) * sy;
    float x1 = (v[Matrix4.M00] * b[2] + v[Matrix4.M01] * b[3] + v[Matrix4.M03] + 1f) * sx;
    float y1 = (v[Matrix4.M10] * b[2] + v[Matrix4.M11] * b[3] + v[Matrix4.M13] + 1f) * sy;
    return new int[] {
      Math.round(Math.min(x0, x1)),
      Math.round(Math.min(y0, y1)),
      Math.round(Math.max(x0, x1)),
      Math.round(Math.max(y0, y1))
    };
  }
}
