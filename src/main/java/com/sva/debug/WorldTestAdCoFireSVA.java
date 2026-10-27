package com.sva.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.sva.ConfigSVA;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.CodeOnlyMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneMath;
import com.sva.block.BlockAirDefenseSVA;
import com.sva.block.TileEntityAirDefenseSVA;
import com.sva.block.TileEntityLauncherSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemAmmoSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * The user's request 2026-10-27 (airdefense.codeOnlyFire): «пусть ПВО по умолчанию стреляет, если не совпадает код
 * самолётного ответчика». Run by WorldTestSVA alongside its other phases from tick START in its own area (x spawn + 19800,
 * z spawn - 19800, a stone floor at Y 150), owner «TestADCF». Three AA guns side by side, each linked to a post that exists
 * only as trust data (as an unloaded post does): «FC» code-only and ARMED, «FN» usual, «FA» code-only but still ARMING; the
 * owner's forbidden zone over all three; a stranger's manned plane in it squawking 7000 (no card), held still. The guns'
 * rule - «по нарушителям» (a launcher without a working post), the message on, no warning shot. When the code-only gun's
 * engagement reaches «Огонь» one decision round of each gun with airdefense.mode = warnings and codeOnlyFire on (the
 * defaults; the other phases flip the mode, so it is set for the round and put back the same tick):
 * <ul>
 * <li>the code-only gun - nothing blocks the fire, the target ready, a real burst goes (the look before the shot finds no
 * code); its screen «огонь разрешён»; between the rounds its ready target is held at once once the post's ПО КОДУ or day
 * key goes off (review 2026-10-27), not with mode = fire;</li>
 * <li>the usual gun and the arming one - at «Огонь» too, but «только предупреждения»: no fire; their screens «огонь только
 * ПО КОДУ»; with mode = fire the same round fires (the setup is not what stops them);</li>
 * <li>codeOnlyFire = false - the code-only gun obeys the mode: warnings only, its readiness line «только предупреждения».</li>
 * </ul>
 */
final class WorldTestAdCoFireSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000cf10", OWNER_NAME = "TestADCF";
    private static final String STRANGER = "00000000-5bad-4e57-0000-00000000cf11";
    private static final String SECRET = "coFireKey";
    static final int START = 3350;
    private static final int Y = 150, FLY = Y + 20, MAX_TICKS = 1400;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    boolean done;
    private TileEntityLauncherSVA gCo, gNo, gArm;
    private FakePlayer pilot;
    private EntityVehicleSVA plane;
    private final double[] hold = new double[3];
    private String keyCo = "", keyNo = "", keyArm = "";

    WorldTestAdCoFireSVA(WorldTestSVA t) {
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
            phase = gCo == null ? 9 : 1;
        }
        int k = ticks - t0;
        if (phase == 1) {
            for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {gCo, gNo, gArm}) {
                te.fillForTest();
            }
            if (plane != null && !plane.isDead) {
                plane.setPosition(hold[0], hold[1], hold[2]);
                plane.motionX = plane.motionY = plane.motionZ = 0;
            }
            Integer id = Integer.valueOf(plane.getEntityId());
            long[] st = gCo.engagements().get(id);
            if (st != null && st[1] == RoeMath.ST_FIRE) {
                verdict(w, id.intValue());
                phase = 9;
            } else if (k > MAX_TICKS) {
                int[] c = gCo.lastPicture().get(id);
                check(false, "ad code-only fire world: the code-only gun reached «Огонь» in time (class " + (c == null ? "-" : String.valueOf(c[0]))
                        + ", reason " + (c == null ? "-" : String.valueOf(c[3])) + ", stage " + (st == null ? "-" : String.valueOf(st[1])) + ")");
                phase = 9;
            }
        }
        if (phase == 9) {
            finish(w);
        }
    }

    /** The decision rounds at the code-only gun's «Огонь» - the server's switches set for them and put back at once. */
    private void verdict(World w, int id) {
        boolean en0 = ConfigSVA.adEnabled, cof0 = ConfigSVA.adCodeOnlyFire, co0 = ConfigSVA.adCodeOnly, dk0 = ConfigSVA.adDayKey;
        String mode0 = ConfigSVA.adMode;
        long now = w.getTotalWorldTime();
        try {
            ConfigSVA.adEnabled = true;
            ConfigSVA.adCodeOnly = true;
            ConfigSVA.adDayKey = true;
            ConfigSVA.adMode = ConfigSVA.AD_WARNINGS;
            ConfigSVA.adCodeOnlyFire = true;
            for (TileEntityLauncherSVA g : new TileEntityLauncherSVA[] {gCo, gNo, gArm}) {
                g.engage(now);
            }
            int[] pc = gCo.lastPicture().get(Integer.valueOf(id));
            boolean target = pc != null && pc[0] == RoeMath.VIOLATOR && pc[3] == RoeMath.ID_NO_CARD;
            boolean coClear = gCo.debugBlock(id) == RoeMath.OK && gCo.debugReady(id);
            // review 2026-10-27: ПО КОДУ or its day key switched off on the post since the round - the fire between the 5-tick
            // rounds (the last round's ready list) stops at once in "warnings"; in "fire" it goes on (the mode allows it anyway)
            AirDefenseDataSVA.KpInfo fc = AirDefenseDataSVA.get(w).info(keyCo, false);
            boolean betweenGoes = !gCo.debugHeldBetween(id), heldOff = false, fireModeGoes = false, heldKey = false;
            if (fc != null) {
                fc.codeOnly = false;
                heldOff = gCo.debugHeldBetween(id);
                ConfigSVA.adMode = ConfigSVA.AD_FIRE;
                fireModeGoes = !gCo.debugHeldBetween(id);
                ConfigSVA.adMode = ConfigSVA.AD_WARNINGS;
                fc.codeOnly = true;
                fc.dayKey = false;
                heldKey = gCo.debugHeldBetween(id);
                fc.dayKey = true;
            }
            check(betweenGoes && heldOff && fireModeGoes && heldKey,
                    "ad code-only fire world (review): mode=warnings - between the rounds the code-only gun's ready target goes (" + betweenGoes
                            + "), held at once with ПО КОДУ switched off (" + heldOff + ") or its day key off (" + heldKey + "); with mode=fire not held ("
                            + fireModeGoes + ")");
            NBTTagCompound sCo = gCo.snapshot(), sNo = gNo.snapshot(), sArm = gArm.snapshot();
            gCo.debugAmmo(ModContentSVA.ammo.stackOf(ItemAmmoSVA.SHELLS, 4));
            int ammo0 = gCo.ammo();
            boolean shot = gCo.debugShoot(id);
            long[] st = gCo.engagements().get(Integer.valueOf(id));
            boolean fired = shot && gCo.ammo() < ammo0 && st != null && st[2] == 1;
            check(target && coClear && fired && CodeOnlyMath.M_FIRE.equals(sCo.getString("Mode")) && sCo.getByte("Co") == CodeOnlyMath.CO_ON
                    && sCo.getByte("CoReady") == CodeOnlyMath.R_KP_OFF,
                    "ad code-only fire world: mode=warnings, codeOnlyFire on - the gun of an ARMED code-only post fires at a no-code board after the "
                            + "warnings (a violator without a card " + target + ", nothing blocks it " + coClear + " [" + blockKey(gCo, id) + "], a burst "
                            + fired + " [ammo " + ammo0 + " -> " + gCo.ammo() + "]), its screen «" + sCo.getString("Mode") + "», readiness "
                            + sCo.getByte("CoReady"));
            long[] sn = gNo.engagements().get(Integer.valueOf(id)), sa = gArm.engagements().get(Integer.valueOf(id));
            boolean noHeld = sn != null && sn[1] == RoeMath.ST_FIRE && gNo.debugBlock(id) == RoeMath.B_WARN_ONLY && !gNo.debugReady(id) && !gNo.debugShoot(id);
            boolean armHeld = sa != null && sa[1] == RoeMath.ST_FIRE && gArm.debugBlock(id) == RoeMath.B_WARN_ONLY && !gArm.debugReady(id)
                    && !gArm.debugShoot(id) && sArm.getByte("Co") == CodeOnlyMath.CO_ARMING;
            check(noHeld && armHeld && CodeOnlyMath.M_CODE.equals(sNo.getString("Mode")) && CodeOnlyMath.M_CODE.equals(sArm.getString("Mode")),
                    "ad code-only fire world: mode=warnings - the usual post's gun (" + noHeld + " [" + blockKey(gNo, id) + "]) and the ARMING post's gun ("
                            + armHeld + " [" + blockKey(gArm, id) + "]) at «Огонь» only warn; their screens «" + sNo.getString("Mode") + "» / «"
                            + sArm.getString("Mode") + "»");
            // the control: the same rounds in the "fire" mode fire - only the mode held those two
            ConfigSVA.adMode = ConfigSVA.AD_FIRE;
            gNo.engage(now);
            gArm.engage(now);
            boolean fireMode = gNo.debugBlock(id) == RoeMath.OK && gNo.debugReady(id) && gArm.debugBlock(id) == RoeMath.OK && gArm.debugReady(id);
            check(fireMode, "ad code-only fire world: with mode=fire the usual and the arming post's guns are ready to fire at the same board ("
                    + blockKey(gNo, id) + ", " + blockKey(gArm, id) + ")");
            // codeOnlyFire = false: the code-only gun obeys the mode as before
            ConfigSVA.adMode = ConfigSVA.AD_WARNINGS;
            ConfigSVA.adCodeOnlyFire = false;
            gCo.engage(now);
            NBTTagCompound sOff = gCo.snapshot();
            check(gCo.debugBlock(id) == RoeMath.B_WARN_ONLY && !gCo.debugReady(id) && CodeOnlyMath.M_WARN.equals(sOff.getString("Mode"))
                    && sOff.getByte("CoReady") == CodeOnlyMath.R_WARN_ONLY,
                    "ad code-only fire world: codeOnlyFire=false - the code-only post's gun only warns with mode=warnings (" + blockKey(gCo, id) + ", «"
                            + sOff.getString("Mode") + "», readiness " + sOff.getByte("CoReady") + ")");
        } finally {
            ConfigSVA.adEnabled = en0;
            ConfigSVA.adCodeOnlyFire = cof0;
            ConfigSVA.adCodeOnly = co0;
            ConfigSVA.adDayKey = dk0;
            ConfigSVA.adMode = mode0;
        }
    }

    private static String blockKey(TileEntityLauncherSVA g, int id) {
        int b = g.debugBlock(id);
        return b < 0 ? "not wanted" : RoeMath.BLOCK_KEYS[b];
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

    private static void clearInv(FakePlayer p) {
        for (int i = 0; i < p.inventory.mainInventory.length; i++) {
            p.inventory.mainInventory[i] = null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends TileEntity> T block(World w, int x, int z, net.minecraft.block.Block bl, int meta, Class<T> c) {
        BlockAirDefenseSVA.build(w, x, Y + 1, z, (BlockAirDefenseSVA) bl, meta, 2);
        TileEntity te = w.getTileEntity(x, Y + 1, z);
        if (te instanceof TileEntityAirDefenseSVA) {
            ((TileEntityAirDefenseSVA) te).setOwnerName(OWNER_NAME);
        }
        return c.isInstance(te) ? (T) te : null;
    }

    /** A post that exists only as its trust data (an unloaded one), the owner's, far off. */
    private String phantom(AirDefenseDataSVA d, World w, int dx, String name, boolean codeOnly, long armAt) {
        String key = AirDefenseDataSVA.key(w.provider.dimensionId, x0 + dx, Y + 1, z0 + 2000);
        AirDefenseDataSVA.KpInfo i = d.info(key, true);
        i.owner = OWNER_NAME;
        i.name = name;
        i.dim = w.provider.dimensionId;
        i.dayKey = true;
        i.codeOnly = codeOnly;
        i.armAt = armAt;
        return key;
    }

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = (sp.posX + 19800) >> 4 << 4;
        z0 = (sp.posZ - 19800) >> 4 << 4;
        keepLoaded(w);
        w.getWorldInfo().setRaining(false);
        w.getWorldInfo().setThundering(false);
        area(w, true);
        gCo = block(w, x0 + 10, z0 + 10, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, TileEntityLauncherSVA.class);
        gNo = block(w, x0 + 20, z0 + 10, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, TileEntityLauncherSVA.class);
        gArm = block(w, x0 + 30, z0 + 10, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, TileEntityLauncherSVA.class);
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        check(gCo != null && gNo != null && gArm != null && d != null, "ad code-only fire world: three AA guns placed");
        if (gCo == null || gNo == null || gArm == null || d == null) {
            gCo = null;
            return;
        }
        long now = w.getTotalWorldTime();
        d.setSecret(OWNER_NAME, SECRET);                            // the owner's key (the posts' day key on)
        keyCo = phantom(d, w, 0, "FC", true, now - 1);              // code-only, armed
        keyNo = phantom(d, w, 10, "FN", false, -1);                 // usual
        keyArm = phantom(d, w, 20, "FA", true, now + 20L * 60 * 20); // code-only, still arming (20 min)
        TileEntityLauncherSVA[] gs = {gCo, gNo, gArm};
        String[] ks = {keyCo, keyNo, keyArm};
        for (int i = 0; i < gs.length; i++) {
            gs[i].setKpLink(ks[i]);
            gs[i].debugSettings(RoeMath.VIOLATORS, true, false, 2); // no ammunition: only the test's own burst ever goes
        }
        ZoneMath.Zone z = new ZoneMath.Zone();                      // the owner's forbidden zone over the three guns
        z.name = "FCZ";
        z.type = ZoneMath.FORBIDDEN;
        z.shape = ZoneMath.CIRCLE;
        z.cx = x0 + 20;
        z.cz = z0 + 10;
        z.r = 30;
        z.alarm = false;
        z.kp = "FC";
        z.owner = OWNER_NAME;
        z.dim = w.provider.dimensionId;
        z.kx = x0;
        z.ky = Y + 1;
        z.kz = z0 + 2000;
        List<ZoneMath.Zone> zs = new ArrayList<ZoneMath.Zone>();
        zs.add(z);
        d.setZones(keyCo, zs);
        // a stranger's plane over the middle gun, 15 blocks out, inside the zone and every gun's own eyes; 7000, no card
        pilot = player(w, "TestADCFs");
        VehicleState st = new VehicleState(VehicleType.PLANE);
        st.energy = st.maxEnergy();
        hold[0] = x0 + 20;
        hold[1] = FLY;
        hold[2] = z0 + 25;
        plane = ItemVehicleSVA.spawn(w, ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.PLANE.ordinal()), st), hold[0], hold[1],
                hold[2], 0F, STRANGER, "TestADCFs");
        check(plane != null, "ad code-only fire world: the stranger's plane placed");
        if (plane == null) {
            gCo = null;
            return;
        }
        plane.state.xpdr.mode = XpdrMath.C;
        plane.state.xpdr.dayKey = false;
        plane.state.xpdr.setCode(XpdrMath.VFR, now);
        clearInv(pilot);
        ride(pilot, plane);
    }

    private void area(World w, boolean build) {
        for (int x = x0 - 10; x <= x0 + 45; x++) {
            for (int z = z0 - 10; z <= z0 + 40; z++) {
                for (int y = Y + 1; y <= Y + 10; y++) {
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
        for (int cx = (x0 - 40) >> 4; cx <= (x0 + 80) >> 4; cx++) {
            for (int cz = (z0 - 40) >> 4; cz <= (z0 + 70) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void finish(World w) {
        if (pilot != null) {
            clearInv(pilot);
            ride(pilot, null);
        }
        if (plane != null && !plane.isDead) {
            plane.dismountAll();
            plane.setDead();
        }
        for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {gCo, gNo, gArm}) {
            if (te != null) {
                te.noDrop = true;
            }
        }
        if (x0 != 0 || z0 != 0) {
            keepLoaded(w);
            area(w, false);
        }
        AirDefenseDataSVA dd = AirDefenseDataSVA.get(w);
        if (dd != null) {
            for (String k : new String[] {keyCo, keyNo, keyArm}) {
                if (k.length() > 0) {
                    dd.removeKp(k);                                 // the phantoms' trust data and the zone
                }
            }
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (reg != null) {
            reg.removeOwner(OWNER);
            reg.removeOwner(STRANGER);
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
