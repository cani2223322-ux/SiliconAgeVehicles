package com.sva.client;

import com.sva.block.BlockGuardRailSVA;
import com.sva.block.BlockRoadSignSVA;
import com.sva.block.BlockTrafficLightSVA;
import com.sva.vehicle.TrafficLightMath;

import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;

/**
 * Wave 1, ★11: the road furniture as boxes in the chunk mesh (no tile entities, no per-frame cost): the road sign (a
 * post + a plate with the picture on the front, the grey back behind), the traffic light (a post stub + the housing
 * with three lamps - the lit one full-bright in its colour, the others dark) and the guard rail (a W-beam along its axis
 * on a post). Shapes are made in a local frame whose front looks south (+z) and turned by the block's facing (0 south,
 * 1 west, 2 north, 3 east). Items are the flat pictures (shouldRender3DInInventory false).
 */
public class RenderRoadSVA implements ISimpleBlockRenderingHandler {

    private final int id;

    public RenderRoadSVA(int id) {
        this.id = id;
    }

    /** Face directions: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east (vanilla sides). Vanilla's directional shade. */
    private static final float[] SHADE = {0.5F, 1F, 0.8F, 0.8F, 0.6F, 0.6F};
    /** Lamp colours (red, amber, green) lit and dark. */
    private static final float[][] LIT = {{1F, 0.22F, 0.16F}, {1F, 0.72F, 0.12F}, {0.25F, 1F, 0.35F}};
    private static final float[][] DARK = {{0.28F, 0.08F, 0.07F}, {0.3F, 0.22F, 0.06F}, {0.07F, 0.24F, 0.1F}};

    @Override
    public void renderInventoryBlock(Block block, int metadata, int modelId, RenderBlocks renderer) {
    }

    @Override
    public boolean renderWorldBlock(IBlockAccess w, int x, int y, int z, Block block, int modelId, RenderBlocks renderer) {
        Tessellator t = Tessellator.instance;
        int meta = w.getBlockMetadata(x, y, z);
        int light = block.getMixedBrightnessForBlock(w, x, y, z);
        if (block instanceof BlockRoadSignSVA) {
            BlockRoadSignSVA b = (BlockRoadSignSVA) block;
            int f = BlockRoadSignSVA.facing(meta);
            IIcon pole = b.pole, face = b.faces[BlockRoadSignSVA.picture(meta)], back = b.back;
            box(t, x, y, z, f, 0.46, 0.0, 0.40, 0.54, 0.56, 0.48, pole, pole, light, 1F, 1F, 1F, false);       // the post
            box(t, x, y, z, f, 0.18, 0.36, 0.48, 0.82, 1.0, 0.54, back, face, light, 1F, 1F, 1F, false);       // the plate
            return true;
        }
        if (block instanceof BlockTrafficLightSVA) {
            BlockTrafficLightSVA b = (BlockTrafficLightSVA) block;
            int f = BlockTrafficLightSVA.facing(meta), st = BlockTrafficLightSVA.state(meta);
            box(t, x, y, z, f, 0.44, 0.0, 0.44, 0.56, 0.1, 0.56, b.body, b.body, light, 0.7F, 0.7F, 0.72F, false);      // post stub
            box(t, x, y, z, f, 0.31, 0.1, 0.36, 0.69, 1.0, 0.64, b.body, b.body, light, 1F, 1F, 1F, false);            // housing
            for (int i = 0; i < 3; i++) {
                double cy = 0.84 - i * 0.28;                          // red on top, amber, green at the bottom
                boolean on = TrafficLightMath.lit(st, i == 0 ? TrafficLightMath.RED : i == 1 ? TrafficLightMath.AMBER : TrafficLightMath.GREEN);
                float[] c = on ? LIT[i] : DARK[i];
                box(t, x, y, z, f, 0.39, cy - 0.11, 0.64, 0.61, cy + 0.11, 0.68, b.lamp, b.lamp, on ? 0xF000F0 : light, c[0], c[1], c[2], on);
                // the visor over each lamp
                box(t, x, y, z, f, 0.37, cy + 0.11, 0.64, 0.63, cy + 0.13, 0.76, b.body, b.body, light, 0.6F, 0.6F, 0.62F, false);
            }
            return true;
        }
        if (block instanceof BlockGuardRailSVA) {
            BlockGuardRailSVA b = (BlockGuardRailSVA) block;
            int f = BlockGuardRailSVA.alongX(meta) ? 1 : 0;           // local z = along the rail
            IIcon s = b.steel;
            box(t, x, y, z, f, 0.42, 0.0, 0.44, 0.58, 0.8, 0.56, s, s, light, 0.8F, 0.82F, 0.85F, false);       // post
            box(t, x, y, z, f, 0.34, 0.42, 0.0, 0.42, 0.78, 1.0, s, s, light, 1F, 1F, 1F, false);               // W-beam
            box(t, x, y, z, f, 0.31, 0.55, 0.0, 0.34, 0.65, 1.0, s, s, light, 0.9F, 0.92F, 0.95F, false);       // its fold
            return true;
        }
        return false;
    }

    @Override
    public boolean shouldRender3DInInventory(int modelId) {
        return false;
    }

    @Override
    public int getRenderId() {
        return id;
    }

    /** Local (front = south) -> world x/z inside the block for facing f. */
    static double[] turn(int f, double lx, double lz) {
        switch (f & 3) {
            case 1:
                return new double[] {1 - lz, lx};                  // front -> west
            case 2:
                return new double[] {1 - lx, 1 - lz};              // front -> north
            case 3:
                return new double[] {lz, 1 - lx};                  // front -> east
            default:
                return new double[] {lx, lz};
        }
    }

    /** The world side the local front (south) / back (north) faces for facing f. */
    static int frontSide(int f) {
        switch (f & 3) {
            case 1:
                return 4;
            case 2:
                return 2;
            case 3:
                return 5;
            default:
                return 3;
        }
    }

    private static int opposite(int side) {
        return side ^ 1;
    }

    /**
     * A box given in the local frame, turned by f: `front` icon on the local front, `other` on the rest; the whole icon on
     * every face; colour x the directional shade (bright: no shade - a lamp).
     */
    static void box(Tessellator t, int bx, int by, int bz, int f, double x0, double y0, double z0, double x1, double y1, double z1,
                    IIcon other, IIcon front, int brightness, float r, float g, float b, boolean bright) {
        double[] a = turn(f, x0, z0), c = turn(f, x1, z1);
        double minX = bx + Math.min(a[0], c[0]), maxX = bx + Math.max(a[0], c[0]);
        double minZ = bz + Math.min(a[1], c[1]), maxZ = bz + Math.max(a[1], c[1]);
        double minY = by + y0, maxY = by + y1;
        int fs = frontSide(f);
        t.setBrightness(brightness);
        for (int side = 0; side < 6; side++) {
            IIcon icon = side == fs ? front : other;
            float k = bright ? 1F : SHADE[side];
            t.setColorOpaque_F(r * k, g * k, b * k);
            face(t, side, minX, minY, minZ, maxX, maxY, maxZ, icon);
        }
    }

    /** One face, counter-clockwise from outside (vanilla RenderBlocks order), the whole icon, upright pictures. */
    static void face(Tessellator t, int side, double x0, double y0, double z0, double x1, double y1, double z1, IIcon i) {
        double u0 = i.getMinU(), u1 = i.getMaxU(), v0 = i.getMinV(), v1 = i.getMaxV();
        switch (side) {
            case 0:
                t.addVertexWithUV(x0, y0, z1, u0, v1);
                t.addVertexWithUV(x0, y0, z0, u0, v0);
                t.addVertexWithUV(x1, y0, z0, u1, v0);
                t.addVertexWithUV(x1, y0, z1, u1, v1);
                break;
            case 1:
                t.addVertexWithUV(x1, y1, z1, u1, v1);
                t.addVertexWithUV(x1, y1, z0, u1, v0);
                t.addVertexWithUV(x0, y1, z0, u0, v0);
                t.addVertexWithUV(x0, y1, z1, u0, v1);
                break;
            case 2:
                t.addVertexWithUV(x0, y1, z0, u1, v0);
                t.addVertexWithUV(x1, y1, z0, u0, v0);
                t.addVertexWithUV(x1, y0, z0, u0, v1);
                t.addVertexWithUV(x0, y0, z0, u1, v1);
                break;
            case 3:
                t.addVertexWithUV(x0, y1, z1, u0, v0);
                t.addVertexWithUV(x0, y0, z1, u0, v1);
                t.addVertexWithUV(x1, y0, z1, u1, v1);
                t.addVertexWithUV(x1, y1, z1, u1, v0);
                break;
            case 4:
                t.addVertexWithUV(x0, y1, z1, u1, v0);
                t.addVertexWithUV(x0, y1, z0, u0, v0);
                t.addVertexWithUV(x0, y0, z0, u0, v1);
                t.addVertexWithUV(x0, y0, z1, u1, v1);
                break;
            default:
                t.addVertexWithUV(x1, y0, z1, u0, v1);
                t.addVertexWithUV(x1, y0, z0, u1, v1);
                t.addVertexWithUV(x1, y1, z0, u1, v0);
                t.addVertexWithUV(x1, y1, z1, u0, v0);
                break;
        }
    }
}
