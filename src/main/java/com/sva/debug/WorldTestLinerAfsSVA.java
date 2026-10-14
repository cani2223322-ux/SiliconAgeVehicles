package com.sva.debug;

import com.sva.block.IlsDataSVA;
import com.sva.block.TileEntityIlsSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerEcam;
import com.sva.vehicle.LinerGuide;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Liner wave 4 world test (the SA-320's auto flight system on the real entity: the server flies it - no player, the
 * «pilot present» input is IN_CRUISE, which the liner ignores - through LinerLogicSVA, LinerPhysicsSVA and the world's
 * blocks), from tick START in its own area (x spawn + 2400 .., z spawn + 3400 ..) with its own owner:
 * <ul>
 * <li>«hdg»: in the air at Y 170, AP 1 in HDG, the FCU's heading 90 -> 180 - it turns (bank 25) and holds it;</li>
 * <li>«alt»: AP 1, the FCU 40 higher, pulled - OP CLB, ALT*, ALT: level within tolerance, the overshoot measured;</li>
 * <li>«vs»: AP 1, V/S -3 m/s pulled with the FCU 50 lower - the rate flown, ALT captured;</li>
 * <li>«spd»: AP 1 + A/THR at CL, the speed pulled and set to 120 km/h - held within tolerance;</li>
 * <li>«appr»: a 5 x 260 stone runway at Y 150 with a «Курсо-глиссадный маяк» flush at its threshold (course 090, 3 deg)
 * and a 3-wide stone carpet 1000 blocks before it (the radio altimeter's ground); the liner 1000 blocks out, 30 off the
 * centre line at a 20 deg intercept, 45 over the carpet, flaps 3, gear down, spoilers armed, autobrake MED; APPR + AP 1
 * + AP 2 - LOC* / LOC, G/S* / G/S, LAND, FLARE, RETARD (the levers to idle then, as the pilot does), the touch-down on the
 * runway near its centre line, ROLL OUT stopped by the autobrake; the beacon found through the save's ILS list;</li>
 * <li>«disc»: AP 1 engaged, the sidestick's red button - the AP off, the red «АВТОПИЛОТ ОТКЛ» with MASTER WARN and the
 * cavalry charge; the second press clears it.</li>
 * </ul>
 */
final class WorldTestLinerAfsSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-0000001a3204";
    static final int START = 1750;
    private static final int Y = 150, FLY_Y = 170, RW_LEN = 260, RW_W = 5, CARPET = 1000, MAX_TICKS = 2600;
    private static final int PILOT = EntityVehicleSVA.IN_CRUISE;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0, rx, rz;
    private EntityVehicleSVA hdg, alt, vs, spd, appr, disc;
    private boolean hdgDone, altDone, vsDone, spdDone, apprDone, discDone;
    // measurements
    private double altTarget, altMax, vsSeen = Double.NaN, spdMax, apprCrossTd = Double.NaN, apprXTd = Double.NaN, apprSink = Double.NaN, lastY;
    private int locCap = -1, gsCap = -1, flare = -1, retard = -1, rollout = -1, touch = -1;
    private boolean wasAir, capSeen;
    private double maxCrossGs;
    boolean done;

    WorldTestLinerAfsSVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = hdg == null || alt == null || vs == null || spd == null || appr == null || disc == null ? 9 : 1;
            return;
        }
        int n = ticks - t0;
        keepLoaded(w);
        if (phase == 1) {
            run(w, n);
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private EntityVehicleSVA[] all() {
        return new EntityVehicleSVA[] {hdg, alt, vs, spd, appr, disc};
    }

    private static LinerSystems L(EntityVehicleSVA v) {
        return v.state.liner();
    }

    private static void click(EntityVehicleSVA v, LinerControls.Ctl c, int action) {
        L(v).control(c.index, action);
    }

    private void run(World w, int n) {
        for (EntityVehicleSVA v : all()) {
            if (v.isDead) {
                check(false, "liner AFS world: a test liner was lost");
                phase = 9;
                return;
            }
        }
        if (n == 3) {
            for (EntityVehicleSVA v : all()) {
                v.debugEngineManual = true;                         // running now: the real systems from here
            }
        }
        if (n == 6) {
            engage();
        }
        if (n > 6 && n % 200 == 0) {
            for (EntityVehicleSVA v : new EntityVehicleSVA[] {vs, spd, appr}) {
                LinerAfs F = L(v).afs;
                System.out.println("[SVA-WORLDTEST] AFS trace t " + n + " " + (v == vs ? "vs" : v == spd ? "spd" : "appr") + " y " + f(v.posY) + " vy " + f(v.motionY * 20) + " spd "
                        + f(v.speed * 72) + " air " + f(v.airspeedNow() * 72) + " thr " + F.thrText() + " cmd " + f(F.thrCmd) + " lever " + f(F.lever) + " n1 "
                        + f(L(v).eng[0].n1) + " ver " + F.verText() + " lat " + F.latText() + " ap " + F.apText() + " law " + L(v).fctl.law + " x " + f(v.posX)
                        + " ra " + f(L(v).lastAir == null ? -1 : L(v).lastAir.ra));
            }
        }
        if (n > 6) {
            hdgTick(n);
            altTick(n);
            vsTick(n);
            spdTick(n);
            apprTick(n);
            discTick(n);
        }
        if (hdgDone && altDone && vsDone && spdDone && apprDone && discDone || n >= MAX_TICKS) {
            if (!apprDone) {
                check(false, "liner AFS world «appr»: did not finish (lat " + L(appr).afs.latText() + ", ver " + L(appr).afs.verText() + ", x "
                        + Math.round(appr.posX - rx) + ", y " + Math.round(appr.posY) + ")");
            }
            phase = 9;
        }
    }

    /** The APs engaged and the FCU set (as the pilots' clicks would). */
    private void engage() {
        for (EntityVehicleSVA v : all()) {
            click(v, LinerControls.FCU_ATHR, LinerControls.A_PRIMARY);
        }
        // «hdg»: AP 1 in HDG, the knob turned 18 clicks: 90 -> 180
        click(hdg, LinerControls.FCU_AP1, LinerControls.A_PRIMARY);
        for (int i = 0; i < 18; i++) {
            click(hdg, LinerControls.FCU_HDG, LinerControls.A_WHEEL_UP);
        }
        // «alt»: the FCU 40 higher, pulled (OP CLB)
        click(alt, LinerControls.FCU_AP1, LinerControls.A_PRIMARY);
        for (int i = 0; i < 4; i++) {
            click(alt, LinerControls.FCU_ALT, LinerControls.A_WHEEL_UP);
        }
        altTarget = L(alt).afs.alt;
        click(alt, LinerControls.FCU_ALT, LinerControls.A_SECONDARY);
        // «vs»: the FCU 50 lower, V/S pulled, -3 m/s
        click(vs, LinerControls.FCU_AP1, LinerControls.A_PRIMARY);
        for (int i = 0; i < 5; i++) {
            click(vs, LinerControls.FCU_ALT, LinerControls.A_WHEEL_DOWN);
        }
        click(vs, LinerControls.FCU_VS, LinerControls.A_SECONDARY);
        for (int i = 0; i < 6; i++) {
            click(vs, LinerControls.FCU_VS, LinerControls.A_WHEEL_DOWN);
        }
        // «spd»: the speed pulled and turned to 120 km/h
        click(spd, LinerControls.FCU_AP1, LinerControls.A_PRIMARY);
        click(spd, LinerControls.FCU_SPD, LinerControls.A_SECONDARY);
        L(spd).afs.spd = 120;
        // «appr»: the autobrake MED (Green is up now), APPR, AP 1 + AP 2
        click(appr, LinerControls.ABRK_MED, LinerControls.A_PRIMARY);
        click(appr, LinerControls.FCU_AP1, LinerControls.A_PRIMARY);
        click(appr, LinerControls.FCU_APPR, LinerControls.A_PRIMARY);
        click(appr, LinerControls.FCU_AP2, LinerControls.A_PRIMARY);
        // «disc»
        click(disc, LinerControls.FCU_AP1, LinerControls.A_PRIMARY);
        check(L(hdg).afs.ap1 && L(alt).afs.ver == LinerAfs.V_OP_CLB && L(vs).afs.ver == LinerAfs.V_VS && L(vs).afs.vs10 == -30 && !L(spd).afs.spdMan
                        && L(appr).afs.ap1 && L(appr).afs.ap2 && L(appr).afs.approachArmedOrOn() && L(disc).afs.ap1 && L(spd).afs.athr && L(appr).afs.athr,
                "liner AFS world: the APs and the A/THR engaged by the FCU's buttons in the air (INS aligned): HDG, OP CLB, V/S -3,0, SPEED 120,"
                        + " APPR + AP 1 + 2");
    }

    private void hdgTick(int n) {
        if (hdgDone || n < 600) {
            return;
        }
        hdgDone = true;
        double h = LinerGuide.compass(hdg.rotationYaw);
        check(L(hdg).afs.lat == LinerAfs.L_HDG && Math.abs(CruiseMath.wrap(h - 180)) < 1.0 && Math.abs(hdg.posY - FLY_Y) < 1.0,
                "liner AFS world «hdg»: AP HDG 090 -> 180 - heading " + f(h) + ", the altitude held (" + f(hdg.posY - FLY_Y) + ")");
        hdg.debugDrive(0);
    }

    private void altTick(int n) {
        if (altDone) {
            return;
        }
        altMax = Math.max(altMax, alt.posY);
        capSeen |= L(alt).afs.ver == LinerAfs.V_ALT_CAP;
        if (n >= 1300) {
            altDone = true;
            check(capSeen && L(alt).afs.ver == LinerAfs.V_ALT && Math.abs(alt.posY - altTarget) < 0.5 && altMax - altTarget < 2.0,
                    "liner AFS world «alt»: OP CLB -> ALT* -> ALT at " + f(alt.posY) + " (FCU " + f(altTarget) + "), overshoot " + f(altMax - altTarget)
                            + " blocks");
            alt.debugDrive(0);
        }
    }

    private void vsTick(int n) {
        if (vsDone) {
            return;
        }
        if (n == 150) {
            vsSeen = (vs.posY - lastY) * 20;
        }
        lastY = vs.posY;
        if (n >= 900) {
            vsDone = true;
            LinerAfs F = L(vs).afs;
            check(Math.abs(vsSeen + 3.0) < 0.15 && F.ver == LinerAfs.V_ALT && Math.abs(vs.posY - F.alt) < 0.5,
                    "liner AFS world «vs»: V/S -3,0 m/s flown " + f(vsSeen) + " m/s, ALT captured at " + f(vs.posY) + " (FCU " + F.alt + ")");
            vs.debugDrive(0);
        }
    }

    private void spdTick(int n) {
        if (spdDone) {
            return;
        }
        double k = Math.max(spd.speed, 0) * 72;
        if (n > 700) {
            spdMax = Math.max(spdMax, Math.abs(k - 120));
        }
        if (n >= 1000) {
            spdDone = true;
            check(L(spd).afs.thr == LinerAfs.T_SPEED && spdMax < 2.0, "liner AFS world «spd»: A/THR SPEED 120 km/h held within " + f(spdMax) + " km/h");
            spd.debugDrive(0);
        }
    }

    private void apprTick(int n) {
        if (apprDone) {
            return;
        }
        LinerSystems S = L(appr);
        LinerAfs F = S.afs;
        if ((F.thr == LinerAfs.T_RETARD || F.call == LinerAfs.CALL_RETARD) && appr.throttle > 0) {
            appr.throttle = 0;                                      // RETARD: the pilot's levers to idle
        }
        locCap = locCap < 0 && F.lat == LinerAfs.L_LOC_CAP ? n : locCap;
        gsCap = gsCap < 0 && F.ver == LinerAfs.V_GS_CAP ? n : gsCap;
        flare = flare < 0 && F.ver == LinerAfs.V_FLARE ? n : flare;
        retard = retard < 0 && F.thr == LinerAfs.T_RETARD ? n : retard;
        rollout = rollout < 0 && F.lat == LinerAfs.L_ROLLOUT ? n : rollout;
        LinerIls.Station st = F.ils;
        boolean air = !appr.onGround;
        if (st != null) {
            LinerIls.Dev d = LinerIls.dev(st, appr.posX, appr.posY, appr.posZ, 4000);
            if (F.ver == LinerAfs.V_GS && air) {
                maxCrossGs = Math.max(maxCrossGs, Math.abs(d.cross));
            }
            if (wasAir && !air && touch < 0) {
                touch = n;
                apprCrossTd = d.cross;
                apprXTd = d.along;
                apprSink = -(appr.posY - lastApprY) * 20;
            }
        }
        if (air) {
            lastApprY = appr.posY;
        }
        wasAir = air || wasAir && touch < 0 && n < 10;
        if (touch >= 0 && appr.speed < 0.01 && n > touch + 20) {
            apprDone = true;
            LinerIls.Dev end = st == null ? null : LinerIls.dev(st, appr.posX, appr.posY, appr.posZ, 4000);
            IlsDataSVA data = IlsDataSVA.get(appr.worldObj);
            check(data != null && data.near(appr.worldObj.provider.dimensionId, rx, rz, 100).size() == 1 && st != null && "ВПП".equals(st.ident)
                            && Math.abs(st.course - 90) < 1e-6,
                    "liner AFS world «appr»: the beacon at the threshold is in the save's ILS list and tuned (" + (st == null ? "-" : st.ident + " "
                            + LinerIls.freqText(st.freq)) + ")");
            check(locCap > 0 && gsCap > 0 && flare > gsCap && retard > flare && rollout > retard && maxCrossGs < 1.5,
                    "liner AFS world «appr»: LOC* " + locCap + ", G/S* " + gsCap + ", FLARE " + flare + ", RETARD " + retard + ", ROLL OUT " + rollout
                            + " (ticks); the centre line held within " + f(maxCrossGs) + " blocks on the glide slope");
            check(apprXTd > 0 && apprXTd < 120 && Math.abs(apprCrossTd) < 1.5 && apprSink < 1.6 && appr.getHp() >= appr.getMaxHp() - 1e-3,
                    "liner AFS world «appr»: touch-down " + f(apprXTd) + " blocks past the threshold, " + f(apprCrossTd) + " off the centre line, sinking "
                            + f(apprSink) + " m/s, no damage");
            check(end != null && end.along < RW_LEN && Math.abs(end.cross) < 1.5 && S.fctl.gndSpl && S.gear.abMode == 2 && F.lat == LinerAfs.L_ROLLOUT,
                    "liner AFS world «appr»: ROLL OUT - the ground spoilers and the autobrake MED stopped it " + f(end == null ? -1 : end.along - apprXTd)
                            + " blocks after the touch-down, " + f(end == null ? 99 : end.cross) + " off the centre line, on the runway");
            appr.debugDrive(0);
        }
    }

    private double lastApprY;

    private void discTick(int n) {
        if (discDone) {
            return;
        }
        LinerSystems S = L(disc);
        if (n == 40) {
            click(disc, LinerControls.AP_DISC_L, LinerControls.A_PRIMARY);
        }
        if (n == 43) {
            boolean warn = !S.afs.apOn() && (S.ecam.shown & 1L << LinerEcam.M_AP_OFF) != 0 && S.masterWarn && S.afs.cavalry != 0;
            check(warn, "liner AFS world «disc»: the sidestick's red button - the AP off, the red «АВТОПИЛОТ ОТКЛ», MASTER WARN, the cavalry charge");
            click(disc, LinerControls.AP_DISC_R, LinerControls.A_PRIMARY);
        }
        if (n == 46) {
            discDone = true;
            check((S.ecam.shown & 1L << LinerEcam.M_AP_OFF) == 0 && S.afs.cavalry == 0, "liner AFS world «disc»: the second press clears the warning and the aural");
            disc.debugDrive(0);
        }
    }

    // ---------------------------------------------------------------- setup

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 2400;
        z0 = sp.posZ + 3400;
        rx = x0 + CARPET + 40;
        rz = z0 + 300;
        keepLoaded(w);
        build(w, true);
        // the beacon flush in the runway at its threshold, the course east (090), 3 deg, «ВПП»
        w.setBlock(rx, Y, rz, ModContentSVA.ils, 0, 3);
        TileEntity te = w.getTileEntity(rx, Y, rz);
        if (te instanceof TileEntityIlsSVA) {
            TileEntityIlsSVA b = (TileEntityIlsSVA) te;
            b.st.elev = Y + 1;
            b.set(90, 3, "ВПП", 11030, 0);
        }
        // the flights: east (compass 090, yaw -90) at Y 170, 1,6 b/t; lanes 60 apart
        hdg = flyer(w, x0, z0, FLY_Y, 1.6, 0);
        alt = flyer(w, x0, z0 + 60, FLY_Y - 40, 1.6, 0);
        vs = flyer(w, x0, z0 + 120, FLY_Y, 1.6, 0);
        spd = flyer(w, x0, z0 + 180, FLY_Y - 20, 1.5, 0);
        disc = flyer(w, x0, z0 + 240, FLY_Y, 1.6, 0);
        // the approach: 1000 blocks before the threshold, 30 left of the centre line, heading 110, 45 over the carpet, flaps 3
        appr = flyer(w, rx - CARPET, rz - 30, Y + 1 + 45, 1.3, 3);
        if (appr != null) {
            appr.rotationYaw = appr.prevRotationYaw = 110 - 180;
            LinerSystems S = L(appr);
            S.set(LinerControls.GEAR, 1);
            S.gear.pos = 0;
            S.set(LinerControls.SPD_BRK, 0);
        }
        check(hdg != null && alt != null && vs != null && spd != null && appr != null && disc != null && te instanceof TileEntityIlsSVA,
                "liner AFS world: six liners in the air, a 5 x " + RW_LEN + " runway with the ILS beacon and a " + CARPET + "-block approach carpet built");
    }

    /** A liner in the air: engines running (the instant start), gear up (unless flaps), the levers at CL with the A/THR. */
    private EntityVehicleSVA flyer(World w, int x, int z, double y, double speed, int flaps) {
        VehicleState st = new VehicleState(VehicleType.LINER);
        st.fillAllGases();
        st.energy = st.maxEnergy();
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.LINER.ordinal()), st);
        EntityVehicleSVA v = ItemVehicleSVA.spawn(w, s, x + 0.5, y, z + 0.5, -90F, OWNER, "WorldTestLinerAfs");
        if (v == null) {
            return null;
        }
        LinerSystems L = L(v);
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        L.set(LinerControls.ADIRS1, 1);
        L.set(LinerControls.ADIRS2, 1);
        L.set(LinerControls.ADIRS3, 1);
        L.set(LinerControls.PARK_BRK, 0);
        L.set(LinerControls.FLAPS, flaps);
        L.fctl.flapPos = flaps;
        L.fctl.slatPos = flaps > 0 ? 1 : 0;
        if (flaps == 0) {
            L.set(LinerControls.GEAR, 0);
            L.gear.pos = 1;
        }
        v.debugLinerAir = false;
        v.speed = speed;
        v.motionY = 0;
        v.throttle = LinerAfs.DET_CL;                               // the A/THR: its FCU button once the engines run (engage)
        L.afs.alt = (int) Math.round(y);
        v.debugDrive(PILOT);
        return v;
    }

    /** The approach carpet (3 wide, CARPET long) and the runway (RW_W x RW_LEN) at Y; build false: air again. */
    private void build(World w, boolean on) {
        for (int x = rx - CARPET; x < rx; x++) {
            for (int z = rz - 1; z <= rz + 1; z++) {
                w.setBlock(x, Y, z, on ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
        for (int x = rx; x < rx + RW_LEN; x++) {
            for (int z = rz - RW_W / 2; z <= rz + RW_W / 2; z++) {
                w.setBlock(x, Y, z, on ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    /** The approach's strip and the chunks round every flying liner (they wander over new ground). */
    private void keepLoaded(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        if (phase == 0) {
            for (int cx = (rx - CARPET - 32) >> 4; cx <= (rx + RW_LEN + 32) >> 4; cx++) {
                for (int cz = (rz - 48) >> 4; cz <= (rz + 48) >> 4; cz++) {
                    cp.loadChunk(cx, cz);
                }
            }
            for (int cz = (z0 - 16) >> 4; cz <= (z0 + 256) >> 4; cz++) {
                for (int cx = (x0 - 16) >> 4; cx <= (x0 + 16) >> 4; cx++) {
                    cp.loadChunk(cx, cz);
                }
            }
            return;
        }
        for (EntityVehicleSVA v : all()) {
            if (v == null || v.isDead) {
                continue;
            }
            int cx = (int) Math.floor(v.posX) >> 4, cz = (int) Math.floor(v.posZ) >> 4;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    cp.loadChunk(cx + dx, cz + dz);
                }
            }
        }
    }

    private void finish(World w) {
        for (EntityVehicleSVA v : all()) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        hdg = alt = vs = spd = appr = disc = null;
        if (x0 != 0 || z0 != 0) {
            build(w, false);
            w.setBlock(rx, Y, rz, Blocks.air, 0, 3);                // the beacon broken: out of the ILS list too
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

    static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(v));
    }
}
