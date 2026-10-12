package com.sva.client;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.vehicle.CockpitDials;
import com.sva.vehicle.GlassMath;
import com.sva.vehicle.HiResMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Hi-res cockpit (2K / 4K wave): the static parts of the round faces baked once into a texture (HiResPaintSVA.paintFace,
 * Java2D, anti-aliased; 256 / 512 / 1024 px by the quality level, mipmapped + anisotropic - HiResSVA) and drawn as one
 * quad; the moving parts (needles, hubs, arcs that change) stay GL geometry over it.
 * - dial: CockpitInstrumentsSVA.drawDial's 21 ticks, the three scale numbers, the reading's name and unit (every
 *   vehicle's dials);
 * - scale: GlassCockpitSVA.scaleMarks (the heli's tach and power faces): ticks and numbers.
 * The marks are baked as grey levels (the old colours' brightness) on transparent and tinted by glColor - so the night
 * glow tint (CockpitInstrumentsSVA.tint) still applies. Cached per face (HiResMath.Key: one reused probe, no garbage a
 * frame), at most MAX kept (the oldest freed with glDeleteTextures); a quality / language change empties the cache.
 * Until a face's bake arrives (or if it fails) the caller draws the old way.
 */
@SideOnly(Side.CLIENT)
public final class FaceArtSVA {

    /** Baked faces kept at most (4K: 2.8 MB each). */
    static final int MAX = 12;

    private static final class Entry {
        Future<byte[][]> job;
        int tex, px, gen;
        boolean failed;
    }

    private static final Map<HiResMath.Key, Entry> CACHE = new LinkedHashMap<HiResMath.Key, Entry>(16, 0.75F, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<HiResMath.Key, Entry> e) {
            if (size() > MAX) {
                HiResSVA.delete(e.getValue().tex);
                return true;
            }
            return false;
        }
    };
    private static final HiResMath.Key PROBE = new HiResMath.Key();

    private FaceArtSVA() {
    }

    static void reset() {
        for (Iterator<Entry> it = CACHE.values().iterator(); it.hasNext();) {
            HiResSVA.delete(it.next().tex);
            it.remove();
        }
    }

    /** CockpitInstrumentsSVA.drawDial's marks (radius r, at z): false = not ready, draw them the old way. */
    static boolean dial(int kind, float min, float max, float r, float z, int rgb) {
        if (!HiResSVA.on()) {
            return false;
        }
        Entry e = entry(PROBE.set(HiResMath.FACE_DIAL, kind, min, max, 0F));
        if (e == null) {
            HiResPaintSVA.Face f = new HiResPaintSVA.Face();
            for (int k = 0; k <= 20; k++) {
                float a = -CockpitDials.SWEEP / 2 + CockpitDials.SWEEP * k / 20F;
                boolean major = k % 2 == 0;
                f.ray(a, major ? 0.68F : 0.76F, 0.86F, major ? 0.035F : 0.02F, 0xEC);
            }
            for (int k = 0; k <= 2; k++) {
                float a = (float) Math.toRadians(-CockpitDials.SWEEP / 2 + CockpitDials.SWEEP * k / 2F);
                f.text(CockpitInstrumentsSVA.num(min + (max - min) * k / 2F), (float) Math.sin(a) * 0.5F, (float) Math.cos(a) * 0.5F, 0.16F, 0.5F, 0xD9);
            }
            String key = CockpitDials.KEYS[kind];
            f.text(Lang.tr("sva.cockpit.dial." + key), 0F, 0.25F, 0.15F, 1.1F, 0xFF);
            f.text(Lang.tr("sva.cockpit.unit." + key), 0F, -0.56F, 0.11F, 0.9F, 0x9C);
            e = bake(f);
        }
        return draw(e, r, z, rgb);
    }

    /** GlassCockpitSVA.scaleMarks over 0..max (radius R, at z): false = not ready. */
    static boolean scale(float R, int max, int minor, int major, float z, int rgb) {
        if (!HiResSVA.on()) {
            return false;
        }
        Entry e = entry(PROBE.set(HiResMath.FACE_SCALE, max, minor, major, 0F));
        if (e == null) {
            HiResPaintSVA.Face f = new HiResPaintSVA.Face();
            for (int v = 0; v <= max; v += minor) {
                float a = GlassMath.angle(v / (float) max);
                boolean big = v % major == 0;
                f.ray(a, big ? 0.73F : 0.79F, 0.86F, big ? 0.035F : 0.02F, 0xFF);
                if (big) {
                    double t = Math.toRadians(a);
                    f.text(String.valueOf(v), (float) Math.sin(t) * 0.58F, (float) Math.cos(t) * 0.58F, 0.13F, 0.36F, 0xFF);
                }
            }
            e = bake(f);
        }
        return draw(e, R, z, rgb);
    }

    /** The cached entry for the probe, or null (then the caller builds the face and bake()s it under a copy of the probe). */
    private static Entry entry(HiResMath.Key probe) {
        return CACHE.get(probe);
    }

    private static Entry bake(final HiResPaintSVA.Face f) {
        final int px = HiResMath.facePx(HiResSVA.level());
        Entry e = new Entry();
        e.px = px;
        e.gen = HiResSVA.generation;
        e.job = HiResSVA.submit(new Callable<byte[][]>() {
            @Override
            public byte[][] call() {
                return HiResPaintSVA.paintFace(f, px);
            }
        });
        CACHE.put(PROBE.copy(), e);
        return e;
    }

    /** The baked quad over [-half, half]^2 at z, tinted rgb; false while baking or after a failure. */
    private static boolean draw(Entry e, float half, float z, int rgb) {
        if (e.failed) {
            return false;
        }
        if (e.tex == 0) {
            if (e.job == null || !e.job.isDone()) {
                return false;
            }
            try {
                byte[][] mips = e.job.get();
                e.job = null;
                if (e.gen != HiResSVA.generation) {
                    e.failed = true;
                    return false;
                }
                e.tex = HiResSVA.upload(e.px, e.px, mips);
            } catch (Throwable t) {
                e.failed = true;
                e.job = null;
                HiResSVA.fail("a dial face", t);
                return false;
            }
        }
        HiResSVA.beginTextured(e.tex);
        CockpitInstrumentsSVA.glColor(rgb);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0F, 0F);
        GL11.glVertex3f(-half, half, z);
        GL11.glTexCoord2f(0F, 1F);
        GL11.glVertex3f(-half, -half, z);
        GL11.glTexCoord2f(1F, 1F);
        GL11.glVertex3f(half, -half, z);
        GL11.glTexCoord2f(1F, 0F);
        GL11.glVertex3f(half, half, z);
        GL11.glEnd();
        HiResSVA.endTextured();
        return true;
    }
}
