package com.sva.debug;

import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.AutopilotMath;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.RouteMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Aircraft round 2, stage D4 world test (★22 the flight plan), run by WorldTestSVA alongside its other phases from tick START
 * in its own area (x spawn + 1600 .. + 1800, z spawn + 1600 .. + 1780, in the air at Y 170..180) with its own test owner. A
 * light plane is given a 3-point plan (EntityVehicleSVA.debugSetPlan - as the RouteSet packet stores it) and the autopilot
 * in МАРШ + СКОР (debugDrive with IN_CRUISE, the server's own physics as without a player); the chunks round the plane are
 * kept loaded as it flies. It must pass every point closely (the turns anticipated), climb / descend to each point's
 * height, show the active point on the jet-HUD hook (hudWaypoint) and the synced panel (МАРШ: Т2), take the legs in order,
 * and after the last point hold the last leg's course and height with the autopilot still engaged.
 */
final class WorldTestAirD4SVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000a5d4";
    static final int START = 1040;
    private static final int Y = 150, MAX_TICKS = 900, HOLD_TICKS = 100;
    private static final int IN = EntityVehicleSVA.IN_CRUISE;
    /** The plan, relative to the area's corner: {dx, dz, height over Y}. */
    private static final int[][] PTS = {{110, 0, 24}, {180, 90, 30}, {80, 150, 26}};

    private final WorldTestSVA t;
    private int phase, t0, x0, z0, doneAt = -1, order = 0, hudSeen = -1, panelSeen = -1;
    private EntityVehicleSVA plane;
    private final double[] minD = {1e9, 1e9, 1e9}, altAt = {Double.NaN, Double.NaN, Double.NaN};
    private boolean inOrder = true;
    private double holdHdgErr, holdAltErr;
    boolean done;

    WorldTestAirD4SVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = plane == null ? 9 : 1;
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
        if (plane.isDead) {
            check(false, "D4 world: the test plane was lost");
            phase = 9;
            return;
        }
        RouteMath.Plan p = plane.plan();
        for (int k = 0; k < 3; k++) {
            int[] w = p.pts.get(k);
            double d = AutopilotMath.distance(plane.posX, plane.posZ, w[0] + 0.5, w[1] + 0.5);
            if (d < minD[k]) {
                minD[k] = d;
                altAt[k] = plane.posY;                              // the height when closest to it
            }
        }
        if (p.active != order) {
            inOrder &= p.active == order + 1;                       // one leg at a time, forward
            order = p.active;
        }
        double[] hud = plane.hudWaypoint();
        if (hudSeen < 0 && p.active == 1 && hud != null && (int) hud[2] == 2 && Math.abs(hud[0] - (p.pts.get(1)[0] + 0.5)) < 1e-6) {
            hudSeen = n;
        }
        AutopilotMath.Panel ap = plane.apPanel();
        if (panelSeen < 0 && ap.on && ap.mode(AutopilotMath.M_ROUTE) && ap.routeAct == 1 && ap.routeN == 3) {
            panelSeen = n;
        }
        if (p.done() && doneAt < 0) {
            doneAt = n;
        }
        if (doneAt >= 0 && n > doneAt + 40) {
            int[] a = p.pts.get(1), b = p.pts.get(2);
            double crs = RouteMath.course(a[0] + 0.5, a[1] + 0.5, b[0] + 0.5, b[1] + 0.5);
            holdHdgErr = Math.max(holdHdgErr, Math.abs(CruiseMath.wrap(CruiseMath.heading(plane.rotationYaw) - crs)));
            if (n > doneAt + HOLD_TICKS - 20) {
                holdAltErr = Math.max(holdAltErr, Math.abs(plane.posY - b[2]));
            }
        }
        if (doneAt >= 0 && n >= doneAt + HOLD_TICKS || n >= MAX_TICKS) {
            boolean engaged = plane.isCruiseOn();
            check(doneAt > 0 && inOrder && minD[0] < 25 && minD[1] < 30 && minD[2] < RouteMath.ARRIVE + 4,
                    "D4 world: МАРШ - a light plane flies a 3-point plan leg by leg (in order " + inOrder + ", flown through at tick " + doneAt
                            + "), passing T1 / T2 / T3 within " + r(minD[0]) + " / " + r(minD[1]) + " / " + r(minD[2]) + " blocks (turns anticipated)");
            boolean alts = true;
            for (int k = 0; k < 3; k++) {
                alts &= !Double.isNaN(altAt[k]) && Math.abs(altAt[k] - (Y + PTS[k][2])) < 3.5;
            }
            check(alts, "D4 world: МАРШ - the heights of the points flown (" + r(altAt[0]) + " / " + r(altAt[1]) + " / " + r(altAt[2]) + " for "
                    + (Y + PTS[0][2]) + " / " + (Y + PTS[1][2]) + " / " + (Y + PTS[2][2]) + ")");
            check(doneAt > 0 && engaged && holdHdgErr < 3 && holdAltErr < 1.5,
                    "D4 world: МАРШ - after the last point it holds the last leg's course (err " + r(holdHdgErr) + " deg) and the height (err "
                            + r(holdAltErr) + " bl.), the autopilot still engaged (" + engaged + ")");
            check(hudSeen >= 0 && panelSeen >= 0, "D4 world: the active point on the jet-HUD hook (hudWaypoint: point 2 at tick " + hudSeen
                    + ") and the synced panel (МАРШ: Т2, 3 points at tick " + panelSeen + ")");
            plane.debugDrive(0);
            phase = 9;
        }
    }

    private static String r(double v) {
        return String.valueOf(Math.round(v * 10) / 10.0);
    }

    // ---------------------------------------------------------------- setup

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 1600;
        z0 = sp.posZ + 1600;
        double sx = x0 + 0.5, sz = z0 + 0.5;
        keepAround(w, sx, sz);
        VehicleState st = new VehicleState(VehicleType.PLANE);
        st.energy = st.maxEnergy() * 0.9;
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.PLANE.ordinal()), st);
        plane = ItemVehicleSVA.spawn(w, s, sx, Y + 20, sz, -90F, OWNER, "WorldTestAirD4");      // facing east (+x)
        check(plane != null, "D4 world: a light plane placed for the flight plan");
        if (plane == null) {
            return;
        }
        RouteMath.Plan p = new RouteMath.Plan();
        for (int[] q : PTS) {
            p.add(x0 + q[0], z0 + q[1], Y + q[2]);
        }
        plane.debugSetPlan(p);
        plane.speed = 1.1;
        plane.throttle = 0.85;
        plane.setCruiseTargets(CruiseMath.fromKmh(79), plane.rotationYaw, plane.posY);
        plane.setApModes(AutopilotMath.M_ROUTE | AutopilotMath.M_SPD, 0);
        plane.debugDrive(IN);
    }

    /** The chunks round the plane (entities tick only with 32 blocks round them loaded) - it flies into new ones. */
    private void keepLoaded(World w) {
        if (plane != null && !plane.isDead) {
            keepAround(w, plane.posX, plane.posZ);
        }
    }

    private static void keepAround(World w, double x, double z) {
        if (!(w instanceof net.minecraft.world.WorldServer)) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
        for (int i = -3; i <= 3; i++) {
            for (int k = -3; k <= 3; k++) {
                cp.loadChunk(cx + i, cz + k);
            }
        }
    }

    private void finish(World w) {
        if (plane != null && !plane.isDead) {
            plane.debugDrive(0);
            plane.dismountAll();
            plane.setDead();
        }
        plane = null;
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
