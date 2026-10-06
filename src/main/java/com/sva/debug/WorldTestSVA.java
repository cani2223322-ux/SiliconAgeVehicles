package com.sva.debug;

import com.sva.ConfigSVA;
import com.sva.block.TileEntityChargerSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

/**
 * -Dsva.worldtest=true (dedicated test server): a charger + a buggy on it, a buggy driven through the server
 * input path, an item round trip, wear and the per-player count - in the running world, near spawn at Y 230.
 * Everything is removed afterwards. Prints [SVA-WORLDTEST] lines.
 */
public class WorldTestSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000c0de";
    private static final int START = 60, CHECK = 160, CLEAN = 170, Y = 229;

    private int ticks, passed, failed;
    private int bx, bz;
    private TileEntityChargerSVA charger;
    private EntityVehicleSVA onCharger, driven, replaced, crasher;
    private double startX;

    private void check(boolean ok, String name) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        System.out.println("[SVA-WORLDTEST] " + (ok ? "PASS " : "FAIL ") + name);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) {
            return;
        }
        ticks++;
        World w = DimensionManager.getWorld(0);
        if (w == null) {
            return;
        }
        try {
            if (ticks == START) {
                setup(w);
            } else if (ticks > START && ticks < CHECK && charger != null) {
                charger.fillForTest(charger.getMaxEnergyStored());        // stands in for a cable feed
            } else if (ticks == CHECK) {
                verify(w);
            } else if (ticks == CLEAN) {
                cleanup(w);
            }
        } catch (Throwable t) {
            failed++;
            System.out.println("[SVA-WORLDTEST] FAIL exception " + t);
            t.printStackTrace(System.out);
            if (ticks < CLEAN) {
                ticks = CLEAN - 1;
            }
        }
    }

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        bx = sp.posX + 4;
        bz = sp.posZ + 4;
        clearArea(w);
        for (int x = bx - 3; x <= bx + 3; x++) {
            for (int z = bz - 3; z <= bz + 3; z++) {
                w.setBlock(x, Y, z, Blocks.stone, 0, 2);
            }
        }
        for (int x = bx + 8; x <= bx + 75; x++) {
            for (int z = bz + 8; z <= bz + 12; z++) {
                w.setBlock(x, Y, z, Blocks.stone, 0, 2);
            }
        }
        for (int x = bx + 40; x <= bx + 62; x++) {
            for (int z = bz + 15; z <= bz + 21; z++) {
                w.setBlock(x, Y, z, Blocks.stone, 0, 2);
                if (x == bx + 60) {
                    w.setBlock(x, Y + 1, z, Blocks.stone, 0, 2);
                    w.setBlock(x, Y + 2, z, Blocks.stone, 0, 2);
                }
            }
        }
        w.setBlock(bx, Y, bz, ModContentSVA.charger, com.sc.energy.Tier.MV.ordinal(), 3);
        TileEntity te = w.getTileEntity(bx, Y, bz);
        check(te instanceof TileEntityChargerSVA, "charger placed with its tile");
        charger = te instanceof TileEntityChargerSVA ? (TileEntityChargerSVA) te : null;
        if (charger != null) {
            charger.fillForTest(charger.getMaxEnergyStored());
        }
        ItemStack empty = new ItemStack(ModContentSVA.vehicle, 1, VehicleType.BUGGY.ordinal());
        onCharger = ItemVehicleSVA.spawn(w, empty, bx + 0.5, Y + 1, bz + 0.5, 0F, OWNER, "WorldTest");
        check(onCharger != null, "empty buggy placed on the charger");
        VehicleState full = new VehicleState(VehicleType.BUGGY);
        full.energy = full.maxEnergy();
        ItemStack fullStack = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, 0), full);
        startX = bx + 12.5;
        driven = ItemVehicleSVA.spawn(w, fullStack, startX, Y + 1, bz + 10.5, -90F, OWNER, "WorldTest");   // facing +x
        check(driven != null, "full buggy placed on the track");
        crasher = ItemVehicleSVA.spawn(w, ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, 0), full),
                bx + 46.5, Y + 1, bz + 18.5, -90F, OWNER, "WorldTest");
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        check(reg != null && reg.count(OWNER) == 3, "registry counts 3 placed vehicles for the owner (" + (reg == null ? -1 : reg.count(OWNER)) + ")");
        if (driven != null) {
            driven.debugDrive(EntityVehicleSVA.IN_FWD);          // the same server input path as the Input packet
        }
        if (crasher != null) {
            crasher.debugDrive(EntityVehicleSVA.IN_FWD);         // into the wall at x + 60
        }
    }

    private void verify(World w) {
        if (onCharger != null) {
            int eu = (int) onCharger.state.energy;
            check(eu >= 128 * 40 && eu <= 128 * 101, "charger charged the buggy on top: " + eu + " EU in ~100 ticks (MV 128 EU/t)");
            check(charger != null && charger.isActive(), "charger shows active while charging");
        }
        if (driven != null) {
            double moved = driven.posX - startX;
            double spent = driven.state.maxEnergy() - driven.state.energy;
            check(moved > 15, "driven buggy moved forward " + Math.round(moved * 10) / 10.0 + " blocks in ~100 ticks");
            check(spent > 100, "driving spent " + Math.round(spent) + " EU");
            check(driven.state.heat > 0, "driving warmed the motor to " + Math.round(driven.state.heat * 10) / 10.0 + "%");
            check(Math.abs(driven.posZ - (bz + 10.5)) < 0.5, "drove straight (z drift " + Math.round(Math.abs(driven.posZ - (bz + 10.5)) * 100) / 100.0 + ")");
            driven.debugDrive(0);
            // wrench pick-up path: entity -> item (what pickUp gives the player), then place again
            driven.setInventorySlotContents(0, new ItemStack(Items.diamond, 7));
            double eu = driven.state.energy;
            String id = driven.state.vehId;
            ItemStack item = driven.toItemStack();
            driven.setDead();
            VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
            check(reg.count(OWNER) == 2, "picked up: the registry forgets it (" + reg.count(OWNER) + ")");
            VehicleState st = ItemVehicleSVA.stateOf(item);
            check(st.energy == eu && st.vehId.equals(id) && st.cargo[0] != null && st.cargo[0].stackSize == 7 && OWNER.equals(st.ownerId),
                    "item keeps EU " + Math.round(eu) + ", id, cargo and owner");
            replaced = ItemVehicleSVA.spawn(w, item, bx + 40.5, Y + 1, bz + 10.5, 0F, OWNER, "WorldTest");
            check(replaced != null && replaced.state.energy == eu && replaced.getStackInSlot(0) != null && reg.count(OWNER) == 3,
                    "placed again with the same EU and cargo");
            if (replaced != null) {
                clientMoves(w, replaced);
            }
            int max = ConfigSVA.maxPerPlayer;
            ConfigSVA.maxPerPlayer = 3;
            check(!VehicleMath.limitAllows(reg.count(OWNER), ConfigSVA.maxPerPlayer), "maxPerPlayer 3 refuses a fourth vehicle");
            ConfigSVA.maxPerPlayer = max;
        }
        if (crasher != null && ConfigSVA.wear) {
            check(crasher.state.hp < VehicleType.BUGGY.maxHp && crasher.posX < bx + 60 - 1.0,
                    "wall hit at speed: hull " + crasher.state.hp + " HP, stopped at x+" + Math.round((crasher.posX - bx) * 10) / 10.0);
            crasher.debugDrive(0);
        }
        if (onCharger != null && ConfigSVA.wear) {
            float hp = onCharger.state.hp;
            onCharger.attackEntityFrom(DamageSource.generic, 30F);
            check(onCharger.state.hp == hp - 30F, "a 30-point hit takes 30 HP");
            onCharger.damageHull(1000F);
            check(onCharger.isWrecked() && !onCharger.canThrust(), "0 HP: wrecked, the motor can't push");
            onCharger.state.hp = VehicleMath.repair(onCharger.state.hp, VehicleType.BUGGY.maxHp);
            check(!onCharger.isWrecked(), "one repair step lifts the wreck");
        }
    }

    /** The driver-client path: Move packets checked by the server (a fake player sits in the driver's seat). */
    private void clientMoves(World w, EntityVehicleSVA v) {
        net.minecraftforge.common.util.FakePlayer owner = net.minecraftforge.common.util.FakePlayerFactory.get((net.minecraft.world.WorldServer) w,
                new com.mojang.authlib.GameProfile(java.util.UUID.fromString(OWNER), "WorldTest"));
        net.minecraftforge.common.util.FakePlayer other = net.minecraftforge.common.util.FakePlayerFactory.get((net.minecraft.world.WorldServer) w,
                new com.mojang.authlib.GameProfile(java.util.UUID.fromString("00000000-5bad-4e57-0000-0000000000ff"), "Stranger"));
        check(v.canManage(owner) && v.canDrive(owner), "owner may drive, pick up and open the cargo");
        check(!v.canManage(other) && !v.canDrive(other), "a stranger may not (passenger seat only)");
        v.riddenByEntity = owner;                   // seated without mountEntity (a fake player has no connection)
        owner.ridingEntity = v;
        double x0 = v.posX, y0 = v.posY, z0 = v.posZ;
        v.serverMove(owner, x0 + 0.4, y0, z0, 10F, 0.4F);
        check(Math.abs(v.posX - (x0 + 0.4)) < 1e-9 && v.rotationYaw == 10F, "move: a normal step is accepted");
        v.serverMove(owner, x0 + 6, y0, z0, 10F, 0.5F);
        check(Math.abs(v.posX - (x0 + 0.4)) < 1e-9, "move: a 6-block jump is refused");
        v.serverMove(owner, x0 + 0.4, y0 + 0.9, z0, 10F, 0.5F);
        check(Math.abs(v.posY - y0) < 1e-9, "move: rising with nothing underneath is refused");
        v.serverMove(owner, x0 + 0.4, y0 - 0.5, z0, 10F, 0.5F);
        check(Math.abs(v.posY - y0) < 1e-9, "move: into the floor is refused");
        v.riddenByEntity = null;
        owner.ridingEntity = null;
    }

    private void cleanup(World w) {
        if (onCharger != null) {
            onCharger.setDead();
        }
        if (replaced != null) {
            replaced.setDead();
        }
        if (crasher != null) {
            crasher.setDead();
        }
        clearArea(w);
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        check(reg.count(OWNER) == 0, "cleanup: no test vehicles left in the registry");
        System.out.println("[SVA-WORLDTEST] SUMMARY passed=" + passed + " failed=" + failed);
        System.out.println("[SVA-WORLDTEST] DONE");
    }

    private void clearArea(World w) {
        for (int x = bx - 3; x <= bx + 75; x++) {
            for (int z = bz - 3; z <= bz + 21; z++) {
                for (int y = Y; y <= Y + 3; y++) {
                    if (!w.isAirBlock(x, y, z)) {
                        w.setBlockToAir(x, y, z);
                    }
                }
            }
        }
    }
}
