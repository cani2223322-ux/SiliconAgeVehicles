package com.sva.debug;

import com.sc.util.ArmorGasSC.Gas;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemTrailerSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.net.NetSVA;
import com.sva.vehicle.MapMath;
import com.sva.vehicle.TrailerMath;
import com.sva.vehicle.TuningMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * Wave 3 world test (★15 trailers, ★19 the vehicle registry / map), run by WorldTestSVA alongside its other phases from
 * tick START in its own area (x spawn + 40 .. + 80, z spawn + 100 .. + 124, Y 200) with its own test owner:
 * - a crawler with a TANKER: a water bucket in the «in» slot fills 1000 mB (the empty bucket stays), an empty bucket in
 *   «out» takes it back; with helium in the tank «Заправить технику рядом» fills a helicopter standing by the trailer; a
 *   tank over half full slows the crawler (x0.85); unhitched, the trailer item keeps its fluid;
 * - a CAR PLATFORM: «Погрузить ближайшую» takes a buggy (37 % charge, an upgrade, a friend on its list) off the ground,
 *   x0.8 speed; «Выгрузить» puts the same vehicle down beside the crawler - same id, charge, upgrade, access;
 * - a MOBILE CHARGER with 1M EU charges a nearly empty buggy by the trailer;
 * - the REGISTRY: the unloaded buggy has a card with its coordinates; moved 20 blocks it shows the new place after the
 *   next refresh; the owner's map rows (NetSVA.MapInfo.build) list it, a stranger's don't.
 */
final class WorldTestWave3SVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000a5a3", STRANGER = "00000000-5bad-4e57-0000-0000000000f3";
    static final int START = 420;
    private static final int Y = 200, WAIT = 12, CHARGE_WAIT = 40;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    private EntityVehicleSVA truck, heli, buggy, unloaded, flat;
    private FakePlayer owner, stranger;
    private String buggyId;
    private double buggyEnergy, flatEnergy;
    boolean done;

    WorldTestWave3SVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = truck == null ? 9 : 1;
        } else if (phase == 1 && ticks - t0 >= WAIT) {
            tankIn();
            t0 = ticks;
            phase = 2;
        } else if (phase == 2 && ticks - t0 >= WAIT) {
            tankOutAndRefuel(w);
            platform(w);
            charger(w);
            t0 = ticks;
            phase = 3;
        } else if (phase == 3 && ticks - t0 >= CHARGE_WAIT) {
            verifyCharger();
            moveForRegistry();
            t0 = ticks;
            phase = 4;
        } else if (phase == 4 && ticks - t0 >= MapMath.UPDATE_EVERY + 5) {
            verifyRegistry(w);
            phase = 9;
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private void keepLoaded(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 40) >> 4; cx <= (x0 + 80) >> 4; cx++) {
            for (int cz = (z0 - 40) >> 4; cz <= (z0 + 64) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void area(World w, boolean build) {
        for (int x = x0 - 4; x <= x0 + 40; x++) {
            for (int z = z0 - 2; z <= z0 + 24; z++) {
                for (int y = Y + 1; y <= Y + 5; y++) {
                    if (w.getBlock(x, y, z) != Blocks.air) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
                w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    private EntityVehicleSVA place(World w, VehicleType type, double x, double z, double charge) {
        VehicleState st = new VehicleState(type);
        st.energy = st.maxEnergy() * charge;
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, type.ordinal()), st);
        return ItemVehicleSVA.spawn(w, s, x, Y + 1, z, -90F, OWNER, "WorldTest3");     // facing +x
    }

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 40;
        z0 = sp.posZ + 100;
        keepLoaded(w);
        area(w, true);
        owner = FakePlayerFactory.get((net.minecraft.world.WorldServer) w, new com.mojang.authlib.GameProfile(java.util.UUID.fromString(OWNER), "WorldTest3"));
        stranger = FakePlayerFactory.get((net.minecraft.world.WorldServer) w,
                new com.mojang.authlib.GameProfile(java.util.UUID.fromString(STRANGER), "Stranger3"));
        owner.inventory.clearInventory(null, -1);
        stranger.inventory.clearInventory(null, -1);
        truck = place(w, VehicleType.TRUCK, x0 + 14.5, z0 + 8.5, 1.0);
        check(truck != null, "wave 3: test crawler placed");
        if (truck == null) {
            return;
        }
        owner.setPosition(truck.posX + 2, truck.posY, truck.posZ + 3);
        int r = truck.attachTrailer(owner, new ItemStack(ModContentSVA.trailer, 1, TrailerMath.TANK));
        check(r == com.sva.vehicle.CrawlerMath.OK && truck.hasTrailerKind(TrailerMath.TANK) && truck.cargoCapacity() == VehicleType.TRUCK.cargoSlots
                        && truck.extraSlots() == 2,
                "wave 3 tanker: hitched - no extra cargo slots, two container slots (cargo " + truck.cargoCapacity() + ")");
        int n = truck.state.cargo.length;
        truck.setInventorySlotContents(n, new ItemStack(Items.water_bucket));      // the «in» slot
    }

    private void tankIn() {
        int n = truck.state.cargo.length;
        ItemStack in = truck.getStackInSlot(n);
        check(truck.tankAmount() == 1000 && "water".equals(truck.tankFluid()) && in != null && in.getItem() == Items.bucket,
                "wave 3 tanker: a water bucket in \"Fill\" pours 1000 mB in, the empty bucket stays (" + truck.tankAmount() + " mB "
                        + truck.tankFluid() + ")");
        truck.setInventorySlotContents(n, null);
        truck.setInventorySlotContents(n + 1, new ItemStack(Items.bucket));        // the «out» slot
    }

    private void tankOutAndRefuel(World w) {
        int n = truck.state.cargo.length;
        ItemStack out = truck.getStackInSlot(n + 1);
        check(truck.tankAmount() == 0 && out != null && out.getItem() == Items.water_bucket,
                "wave 3 tanker: an empty bucket in \"Drain\" takes the water back out (" + truck.tankAmount() + " mB left)");
        truck.setInventorySlotContents(n + 1, null);
        // helium -> a helicopter standing by the trailer
        String he = Gas.HELIUM.fluidOf() == null ? Gas.HELIUM.fluid : Gas.HELIUM.fluidOf().getName();
        truck.tankAdd(he, 9000);
        check(Math.abs(truck.trailerSpeedMul() - TrailerMath.TANK_SPEED) < 1e-9, "wave 3 tanker: over half full the crawler is slower (x"
                + truck.trailerSpeedMul() + ")");
        double[] tp = truck.trailerPos();
        heli = place(w, VehicleType.HELI, tp[0], tp[1] + 4.0, 0.5);
        check(heli != null && heli.state.gas(Gas.HELIUM) == 0, "wave 3 tanker: an empty helicopter placed 4 blocks from the trailer");
        if (heli != null) {
            int moved = truck.refuelNearby(owner);
            int cap = heli.state.gasCapacity(Gas.HELIUM);
            check(heli.state.gas(Gas.HELIUM) == cap && moved >= cap && truck.tankAmount() == 9000 - moved,
                    "wave 3 tanker: \"Refuel vehicles nearby\" fills the helicopter's helium (" + heli.state.gas(Gas.HELIUM) + " / " + cap
                            + ", tank " + truck.tankAmount() + " mB)");
        }
        int left = truck.tankAmount();
        ItemStack item = truck.detachTrailer();
        VehicleState c = ItemTrailerSVA.contents(item);
        check(ItemTrailerSVA.kindOf(item) == TrailerMath.TANK && c.tankAmount == left && he.equals(c.tankFluid) && !truck.hasTrailer()
                        && truck.extraSlots() == 0,
                "wave 3 tanker: unhitched, the trailer item keeps " + c.tankAmount + " mB of " + c.tankFluid);
    }

    private void platform(World w) {
        int r = truck.attachTrailer(owner, new ItemStack(ModContentSVA.trailer, 1, TrailerMath.PLATFORM));
        check(r == com.sva.vehicle.CrawlerMath.OK && truck.hasTrailerKind(TrailerMath.PLATFORM) && truck.trailerSpeedMul() == 1,
                "wave 3 platform: hitched, empty - full speed");
        double[] tp = truck.trailerPos();
        buggy = place(w, VehicleType.BUGGY, tp[0] - 1.0, tp[1] + 3.5, 0.37);
        check(buggy != null, "wave 3 platform: a buggy placed by the platform");
        if (buggy == null) {
            return;
        }
        int[] tu = TuningMath.empty();
        tu[0] = TuningMath.ARMOR;
        buggy.setTuning(tu);
        buggy.state.access.add("Friend3");
        buggyId = buggy.state.vehId;
        buggyEnergy = buggy.state.energy;
        int board = buggy.getBoardNumber();
        boolean loaded = truck.loadNearest(owner);
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        check(loaded && buggy.isDead && truck.carried()[0] == VehicleType.BUGGY.ordinal() && truck.carried()[1] == board
                        && (reg == null || !reg.has(buggyId)),
                "wave 3 platform: \"Load nearest\" takes the buggy off the ground (not placed any more)");
        check(Math.abs(truck.trailerSpeedMul() - TrailerMath.PLATFORM_SPEED) < 1e-9, "wave 3 platform: loaded, the crawler is slower (x"
                + truck.trailerSpeedMul() + ")");
        unloaded = truck.unloadCarried(owner);
        check(unloaded != null && buggyId.equals(unloaded.state.vehId) && unloaded.getBoardNumber() == board
                        && unloaded.state.energy >= buggyEnergy - 1 && unloaded.state.tuning[0] == TuningMath.ARMOR
                        && unloaded.state.access.find("Friend3") >= 0 && OWNER.equals(unloaded.state.ownerId) && truck.carried()[0] < 0,
                "wave 3 platform: \"Unload\" puts the same buggy down (same id / number, charge, upgrade, access)");
        check(unloaded != null && reg != null && reg.has(buggyId), "wave 3 platform: the unloaded buggy is placed again (registry)");
        truck.detachTrailer();
    }

    private void charger(World w) {
        ItemStack item = new ItemStack(ModContentSVA.trailer, 1, TrailerMath.CHARGER);
        NBTTagCompound d = new NBTTagCompound();
        d.setDouble(VehicleState.T_ENERGY, 1000000);
        ItemTrailerSVA.withData(item, d);
        int r = truck.attachTrailer(owner, item);
        check(r == com.sva.vehicle.CrawlerMath.OK && truck.hasTrailerKind(TrailerMath.CHARGER) && Math.abs(truck.chargerEnergy() - 1000000) < 1,
                "wave 3 charger: hitched with its 1 000 000 EU");
        double[] tp = truck.trailerPos();
        flat = place(w, VehicleType.BUGGY, tp[0] - 1.0, tp[1] - 2.5, 0.05);
        check(flat != null, "wave 3 charger: a nearly empty buggy placed by the trailer");
        flatEnergy = flat == null ? 0 : flat.state.energy;
    }

    private void verifyCharger() {
        if (flat == null) {
            return;
        }
        double got = flat.state.energy - flatEnergy;
        check(got > 2000 && truck.chargerEnergy() < 1000000 - 2000, "wave 3 charger: it charged the buggy by the trailer (+" + (long) got
                + " EU, " + (long) truck.chargerEnergy() + " EU left)");
    }

    private void moveForRegistry() {
        if (unloaded == null) {
            return;
        }
        unloaded.setPosition(x0 + 34.5, Y + 1, z0 + 20.5);
    }

    private void verifyRegistry(World w) {
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        VehicleRegistrySVA.Entry e = reg == null || buggyId == null ? null : reg.entry(buggyId);
        check(e != null && e.placed && Math.abs(e.x - (x0 + 34.5)) < 1.5 && Math.abs(e.z - (z0 + 20.5)) < 1.5 && e.type == VehicleType.BUGGY.ordinal()
                        && OWNER.equals(e.owner) && e.dim == w.provider.dimensionId,
                "wave 3 registry: the buggy's card shows its new place after it moved (" + (e == null ? "none" : (int) e.x + " " + (int) e.z) + ")");
        NetSVA.MapInfo mine = NetSVA.MapInfo.build(owner), theirs = NetSVA.MapInfo.build(stranger);
        boolean listed = false;
        for (NetSVA.MapInfo.Row r : mine.rows) {
            listed |= r.own && r.type == VehicleType.BUGGY.ordinal() && r.x == (int) Math.floor(x0 + 34.5) && r.z == (int) Math.floor(z0 + 20.5);
        }
        boolean strangerSees = false;
        for (NetSVA.MapInfo.Row r : theirs.rows) {
            strangerSees |= "WorldTest3".equals(r.owner);
        }
        check(listed && !strangerSees, "wave 3 map: the owner's map lists the buggy at its place, a stranger's map doesn't");
    }

    private void finish(World w) {
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {truck, heli, buggy, unloaded, flat}) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        truck = heli = buggy = unloaded = flat = null;
        if (x0 != 0 || z0 != 0) {
            area(w, false);
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (reg != null) {
            reg.removeOwner(OWNER);
        }
        check(reg == null || reg.count(OWNER) == 0, "wave 3 cleanup: no test vehicles left");
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
