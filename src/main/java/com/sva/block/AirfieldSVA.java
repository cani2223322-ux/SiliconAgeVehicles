package com.sva.block;

import com.sva.init.ModContentSVA;
import com.sva.vehicle.AirfieldMath;
import com.sva.vehicle.RunwayMath;

import net.minecraft.block.Block;
import net.minecraft.world.World;

/**
 * Stage D1 (★1 / ★2): the airfield blocks' world glue - the runway finder's and the PAPI finder's views of a real world
 * (only loaded chunks: the height map and blockExists, nothing is loaded or generated), the nearest strip with its PAPI
 * rows, the redstone rule of the lights (AirfieldMath.manual: a block with a redstone part next to it - a lever, a wire,
 * a button, a torch, a repeater... - follows its signal, else night / rain).
 */
public final class AirfieldSVA {

    private AirfieldSVA() {
    }

    /** RunwayMath's view of a world (the runway blocks). */
    public static RunwayMath.World view(final World w) {
        return new RunwayMath.World() {
            @Override
            public int top(int x, int z) {
                if (!w.blockExists(x, 64, z)) {
                    return -1;
                }
                int h = w.getHeightValue(x, z);
                return h <= 0 ? -1 : h - 1;
            }

            @Override
            public boolean runway(int x, int y, int z) {
                return ModContentSVA.runway != null && w.blockExists(x, y, z) && w.getBlock(x, y, z) == ModContentSVA.runway;
            }
        };
    }

    /** AirfieldMath's view: the PAPI units (their metadata), -1 elsewhere / unloaded. */
    public static AirfieldMath.Blocks papiBlocks(final World w) {
        return new AirfieldMath.Blocks() {
            @Override
            public int papi(int x, int y, int z) {
                if (ModContentSVA.papi == null || y < 0 || y > 255 || !w.blockExists(x, y, z) || w.getBlock(x, y, z) != ModContentSVA.papi) {
                    return -1;
                }
                return w.getBlockMetadata(x, y, z);
            }
        };
    }

    /** The nearest runway strip within `scan` blocks of (x, z) with its PAPI rows, or null. */
    public static RunwayMath.Runway near(World w, double x, double z, int scan) {
        RunwayMath.Runway r = RunwayMath.find(view(w), x, z, scan);
        return r == null ? null : r.withPapi(papiBlocks(w));
    }

    /** A redstone part next to the block (it then follows the signal). */
    public static boolean wired(World w, int x, int y, int z) {
        int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : nb) {
            Block b = w.getBlock(x + d[0], y + d[1], z + d[2]);
            if (b.canProvidePower()) {
                return true;
            }
        }
        return false;
    }

    /** The block's manual state (AirfieldMath.AUTO / OFF / ON). */
    public static int manual(World w, int x, int y, int z) {
        boolean wired = wired(w, x, y, z);
        return AirfieldMath.manual(wired, wired && w.isBlockIndirectlyGettingPowered(x, y, z));
    }

    /** The automatic rule now (night or rain) for lights without redstone. */
    public static boolean autoOn(World w, float pt) {
        return AirfieldMath.lightsOn(w.getCelestialAngle(pt), w.isRaining(), AirfieldMath.AUTO);
    }
}
