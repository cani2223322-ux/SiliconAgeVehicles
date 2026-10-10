package com.sva.debug;

import com.sva.block.BlockGaragePlateSVA;
import com.sva.block.TileEntityGarageSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemKeyCardSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.AccessMath;
import com.sva.vehicle.GarageMath;
import com.sva.vehicle.TuningMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * Wave 2 world test (★7 garage, ★8 tuning, ★10 locks), run by WorldTestSVA alongside its other phases from tick START
 * in its own area (x spawn + 30 .. + 122, z spawn + 62 .. + 90, Y 210) with its own test owner:
 * - a Repair Garage with a 3x3 floor of lift pads, EU and 2 steel + 4 iron in its slots; a buggy at 62 % hull and 60 %
 *   heat drives in: a stranger may not start a repair, the owner may («Чинить всё»); the metal goes at once, the EU
 *   over the job, the comparator shows progress; at the end the hull is full and the engine cold;
 * - then the garage's tuning slots: «Мотор+ II» put in is installed on the vehicle, «Мотор+ I» is refused (conflict),
 *   taking it out removes it; the vehicle -> item -> vehicle keeps upgrades and the access list;
 * - a locked buggy refuses a stranger's right-click (nobody boards), a valid key card lets him drive, «Сменить замок»
 *   voids the card, «Открыта» opens it to all;
 * - two buggies race on stone for RUN ticks: the one with «Мотор+ II» must get clearly farther.
 */
final class WorldTestWave2SVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000a5a2", STRANGER = "00000000-5bad-4e57-0000-0000000000f2";
    static final int START = 300;
    private static final int Y = 210, LANE = 92, RUN = 100, REPAIR_WAIT = 130;
    private static final double MOTOR_MIN = 1.15;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0, gx, gz;
    private TileEntityGarageSVA garage;
    private EntityVehicleSVA repaired, locked, plain, tuned;
    private FakePlayer owner, stranger;
    private int euBefore, cmpMid = -1;
    private long jobEu;
    boolean done;

    WorldTestWave2SVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = 1;
        } else if (phase == 1 && ticks - t0 == REPAIR_WAIT / 2) {
            cmpMid = garage == null ? -1 : garage.comparatorLevel();
            phase = 2;
        } else if (phase == 2 && ticks - t0 >= REPAIR_WAIT) {
            verifyRepair(w);
            race(w);
            t0 = ticks;
            phase = 3;
        } else if (phase == 3 && ticks - t0 >= RUN) {
            verifyRace();
            finish(w);
        }
    }

    private EntityVehicleSVA buggy(World w, double x, double z, float hp, double heat) {
        VehicleState st = new VehicleState(VehicleType.BUGGY);
        st.energy = st.maxEnergy();
        st.hp = hp;
        st.heat = heat;
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.BUGGY.ordinal()), st);
        return ItemVehicleSVA.spawn(w, s, x, Y + 1, z, -90F, OWNER, "WorldTest2");      // facing +x
    }

    private void area(World w, boolean build) {
        for (int x = x0 - 4; x <= x0 + LANE; x++) {
            for (int z = z0 - 2; z <= z0 + 28; z++) {
                for (int y = Y + 1; y <= Y + 4; y++) {
                    if (w.getBlock(x, y, z) != Blocks.air) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
                w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 30;
        z0 = sp.posZ + 62;
        area(w, true);
        owner = FakePlayerFactory.get((net.minecraft.world.WorldServer) w, new com.mojang.authlib.GameProfile(java.util.UUID.fromString(OWNER), "WorldTest2"));
        stranger = FakePlayerFactory.get((net.minecraft.world.WorldServer) w,
                new com.mojang.authlib.GameProfile(java.util.UUID.fromString(STRANGER), "Stranger2"));
        stranger.inventory.clearInventory(null, -1);
        // ---- the garage: the controller, a 3x3 floor east of it (one lamp plate), EU, 2 steel + 4 iron
        gx = x0;
        gz = z0 + 20;
        w.setBlock(gx, Y, gz, ModContentSVA.garage, 0, 3);
        for (int dx = 1; dx <= 3; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                w.setBlock(gx + dx, Y, gz + dz, ModContentSVA.garagePlate, dx == 3 && dz == 1 ? BlockGaragePlateSVA.LAMP : BlockGaragePlateSVA.PAD, 3);
            }
        }
        TileEntity te = w.getTileEntity(gx, Y, gz);
        garage = te instanceof TileEntityGarageSVA ? (TileEntityGarageSVA) te : null;
        check(garage != null, "wave 2 garage: the controller has its tile");
        if (garage == null) {
            return;
        }
        garage.fillForTest(GarageMath.BUFFER);
        garage.setInventorySlotContents(0, ModContentSVA.steelStack(2));
        garage.setInventorySlotContents(1, new ItemStack(Items.iron_ingot, 4));
        float max = VehicleType.BUGGY.maxHp;
        repaired = buggy(w, gx + 2.5, gz + 0.5, max * 0.62F, 60);
        locked = buggy(w, x0 + 12.5, gz + 0.5, max, 0);
        check(repaired != null && locked != null, "wave 2: test buggies placed (garage, lock)");
        if (repaired == null || locked == null) {
            return;
        }
        garage.rescanForTest();
        check(garage.getCurrent() == repaired && garage.plateCount() == 9, "wave 2 garage: the buggy on the 3x3 floor is the garage's vehicle ("
                + garage.plateCount() + " plates)");
        long[] cost = garage.cost(GarageMath.ALL);
        check(cost[0] == GarageMath.euCost(max * 0.62F, max, 60, GarageMath.ALL) && cost[1] == 4,
                "wave 2 garage: the price for 38 % wear + 60 % heat is " + cost[0] + " EU + " + cost[1] + " steel");
        check(garage.startRepair(stranger, GarageMath.ALL) == TileEntityGarageSVA.R_NO_RIGHT && !garage.isRepairing(),
                "wave 2 garage: a stranger without the \"Repair\" right can't start a repair");
        euBefore = garage.getEnergyStored();
        jobEu = cost[0];
        int r = garage.startRepair(owner, GarageMath.ALL);
        int[] m = garage.materials();
        check(r == TileEntityGarageSVA.R_OK && garage.isRepairing() && m[0] == 0 && m[1] == 0,
                "wave 2 garage: the owner starts \"Repair all\"; 2 steel + 4 iron taken (left " + m[0] + " / " + m[1] + ")");
        lockChecks(w);
    }

    /** ★10 on the parked buggy: a stranger is refused, a card opens it, a new lock voids the card, «Открыта» opens it. */
    private void lockChecks(World w) {
        EntityVehicleSVA v = locked;
        owner.setPosition(v.posX + 2, v.posY, v.posZ);              // access edits need the owner within 8 blocks
        stranger.setPosition(v.posX - 2, v.posY, v.posZ);
        stranger.setSneaking(false);
        stranger.inventory.currentItem = 0;
        stranger.inventory.setInventorySlotContents(0, null);
        v.interactFirst(stranger);
        check(v.riddenByEntity == null && v.riders().isEmpty() && v.rightsOf(stranger) == 0 && !v.canOpenCargo(stranger),
                "wave 2 lock: a locked buggy (Friends, nobody listed) refuses a stranger - no seat, no cargo");
        check(v.rightsOf(owner) == AccessMath.ALL, "wave 2 lock: the owner keeps every right");
        ItemStack card = ItemKeyCardSVA.write(new ItemStack(ModContentSVA.keyCard), v.state.vehId, v.getBoardNumber(), v.state.type,
                "WorldTest2", "Stranger2", v.state.access.lockVersion, AccessMath.DRIVE);
        stranger.inventory.setInventorySlotContents(5, card);
        check(v.rightsOf(stranger) == 0 && !v.canDrive(stranger),
                "wave 2 lock: a friend's card whose holder is not on the list opens nothing (ТН-1)");
        v.accessEdit(owner, EntityVehicleSVA.AE_ADD, "Stranger2", 0);
        v.accessEdit(owner, EntityVehicleSVA.AE_SET, "Stranger2", 0);  // listed with no rights: only the card gives any
        int issue = v.state.access.reissue("Stranger2");
        card = ItemKeyCardSVA.write(new ItemStack(ModContentSVA.keyCard), v.state.vehId, v.getBoardNumber(), v.state.type,
                "WorldTest2", "Stranger2", v.state.access.lockVersion, AccessMath.DRIVE, v.state.access.idOf("Stranger2"), issue);
        stranger.inventory.setInventorySlotContents(5, card);
        check(v.canDrive(stranger) && (v.rightsOf(stranger) & AccessMath.PASSENGER) != 0 && !v.canOpenCargo(stranger),
                "wave 2 lock: a valid key card with \"Drive\" lets the stranger drive (and sit), not open the cargo");
        check(STRANGER.equals(v.state.access.idOf("Stranger2")), "wave 2 lock: the listed stranger's row gets his UUID when he comes (ТН-4)");
        int issue2 = v.state.access.reissue("Stranger2");
        check(issue2 != issue && v.rightsOf(stranger) == 0, "wave 2 lock: a newer card for the same player voids his old one (ТН-1)");
        card = ItemKeyCardSVA.write(new ItemStack(ModContentSVA.keyCard), v.state.vehId, v.getBoardNumber(), v.state.type,
                "WorldTest2", "Stranger2", v.state.access.lockVersion, AccessMath.DRIVE, v.state.access.idOf("Stranger2"), issue2);
        stranger.inventory.setInventorySlotContents(5, card);
        check(v.canDrive(stranger), "wave 2 lock: the newer card drives");
        v.accessEdit(owner, EntityVehicleSVA.AE_RELOCK, "", 0);
        check(!v.canDrive(stranger) && v.rightsOf(stranger) == 0 && v.state.access.lockVersion == 1,
                "wave 2 lock: \"Change lock\" - the old card no longer opens it");
        v.accessEdit(owner, EntityVehicleSVA.AE_ADD, "Stranger2", 0);
        v.accessEdit(owner, EntityVehicleSVA.AE_SET, "Stranger2", AccessMath.CARGO);
        check(v.canOpenCargo(stranger) && !v.canDrive(stranger), "wave 2 lock: listed with \"Cargo\" only - cargo yes, driving no");
        v.accessEdit(owner, EntityVehicleSVA.AE_MODE, "", AccessMath.OPEN);
        check(v.rightsOf(stranger) == AccessMath.ALL, "wave 2 lock: \"Open\" - anyone may do anything");
        v.accessEdit(stranger, EntityVehicleSVA.AE_MODE, "", AccessMath.OWNER);
        check(v.state.access.mode == AccessMath.OPEN, "wave 2 lock: a stranger's access edit is ignored");
        stranger.inventory.setInventorySlotContents(5, null);
    }

    private void verifyRepair(World w) {
        if (garage == null || repaired == null) {
            return;
        }
        VehicleState st = repaired.state;
        int used = euBefore - garage.getEnergyStored();
        check(!garage.isRepairing() && st.hp == st.maxHp() && st.heat == 0 && !st.heatStop,
                "wave 2 garage: repaired - hull " + st.hp + " / " + st.maxHp() + ", heat " + st.heat);
        check(used >= jobEu && used <= jobEu + 1000, "wave 2 garage: the job took its EU from the buffer (" + used + " of " + jobEu + ")");
        check(cmpMid > 0 && cmpMid < 15, "wave 2 garage: the comparator showed the progress half way (" + cmpMid + ")");
        // ★8: the garage's tuning slots are the vehicle's
        ItemStack m2 = ModContentSVA.tuning.stackOf(TuningMath.MOTOR_II), m1 = ModContentSVA.tuning.stackOf(TuningMath.MOTOR_I);
        boolean okIn = garage.tuning.isItemValidForSlot(0, m2);
        garage.tuning.setInventorySlotContents(0, m2);
        boolean conflict = !garage.tuning.isItemValidForSlot(1, m1);
        check(okIn && st.tuning[0] == TuningMath.MOTOR_II && conflict && repaired.getTuning()[0] == TuningMath.MOTOR_II,
                "wave 2 tuning: \"Motor+ II\" put in the garage slot is installed; \"Motor+ I\" refused next to it");
        garage.tuning.setInventorySlotContents(1, ModContentSVA.tuning.stackOf(TuningMath.ARMOR));
        ItemStack it = repaired.toItemStack();
        VehicleState back = ItemVehicleSVA.stateOf(it);
        check(back.tuning[0] == TuningMath.MOTOR_II && back.tuning[1] == TuningMath.ARMOR && back.access.mode == st.access.mode
                        && back.access.others == st.access.others,
                "wave 2 tuning: vehicle -> item keeps the upgrades and the access list");
        ItemStack out = garage.tuning.decrStackSize(0, 1);
        check(out != null && TuningMath.MOTOR_II == com.sva.item.ItemTuningSVA.kind(out) && !TuningMath.has(st.tuning, TuningMath.MOTOR_II)
                        && TuningMath.has(st.tuning, TuningMath.ARMOR),
                "wave 2 tuning: taking it out of the slot removes it (the item comes back)");
    }

    private void race(World w) {
        plain = buggy(w, x0 + 2.5, z0 + 0.5, VehicleType.BUGGY.maxHp, 0);
        tuned = buggy(w, x0 + 2.5, z0 + 6.5, VehicleType.BUGGY.maxHp, 0);
        check(plain != null && tuned != null, "wave 2: race buggies placed");
        if (plain == null || tuned == null) {
            return;
        }
        int[] tu = TuningMath.empty();
        tu[0] = TuningMath.MOTOR_II;
        tuned.setTuning(tu);
        plain.debugDrive(EntityVehicleSVA.IN_FWD);
        tuned.debugDrive(EntityVehicleSVA.IN_FWD);
    }

    private void verifyRace() {
        if (plain == null || tuned == null) {
            return;
        }
        double a = plain.posX - (x0 + 2.5), b = tuned.posX - (x0 + 2.5);
        check(b > a * MOTOR_MIN && a > 20, "wave 2 tuning: the buggy with \"Motor+ II\" goes farther in " + RUN + " ticks (" + fmt(b) + " vs "
                + fmt(a) + " blocks, x" + fmt(a > 0 ? b / a : 0) + ")");
    }

    private void finish(World w) {
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {repaired, locked, plain, tuned}) {
            if (v != null) {
                v.debugDrive(0);
                v.setDead();
            }
        }
        repaired = locked = plain = tuned = null;
        if (garage != null) {
            for (int i = 0; i < garage.getSizeInventory(); i++) {
                garage.setInventorySlotContents(i, null);
            }
        }
        w.setBlock(gx, Y, gz, Blocks.air, 0, 3);
        for (int dx = 1; dx <= 3; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                w.setBlock(gx + dx, Y, gz + dz, Blocks.air, 0, 3);
            }
        }
        area(w, false);
        if (stranger != null) {
            stranger.inventory.clearInventory(null, -1);
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (reg != null) {
            reg.removeOwner(OWNER);
        }
        check(reg == null || reg.count(OWNER) == 0, "wave 2 cleanup: no test vehicles left");
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

    private static String fmt(double d) {
        return String.format("%.2f", Double.valueOf(d));
    }
}
