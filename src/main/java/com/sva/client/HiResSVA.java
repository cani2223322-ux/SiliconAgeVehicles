package com.sva.client;

import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GLContext;

import com.sva.ConfigSVA;
import com.sva.vehicle.HiResMath;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;

/**
 * Hi-res cockpit (2K / 4K wave; config vehicles.cockpitHiRes, vehicles.cockpitQuality): the GL side shared by HiFontSVA
 * (the glyph atlas) and FaceArtSVA (the baked dial faces).
 * - beginFrame (CockpitInstrumentsSVA.render, once a frame): resolves the quality level (AUTO: from the window's height);
 *   a change drops every baked texture, so they come back at the new size.
 * - Baking runs lazily on first use on one low-priority daemon thread (Java2D only, HiResPaintSVA); the render thread
 *   uploads the result when it is done and draws the old vanilla-font / geometry way meanwhile - no hitch on entering
 *   a cockpit.
 * - Textures: luminance + alpha, our own mips (premultiplied box filter), GL_LINEAR_MIPMAP_LINEAR, anisotropic filtering
 *   (up to 8x) where GL_EXT_texture_filter_anisotropic exists - text on the angled wall / overhead panels stays readable.
 * - A resource reload (also a language change) empties the caches; a failure turns the hi-res path off until the next
 *   reload (logged once) - the cockpit then looks as before.
 * Every GL call here restores what it changes (the bound texture, the pixel store, the enable / blend / alpha state).
 */
@SideOnly(Side.CLIENT)
public final class HiResSVA {

    private static int level = -1;
    private static boolean frameOn, broken, logged;
    /** Bumped by invalidate(): background results of an older generation are dropped. */
    static int generation;
    private static ExecutorService pool;
    private static float maxAniso = -1F;

    private HiResSVA() {
    }

    /** Once a frame before any cockpit text: false = the vanilla way this frame. */
    static boolean beginFrame() {
        if (!ConfigSVA.cockpitHiRes || broken) {
            frameOn = false;
            return false;
        }
        int lv = HiResMath.level(HiResMath.parse(ConfigSVA.cockpitQuality), Minecraft.getMinecraft().displayHeight);
        if (lv != level) {
            invalidate();
            level = lv;
        }
        frameOn = true;
        HiFontSVA.poll();
        return true;
    }

    /** This frame draws hi-res (where its texture is ready). */
    static boolean on() {
        return frameOn;
    }

    static int level() {
        return level;
    }

    /** Drops every baked texture (quality change, resource reload); in-flight bakes are discarded on arrival. */
    static void invalidate() {
        generation++;
        HiFontSVA.reset();
        FaceArtSVA.reset();
    }

    static synchronized <T> Future<T> submit(Callable<T> job) {
        if (pool == null) {
            pool = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "SVA cockpit baker");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            });
        }
        return pool.submit(job);
    }

    /** A bake or upload failed: the hi-res path off until the next resource reload, logged once. */
    static void fail(String what, Throwable t) {
        broken = true;
        frameOn = false;
        if (!logged) {
            logged = true;
            FMLLog.log(org.apache.logging.log4j.Level.WARN, t, "[SVA] hi-res cockpit: %s failed - the vanilla font is used", what);
        }
    }

    /** ClientProxySVA.init: a resource reload (resource packs, the language) empties the caches and retries after a failure. */
    public static void register() {
        Object rm = Minecraft.getMinecraft().getResourceManager();
        if (rm instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) rm).registerReloadListener(new IResourceManagerReloadListener() {
                @Override
                public void onResourceManagerReload(IResourceManager m) {
                    broken = false;
                    invalidate();
                }
            });
        }
    }

    // ------------------------------------------------------------------ GL

    /** Uploads luminance + alpha mips (level 0 w x h); returns the texture id. Restores the binding and the pixel store. */
    static int upload(int w, int h, byte[][] mips) {
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
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, i, GL11.GL_LUMINANCE8_ALPHA8, Math.max(1, w >> i), Math.max(1, h >> i), 0,
                        GL11.GL_LUMINANCE_ALPHA, GL11.GL_UNSIGNED_BYTE, buf);
            }
        } finally {
            GL11.glPopClientAttrib();
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, mips.length - 1);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
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

    static void delete(int tex) {
        if (tex > 0) {
            GL11.glDeleteTextures(tex);
        }
    }

    /**
     * Before drawing with one of our textures: pushes the enable / colour-buffer / texture state, binds it, alpha blending
     * (the caller's blend kept when it already blends - the jet HUD's additive glass), a low alpha cut for the soft edges.
     */
    static void beginTextured(int tex) {
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_TEXTURE_BIT);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        if (!blend) {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.004F);
    }

    static void endTextured() {
        GL11.glPopAttrib();
    }
}
