package com.sva.debug;

import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.AnimMath;
import com.sva.vehicle.GearMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Aircraft round 2, stage D3 world test (★12 the gear doors and the struts), run by WorldTestSVA alongside its other
 * phases from tick START in its own area (x spawn + 1400 .. + 1500, z spawn + 1200 .. + 1220, a stone lane at Y 150) with
 * its own test owner. Two real jets; their part animation is ticked on the server exactly as the client ticks it
 * (EntityVehicleSVA.debugAnimTick - AnimMath / GearMath on the entity's own motion and its measured height):
 * - «gear»: held 40 blocks over the lane, moved 0.8 b/t along its facing - the gear wanted up: from the parked look (doors
 *   open) the legs swing up, then the doors shut (GearMath.phase in order, the travel and the doors' timings); then held
 *   2 blocks over the lane - down: the doors open first (the legs wait), the legs come down, the doors stay open;
 * - «drop»: let go 5 blocks over the lane on its own physics: in the air the struts extend (0), the touchdown compresses
 *   them past the parking stroke, they settle back at it.
 */
final class WorldTestAirD3SVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000a5d3";
    static final int START = 1000;
    private static final int Y = 150, LEN = 100, HIGH = 40, MAX_TICKS = 320;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0, downAt = -1;
    private EntityVehicleSVA gear, drop;
    // «gear»
    private int lastPhase, upAt = -1, shutAt = -1, openAt = -1, downDoneAt = -1;
    private boolean order = true, waited = true, doorsWhileDown = true;
    // «drop»
    private int contactAt = -1;
    private float airMax, peak, settled = -1;
    boolean done;

    WorldTestAirD3SVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = gear == null || drop == null ? 9 : 1;
            return;
        }
        int n = ticks - t0;
        if (phase == 1) {
            run(n);
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private void run(int n) {
        if (gear.isDead || drop.isDead) {
            check(false, "D3 world: a test jet was lost");
            phase = 9;
            return;
        }
        AnimMath.State s = gear.anim;
        // «gear»: held up high and moving (retract), from tick 100 held low (extend)
        boolean low = n >= 100;
        double y = low ? Y + 1 + 2 : Y + 1 + HIGH;
        double x = x0 + 5 + (n % 90) * 0.8;
        gear.setPosition(x, y, z0 + 0.5);
        gear.prevPosX = x - 0.8;
        gear.prevPosY = y;
        gear.prevPosZ = z0 + 0.5;
        gear.motionX = gear.motionY = gear.motionZ = 0;
        gear.prevRotationYaw = gear.rotationYaw = -90F;          // facing +x
        float gBefore = s.gear;
        gear.debugAnimTick();
        if (!low) {
            int ph = GearMath.phase(s.gear, s.door, true);
            if (n > 8) {                                          // the height is looked up every 4 ticks at first
                order &= ph >= lastPhase;
                lastPhase = ph;
            }
            doorsWhileDown &= s.gear <= 0F || s.gear >= 1F || s.door >= 1F;
            if (upAt < 0 && s.gear >= 1F) {
                upAt = n;
            }
            if (shutAt < 0 && s.gear >= 1F && s.door <= 0F) {
                shutAt = n;
            }
        } else {
            if (downAt < 0) {
                downAt = n;
            }
            if (openAt < 0 && s.door >= 1F) {
                openAt = n;
            }
            if (openAt < 0) {
                waited &= s.gear >= gBefore;                      // the legs wait for the doors
            }
            if (downDoneAt < 0 && s.gear <= 0F) {
                downDoneAt = n;
            }
        }
        // «drop»: its own physics, the strut from its own motion
        drop.debugAnimTick();
        AnimMath.State d = drop.anim;
        if (contactAt < 0) {
            if (n > 2 && !d.contact) {
                airMax = Math.max(airMax, d.strut);
            }
            if (d.contact && n > 2) {
                contactAt = n;
            }
        } else {
            peak = Math.max(peak, d.strut);
            if (n == contactAt + 40) {
                settled = d.strut;
            }
        }
        if (n >= 170 && downDoneAt >= 0 && (contactAt >= 0 && settled >= 0 || n >= MAX_TICKS) || n >= MAX_TICKS) {
            int g = GearMath.GEAR_TICKS, dt = GearMath.DOOR_TICKS;
            check(order && doorsWhileDown && upAt >= g - 4 && upAt <= g + 12 && shutAt - upAt >= dt - 1 && shutAt - upAt <= dt + 1,
                    "D3 world: a jet over 6 blocks at speed retracts its gear - the legs swing up (in at tick " + upAt + ", " + g
                            + " ticks of travel) with the doors open, then the doors shut (" + (shutAt - upAt) + " ticks later); phases in order");
            check(waited && openAt >= 0 && openAt - downAt <= dt + 4 && downDoneAt - openAt >= g - 1 && downDoneAt - openAt <= g + 1 && s.door == 1F,
                    "D3 world: low over the lane the gear comes down - the doors open first (" + (openAt - downAt) + " ticks, the legs wait), the legs in "
                            + (downDoneAt - openAt) + " ticks, the doors stay open");
            check(contactAt >= 0 && airMax < 0.05F && peak > GearMath.REST + 0.15F && peak <= 1F && Math.abs(settled - GearMath.REST) < 0.05F,
                    String.format(java.util.Locale.ROOT, "D3 world: a jet dropped onto the lane - struts extended in the air (%.2f), the touchdown at tick %d"
                            + " compresses them to %.0f %%, they settle at %.0f %% (parking %.0f %%)", airMax, contactAt, peak * 100, settled * 100,
                            GearMath.REST * 100));
            phase = 9;
        }
    }

    // ---------------------------------------------------------------- setup

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 1400;
        z0 = sp.posZ + 1200;
        keepLoaded(w);
        area(w, true);
        gear = jet(w, x0 + 5.5, Y + 1 + HIGH, z0 + 0.5, "gear");
        drop = jet(w, x0 + 50.5, Y + 6, z0 + 0.5, "drop");
        check(gear != null && drop != null && gear.anim.gear == 0F && gear.anim.door == 1F,
                "D3 world: two jets placed - a new one stands with its gear down, the doors open (the parked look)");
    }

    private EntityVehicleSVA jet(World w, double x, double y, double z, String name) {
        VehicleState st = new VehicleState(VehicleType.JET);
        st.energy = st.maxEnergy() * 0.9;
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.JET.ordinal()), st);
        return ItemVehicleSVA.spawn(w, s, x, y, z, -90F, OWNER, "WorldTestAirD3" + name);
    }

    private void keepLoaded(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 48) >> 4; cx <= (x0 + LEN + 48) >> 4; cx++) {
            for (int cz = (z0 - 48) >> 4; cz <= (z0 + 48) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    /** A stone lane (9 wide, LEN long) at Y; build false: air again. */
    private void area(World w, boolean build) {
        for (int x = x0; x < x0 + LEN; x++) {
            for (int z = z0 - 4; z <= z0 + 4; z++) {
                w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    private void finish(World w) {
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {gear, drop}) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        gear = drop = null;
        if (x0 != 0 || z0 != 0) {
            area(w, false);
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (reg != null) {
            reg.removeOwner(OWNER);
        }
        done = true;
    }

    void abort(World w) {
        try {
            finish(w);
        } catch (Throwable t2) {
            done = true;
        }
    }

    private void check(boolean ok, String name) {
        t.check(ok, name);
    }
}
