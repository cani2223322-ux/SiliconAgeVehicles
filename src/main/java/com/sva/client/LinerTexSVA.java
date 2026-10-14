package com.sva.client;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GLContext;

import com.sva.ConfigSVA;
import com.sva.vehicle.HiResMath;
import com.sva.vehicle.LinerTexMath;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;

/**
 * Liner wave 1: the SA-320's textures on the GL side. Painted by LinerPaintSVA (Java2D) on the cockpit baker thread
 * (HiResSVA.submit - lazily, the first time a liner is drawn near; the first frames draw without them), uploaded here
 * as RGBA with our own box-filtered mip chain, GL_LINEAR_MIPMAP_LINEAR and anisotropic filtering (up to 8x):
 * <ul>
 * <li>the cockpit / cabin ATLAS at the quality's size (LinerTexMath.size: vehicles.linerTextures, AUTO = the cockpits'
 * vehicles.cockpitQuality) and its GLOW (half size: the integral panel lighting, added at night) - a size change drops
 * and re-bakes them;</li>
 * <li>the exterior LIVERY per board number (the registration on the fuselage and the fin: at most LIVERIES kept);</li>
 * <li>the spinner's and the cascades' small repeat patterns.</li>
 * </ul>
 * A failure is logged once and the liner then draws untextured (flat colours) until the next resource reload.
 */
@SideOnly(Side.CLIENT)
public final class LinerTexSVA {

    /** Liveries kept (one per board number seen). */
    static final int LIVERIES = 3;

    private static final class Job {
        Future<Object[]> job;
        int[] tex;
        int gen;
    }

    private static Job atlas;
    private static int atlasSize;
    private static final Map<Integer, Job> LIVERY = new LinkedHashMap<Integer, Job>(4, 0.75F, true);
    private static int spinner, cascade;
    private static boolean broken, logged;
    private static float maxAniso = -1F;
    private static int gen;

    private LinerTexSVA() {
    }

    /** The atlas size wanted now. */
    static int wantedSize() {
        int lv = HiResMath.level(HiResMath.parse(ConfigSVA.cockpitQuality), Minecraft.getMinecraft().displayHeight);
        return LinerTexMath.size(LinerTexMath.parse(ConfigSVA.linerTextures), lv);
    }

    /** The atlas texture (0 while it bakes / failed) - polls the bake. {day, glow}. */
    static int[] atlas() {
        if (broken) {
            return null;
        }
        final int size = wantedSize();
        if (atlas != null && atlasSize != size) {
            free(atlas);
            atlas = null;
        }
        if (atlas == null) {
            atlas = new Job();
            atlasSize = size;
            atlas.gen = gen;
            atlas.job = HiResSVA.submit(new Callable<Object[]>() {
                @Override
                public Object[] call() {
                    LinerPaintSVA.Baked b = LinerPaintSVA.bake(size);
                    return new Object[] {mips(b.day), mips(b.glow), Integer.valueOf(b.day.getWidth()), Integer.valueOf(b.glow.getWidth())};
                }
            });
        }
        return poll(atlas);
    }

    /** The livery for a board number (0 while it bakes). */
    static int livery(final int board) {
        if (broken) {
            return 0;
        }
        Job j = LIVERY.get(Integer.valueOf(board));
        if (j == null) {
            j = new Job();
            j.gen = gen;
            j.job = HiResSVA.submit(new Callable<Object[]>() {
                @Override
                public Object[] call() {
                    BufferedImage img = LinerPaintSVA.bakeLivery(board);
                    return new Object[] {mips(img), null, Integer.valueOf(img.getWidth()), Integer.valueOf(0), Integer.valueOf(img.getHeight())};
                }
            });
            LIVERY.put(Integer.valueOf(board), j);
            while (LIVERY.size() > LIVERIES) {
                Iterator<Map.Entry<Integer, Job>> it = LIVERY.entrySet().iterator();
                free(it.next().getValue());
                it.remove();
            }
        }
        int[] t = poll(j);
        return t == null ? 0 : t[0];
    }

    /** The spinner's spiral (true) / the cascades' grid: small GL_REPEAT textures, made at once. */
    static int pattern(boolean spin) {
        if (broken) {
            return 0;
        }
        int t = spin ? spinner : cascade;
        if (t == 0) {
            try {
                BufferedImage img = LinerPaintSVA.pattern(spin);
                t = upload(mips(img), 64, 64, true);
                if (spin) {
                    spinner = t;
                } else {
                    cascade = t;
                }
            } catch (Throwable e) {
                fail("the patterns", e);
                return 0;
            }
        }
        return t;
    }

    private static int[] poll(Job j) {
        if (j.tex != null) {
            return j.tex;
        }
        if (j.job == null || !j.job.isDone()) {
            return null;
        }
        try {
            Object[] r = j.job.get();
            j.job = null;
            byte[][] day = (byte[][]) r[0];
            int w = ((Integer) r[2]).intValue();
            int h = r.length > 4 ? ((Integer) r[4]).intValue() : w;
            int a = upload(day, w, h, false);
            int g = 0;
            if (r[1] != null) {
                int gw = ((Integer) r[3]).intValue();
                g = upload((byte[][]) r[1], gw, gw, false);
            }
            j.tex = new int[] {a, g};
        } catch (Throwable e) {
            j.job = null;
            fail("a bake", e);
        }
        return j.tex;
    }

    private static void free(Job j) {
        if (j == null) {
            return;
        }
        if (j.tex != null) {
            for (int t : j.tex) {
                if (t > 0) {
                    GL11.glDeleteTextures(t);
                }
            }
        }
        if (j.job != null) {
            j.job.cancel(false);
        }
    }

    /** A resource reload (or a failure's retry): drop everything. */
    static void reset() {
        gen++;
        free(atlas);
        atlas = null;
        for (Job j : LIVERY.values()) {
            free(j);
        }
        LIVERY.clear();
        if (spinner > 0) {
            GL11.glDeleteTextures(spinner);
        }
        if (cascade > 0) {
            GL11.glDeleteTextures(cascade);
        }
        spinner = cascade = 0;
        broken = false;
    }

    static void fail(String what, Throwable t) {
        broken = true;
        if (!logged) {
            logged = true;
            FMLLog.log(org.apache.logging.log4j.Level.WARN, t, "[SVA] liner textures: %s failed - the liner is drawn in flat colours", what);
        }
    }

    // ---------------------------------------------------------------- mips + upload

    /** RGBA mip chain of an image (2x2 box filter) down to 1 px. */
    static byte[][] mips(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        java.util.List<byte[]> out = new java.util.ArrayList<byte[]>();
        byte[] lv = new byte[w * h * 4];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            lv[i * 4] = (byte) (c >> 16);
            lv[i * 4 + 1] = (byte) (c >> 8);
            lv[i * 4 + 2] = (byte) c;
            lv[i * 4 + 3] = (byte) 255;
        }
        out.add(lv);
        while (w > 1 || h > 1) {
            int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
            byte[] n = new byte[nw * nh * 4];
            for (int y = 0; y < nh; y++) {
                for (int x = 0; x < nw; x++) {
                    for (int c = 0; c < 4; c++) {
                        int s = 0;
                        for (int dy = 0; dy < 2; dy++) {
                            for (int dx = 0; dx < 2; dx++) {
                                int sx = Math.min(w - 1, x * 2 + dx), sy = Math.min(h - 1, y * 2 + dy);
                                s += lv[(sy * w + sx) * 4 + c] & 255;
                            }
                        }
                        n[(y * nw + x) * 4 + c] = (byte) ((s + 2) / 4);
                    }
                }
            }
            out.add(n);
            lv = n;
            w = nw;
            h = nh;
        }
        return out.toArray(new byte[out.size()][]);
    }

    static int upload(byte[][] mips, int w, int h, boolean repeat) {
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        try {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            ByteBuffer buf = BufferUtils.createByteBuffer(mips[0].length);
            for (int i = 0; i < mips.length; i++) {
                buf.clear();
                buf.put(mips[i]).flip();
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, i, GL11.GL_RGBA8, Math.max(1, w >> i), Math.max(1, h >> i), 0, GL11.GL_RGBA,
                        GL11.GL_UNSIGNED_BYTE, buf);
            }
        } finally {
            GL11.glPopClientAttrib();
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, mips.length - 1);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        int wrap = repeat ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrap);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrap);
        if (maxAniso < 0F) {
            maxAniso = GLContext.getCapabilities().GL_EXT_texture_filter_anisotropic
                    ? GL11.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT) : 0F;
        }
        if (maxAniso > 1F) {
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, Math.min(8F, maxAniso));
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
        return id;
    }
}
