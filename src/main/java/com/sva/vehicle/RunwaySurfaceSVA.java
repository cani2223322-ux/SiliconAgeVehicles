package com.sva.vehicle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sva.compat.EmbassyCompatSVA;
import com.sva.init.ModContentSVA;

import net.minecraft.block.Block;
import net.minecraft.world.World;

/**
 * «Лётное поле посольства» (docs/plan-embassy-airfield-addon.md §3.1; the core's docs/plan-embassy-airfield.md §3.2): the ONE answer to
 * «is this block a runway?» for the whole add-on - the add-on's own «Взлётно-посадочная полоса» (block.BlockRunwaySVA, any meta) or an
 * embassy runway's surface of the core (EmbassyCompatSVA.isRunwaySurface: the paving, its white marks, its edge / threshold / end
 * lights; the shoulder, the apron, the yellow marks and the blue taxi lights are not - RunwayMath's flood never joins the apron to the
 * runway). Both sides (block identities only). Used by RunwayMath's world views (block.AirfieldSVA, EntityVehicleSVA.runway(), the map's
 * GuiMapSVA) and EntityVehicleSVA.onRunway() (the plane's x0.5 take-off run, the soft brakes, the HSI, ЗАХВ).
 * <p>
 * Snow: the core's paving is not an opaque cube (BlockSnow.canPlaceBlockAt refuses it) - no layer hides it; a column's top block is the
 * paving itself. Written whole by the coordinator.
 */
public final class RunwaySurfaceSVA {

    /**
     * Review 2026-10-27 (RunwayMath.find asks ~18 600 columns a search): per block kind - may some meta of it be an embassy's runway
     * surface (the core's answer for metas 0..15, asked once a kind)? The world views read a block's meta only for such a kind. Both
     * sides of one JVM read it (concurrent); never filled while the core's API is off.
     */
    private static final Map<Block, Boolean> MAYBE = new ConcurrentHashMap<Block, Boolean>();

    private RunwaySurfaceSVA() {
    }

    /** Some meta of block kind `b` is an embassy runway's surface (the core's paving) - the meta worth reading. */
    static boolean maybeEmbassy(Block b) {
        if (b == null || !EmbassyCompatSVA.on()) {
            return false;
        }
        Boolean m = MAYBE.get(b);
        if (m == null) {
            boolean any = false;
            for (int i = 0; i < 16 && !any; i++) {
                any = EmbassyCompatSVA.isRunwaySurface(b, i);
            }
            m = Boolean.valueOf(any);
            MAYBE.put(b, m);
        }
        return m.booleanValue();
    }

    /** Block `b` of `meta` is a runway surface (the add-on's runway block, or an embassy runway's). */
    public static boolean is(Block b, int meta) {
        return b != null && (ModContentSVA.runway != null && b == ModContentSVA.runway || EmbassyCompatSVA.isRunwaySurface(b, meta));
    }

    /** The block at (x, y, z) of a loaded chunk is a runway surface (unloaded / out of the world: false). */
    public static boolean is(World w, int x, int y, int z) {
        if (w == null || y < 0 || y > 255 || !w.blockExists(x, y, z)) {
            return false;
        }
        Block b = w.getBlock(x, y, z);
        if (ModContentSVA.runway != null && b == ModContentSVA.runway) {
            return true;
        }
        return maybeEmbassy(b) && EmbassyCompatSVA.isRunwaySurface(b, w.getBlockMetadata(x, y, z));   // the meta read for the core's paving only
    }

    /** It is an embassy's runway surface (not the add-on's own): the add-on's paint and its old automatic marks keep off it. */
    public static boolean embassy(World w, int x, int y, int z) {
        if (w == null || y < 0 || y > 255 || !w.blockExists(x, y, z)) {
            return false;
        }
        Block b = w.getBlock(x, y, z);
        return b != ModContentSVA.runway && maybeEmbassy(b) && EmbassyCompatSVA.isRunwaySurface(b, w.getBlockMetadata(x, y, z));
    }
}
