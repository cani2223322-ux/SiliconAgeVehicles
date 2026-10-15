package com.sva.client;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import com.sva.airdefense.CoverageMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Wave A (plan-next §9 A9, mock-ups veh_ad_impl_a_1_kp_map / a_3_tower_iff): the coverage maps the server sent for the open
 * command post / tower screen (NetADSVA D_COVER) - one 128 x 128 texture per radar and height: covered faint green, the
 * «мёртвые зоны» (no line of sight - behind the hills) red, out of reach / unknown clear. Drawn over the screen's map as
 * one textured quad (the screen's scissor clips it). The textures go when the screen closes (release()).
 */
@SideOnly(Side.CLIENT)
public final class CoverClientSVA {

    static final int C_COVERED = 0x3862E06A, C_SHADOW = 0x80E04848, C_UNKNOWN = 0x10808080;

    private static final class Map1 {
        double x, z, r;
        int agl, percent = 100, version = -1;
        byte[] grid;
        DynamicTexture tex;
        boolean dirty;
    }

    private static final Map<String, Map1> MAPS = new HashMap<String, Map1>();

    private CoverClientSVA() {
    }

    /** A map (or its progress) from the server. */
    public static void receive(NBTTagCompound t) {
        String k = t.getString("K");
        Map1 m = MAPS.get(k);
        if (m == null) {
            m = new Map1();
            MAPS.put(k, m);
        }
        m.agl = t.getInteger("A");
        if (t.hasKey("G")) {
            m.x = t.getDouble("X");
            m.z = t.getDouble("Z");
            m.r = t.getDouble("R");
            m.grid = t.getByteArray("G");
            m.version = t.getInteger("V");
            m.percent = 100;
            m.dirty = true;
        } else {
            m.percent = t.getInteger("P");
        }
    }

    /** The progress of a radar's map at a height: 100 done, -1 nothing asked / received. */
    public static int percent(String radarKey, int agl) {
        Map1 m = MAPS.get(radarKey + "|" + agl);
        return m == null ? -1 : m.grid == null ? m.percent : 100;
    }

    /** The legend's numbers of a map: {covered, shadow} cells (-1 none). */
    public static int[] counts(String radarKey, int agl) {
        Map1 m = MAPS.get(radarKey + "|" + agl);
        if (m == null || m.grid == null) {
            return new int[] {-1, -1};
        }
        return new int[] {CoverageMath.count(m.grid, CoverageMath.COVERED), CoverageMath.count(m.grid, CoverageMath.SHADOW)};
    }

    /**
     * Draws a radar's map at a height on a screen map: the screen point (ox, oy) shows the world point (cx, cz), `scale`
     * blocks a pixel.
     */
    public static void draw(String radarKey, int agl, double ox, double oy, double cx, double cz, double scale) {
        Map1 m = MAPS.get(radarKey + "|" + agl);
        if (m == null || m.grid == null) {
            return;
        }
        if (m.tex == null) {
            m.tex = new DynamicTexture(CoverageMath.GRID, CoverageMath.GRID);
            m.dirty = true;
        }
        if (m.dirty) {
            int[] px = m.tex.getTextureData();
            for (int j = 0; j < CoverageMath.GRID; j++) {
                for (int i = 0; i < CoverageMath.GRID; i++) {
                    int st = CoverageMath.get(m.grid, i, j);
                    px[j * CoverageMath.GRID + i] = st == CoverageMath.COVERED ? C_COVERED : st == CoverageMath.SHADOW ? C_SHADOW
                            : st == CoverageMath.UNKNOWN ? C_UNKNOWN : 0;
                }
            }
            m.tex.updateDynamicTexture();
            m.dirty = false;
        }
        double x0 = ox + (m.x - m.r - cx) / scale, y0 = oy + (m.z - m.r - cz) / scale, x1 = ox + (m.x + m.r - cx) / scale, y1 = oy + (m.z + m.r - cz) / scale;
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, m.tex.getGlTextureId());
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(x0, y1, 0, 0, 1);
        t.addVertexWithUV(x1, y1, 0, 1, 1);
        t.addVertexWithUV(x1, y0, 0, 1, 0);
        t.addVertexWithUV(x0, y0, 0, 0, 0);
        t.draw();
    }

    /** The screen closed: the textures go (the grids are kept for a quick reopen). */
    public static void release() {
        for (Iterator<Map1> it = MAPS.values().iterator(); it.hasNext();) {
            Map1 m = it.next();
            if (m.tex != null) {
                m.tex.deleteGlTexture();
                m.tex = null;
                m.dirty = true;
            }
        }
        if (MAPS.size() > 32) {
            MAPS.clear();
        }
    }
}
