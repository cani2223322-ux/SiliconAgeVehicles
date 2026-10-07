package com.sva.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.VehicleLights;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

/**
 * ★6 dynamic light (config vehicles.dynamicLight + vehicles.headlights): at night the headlights really light the blocks
 * ahead - on THIS client only. The known trick: World.setLightValue(EnumSkyBlock.Block, ...) on the CLIENT world for the
 * air blocks along each beam (and one to each side), plus a render update of the blocks around; when a vehicle moves on,
 * despawns, goes out of range, the option is switched off or the world changes, those blocks get their real light back
 * (World.updateLightByType recomputes them and clears anything that spread from them). The server world is never
 * touched and nothing is saved: a chunk sent again by the server simply comes with its real light.
 * Limits: the local player's vehicle and the nearest MAX_VEHICLES others within RANGE blocks, updated every PERIOD
 * ticks; vanilla's random light checks near the player can briefly reset a lit block until the next update.
 */
public final class DynamicLightSVA {

    private static final int PERIOD = 4, MAX_VEHICLES = 4, RANGE = 32, REACH = 10;
    /** Aircraft light the ground only below this height (blocks). */
    private static final double LANDING_HEIGHT = 16;
    /** Packed block position -> the level we set. */
    private final Map<Long, Integer> lit = new HashMap<Long, Integer>();
    private World world;
    private int timer;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        World w = mc.theWorld;
        if (w != world) {
            lit.clear();                                            // the old world went with its light
            world = w;
        }
        if (w == null || mc.thePlayer == null || ++timer < PERIOD) {
            return;
        }
        timer = 0;
        Map<Long, Integer> want = new HashMap<Long, Integer>();
        if (ConfigSVA.dynamicLight && ConfigSVA.headlights) {
            for (EntityVehicleSVA v : nearest(w, mc.thePlayer)) {
                beamCells(w, v, want);
            }
        }
        // give back the blocks no beam wants any more (or wants dimmer)
        for (Iterator<Map.Entry<Long, Integer>> it = lit.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Long, Integer> en = it.next();
            Integer now = want.get(en.getKey());
            if (now == null || now.intValue() < en.getValue().intValue()) {
                restore(w, en.getKey().longValue());
                it.remove();
            }
        }
        for (Map.Entry<Long, Integer> en : want.entrySet()) {
            long k = en.getKey().longValue();
            int x = x(k), y = y(k), z = z(k), level = en.getValue().intValue();
            if (w.getSavedLightValue(EnumSkyBlock.Block, x, y, z) >= level) {
                continue;                                           // already this bright (ours, or a torch)
            }
            w.setLightValue(EnumSkyBlock.Block, x, y, z, level);
            w.markBlockRangeForRenderUpdate(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1);
            lit.put(en.getKey(), en.getValue());
        }
    }

    /** The world is unloading (leaving it / changing dimension): its light state goes with it. */
    @SubscribeEvent
    public void onUnload(net.minecraftforge.event.world.WorldEvent.Unload e) {
        if (e.world == world) {
            lit.clear();
            world = null;
        }
    }

    private static void restore(World w, long k) {
        int x = x(k), y = y(k), z = z(k);
        if (y < 0 || y > 255 || !w.blockExists(x, y, z)) {
            return;
        }
        w.updateLightByType(EnumSkyBlock.Block, x, y, z);
        w.markBlockRangeForRenderUpdate(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1);
    }

    /** The local player's vehicle plus the nearest powered vehicles with headlights, at most MAX_VEHICLES. */
    private static List<EntityVehicleSVA> nearest(World w, Entity player) {
        List<EntityVehicleSVA> out = new ArrayList<EntityVehicleSVA>();
        List<Double> dist = new ArrayList<Double>();
        for (Object o : w.loadedEntityList) {
            if (!(o instanceof EntityVehicleSVA)) {
                continue;
            }
            EntityVehicleSVA v = (EntityVehicleSVA) o;
            double d2 = v.getDistanceSqToEntity(player);
            boolean own = HudVehicleSVA.vehicleOf(Minecraft.getMinecraft().thePlayer) == v;
            if (v.isDead || !own && d2 > RANGE * RANGE || !v.powered() || !VehicleLights.hasHead(v.getVehicleType().model)) {
                continue;
            }
            double key = own ? -1 : d2;
            int i = 0;
            while (i < dist.size() && dist.get(i).doubleValue() <= key) {
                i++;
            }
            out.add(i, v);
            dist.add(i, Double.valueOf(key));
        }
        return out.size() > MAX_VEHICLES ? out.subList(0, MAX_VEHICLES) : out;
    }

    /**
     * The air blocks along the vehicle's beam: from the front at the headlights' height, forward and down by their
     * tilt, one block a step for REACH blocks or until it meets a solid block; each step and one block to each side,
     * dimmer with distance (15 next to the lamp, 9 at the end). Only in the dark (by the sky light).
     */
    private static void beamCells(World w, EntityVehicleSVA v, Map<Long, Integer> want) {
        VehicleType t = v.getVehicleType();
        if (t.movement.flies && v.anim.height > LANDING_HEIGHT || VehicleLightsSVA.darkness(v) < 0.3F) {
            return;
        }
        float hy = 0, pitch = 0;
        int n = 0;
        float front = Float.NEGATIVE_INFINITY;
        for (VehicleLights.Light l : VehicleLights.of(t.model)) {
            if (l.kind == VehicleLights.HEAD) {
                hy += l.y;
                pitch += l.pitch;
                front = Math.max(front, l.x);
                n++;
            }
        }
        if (n == 0) {
            return;
        }
        hy /= n;
        pitch /= n;
        double yaw = Math.toRadians(v.rotationYaw), p = Math.toRadians(Math.max(pitch, 4F));
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);                // forward (MC yaw)
        double sx = -fz, sz = fx;                                       // to the side
        double ox = v.posX + fx * (front - t.modelOffsetX) * t.modelScale, oz = v.posZ + fz * (front - t.modelOffsetX) * t.modelScale;
        double oy = v.posY + hy * t.modelScale;
        double dx = fx * Math.cos(p), dy = -Math.sin(p), dz = fz * Math.cos(p);
        int reach = (int) Math.round(REACH * v.tuneLight());             // ★8 «Люстра фар»: x2
        for (int d = 1; d <= reach; d++) {
            int bx = MathHelper.floor_double(ox + dx * d), by = MathHelper.floor_double(oy + dy * d), bz = MathHelper.floor_double(oz + dz * d);
            if (by < 1 || by > 254 || !w.blockExists(bx, by, bz)) {
                return;
            }
            Block b = w.getBlock(bx, by, bz);
            if (b.isOpaqueCube()) {
                return;                                             // the beam hits the ground / a wall here
            }
            int level = 15 - (int) (d * 0.6F / (float) v.tuneLight());
            put(w, want, bx, by, bz, level);
            for (int side = -1; side <= 1; side += 2) {
                put(w, want, MathHelper.floor_double(ox + dx * d + sx * side), by, MathHelper.floor_double(oz + dz * d + sz * side), level - 2);
            }
        }
    }

    private static void put(World w, Map<Long, Integer> want, int x, int y, int z, int level) {
        if (level <= 0 || !w.blockExists(x, y, z) || w.getBlock(x, y, z).isOpaqueCube()) {
            return;
        }
        int sky = w.getSavedLightValue(EnumSkyBlock.Sky, x, y, z) - w.skylightSubtracted;
        if (sky >= level) {
            return;                                                 // daylight is brighter here anyway
        }
        Long k = Long.valueOf(pack(x, y, z));
        Integer old = want.get(k);
        if (old == null || old.intValue() < level) {
            want.put(k, Integer.valueOf(Math.min(15, level)));
        }
    }

    static long pack(int x, int y, int z) {
        return (long) x << 38 | ((long) y & 0xFFF) << 26 | (long) z & 0x3FFFFFF;
    }

    static int x(long k) {
        return (int) (k >> 38);
    }

    static int y(long k) {
        return (int) (k >> 26 & 0xFFF);
    }

    static int z(long k) {
        return (int) (k << 38 >> 38);
    }
}
