package com.sva.debug;

import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.XpdrMath;
import com.sva.entity.EntitySeatSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.net.NetADSVA;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerView;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * Audit 2026-10-22 world test (cockpits, the RWR, the transponder's screen), run by WorldTestSVA alongside its other phases
 * from tick START in its own area (x spawn + 9800, z spawn - 9800, a stone floor at Y 150), owner «TestADK»:
 * <ul>
 * <li>bug 12: a plane whose day key / card set 2471 - the server's XPDR_SET handler with only the mode changed ("Ch") keeps
 * the code and the key; an old client's full packet (no "Ch") still sets all three;</li>
 * <li>7.3: the liner's F/O works the transponder - IDENT from his seat (the Action packet's handler) and the panel's
 * XPDR_SET; a passenger in the cabin cannot;</li>
 * <li>the RWR: a missile in the air at the plane - its position (T_MISSILE) and its launcher's site (T_LAUNCH, once for
 * two missiles of one launcher) among the plane's threats.</li>
 * </ul>
 */
final class WorldTestAdClientSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000adc8", OWNER_NAME = "TestADK";
    static final int START = 2700;
    private static final int Y = 150, MAX_TICKS = 400;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    boolean done;
    private EntityVehicleSVA plane, liner;
    private FakePlayer fo, pax;

    WorldTestAdClientSVA(WorldTestSVA t) {
        this.t = t;
    }

    private void check(boolean ok, String name) {
        t.check(ok, name);
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = plane == null || liner == null ? 9 : 1;
        }
        int k = ticks - t0;
        if (plane != null && !plane.isDead) {
            plane.setPosition(x0 + 0.5, Y + 30, z0 - 20.5);
            plane.motionX = plane.motionY = plane.motionZ = 0;
        }
        if (phase == 1) {
            panel(w);
            launch(w);
            phase = 2;
        } else if (phase == 2 && k >= 60) {
            linerSeats(w);
            phase = 9;
        } else if (phase < 9 && k > MAX_TICKS) {
            check(false, "ad client world: every stage finished in time (stopped in stage " + phase + ")");
            phase = 9;
        }
        if (phase == 9) {
            finish(w);
        }
    }

    // ---------------------------------------------------------------- bug 12

    private static NBTTagCompound set(Integer ch, int mode, int code, boolean key) {
        NBTTagCompound d = new NBTTagCompound();
        if (ch != null) {
            d.setInteger("Ch", ch.intValue());
        }
        d.setInteger("M", mode);
        d.setInteger("C", code);
        d.setBoolean("D", key);
        return d;
    }

    private void panel(World w) {
        long now = w.getTotalWorldTime();
        XpdrMath.Xpdr x = plane.state.xpdr;
        x.mode = XpdrMath.C;
        x.setCode(02471, now);                                      // the card's code (the day key on)
        x.dayKey = true;
        x.cardDay = XpdrMath.day(w.getWorldTime());
        FakePlayer pilot = player(w, "TestADKpilot");
        ride(pilot, plane);
        // the stale screen: the mode pressed, its copy still 7000 and the key off - only the mode is marked changed
        AirDefenseSVA.INSTANCE.command(pilot, new NetADSVA.Cmd(NetADSVA.XPDR_SET, 0, 0, 0, plane.getEntityId(),
                set(Integer.valueOf(XpdrMath.CH_MODE), XpdrMath.S, XpdrMath.VFR, false)));
        boolean kept = x.mode == XpdrMath.S && x.code == 02471 && x.dayKey && x.cardDay >= 0;
        // a tower's code: the key pressed off with a stale code - the code stays
        x.setCode(04100, now);
        AirDefenseSVA.INSTANCE.command(pilot, new NetADSVA.Cmd(NetADSVA.XPDR_SET, 0, 0, 0, plane.getEntityId(),
                set(Integer.valueOf(XpdrMath.CH_KEY), XpdrMath.S, XpdrMath.VFR, false)));
        boolean tower = x.code == 04100 && !x.dayKey;
        // an old client (no "Ch"): all three, as before
        AirDefenseSVA.INSTANCE.command(pilot, new NetADSVA.Cmd(NetADSVA.XPDR_SET, 0, 0, 0, plane.getEntityId(), set(null, XpdrMath.A, 01234, false)));
        boolean old = x.mode == XpdrMath.A && x.code == 01234;
        check(kept && tower && old, "ad client world (bug 12): XPDR_SET with only the mode / the key changed keeps the card's 2471 and the "
                + "tower's 4100 (" + kept + "/" + tower + "); an old full packet still sets all (" + old + ")");
        ride(pilot, null);
        x.mode = XpdrMath.C;
        x.setCode(XpdrMath.VFR, now);
    }

    // ---------------------------------------------------------------- the RWR: the launching site

    private void launch(World w) {
        int[][] cells = {{0, 0, 0}};
        for (int i = 0; i < 2; i++) {
            AirDefenseSVA.Projectile p = new AirDefenseSVA.Projectile();
            p.missile = true;
            p.harmless = true;                                      // a dummy: never damages anything
            p.target = plane.getEntityId();
            p.dim = plane.dimension;
            p.x = x0 + 0.5 + i;
            p.y = Y + 90;
            p.z = z0 + 40.5;
            p.life = 2;
            p.hx = x0 + 12;
            p.hy = Y + 1;
            p.hz = z0 + 40;
            p.homeCells = cells;
            AirDefenseSVA.INSTANCE.fire(p);
        }
        int missiles = 0, sites = 0;
        boolean at = false;
        for (double[] th : AirDefenseSVA.INSTANCE.threats(plane)) {
            missiles += (int) th[0] == RadarMath.T_MISSILE ? 1 : 0;
            if ((int) th[0] == RadarMath.T_LAUNCH) {
                sites++;
                at = Math.abs(th[1] - (x0 + 12.5)) < 1e-6 && Math.abs(th[2] - (z0 + 40.5)) < 1e-6;
            }
        }
        check(missiles == 2 && sites == 1 && at, "ad client world (RWR): two missiles in the air at the plane - both, and their launcher's site "
                + "once, at the launcher (" + missiles + " / " + sites + ")");
    }

    // ---------------------------------------------------------------- 7.3: the liner's F/O

    private void linerSeats(World w) {
        long now = w.getTotalWorldTime();
        EntitySeatSVA foSeat = liner.seatAt(LinerView.FO_SEAT), paxSeat = liner.seatAt(5);
        boolean powered = AirDefenseSVA.xpdrOn(liner);
        if (foSeat == null || paxSeat == null || !powered) {
            check(false, "ad client world (7.3): the liner's seats and power are there (F/O " + (foSeat != null) + ", cabin " + (paxSeat != null)
                    + ", transponder on " + powered + ")");
            return;
        }
        ride(fo, foSeat);
        ride(pax, paxSeat);
        XpdrMath.Xpdr x = liner.state.xpdr;
        x.identUntil = -1;
        liner.serverAction(pax, EntityVehicleSVA.A_IDENT);
        boolean paxNo = x.identUntil < 0;
        liner.serverAction(fo, EntityVehicleSVA.A_IDENT);
        boolean foYes = x.identUntil > now;
        x.setCode(04100, now);
        x.mode = XpdrMath.C;
        AirDefenseSVA.INSTANCE.command(pax, new NetADSVA.Cmd(NetADSVA.XPDR_SET, 0, 0, 0, liner.getEntityId(),
                set(Integer.valueOf(XpdrMath.CH_MODE), XpdrMath.OFF, XpdrMath.VFR, false)));
        boolean paxSet = x.mode == XpdrMath.C;
        AirDefenseSVA.INSTANCE.command(fo, new NetADSVA.Cmd(NetADSVA.XPDR_SET, 0, 0, 0, liner.getEntityId(),
                set(Integer.valueOf(XpdrMath.CH_MODE), XpdrMath.S, XpdrMath.VFR, false)));
        boolean foSet = x.mode == XpdrMath.S && x.code == 04100;
        check(paxNo && foYes && paxSet && foSet, "ad client world (7.3): the liner's F/O works the transponder - IDENT (" + foYes + ") and the "
                + "panel (" + foSet + "); a passenger cannot (" + paxNo + "/" + paxSet + ")");
        ride(fo, null);
        ride(pax, null);
    }

    // ---------------------------------------------------------------- the area

    private static FakePlayer player(World w, String name) {
        return FakePlayerFactory.get((WorldServer) w, new GameProfile(UUID.nameUUIDFromBytes(("sva-test:" + name).getBytes()), name));
    }

    /** Mounts / unmounts a fake player by the fields (EntityPlayerMP.mountEntity sends a packet it has no connection for). */
    private static void ride(FakePlayer p, Entity m) {
        if (p.ridingEntity != null) {
            p.ridingEntity.riddenByEntity = null;
        }
        p.ridingEntity = m;
        if (m != null) {
            m.riddenByEntity = p;
        }
    }

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = (sp.posX + 9800) >> 4 << 4;
        z0 = (sp.posZ - 9800) >> 4 << 4;
        keepLoaded(w);
        area(w, true);
        fo = player(w, "TestADKfo");
        pax = player(w, "TestADKpax");
        VehicleState st = new VehicleState(VehicleType.PLANE);
        st.energy = st.maxEnergy();
        plane = ItemVehicleSVA.spawn(w, ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.PLANE.ordinal()), st),
                x0 + 0.5, Y + 30, z0 - 20.5, 0F, OWNER, OWNER_NAME);
        VehicleState ls = new VehicleState(VehicleType.LINER);
        ls.fillAllGases();
        ls.energy = ls.maxEnergy();
        liner = ItemVehicleSVA.spawn(w, ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.LINER.ordinal()), ls),
                x0 + 0.5, Y + 1, z0 + 0.5, -90F, OWNER, OWNER_NAME);
        if (liner != null) {
            liner.debugEngineManual = true;
            liner.state.liner().set(LinerControls.BAT1, 1);         // the board on the batteries: the transponder has power
            liner.state.liner().set(LinerControls.BAT2, 1);
        }
        check(plane != null && liner != null, "ad client world: a plane and a liner spawned");
    }

    private void area(World w, boolean build) {
        for (int x = x0 - 32; x <= x0 + 32; x++) {
            for (int z = z0 - 28; z <= z0 + 28; z++) {
                for (int y = Y + 1; y <= Y + 14; y++) {
                    if (w.getBlock(x, y, z) != Blocks.air) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
                w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    private void keepLoaded(World w) {
        if (!(w instanceof WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        ChunkProviderServer cp = ((WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 48) >> 4; cx <= (x0 + 48) >> 4; cx++) {
            for (int cz = (z0 - 48) >> 4; cz <= (z0 + 64) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void finish(World w) {
        for (FakePlayer p : new FakePlayer[] {fo, pax}) {
            if (p != null) {
                ride(p, null);
            }
        }
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {plane, liner}) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        if (x0 != 0 || z0 != 0) {
            keepLoaded(w);
            area(w, false);
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (reg != null) {
            reg.removeOwner(OWNER);
        }
        phase = 10;
        done = true;
    }

    void abort(World w) {
        try {
            finish(w);
        } catch (Throwable t2) {
            done = true;
        }
    }
}
