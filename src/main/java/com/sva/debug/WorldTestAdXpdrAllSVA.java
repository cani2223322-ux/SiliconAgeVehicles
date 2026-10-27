package com.sva.debug;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.sva.ConfigSVA;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.XpdrMath;
import com.sva.block.AirportDataSVA;
import com.sva.block.BlockAirDefenseSVA;
import com.sva.block.TileEntityAirDefenseSVA;
import com.sva.block.TileEntityCommandPostSVA;
import com.sva.block.TileEntityRadarSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.net.NetADSVA;
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
 * Code-only wave world test, the user's decision 3 (plan-next §10: a transponder on every vehicle, the ground ones too). Run
 * by WorldTestSVA alongside its other phases from tick START in its own area (x spawn + 15800, z spawn - 15800, a stone floor
 * at Y 150), owner «TestADX»: a command post (the key, the cards), a surveillance radar, a buggy, a hoverbike, a cargo
 * crawler, a drill vehicle and a light plane:
 * <ul>
 * <li>every ground vehicle has a powered transponder (C 7000, the day key on) - its driver sets it through the panel's
 * packet (XPDR_SET; nobody else), IDENT from the driver's seat; airdefense.allTransponders=false takes them away again;</li>
 * <li>the server's own tick (once a second) sets a ground vehicle's code from its driver's card and sends him the panel's
 * copy; the boarding warning is never checked in a buggy (no radar ever sees it), in the hoverbike it is;</li>
 * <li>the radar rules stay: the buggy, the crawler, the drill and the hoverbike on the ground are not seen; the hoverbike 6
 * blocks up is - and its transponder now answers the surveillance radar with its card's code (it used to be «no
 * reply»);</li>
 * <li>a tower hears the plane (on the ground too) and the raised hoverbike, never the buggy / crawler / drill.</li>
 * </ul>
 */
final class WorldTestAdXpdrAllSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000adf8", OWNER_NAME = "TestADX";
    private static final String SECRET = "xpKey";
    static final int START = 3250;
    private static final int Y = 150, MAX_TICKS = 300;
    /** The hoverbike's height over the floor while a radar should see it (RadarMath.MIN_AGL 4 + a margin). */
    private static final double UP = 6;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0, tickCard;
    boolean done;
    private TileEntityCommandPostSVA kp;
    private TileEntityRadarSVA radar;
    private EntityVehicleSVA buggy, hover, truck, drill, plane;
    private FakePlayer driver, rider, stranger;
    private boolean hoverUp;

    WorldTestAdXpdrAllSVA(WorldTestSVA t) {
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
            phase = kp == null || radar == null || buggy == null || hover == null || truck == null || drill == null || plane == null ? 9 : 1;
        }
        int k = ticks - t0;
        if (phase < 9) {
            kp.fillForTest();
            radar.fillForTest();
            hold(hover, x0 + 12.5, hoverUp ? Y + 1 + UP : Y + 1, z0 + 24.5);
            hold(plane, x0 + 36.5, Y + 1, z0 + 24.5);
        }
        if (phase == 1 && k >= 5) {
            fitted(w);
            panelAndIdent(w);
            switchedOff(w);
            boardWithCards(w);
            phase = 2;
            tickCard = k;
        } else if (phase == 2 && k >= tickCard + 45) {
            serverTick(w);
            radarRules(w);
            phase = 9;
        } else if (phase < 9 && k > MAX_TICKS) {
            check(false, "ad xpdr all world: every stage finished in time (stopped in stage " + phase + ")");
            phase = 9;
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private static void hold(EntityVehicleSVA v, double x, double y, double z) {
        if (v != null && !v.isDead) {
            v.setPosition(x, y, z);
            v.motionX = v.motionY = v.motionZ = 0;
        }
    }

    // ---------------------------------------------------------------- every vehicle has one

    private void fitted(World w) {
        boolean ok = true;
        StringBuilder off = new StringBuilder();
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {buggy, hover, truck, drill}) {
            XpdrMath.Xpdr x = v.state.xpdr;
            boolean on = XpdrMath.fitted(v.getVehicleType()) && AirDefenseSVA.xpdrPowered(v) && AirDefenseSVA.xpdrOn(v) && x.mode == XpdrMath.C
                    && x.code == XpdrMath.VFR;
            ok &= on;
            if (!on) {
                off.append(' ').append(v.getVehicleType().key);
            }
        }
        check(ok, "ad xpdr all world (decision 3): the buggy, the hoverbike, the crawler and the drill vehicle have powered transponders, C 7000"
                + (off.length() == 0 ? "" : " - not:" + off));
    }

    private static NBTTagCompound panel(int ch, int mode, int code, boolean key) {
        NBTTagCompound d = new NBTTagCompound();
        d.setInteger("Ch", ch);
        d.setInteger("M", mode);
        d.setInteger("C", code);
        d.setBoolean("D", key);
        return d;
    }

    private void panelAndIdent(World w) {
        ride(driver, truck);
        AirDefenseSVA.INSTANCE.command(driver, new NetADSVA.Cmd(NetADSVA.XPDR_SET, 0, 0, 0, truck.getEntityId(),
                panel(XpdrMath.CH_MODE | XpdrMath.CH_CODE, XpdrMath.A, 01234, false)));
        XpdrMath.Xpdr x = truck.state.xpdr;
        boolean set = x.mode == XpdrMath.A && x.code == 01234 && !x.dayKey;
        AirDefenseSVA.INSTANCE.command(stranger, new NetADSVA.Cmd(NetADSVA.XPDR_SET, 0, 0, 0, drill.getEntityId(),
                panel(XpdrMath.CH_CODE, XpdrMath.C, 07700, false)));
        boolean refused = drill.state.xpdr.code == XpdrMath.VFR;
        check(set && refused, "ad xpdr all world: the crawler's driver sets its transponder by the panel (A 1234) (" + set
                + "); a player not driving the drill vehicle cannot (" + refused + ")");
        long now = w.getTotalWorldTime();
        truck.serverAction(driver, EntityVehicleSVA.A_IDENT);
        boolean ident = x.identOn(now);
        drill.serverAction(stranger, EntityVehicleSVA.A_IDENT);
        check(ident && !drill.state.xpdr.identOn(now), "ad xpdr all world: IDENT from the crawler's driver seat flashes its blip; not from outside the drill vehicle");
        x.mode = XpdrMath.C;
        x.dayKey = true;
        x.setCode(XpdrMath.VFR, now);
        x.identUntil = 0;
        ride(driver, null);
    }

    private void switchedOff(World w) {
        boolean was = ConfigSVA.adAllTransponders;
        ConfigSVA.adAllTransponders = false;                         // within this tick only (the dedicated server reads its config)
        try {
            ride(driver, buggy);
            long now = w.getTotalWorldTime();
            AirDefenseSVA.INSTANCE.ident(buggy);
            AirDefenseSVA.INSTANCE.command(driver, new NetADSVA.Cmd(NetADSVA.XPDR_SET, 0, 0, 0, buggy.getEntityId(),
                    panel(XpdrMath.CH_CODE, XpdrMath.C, 02222, false)));
            check(!AirDefenseSVA.xpdrOn(buggy) && !AirDefenseSVA.xpdrOn(hover) && AirDefenseSVA.xpdrOn(plane) && !buggy.state.xpdr.identOn(now)
                    && buggy.state.xpdr.code == XpdrMath.VFR,
                    "ad xpdr all world: airdefense.allTransponders=false - the ground vehicles have none (no answer, no IDENT, the panel refused), the plane keeps its");
        } finally {
            ConfigSVA.adAllTransponders = was;
            ride(driver, null);
        }
    }

    // ---------------------------------------------------------------- the card, the server's tick

    private static void give(FakePlayer p, ItemStack s) {
        for (int i = 0; i < p.inventory.mainInventory.length; i++) {
            p.inventory.mainInventory[i] = null;
        }
        p.inventory.mainInventory[3] = s;
    }

    private ItemStack card() {
        ItemStack c = new ItemStack(ModContentSVA.codeCard);
        kp.printCardForTest(c);
        return c;
    }

    private void boardWithCards(World w) {
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {buggy, hover}) {
            v.state.xpdr.mode = XpdrMath.C;
            v.state.xpdr.dayKey = true;
            v.state.xpdr.setCode(XpdrMath.VFR, w.getTotalWorldTime());
        }
        give(driver, card());
        ride(driver, buggy);
        give(rider, card());
        ride(rider, hover);
    }

    private void serverTick(World w) {
        int dc = kp.dayCode();
        XpdrMath.Xpdr b = buggy.state.xpdr, h = hover.state.xpdr;
        long period = AirDefenseDataSVA.keyPeriod(w);
        check(dc >= 0 && b.code == dc && b.fromCard() && OWNER_NAME.equals(b.cardOwner) && b.keyLamp(period) && h.code == dc && h.fromCard(),
                "ad xpdr all world (decision 3): the server's own tick sets the buggy's and the hoverbike's code from their drivers' cards (КЛЮЧ ДНЯ lit)");
        check(AirDefenseSVA.INSTANCE.debugXpdrSent(buggy) != null && AirDefenseSVA.INSTANCE.debugXpdrSent(hover) != null,
                "ad xpdr all world: the buggy's and the hoverbike's drivers get their transponder's copy (the menu row, the screen line, the HUD)");
        check(!AirDefenseSVA.INSTANCE.debugBoarded(buggy) && AirDefenseSVA.INSTANCE.debugBoarded(hover),
                "ad xpdr all world: the boarding warning «Нет карты кодов» is never checked in a buggy (no radar sees it), in the hoverbike it is");
    }

    // ---------------------------------------------------------------- the radar rules

    private static boolean has(List<EntityVehicleSVA> l, EntityVehicleSVA v) {
        for (EntityVehicleSVA o : l) {
            if (o == v) {
                return true;
            }
        }
        return false;
    }

    private void radarRules(World w) {
        // the hoverbike on the ground: nothing of ours is seen (a ground vehicle's transponder is asked only where a radar sees it)
        hold(hover, x0 + 12.5, Y + 1, z0 + 24.5);
        radar.scan(RadarMath.SURVEY);
        boolean none = true;
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {buggy, hover, truck, drill}) {
            none &= !AirDefenseSVA.radarVisible(v) && !radar.contacts().containsKey(Integer.valueOf(v.getEntityId()));
        }
        List<EntityVehicleSVA> low = AirportDataSVA.heard(w);
        boolean lowHeard = has(low, plane) && !has(low, hover) && !has(low, buggy) && !has(low, truck) && !has(low, drill);
        check(none && lowHeard, "ad xpdr all world (radar rules kept): the buggy, the crawler, the drill and the hoverbike on the ground - no radar contact (" + none
                + "); a tower hears the plane on the ground but none of them (" + lowHeard + ")");
        // 6 blocks up: the hoverbike is seen, and its transponder answers with its card's code (no longer «no reply»)
        hoverUp = true;
        hold(hover, x0 + 12.5, Y + 1 + UP, z0 + 24.5);
        radar.scan(RadarMath.SURVEY);
        AirDefenseSVA.Contact c = radar.contacts().get(Integer.valueOf(hover.getEntityId()));
        boolean seen = AirDefenseSVA.radarVisible(hover) && c != null && c.replied && c.code == kp.dayCode() && c.fromCard && OWNER_NAME.equals(c.cardOwner)
                && c.mode == XpdrMath.C;
        boolean ground = !radar.contacts().containsKey(Integer.valueOf(buggy.getEntityId())) && !radar.contacts().containsKey(Integer.valueOf(truck.getEntityId()));
        check(seen && ground, "ad xpdr all world: the hoverbike 6 blocks up - the surveillance radar sees it and its transponder answers with the card's code (" + seen
                + "); the buggy and the crawler still unseen (" + ground + ")");
        List<EntityVehicleSVA> up = AirportDataSVA.heard(w);
        check(has(up, hover) && has(up, plane) && !has(up, buggy), "ad xpdr all world: a tower hears the raised hoverbike's transponder (a radar could see it), still no buggy");
        hoverUp = false;
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

    private EntityVehicleSVA spawn(World w, VehicleType type, double x, double z) {
        VehicleState st = new VehicleState(type);
        st.energy = st.maxEnergy();
        st.fillAllGases();
        return ItemVehicleSVA.spawn(w, ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, type.ordinal()), st), x, Y + 1, z, 0F, OWNER,
                OWNER_NAME);
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

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = (sp.posX + 15800) >> 4 << 4;
        z0 = (sp.posZ - 15800) >> 4 << 4;
        keepLoaded(w);
        area(w, true);
        driver = player(w, "TestADXdrv");
        rider = player(w, "TestADXhov");
        stranger = player(w, "TestADXout");
        kp = block(w, x0, z0, ModContentSVA.commandPost, 2, TileEntityCommandPostSVA.class);
        radar = block(w, x0 + 24, z0 - 12, ModContentSVA.radar, RadarMath.SURVEY, TileEntityRadarSVA.class);
        if (kp != null) {
            kp.debugSetup(SECRET, null, 0, null);
        }
        if (radar != null && kp != null) {
            radar.setKpLink(kp.key());
        }
        buggy = spawn(w, VehicleType.BUGGY, x0 + 12.5, z0 + 10.5);
        truck = spawn(w, VehicleType.TRUCK, x0 + 22.5, z0 + 10.5);
        drill = spawn(w, VehicleType.DRILL, x0 + 32.5, z0 + 10.5);
        hover = spawn(w, VehicleType.HOVER, x0 + 12.5, z0 + 24.5);
        plane = spawn(w, VehicleType.PLANE, x0 + 36.5, z0 + 24.5);
        check(kp != null && radar != null && buggy != null && hover != null && truck != null && drill != null && plane != null,
                "ad xpdr all world: a command post, a surveillance radar, a buggy, a hoverbike, a crawler, a drill vehicle and a plane placed");
    }

    private void area(World w, boolean build) {
        for (int x = x0 - 24; x <= x0 + 48; x++) {
            for (int z = z0 - 24; z <= z0 + 40; z++) {
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
        for (int cx = (x0 - 40) >> 4; cx <= (x0 + 64) >> 4; cx++) {
            for (int cz = (z0 - 40) >> 4; cz <= (z0 + 56) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void finish(World w) {
        for (FakePlayer p : new FakePlayer[] {driver, rider, stranger}) {
            if (p != null) {
                give(p, null);
                ride(p, null);
            }
        }
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {buggy, hover, truck, drill, plane}) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {kp, radar}) {
            if (te != null) {
                te.noDrop = true;
            }
        }
        if (x0 != 0 || z0 != 0) {
            keepLoaded(w);
            area(w, false);
        }
        AirDefenseDataSVA dd = AirDefenseDataSVA.get(w);
        if (dd != null && kp != null) {
            dd.removeKp(kp.key());
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
