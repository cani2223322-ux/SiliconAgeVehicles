package com.sva.debug;

import com.sva.ConfigSVA;
import com.sva.block.TileEntityChargerSVA;
import com.sva.block.TileEntityGasColumnSVA;
import com.sva.vehicle.HoverMath;
import com.sva.block.TileEntityWorkbenchSVA;
import com.sva.entity.EntitySeatSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.HeliMath;
import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.TiltMath;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;
import com.sva.workbench.WorkbenchRecipeSVA;
import com.sva.workbench.WorkbenchRecipesSVA;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

/**
 * -Dsva.worldtest=true (dedicated test server): a charger + a buggy on it, a buggy driven through the server
 * input path, an item round trip, wear and the per-player count; then the Engineering Workbench (a buggy assembled,
 * a wheel crafted, an assembly cancelled) - in the running world, near spawn at Y 230.
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

    /** One [SVA-WORLDTEST] line (package-visible: WorldTestWave1SVA reports through it). */
    void check(boolean ok, String name) {
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
        // round 2, D2: the tests from before the engine start fly at once - every aircraft's engine runs whenever it may
        // and no failure comes at random (WorldTestAirD2SVA flies its own planes through the real sequence)
        EntityVehicleSVA.testInstantEngine = true;
        // hydrogen wave: the tests from before it check the EU behaviour; WorldTestFuelSVA's vehicles force hydrogen
        com.sva.vehicle.FuelMath.legacyTest = true;
        World w = DimensionManager.getWorld(0);
        if (w == null) {
            return;
        }
        if (w instanceof net.minecraft.world.WorldServer) {
            ((net.minecraft.world.WorldServer) w).resetUpdateEntityTick();     // no players: vanilla stops ticking after 60 s
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
            } else if (ticks == WB_START) {
                workbenchSetup(w);
            } else if (ticks == WB_CANCEL) {
                workbenchCancel();
            } else if (ticks == WB_MID) {
                workbenchMid();
            } else if (ticks == WB_CHECK) {
                workbenchCheck();
            } else if (ticks == WB_CLEAN) {
                workbenchClean(w);
            } else if (ticks >= HV_START && hvPhase < 6) {
                hoverTick(w);
            } else if (pfAt > 0 && ticks >= pfAt && pfPhase < PF_DONE) {
                platformTick(w);
            } else if (afAt > 0 && ticks >= afAt && afPhase < AF_DONE) {
                aircraftTick(w);
            } else if (cvAt > 0 && ticks >= cvAt && cvPhase < CV_DONE) {
                stageCTick(w);
            } else if (sdAt > 0 && ticks >= sdAt && sdPhase < SD_DONE) {
                stageDTick(w);
            }
        } catch (Throwable t) {
            failed++;
            System.out.println("[SVA-WORLDTEST] FAIL exception " + t);

            t.printStackTrace(System.out);
            if (ticks < CLEAN) {
                ticks = CLEAN - 1;
            } else if (ticks > WB_START && ticks < WB_CLEAN) {
                ticks = WB_CLEAN - 1;
            } else if (ticks >= HV_START && hvPhase < 6) {
                try {
                    finish(w);
                } catch (Throwable t2) {
                    hvPhase = 6;
                    pfAt = ticks + 20;
                }
            } else if (pfAt > 0 && pfPhase < PF_DONE) {
                platformFinish(w);
            } else if (afAt > 0 && afPhase < AF_DONE) {
                aircraftFinish(w);
            } else if (cvAt > 0 && cvPhase < CV_DONE) {
                stageCFinish(w);
            } else if (sdAt > 0 && sdPhase < SD_DONE) {
                stageDFinish(w);
            }
        }
        // wave 1 (roads, cruise, traffic light) runs alongside too, in its own area with its own owner
        if (ticks >= WorldTestWave1SVA.START && !wave1.done) {
            try {
                wave1.tick(w, ticks);
            } catch (Throwable t) {
                failed++;
                System.out.println("[SVA-WORLDTEST] FAIL wave 1 exception " + t);
                t.printStackTrace(System.out);
                wave1.abort(w);
            }
            if (wave1.done) {
                maybeSummary();
            }
        }
        // wave 2 (garage, tuning, locks) runs alongside too, in its own area with its own owner
        if (ticks >= WorldTestWave2SVA.START && !wave2.done) {
            try {
                wave2.tick(w, ticks);
            } catch (Throwable t) {
                failed++;
                System.out.println("[SVA-WORLDTEST] FAIL wave 2 exception " + t);
                t.printStackTrace(System.out);
                wave2.abort(w);
            }
            if (wave2.done) {
                maybeSummary();
            }
        }
        // wave 3 (trailers, the vehicle registry / map) runs alongside too, in its own area with its own owner
        if (ticks >= WorldTestWave3SVA.START && !wave3.done) {
            try {
                wave3.tick(w, ticks);
            } catch (Throwable t) {
                failed++;
                System.out.println("[SVA-WORLDTEST] FAIL wave 3 exception " + t);
                t.printStackTrace(System.out);
                wave3.abort(w);
            }
            if (wave3.done) {
                maybeSummary();
            }
        }
        // fast charging (2026-10-10) runs alongside too, in its own area with its own owner
        if (ticks >= WorldTestFastChargeSVA.START && !fastCharge.done) {
            try {
                fastCharge.tick(w, ticks);
            } catch (Throwable t) {
                failed++;
                System.out.println("[SVA-WORLDTEST] FAIL fast charge exception " + t);
                t.printStackTrace(System.out);
                fastCharge.abort(w);
            }
            if (fastCharge.done) {
                maybeSummary();
            }
        }
        // wave 4 (★15 рекуперация: braking, a hill) runs alongside too, in its own area with its own owner
        if (ticks >= WorldTestRegenSVA.START && !regen.done) {
            try {
                regen.tick(w, ticks);
            } catch (Throwable t) {
                failed++;
                System.out.println("[SVA-WORLDTEST] FAIL regen exception " + t);
                t.printStackTrace(System.out);
                regen.abort(w);
            }
            if (regen.done) {
                maybeSummary();
            }
        }
        // the driver-client path (2026-10-10: the stall flag in level flight, reversing) - alongside, its own area and owner
        if (ticks >= WorldTestDriveSVA.START && !drive.done) {
            try {
                drive.tick(w, ticks);
            } catch (Throwable t) {
                failed++;
                System.out.println("[SVA-WORLDTEST] FAIL drive exception " + t);
                t.printStackTrace(System.out);
                drive.abort(w);
            }
            if (drive.done) {
                maybeSummary();
            }
        }
        // aircraft stage A (flaps, spoilers, protection, airbrake) - alongside, its own area and owner
        if (ticks >= WorldTestAirSVA.START && !air.done) {
            try {
                air.tick(w, ticks);
            } catch (Throwable t) {
                failed++;
                System.out.println("[SVA-WORLDTEST] FAIL air exception " + t);
                t.printStackTrace(System.out);
                air.abort(w);
            }
            if (air.done) {
                maybeSummary();
            }
        }
        // aircraft stage C (the autopilot's modes, runway capture) - alongside, its own area and owner
        if (ticks >= WorldTestAirCSVA.START && !airC.done) {
            try {
                airC.tick(w, ticks);
            } catch (Throwable t) {
                failed++;
                System.out.println("[SVA-WORLDTEST] FAIL air C exception " + t);
                t.printStackTrace(System.out);
                airC.abort(w);
            }
            if (airC.done) {
                maybeSummary();
            }
        }
        // aircraft round 2, D1 (PAPI, the designator, approach lights, ЗАХВ's phases) - alongside, its own area and owner
        if (ticks >= WorldTestAirD1SVA.START && !airD1.done) {
            try {
                airD1.tick(w, ticks);
            } catch (Throwable t) {
                failed++;
                System.out.println("[SVA-WORLDTEST] FAIL air D1 exception " + t);
                t.printStackTrace(System.out);
                airD1.abort(w);
            }
            if (airD1.done) {
                maybeSummary();
            }
        }
        // aircraft round 2, D2 (engine start, switches, failures, the garage's repair) - alongside, its own area and owner
        if (ticks >= WorldTestAirD2SVA.START && !airD2.done) {
            try {
                airD2.tick(w, ticks);
            } catch (Throwable t) {
                failed++;
                System.out.println("[SVA-WORLDTEST] FAIL air D2 exception " + t);
                t.printStackTrace(System.out);
                airD2.abort(w);
            }
            if (airD2.done) {
                maybeSummary();
            }
        }
        // aircraft round 2, D3 (the gear doors' sequence, the struts on touchdown) - alongside, its own area and owner
        if (ticks >= WorldTestAirD3SVA.START && !airD3.done) {
            try {
                airD3.tick(w, ticks);
            } catch (Throwable t) {
                failed++;
                System.out.println("[SVA-WORLDTEST] FAIL air D3 exception " + t);
                t.printStackTrace(System.out);
                airD3.abort(w);
            }
            if (airD3.done) {
                maybeSummary();
            }
        }
        // aircraft round 2, D4 (the flight plan: МАРШ flies a 3-point plan) - alongside, its own area and owner
        if (ticks >= WorldTestAirD4SVA.START && !airD4.done) {
            try {
                airD4.tick(w, ticks);
            } catch (Throwable t) {
                failed++;
                System.out.println("[SVA-WORLDTEST] FAIL air D4 exception " + t);
                t.printStackTrace(System.out);
                airD4.abort(w);
            }
            if (airD4.done) {
                maybeSummary();
            }
        }
        // hydrogen wave (fuel, the removable battery) - alongside, its own area and owner
        if (ticks >= WorldTestFuelSVA.START && !fuel.done) {
            try {
                fuel.tick(w, ticks);
            } catch (Throwable t) {
                failed++;
                System.out.println("[SVA-WORLDTEST] FAIL fuel exception " + t);
                t.printStackTrace(System.out);
                fuel.abort(w);
            }
            if (fuel.done) {
                maybeSummary();
            }
        }
        // stage E runs alongside the other phases (its own area and test owner); an exception ends only its own branch
        if (seAt > 0 && ticks >= seAt && sePhase < SE_DONE) {
            try {
                stageETick(w);
            } catch (Throwable t) {
                failed++;
                System.out.println("[SVA-WORLDTEST] FAIL stage E exception " + t);
                t.printStackTrace(System.out);
                try {
                    stageEFinish(w);
                } catch (Throwable t2) {
                    sePhase = SE_DONE;
                    eDone = true;
                    maybeSummary();
                }
            }
        }
    }

    private void setup(World w) {
        purgeLeftovers(w, null);
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
    // ------------------------------------------------------------------ the workbench (phase 2)

    private static final int WB_START = 200, WB_MID = WB_START + 300, WB_CANCEL = WB_START + 40, WB_CHECK = WB_START + 640,
            WB_CLEAN = WB_CHECK + 10;
    private TileEntityWorkbenchSVA wbBuggy, wbWheel, wbCancel;
    private int euBefore;
    private ItemStack removed;

    private TileEntityWorkbenchSVA placeWorkbench(World w, int x, int z) {
        w.setBlock(x, Y, z, ModContentSVA.workbench, 0, 3);
        TileEntity te = w.getTileEntity(x, Y, z);
        return te instanceof TileEntityWorkbenchSVA ? (TileEntityWorkbenchSVA) te : null;
    }

    /** The recipe's layout into a workbench grid, `n` of each item per cell. */
    private static void lay(TileEntityWorkbenchSVA wb, WorkbenchRecipeSVA r, int n) {
        ItemStack[] g = SelfTestSVA.laid(r, (5 - r.width) / 2, (5 - r.height) / 2, false);
        for (int i = 0; i < g.length; i++) {
            if (g[i] != null) {
                g[i].stackSize = n;
            }
            wb.setInventorySlotContents(i, g[i]);
        }
    }

    private static int gridItems(TileEntityWorkbenchSVA wb) {
        int n = 0;
        for (int i = 0; i < TileEntityWorkbenchSVA.GRID_SLOTS; i++) {
            ItemStack s = wb.getStackInSlot(i);
            n += s == null ? 0 : s.stackSize;
        }
        return n;
    }

    private void workbenchSetup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        bx = sp.posX + 4;
        bz = sp.posZ + 4;
        clearArea(w);
        WorkbenchRecipeSVA buggy = WorkbenchRecipesSVA.byId("buggy");
        wbBuggy = placeWorkbench(w, bx, bz);
        wbWheel = placeWorkbench(w, bx + 2, bz);
        wbCancel = placeWorkbench(w, bx + 4, bz);
        check(wbBuggy != null && wbWheel != null && wbCancel != null, "workbench: three placed with their tiles");
        if (wbBuggy == null || wbWheel == null || wbCancel == null) {
            return;
        }
        check(wbBuggy.getMaxEnergyStored() == 2000000 && wbBuggy.inputTier() == com.sc.energy.Tier.MV, "workbench: MV input, 2M buffer");
        // the buggy: two of everything per cell, so a single consumption leaves exactly one
        wbBuggy.fillForTest(wbBuggy.getMaxEnergyStored());
        lay(wbBuggy, buggy, 2);
        euBefore = wbBuggy.getEnergyStored();
        check(wbBuggy.instantPreview() == null, "workbench: a vehicle shows no instant product");
        int r = wbBuggy.start();
        check(r == TileEntityWorkbenchSVA.OK && wbBuggy.isAssembling() && wbBuggy.getTotalTicks() == 600,
                "workbench: buggy assembly started (" + r + ", " + wbBuggy.getTotalTicks() + " ticks)");
        check(wbBuggy.start() == TileEntityWorkbenchSVA.BUSY, "workbench: a second start is refused");
        check(gridItems(wbBuggy) == 46, "workbench: grid untouched at the start (" + gridItems(wbBuggy) + ")");
        // the wheel: a component taken at once, the 3x3 in the top-left corner
        WorkbenchRecipeSVA wheel = WorkbenchRecipesSVA.byId("wheel");
        ItemStack[] g = SelfTestSVA.laid(wheel, 0, 0, false);
        for (int i = 0; i < g.length; i++) {
            wbWheel.setInventorySlotContents(i, g[i]);
        }
        ItemStack prev = wbWheel.instantPreview();
        check(prev != null && prev.getItem() == ModContentSVA.component && prev.stackSize == 4, "workbench: wheel x4 shown in the output");
        check(wbWheel.start() == TileEntityWorkbenchSVA.INSTANT, "workbench: Assemble refuses a component (instant)");
        ItemStack made = wbWheel.craftInstant(null);
        check(made != null && made.stackSize == 4 && com.sva.item.ItemComponentSVA.typeOf(made) == com.sva.item.ComponentType.WHEEL,
                "workbench: wheel crafted x4");
        check(gridItems(wbWheel) == 0 && wbWheel.instantPreview() == null, "workbench: the wheel's 9 inputs consumed");
        check(wbWheel.getEnergyStored() == 0, "workbench: the component took no EU");
        // the one to cancel
        wbCancel.fillForTest(wbCancel.getMaxEnergyStored());
        lay(wbCancel, buggy, 1);
        check(wbCancel.start() == TileEntityWorkbenchSVA.OK, "workbench: second buggy started (to be cancelled)");
    }

    private void workbenchCancel() {
        if (wbCancel == null) {
            return;
        }
        int before = gridItems(wbCancel);
        check(wbCancel.getProgress() > 0, "workbench: progress after 40 ticks (" + wbCancel.getProgress() + ")");
        removed = wbCancel.decrStackSize(12, 1);             // the player takes the computer out
        wbCancel.updateEntity();
        check(!wbCancel.isAssembling() && wbCancel.getStackInSlot(TileEntityWorkbenchSVA.OUTPUT) == null,
                "workbench: a changed grid cancels the assembly, no product");
        check(removed != null && gridItems(wbCancel) == before - 1, "workbench: nothing else lost (" + gridItems(wbCancel) + " of " + (before - 1) + ")");
    }

    private void workbenchMid() {
        if (wbBuggy == null) {
            return;
        }
        int sig = wbBuggy.comparatorSignal();
        check(wbBuggy.isAssembling() && sig > 0 && sig < 15, "workbench: halfway, comparator " + sig);
        check(gridItems(wbBuggy) == 46, "workbench: grid still untouched halfway");
    }

    private void workbenchCheck() {
        if (wbBuggy == null) {
            return;
        }
        ItemStack out = wbBuggy.getStackInSlot(TileEntityWorkbenchSVA.OUTPUT);
        check(!wbBuggy.isAssembling() && out != null && out.getItem() == ModContentSVA.vehicle && out.getItemDamage() == VehicleType.BUGGY.ordinal(),
                "workbench: a buggy in the output");
        if (out != null) {
            VehicleState st = ItemVehicleSVA.stateOf(out);
            check(out.hasTagCompound() && out.getTagCompound().hasKey(EntityVehicleSVA.NBT_ROOT) && st.energy == 0
                    && st.hp == VehicleType.BUGGY.maxHp && st.cargoUsed() == 0 && st.vehId != null && st.vehId.length() > 0,
                    "workbench: the buggy carries full default NBT");
        }
        boolean once = true;
        for (int i = 0; i < TileEntityWorkbenchSVA.GRID_SLOTS; i++) {
            ItemStack s = wbBuggy.getStackInSlot(i);
            Object want = WorkbenchRecipesSVA.byId("buggy").layout()[i];
            once &= want == null ? s == null : s != null && s.stackSize == 1;
        }
        check(once && gridItems(wbBuggy) == 23, "workbench: the grid paid exactly once (" + gridItems(wbBuggy) + " left of 46)");
        int spent = euBefore - wbBuggy.getEnergyStored();
        check(spent == 200000, "workbench: spent " + spent + " EU (200 000)");
        check(wbBuggy.comparatorSignal() == 0, "workbench: comparator back to 0");
        check(wbBuggy.start() == TileEntityWorkbenchSVA.OUTPUT_FULL, "workbench: a full output refuses the next start");
        // save / load keeps the grid and the output
        net.minecraft.nbt.NBTTagCompound t = new net.minecraft.nbt.NBTTagCompound();
        wbBuggy.writeToNBT(t);
        TileEntityWorkbenchSVA copy = new TileEntityWorkbenchSVA();
        copy.readFromNBT(t);
        check(gridItems(copy) == 23 && copy.getStackInSlot(TileEntityWorkbenchSVA.OUTPUT) != null, "workbench: NBT round trip keeps grid and output");
    }

    private void workbenchClean(World w) {
        for (TileEntityWorkbenchSVA wb : new TileEntityWorkbenchSVA[] {wbBuggy, wbWheel, wbCancel}) {
            if (wb != null) {
                for (int i = 0; i < wb.getSizeInventory(); i++) {
                    wb.setInventorySlotContents(i, null);       // no drops
                }
            }
        }
        clearArea(w);
    }
    // ------------------------------------------------------------------ the hoverbike, gases, the Gas Column, pick-up (phase 3)

    private static final int HV_START = WB_CLEAN + 50, HV_RISE = HV_START + 25, HV_DRIVE_END = HV_START + 200;
    private static final int HV_Z = 33;
    private EntityVehicleSVA hover;
    private int hvStop = -1, liftTicks, hvPhase;
    private double minOverWater = 99, maxOverSurface = -99;
    private int h2AtStart, waterTicks;
    private double euAtStart;
    private TileEntityGasColumnSVA column;
    private int columnH2Before, bikeH2Before, columnAt;

    private static final com.sc.util.ArmorGasSC.Gas H2 = com.sc.util.ArmorGasSC.Gas.HYDROGEN, HE = com.sc.util.ArmorGasSC.Gas.HELIUM,
            AR = com.sc.util.ArmorGasSC.Gas.ARGON;

    private void hoverZone(World w, boolean build) {
        int z0 = bz + HV_Z - 3, z1 = bz + HV_Z + 3;
        for (int x = bx - 2; x <= bx + 96; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = Y - 1; y <= Y + 6; y++) {
                    if (!w.isAirBlock(x, y, z)) {
                        w.setBlockToAir(x, y, z);
                    }
                }
            }
        }
        if (!build) {
            return;
        }
        for (int x = bx; x <= bx + 95; x++) {
            for (int z = bz + HV_Z - 2; z <= bz + HV_Z + 2; z++) {
                boolean water = x >= bx + 20 && x <= bx + 60 && Math.abs(z - (bz + HV_Z)) <= 1;
                if (water) {
                    w.setBlock(x, Y - 1, z, Blocks.stone, 0, 2);
                    w.setBlock(x, Y, z, Blocks.water, 0, 2);
                } else {
                    w.setBlock(x, Y, z, Blocks.stone, 0, 2);
                }
            }
        }
    }

    private void hoverTick(World w) {
        if (ticks == HV_START) {
            hoverSetup(w);
            return;
        }
        if (hover == null) {
            if (hvPhase == 5 && ticks == columnAt + 80 || hvPhase < 5 && ticks == HV_DRIVE_END + 400) {
                finish(w);
            }
            return;
        }
        if (hover.isLifting()) {
            liftTicks++;
        }
        if (ticks == HV_RISE) {
            double h = hover.posY - (Y + 1);
            check(h >= 1.0 && h <= 3.0 && hover.isLifting(), "hover: rose to " + Math.round(h * 100) / 100.0 + " blocks over the floor (1-3)");
        }
        if (hvPhase == 0) {
            double[] s = com.sva.entity.HoverPhysicsSVA.surface(w, hover.posX, hover.posY, hover.posZ);
            if (!Double.isNaN(s[0]) && ticks > HV_RISE) {
                maxOverSurface = Math.max(maxOverSurface, hover.posY - s[0]);
            }
            if (s[1] > 0) {
                waterTicks++;
                minOverWater = Math.min(minOverWater, hover.posY - s[0]);
            }
            if (hover.posX > bx + 72 || ticks >= HV_DRIVE_END) {
                hoverOverWaterCheck();
                hover.speed = 0;
                hover.serverInput(0);                    // still at the wheel (debugDriver), just let go
                hvPhase = 1;
                hvStop = ticks;
                hover.state.heat = 80;
            }
            return;
        }
        if (hvPhase == 1 && ticks == hvStop + 60) {
            check(hover.state.gas(HE) < 2000 && hover.state.heat < 80, "helium: cooled the hot motor (He " + hover.state.gas(HE)
                    + " mB, heat " + Math.round(hover.state.heat) + "%)");
            hover.state.gas[H2.ordinal()] = 2;            // run it dry
            hover.state.gasFrac[H2.ordinal()] = 0;
            hvPhase = 2;
        } else if (hvPhase == 2 && ticks == hvStop + 160) {
            double h = hover.posY - (Y + 1);
            check(hover.state.gas(H2) == 0 && !hover.isLifting(), "hover: out of H2, no lift");
            check(h >= -0.01 && h < 0.1 && hover.onGround, "hover: landed on the floor (" + Math.round(h * 100) / 100.0 + ")");
            check(hover.state.hp == VehicleType.HOVER.maxHp, "hover: no landing damage (" + hover.state.hp + " HP)");
            columnSetup(w);
            hvPhase = 3;
        } else if (hvPhase == 3 && ticks == columnAt + 60) {
            columnCheck(w);
            hvPhase = 4;
        } else if (hvPhase == 4 && ticks == columnAt + 70) {
            handAndPickUp(w);
            hvPhase = 5;
        }
    }

    private void hoverSetup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        bx = sp.posX + 4;
        bz = sp.posZ + 4;
        hoverZone(w, true);
        VehicleState st = new VehicleState(VehicleType.HOVER);
        st.energy = st.maxEnergy();
        st.fillAllGases();
        ItemStack stack = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.HOVER.ordinal()), st);
        hover = ItemVehicleSVA.spawn(w, stack, bx + 3.5, Y + 1, bz + HV_Z + 0.5, -90F, OWNER, "WorldTest");
        check(hover != null && hover.state.gas(H2) == 4000 && hover.state.gas(HE) == 2000, "hover: placed full (H2 4000, He 2000)");
        if (hover == null) {
            return;
        }
        h2AtStart = hover.state.gas(H2);
        euAtStart = hover.state.energy;
        hover.debugDrive(EntityVehicleSVA.IN_FWD);
    }

    private void hoverOverWaterCheck() {
        check(hover.posX > bx + 62, "hover: crossed the 40-block pool (x+" + Math.round(hover.posX - bx) + ")");
        check(waterTicks > 20 && minOverWater >= 1.0, "hover: stayed over the water, lowest " + Math.round(minOverWater * 100) / 100.0
                + " blocks above it (" + waterTicks + " ticks)");
        check(maxOverSurface <= HoverMath.HOVER_MAX + 0.05, "hover: never higher than 3 blocks (" + Math.round(maxOverSurface * 100) / 100.0 + ")");
        int spent = h2AtStart - hover.state.gas(H2);
        check(liftTicks > 50 && spent >= (int) (liftTicks * 0.1) - 1 && spent <= (int) (liftTicks * 0.1) + 1,
                "hover: H2 " + spent + " mB for " + liftTicks + " ticks up (2 mB/s)");
        check(euAtStart - hover.state.energy > 1000, "hover: spent " + Math.round(euAtStart - hover.state.energy) + " EU");
    }

    private void columnSetup(World w) {
        int cx = MathHelper.floor_double(hover.posX), cz = bz + HV_Z + 3;
        w.setBlock(cx, Y + 1, cz, ModContentSVA.gasColumn, 2, 3);
        TileEntity te = w.getTileEntity(cx, Y + 1, cz);
        column = te instanceof TileEntityGasColumnSVA ? (TileEntityGasColumnSVA) te : null;
        check(column != null, "column: placed with its tile");
        columnAt = ticks;
        if (column == null) {
            return;
        }
        column.setOwner("00000000-5bad-4e57-0000-0000000000ff", "Stranger");
        check(!column.serves(hover), "column: a stranger's column doesn't serve the bike");
        column.setOwner(OWNER, "WorldTest");
        check(column.serves(hover), "column: the owner's column does");
        net.minecraftforge.fluids.Fluid h2 = H2.fluidOf(), he = HE.fluidOf();
        int in = column.fill(net.minecraftforge.common.util.ForgeDirection.UP, new net.minecraftforge.fluids.FluidStack(h2, 8000), true);
        int inHe = column.fill(net.minecraftforge.common.util.ForgeDirection.WEST, new net.minecraftforge.fluids.FluidStack(he, 1000), true);
        int water = column.fill(net.minecraftforge.common.util.ForgeDirection.UP,
                new net.minecraftforge.fluids.FluidStack(net.minecraftforge.fluids.FluidRegistry.WATER, 1000), true);
        check(in == 8000 && inHe == 1000 && water == 0, "column: pipes fill H2 8000 / He 1000 from any side, water refused");
        check(column.drain(net.minecraftforge.common.util.ForgeDirection.UP, 100, true) == null, "column: nothing drains it");
        columnH2Before = column.amount(H2);
        bikeH2Before = hover.state.gas(H2);
    }

    private void columnCheck(World w) {
        if (column == null) {
            return;
        }
        int bike = hover.state.gas(H2), col = column.amount(H2);
        int moved = columnH2Before - col;
        check(moved >= 1000 && bike >= bikeH2Before + moved - 20, "column: refuelled the bike with " + moved + " mB H2 in 3 s (bike " + bike + " mB)");
        check(col + bike <= columnH2Before + bikeH2Before && col + bike >= columnH2Before + bikeH2Before - 20,
                "column: nothing made or lost (" + (col + bike) + " of " + (columnH2Before + bikeH2Before) + ", less what it burnt)");
        check(hover.isLifting(), "hover: lifts again with fresh H2");
        NBTTagCompound t = new NBTTagCompound();
        column.writeToNBT(t);
        TileEntityGasColumnSVA copy = new TileEntityGasColumnSVA();
        copy.readFromNBT(t);
        check(copy.amount(H2) == col && copy.serves(hover), "column: NBT keeps tanks and owner");
    }

    private void handAndPickUp(World w) {
        net.minecraftforge.common.util.FakePlayer owner = net.minecraftforge.common.util.FakePlayerFactory.get((net.minecraft.world.WorldServer) w,
                new com.mojang.authlib.GameProfile(java.util.UUID.fromString(OWNER), "WorldTest"));
        net.minecraftforge.common.util.FakePlayer other = net.minecraftforge.common.util.FakePlayerFactory.get((net.minecraft.world.WorldServer) w,
                new com.mojang.authlib.GameProfile(java.util.UUID.fromString("00000000-5bad-4e57-0000-0000000000ff"), "Stranger"));
        owner.setPosition(hover.posX + 2, hover.posY, hover.posZ);
        other.setPosition(hover.posX + 2, hover.posY, hover.posZ);
        // (a) a full hydrogen container right-clicked on the bike - by a stranger (anyone may refuel)
        ItemStack filled = null;
        int amount = 0;
        for (net.minecraftforge.fluids.FluidContainerRegistry.FluidContainerData d
                : net.minecraftforge.fluids.FluidContainerRegistry.getRegisteredFluidContainerData()) {
            if (d.fluid != null && d.fluid.getFluid() == H2.fluidOf() && d.filledContainer != null) {
                filled = d.filledContainer.copy();
                amount = d.fluid.amount;
                break;
            }
        }
        hover.debugDrive(0);
        hover.state.gas[H2.ordinal()] = 100;
        if (filled != null) {
            ItemStack keep = other.inventory.getStackInSlot(0);
            other.inventory.currentItem = 0;
            other.inventory.setInventorySlotContents(0, filled);
            hover.interactFirst(other);
            check(hover.state.gas(H2) == 100 + amount, "refuel by hand: a stranger's " + filled.getDisplayName() + " put " + amount + " mB H2 in ("
                    + hover.state.gas(H2) + ")");
            other.inventory.setInventorySlotContents(0, keep);
        } else {
            check(false, "refuel by hand: no filled hydrogen container registered");
        }
        // the cargo screen's pick-up button: occupied / stranger refused, owner gets the item with gas and cargo
        hover.setInventorySlotContents(3, new ItemStack(Items.diamond, 2));
        hover.riddenByEntity = other;
        other.ridingEntity = hover;
        check(hover.tryPickUp(owner) == EntityVehicleSVA.PICK_OCCUPIED && !hover.isDead, "pick-up: refused while someone sits in it");
        hover.riddenByEntity = null;
        other.ridingEntity = null;
        check(hover.tryPickUp(other) == EntityVehicleSVA.PICK_NOT_OWNER && !hover.isDead, "pick-up: a stranger is refused");
        int h2 = hover.state.gas(H2);
        for (int i = 0; i < owner.inventory.mainInventory.length; i++) {
            owner.inventory.mainInventory[i] = null;
        }
        check(hover.tryPickUp(owner) == EntityVehicleSVA.PICK_OK && hover.isDead, "pick-up: the owner picks it up");
        ItemStack got = null;
        for (ItemStack s : owner.inventory.mainInventory) {
            if (s != null && s.getItem() == ModContentSVA.vehicle) {
                got = s;
            }
        }
        VehicleState st = got == null ? null : ItemVehicleSVA.stateOf(got);
        check(st != null && st.type == VehicleType.HOVER && st.gas(H2) == h2 && st.cargo[3] != null && st.cargo[3].stackSize == 2,
                "pick-up: the item in the inventory keeps H2 " + h2 + " mB and the cargo");
        for (int i = 0; i < owner.inventory.mainInventory.length; i++) {
            owner.inventory.mainInventory[i] = null;
        }
        hover = null;
    }

    private void finish(World w) {
        if (hover != null) {
            hover.setDead();
            hover = null;
        }
        hoverZone(w, false);
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        check(reg.count(OWNER) == 0, "hover cleanup: no test vehicles left in the registry");
        hvPhase = 6;
        pfAt = ticks + 20;                                  // the charging platform next
    }

    // ------------------------------------------------------------------ the charging platform (phase 4, plan §9)

    private static final String STRANGER = "00000000-5bad-4e57-0000-0000000000ff";
    private static final int PF_DONE = 99;
    private int pfAt = -1, pfPhase, pfT0;
    private int px, pz;
    private TileEntityChargerSVA station;
    private net.minecraft.tileentity.TileEntityChest pfChest;
    private EntityVehicleSVA pfVeh;
    private int pfH2Col, pfHeCol;
    private float pfHp;

    private void platformZone(World w, boolean build) {
        for (int x = px - 3; x <= px + 12; x++) {
            for (int z = pz - 4; z <= pz + 12; z++) {
                for (int y = Y - 1; y <= Y + 6; y++) {
                    if (!w.isAirBlock(x, y, z)) {
                        w.setBlockToAir(x, y, z);
                    }
                }
            }
        }
        if (!build) {
            return;
        }
        for (int x = px - 2; x <= px + 9; x++) {
            for (int z = pz - 3; z <= pz + 9; z++) {
                w.setBlock(x, Y - 1, z, Blocks.stone, 0, 2);
            }
        }
    }

    private void plate(World w, int x, int z, int meta) {
        w.setBlock(x, Y, z, ModContentSVA.chargePlate, meta, 3);
    }

    private EntityVehicleSVA pfSpawn(World w, VehicleType t, String owner, boolean fullEu) {
        VehicleState st = new VehicleState(t);
        st.energy = fullEu ? st.maxEnergy() : 0;
        ItemStack stack = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, t.ordinal()), st);
        return ItemVehicleSVA.spawn(w, stack, px + 3.5, Y + 1, pz + 0.5, 0F, owner, owner.equals(OWNER) ? "WorldTest" : "Stranger");
    }

    private int steelInChest() {
        int n = 0;
        for (int i = 0; i < pfChest.getSizeInventory(); i++) {
            ItemStack s = pfChest.getStackInSlot(i);
            if (ModContentSVA.isSteelIngot(s)) {
                n += s.stackSize;
            }
        }
        return n;
    }

    private void platformTick(World w) {
        int t = ticks - pfAt;
        if (station != null && pfPhase < 9) {
            station.fillForTest(station.getMaxEnergyStored());          // stands in for a cable feed
        }
        if (t == 0) {
            platformSetup(w);
        } else if (station == null) {
            platformFinish(w);
        } else if (t == 60) {
            pfCharge();
        } else if (t == 90) {
            pfFull();
        } else if (t == 220) {
            pfRepair(w);
        } else if (t == 260) {
            pfAccess();
        } else if (t == 300) {
            pfAccessOff(w);
        } else if (t == 340) {
            pfGasOff();
        } else if (t == 400) {
            pfGas(w);
        } else if (t == 440) {
            pfNoFit();
        } else if (t == 450) {
            platformFinish(w);
        }
    }

    private void platformSetup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        px = sp.posX + 6;
        pz = sp.posZ + 4 + 50;
        platformZone(w, true);
        w.setBlock(px, Y, pz, ModContentSVA.charger, com.sc.energy.Tier.MV.ordinal(), 3);
        TileEntity te = w.getTileEntity(px, Y, pz);
        station = te instanceof TileEntityChargerSVA ? (TileEntityChargerSVA) te : null;
        check(station != null, "platform: station placed with its tile");
        if (station == null) {
            return;
        }
        station.setOwner(OWNER, "WorldTest");
        // 4 x 3 plates east of the station + a 5-plate strip running south from its corner = 17; an edge light, an H plate
        for (int x = px + 1; x <= px + 4; x++) {
            for (int z = pz - 1; z <= pz + 1; z++) {
                plate(w, x, z, x == px + 4 && z == pz - 1 ? com.sva.block.BlockChargePlateSVA.EDGE
                        : x == px + 2 && z == pz ? com.sva.block.BlockChargePlateSVA.AVIA : 0);
            }
        }
        for (int z = pz + 2; z <= pz + 6; z++) {
            plate(w, px + 1, z, 0);
        }
        plate(w, px + 7, pz, 0);                                    // two blocks away - not part of it
        plate(w, px + 5, pz + 2, 0);                                // only diagonal to a plate - not part of it
        w.setBlock(px + 1, Y, pz + 7, ModContentSVA.gasColumn, 2, 3);   // against the strip's end, 7 blocks from the vehicle
        TileEntity ct = w.getTileEntity(px + 1, Y, pz + 7);
        column = ct instanceof TileEntityGasColumnSVA ? (TileEntityGasColumnSVA) ct : null;
        w.setBlock(px - 1, Y, pz, Blocks.chest, 0, 3);              // against the station
        pfChest = (net.minecraft.tileentity.TileEntityChest) w.getTileEntity(px - 1, Y, pz);
        pfChest.setInventorySlotContents(0, com.sc.init.ModItems.ingot.stackOf(com.sc.util.Material.STEEL).copy());
        pfChest.getStackInSlot(0).stackSize = 5;
        station.fillForTest(station.getMaxEnergyStored());
        station.rescanForTest();
        int[] span = station.span();
        check(station.plateCount() == 17 && span[0] == 4 && span[1] == 8,
                "platform: flood fill found 17 plates, 4x8 (disconnected / diagonal ignored): " + station.plateCount() + ", " + span[0] + "x" + span[1]);
        check(station.getEdgePlates() == 1 && station.getAviaPlates() == 1 && station.column() == column && station.chestCount() == 1,
                "platform: 1 edge light, 1 H plate, the gas column and the chest connected");
        check(ModContentSVA.chargePlate.getLightValue(w, px + 4, Y, pz - 1) == 7 && ModContentSVA.chargePlate.getLightValue(w, px + 1, Y, pz) == 0,
                "platform: the edge plate gives light 7, a plain one none");
        pfVeh = pfSpawn(w, VehicleType.BUGGY, OWNER, false);
        check(pfVeh != null && !pfVeh.boundingBox.intersectsWith(station.topZone()) && station.onPlatform(pfVeh),
                "platform: an empty buggy parked on the plates (not touching the station)");
        pfPhase = 1;
    }

    private void pfCharge() {
        if (pfVeh == null) {
            return;
        }
        int eu = (int) pfVeh.state.energy;
        int rate = station.rateFor(station.bonusFor(pfVeh));
        check(rate == 230, "platform: rate 128 x (1 + 8 plates over 9 x 10%) = 230 EU/t (" + rate + ")");
        check(eu >= rate * 35 && eu <= rate * 61, "platform: the buggy on a plate charged " + eu + " EU in 60 ticks");
        check(station.isActive() && station.getCurrent() == pfVeh && (station.getStatus() & TileEntityChargerSVA.S_CHARGING) != 0 && pfVeh.isCharging(),
                "platform: station active, serving the buggy, the HUD flag on");
        int cmp = station.comparatorLevel();
        check(cmp == com.sva.platform.PlatformMath.comparator(true, pfVeh.state.energy / pfVeh.state.maxEnergy()) && cmp >= 1,
                "platform: comparator follows the buggy's charge (" + cmp + ")");
        check(station.redstonePower() == 0, "platform: no redstone while charging");
        pfVeh.state.energy = pfVeh.state.maxEnergy() - 2000;
        pfPhase = 2;
    }

    private void pfFull() {
        World w = station.getWorldObj();
        check(pfVeh.energyRoom() < 1 && station.isFull() && (station.getStatus() & TileEntityChargerSVA.S_FULL) != 0, "platform: the buggy filled up");
        check(station.redstonePower() == 15 && station.comparatorLevel() == 15 && w.isBlockIndirectlyGettingPowered(px, Y + 1, pz),
                "platform: full - strong redstone 15, comparator 15");
        station.toggleSetting(TileEntityChargerSVA.SET_REDSTONE);
        // the repair module + 5 steel ingots in the chest, the hull at 50%
        station.setInventorySlotContents(TileEntityChargerSVA.FIRST_MODULE, ModContentSVA.module.stackOf(com.sva.item.ItemStationModuleSVA.REPAIR));
        pfVeh.state.hp = VehicleType.BUGGY.maxHp * 0.5F;
        pfHp = pfVeh.state.hp;
        pfPhase = 3;
    }

    private void pfRepair(World w) {
        check(station.redstonePower() == 0 && !w.isBlockIndirectlyGettingPowered(px, Y + 1, pz), "platform: «сигнал при полном» off - no redstone");
        if (ConfigSVA.wear) {
            int steel = steelInChest();
            check(pfVeh.state.hp >= pfHp + 2 * VehicleType.BUGGY.maxHp * 0.01F - 0.01F && pfVeh.state.hp < VehicleType.BUGGY.maxHp,
                    "platform: repair module mended the hull " + pfHp + " -> " + pfVeh.state.hp + " HP in 130 ticks (+1%/60 t)");
            check(steel == 4 && station.getRepairPaid() == 10 - Math.round((pfVeh.state.hp - pfHp) / (VehicleType.BUGGY.maxHp * 0.01F)),
                    "platform: one steel ingot taken from the chest (" + steel + " left), " + station.getRepairPaid() + " steps prepaid");
        }
        station.setInventorySlotContents(TileEntityChargerSVA.FIRST_MODULE, null);
        // the access module + a stranger's buggy
        station.setInventorySlotContents(TileEntityChargerSVA.FIRST_MODULE + 1, ModContentSVA.module.stackOf(com.sva.item.ItemStationModuleSVA.ACCESS));
        pfVeh.setDead();
        pfVeh = pfSpawn(w, VehicleType.BUGGY, STRANGER, false);
        pfPhase = 4;
    }

    private void pfAccess() {
        check(pfVeh != null && pfVeh.state.energy == 0 && (station.getStatus() & TileEntityChargerSVA.S_NO_ACCESS) != 0 && station.getServed().isEmpty(),
                "platform: access module - a stranger's buggy is not served (" + (pfVeh == null ? -1 : (int) pfVeh.state.energy) + " EU)");
        station.setInventorySlotContents(TileEntityChargerSVA.FIRST_MODULE + 1, null);
        pfPhase = 5;
    }

    private void pfAccessOff(World w) {
        check(pfVeh.state.energy > 0, "platform: without the module it charges everyone's (" + (int) pfVeh.state.energy + " EU)");
        pfVeh.setDead();
        // the hoverbike with empty tanks, the column full, no gas module yet
        column.setOwner(OWNER, "WorldTest");
        column.fill(net.minecraftforge.common.util.ForgeDirection.UP, new net.minecraftforge.fluids.FluidStack(H2.fluidOf(), 8000), true);
        column.fill(net.minecraftforge.common.util.ForgeDirection.UP, new net.minecraftforge.fluids.FluidStack(HE.fluidOf(), 1000), true);
        pfVeh = pfSpawn(w, VehicleType.HOVER, OWNER, true);
        pfPhase = 6;
    }

    private void pfGasOff() {
        check(pfVeh != null && pfVeh.state.gas(H2) == 0 && pfVeh.state.gas(HE) == 0 && column.amount(H2) == 8000,
                "platform: no gas module - the column 7 blocks away doesn't reach the bike (H2 " + (pfVeh == null ? -1 : pfVeh.state.gas(H2)) + ")");
        station.setInventorySlotContents(TileEntityChargerSVA.FIRST_MODULE + 2, ModContentSVA.module.stackOf(com.sva.item.ItemStationModuleSVA.GAS));
        pfH2Col = column.amount(H2);
        pfHeCol = column.amount(HE);
        pfPhase = 7;
    }

    private void pfGas(World w) {
        int h2 = pfVeh.state.gas(H2), he = pfVeh.state.gas(HE);
        check(h2 >= 25 * 30 && he >= 25 * 30 && pfH2Col - column.amount(H2) == h2 && pfHeCol - column.amount(HE) == he,
                "platform: gas module refuelled the bike from the column through the plates (H2 " + h2 + ", He " + he + " mB in 60 ticks)");
        pfVeh.setDead();
        // a vehicle bigger than the platform: the buggy faked as 7x7
        VehicleType.BUGGY.platformSize = com.sva.vehicle.PlatformSize.P7x7;
        pfVeh = pfSpawn(w, VehicleType.BUGGY, OWNER, false);
        pfPhase = 8;
    }

    private void pfNoFit() {
        check(pfVeh != null && pfVeh.state.energy == 0 && (station.getStatus() & TileEntityChargerSVA.S_NO_FIT) != 0 && station.getServed().isEmpty(),
                "platform: a 7x7 vehicle on a 17-plate 4x8 platform - «не помещается», not charged");
        VehicleType.BUGGY.platformSize = com.sva.vehicle.PlatformSize.P3x3;
        pfPhase = 9;
    }

    private void platformFinish(World w) {
        VehicleType.BUGGY.platformSize = com.sva.vehicle.PlatformSize.P3x3;
        if (pfVeh != null) {
            pfVeh.setDead();
            pfVeh = null;
        }
        if (station != null) {
            for (int i = 0; i < station.getSizeInventory(); i++) {
                station.setInventorySlotContents(i, null);              // no drops
            }
        }
        if (pfChest != null) {
            for (int i = 0; i < pfChest.getSizeInventory(); i++) {
                pfChest.setInventorySlotContents(i, null);
            }
        }
        platformZone(w, false);
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        check(reg.count(OWNER) == 0 && reg.count(STRANGER) == 0, "platform cleanup: no test vehicles left in the registry");
        pfPhase = PF_DONE;
        afAt = ticks + 20;                                      // the aircraft next
    }

    // ------------------------------------------------------------------ aircraft: helicopter and plane (phase 5, stage B)

    private static final int AF_DONE = 99, AY = 150;
    private int afAt = -1, afPhase, afT0, afTicks;
    private int hx, hz, rx0, rz;
    private EntityVehicleSVA heli, plane;
    private net.minecraft.entity.passive.EntityVillager pilot;
    private net.minecraft.entity.passive.EntityPig passenger;
    private int heliInput;
    private double afY0, afEu0, afYMin, afYMax, afSum, afMinVy, afYaw0, afX0, liftX = -1;
    private int afCount;

    /** Clears (and with build lays stone / runway at AY) a box of the test area. */
    private void afBox(World w, int x0, int z0, int x1, int z1, boolean build, int runwayTo) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = AY; y <= AY + 30; y++) {
                    if (!w.isAirBlock(x, y, z)) {
                        w.setBlockToAir(x, y, z);
                    }
                }
                if (build) {
                    if (x <= runwayTo) {
                        w.setBlock(x, AY, z, ModContentSVA.runway, Math.abs(z - rz) == 2 ? 1 | 2 : 2, 2);
                    } else {
                        w.setBlock(x, AY, z, Blocks.stone, 0, 2);
                    }
                }
            }
        }
    }

    private EntityVehicleSVA afSpawn(World w, VehicleType t, double x, double z) {
        VehicleState st = new VehicleState(t);
        st.energy = st.maxEnergy();
        st.fillAllGases();
        return ItemVehicleSVA.spawn(w, ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, t.ordinal()), st),
                x, AY + 1, z, -90F, OWNER, "WorldTest");
    }

    private void aircraftTick(World w) {
        int t = ticks - afAt;
        if (t == 0) {
            heliSetup(w);
            return;
        }
        if (afPhase < 10) {
            heliTick(w, t);
        } else if (afPhase < 20) {
            planeTick(w);
        }
    }

    // ---- the helicopter: up 20 blocks, hover, fly forward, brake, then out of EU - autorotation down with the riders

    private void heliSetup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        hx = sp.posX + 20;
        hz = sp.posZ - 60;
        rz = Integer.MIN_VALUE;
        afBox(w, hx - 20, hz - 22, hx + 50, hz + 22, true, Integer.MIN_VALUE);
        heli = afSpawn(w, VehicleType.HELI, hx + 0.5, hz + 0.5);
        check(heli != null && heli.state.gas(HE) == 1000, "heli: placed on the pad at Y " + (AY + 1) + ", full (3M EU, He 1000)");
        if (heli == null) {
            afPhase = 10;
            return;
        }
        pilot = new net.minecraft.entity.passive.EntityVillager(w);
        pilot.setPosition(heli.posX, heli.posY, heli.posZ);
        w.spawnEntityInWorld(pilot);
        pilot.mountEntity(heli);                             // a villager at the controls (the motor runs; keys from the test)
        afPhase = 1;
        afT0 = ticks;
    }

    private void heliKeys(int in) {
        heliInput = in;
        heli.serverInput(in);                                // the Input packet's path, refreshed every tick
    }

    private void heliTick(World w, int t) {
        if (heli == null) {
            heliFinish(w);
            return;
        }
        heliKeys(heliInput);
        int pt = ticks - afT0;
        double dy = heli.posY - heli.prevPosY;
        if (afPhase == 1) {
            if (pt == 5) {
                EntitySeatSVA seat = heli.freeSeat();
                passenger = new net.minecraft.entity.passive.EntityPig(w);
                passenger.setPosition(heli.posX, heli.posY, heli.posZ);
                w.spawnEntityInWorld(passenger);
                if (seat != null) {
                    passenger.mountEntity(seat);
                }
                check(heli.riddenByEntity == pilot && seat != null && passenger.ridingEntity == seat, "heli: a villager at the controls, a pig in the co-pilot seat");
                afY0 = heli.posY;
                afMinVy = 0;
                heliKeys(EntityVehicleSVA.IN_BRAKE);         // Space: collective up
            } else if (pt > 5) {
                afMinVy = Math.max(afMinVy, dy);
                if (heli.posY >= afY0 + 20) {
                    check(pt - 5 >= 80 && afMinVy <= HeliMath.CLIMB + 1e-6, "heli: vertical take-off, +20 blocks in " + (pt - 5) + " ticks, climb "
                            + Math.round(afMinVy * 200) / 10.0 + " m/s (max 5)");
                    heliKeys(0);                              // neutral collective: hold the height
                    afPhase = 2;
                    afT0 = ticks;
                } else if (pt > 400) {
                    check(false, "heli: did not climb 20 blocks (" + Math.round((heli.posY - afY0) * 10) / 10.0 + ")");
                    heliFinish(w);
                }
            }
        } else if (afPhase == 2) {
            if (pt == 20) {
                afYMin = afYMax = heli.posY;
                afEu0 = heli.state.energy;
            } else if (pt > 20) {
                afYMin = Math.min(afYMin, heli.posY);
                afYMax = Math.max(afYMax, heli.posY);
            }
            if (pt == 50) {
                double perTick = (afEu0 - heli.state.energy) / 30.0;
                afSum = perTick;
                check(afYMax - afYMin < 0.3 && heli.isLifting() && !heli.isAutorotating(), "heli: hovers (height held within "
                        + Math.round((afYMax - afYMin) * 100) / 100.0 + ")");
                check(perTick > 100 && perTick < 120, "heli: hovering costs " + Math.round(perTick) + " EU/t (110)");
                heliKeys(EntityVehicleSVA.IN_FWD);
                afX0 = heli.posX;
                afPhase = 3;
                afT0 = ticks;
            }
        } else if (afPhase == 3) {
            if (pt == 40) {
                afEu0 = heli.state.energy;
            } else if (pt == 50) {
                double perTick = (afEu0 - heli.state.energy) / 10.0;
                check(heli.posX - afX0 > 12 && Math.abs(heli.posY - afYMax) < 1.0, "heli: flew forward " + Math.round(heli.posX - afX0)
                        + " blocks at a steady height");
                check(perTick < afSum && perTick > 80, "heli: forward flight " + Math.round(perTick) + " EU/t, cheaper than hovering (" + Math.round(afSum) + ")");
                heliKeys(EntityVehicleSVA.IN_BACK);          // slow down
                afPhase = 4;
                afT0 = ticks;
            }
        } else if (afPhase == 4) {
            if (pt == 35) {
                heliKeys(0);
                heli.state.energy = 0;                       // the battery runs flat
                afYaw0 = heli.rotationYaw;
                afY0 = heli.posY;
                afSum = 0;
                afCount = 0;
                afPhase = 5;
                afT0 = ticks;
            }
        } else if (afPhase == 5) {
            boolean air = heli.airborneAt(heli.posX, heli.posY, heli.posZ);
            if (pt == 3) {
                check(heli.isAutorotating() && !heli.isLifting(), "heli: out of EU in the air - autorotation");
            }
            if (pt > 25 && air) {
                afSum += dy;
                afCount++;
            }
            if (!air && pt > 5) {
                double vy = afCount > 0 ? afSum / afCount : 0;
                double turned = Math.abs(heli.rotationYaw - afYaw0);
                check(vy < -0.12 && vy > -0.17, "heli: autorotation sink " + Math.round(-vy * 200) / 10.0 + " m/s (3)");
                check(turned > 90, "heli: came down in a spiral (turned " + Math.round(turned) + " deg in " + pt + " ticks)");
                afPhase = 6;
                afT0 = ticks;
            } else if (pt > 600) {
                check(false, "heli: never landed (" + Math.round(heli.posY - AY) + " blocks up)");
                heliFinish(w);
            }
        } else if (afPhase == 6 && pt == 10) {
            float hp = heli.state.hp, max = VehicleType.HELI.maxHp;
            if (ConfigSVA.wear) {
                check(hp < max && hp >= max * 0.9F - 0.01F, "heli: autorotation landing took " + Math.round(max - hp) + " HP (at most 10% of " + Math.round(max) + ")");
            }
            check(Math.abs(heli.posY - (AY + 1)) < 0.05, "heli: landed on the ground (Y " + Math.round(heli.posY * 100) / 100.0 + ")");
            check(pilot.isEntityAlive() && pilot.getHealth() == pilot.getMaxHealth() && passenger.isEntityAlive()
                    && passenger.getHealth() == passenger.getMaxHealth() && pilot.ridingEntity == heli && passenger.ridingEntity instanceof EntitySeatSVA,
                    "heli: both riders alive, unhurt and still seated after falling " + Math.round(afY0 - heli.posY) + " blocks");
            heliFinish(w);
        }
    }

    private void heliFinish(World w) {
        if (pilot != null) {
            pilot.mountEntity(null);
            pilot.setDead();
        }
        if (passenger != null) {
            passenger.mountEntity(null);
            passenger.setDead();
        }
        if (heli != null) {
            heli.setDead();
            heli = null;
        }
        afBox(w, hx - 20, hz - 22, hx + 50, hz + 22, false, Integer.MIN_VALUE);
        planeSetup(w);
    }

    // ---- the plane: take-off run on runway blocks, climb, cruise at Y >= 100, descend, land and brake on stone

    private void planeSetup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        rx0 = sp.posX - 94;
        rz = sp.posZ - 80;
        afBox(w, rx0 - 3, rz - 2, sp.posX + 96, rz + 2, true, rx0 + 30);
        plane = afSpawn(w, VehicleType.PLANE, rx0 + 0.5, rz + 0.5);
        check(plane != null && plane.onRunway(), "plane: placed on the runway (Y " + (AY + 1) + ")");
        afPhase = plane == null ? 20 : 11;
        afT0 = ticks;
        liftX = -1;
        if (plane != null) {
            afX0 = plane.posX;
            plane.debugDrive(EntityVehicleSVA.IN_FWD | EntityVehicleSVA.IN_BRAKE);    // full throttle, stick back
        }
    }

    private void planeTick(World w) {
        if (plane == null || afPhase >= 20) {
            aircraftFinish(w);
            return;
        }
        int pt = ticks - afT0;
        double h = plane.posY - (AY + 1);
        boolean air = plane.airborneAt(plane.posX, plane.posY, plane.posZ);
        if (plane.posX > rx0 + 186 || pt > 900) {
            check(false, "plane: ran out of strip / time (phase " + afPhase + ", x+" + Math.round(plane.posX - rx0) + ")");
            aircraftFinish(w);
            return;
        }
        if (afPhase == 11) {
            if (liftX < 0 && h > 0.05) {
                liftX = plane.posX - afX0;
                double want = PlaneMath.takeoffDistance(VehicleType.PLANE, true);
                check(Math.abs(liftX - want) < 5 && liftX < PlaneMath.takeoffDistance(VehicleType.PLANE, false) * 0.7,
                        "plane: lifted off the runway after " + Math.round(liftX) + " blocks (runway ~" + Math.round(want) + ", without ~"
                                + Math.round(PlaneMath.takeoffDistance(VehicleType.PLANE, false)) + ")");
            }
            if (h >= 6) {
                check(!plane.isStalling() && plane.isLifting(), "plane: climbed 6 blocks in " + pt + " ticks");
                plane.debugDrive(EntityVehicleSVA.IN_FWD);   // level cruise
                afPhase = 12;
                afT0 = ticks;
            }
        } else if (afPhase == 12) {
            if (pt == 12) {
                afEu0 = plane.state.energy;
                afYMin = afYMax = plane.posY;
            } else if (pt > 12) {
                afYMin = Math.min(afYMin, plane.posY);
                afYMax = Math.max(afYMax, plane.posY);
            }
            if (pt == 32) {
                double perTick = (afEu0 - plane.state.energy) / 20.0;
                check(afYMax - afYMin < 0.6 && air && !plane.isStalling(), "plane: level cruise at Y " + Math.round(plane.posY) + " (height held within "
                        + Math.round((afYMax - afYMin) * 100) / 100.0 + ")");
                check(perTick > 35 && perTick < 45, "plane: cruising at Y >= 100 costs " + Math.round(perTick) + " EU/t (50 - 20% = 40)");
                plane.debugDrive(EntityVehicleSVA.IN_DOWN | EntityVehicleSVA.IN_BACK);   // nose down, throttle back
                afPhase = 13;
                afT0 = ticks;
            }
        } else if (afPhase == 13) {
            if (h <= 3) {
                plane.debugDrive(EntityVehicleSVA.IN_BACK);  // flare: level the descent
                afPhase = 14;
                afT0 = ticks;
                afY0 = plane.state.hp;
            }
        } else if (afPhase == 14) {
            if (plane.posY - plane.prevPosY > -0.1 && air) {
                plane.debugDrive(EntityVehicleSVA.IN_DOWN | EntityVehicleSVA.IN_BACK);   // levelled off: settle onto the wheels
            }
            if (!air) {
                check(plane.state.hp > (float) afY0 - 1F, "plane: a gentle wheels landing, no landing damage (" + Math.round(afY0 - plane.state.hp) + " HP)");
                afPhase = 15;
                afT0 = ticks;
            }
        } else if (afPhase == 15) {
            if (plane.speed < 0.01 && pt > 3) {
                float hp = plane.state.hp, max = VehicleType.PLANE.maxHp;
                check(Math.abs(plane.posY - (AY + 1)) < 0.05 && plane.posX < rx0 + 186, "plane: stopped on the strip after a " + pt + "-tick rollout, x+"
                        + Math.round(plane.posX - rx0));
                check(hp > max - 20 && !plane.isWrecked(), "plane: hull " + Math.round(hp) + " / " + Math.round(max) + " (braking on bare stone scuffs a little)");
                aircraftFinish(w);
            }
        }
    }

    private void aircraftFinish(World w) {
        if (heli != null || pilot != null && !pilot.isDead) {
            heliFinish(w);
            return;
        }
        if (plane != null) {
            plane.debugDrive(0);
            plane.setDead();
            plane = null;
        }
        if (rz != Integer.MIN_VALUE && rx0 != 0) {
            ChunkCoordinates sp = w.getSpawnPoint();
            afBox(w, rx0 - 3, rz - 2, sp.posX + 96, rz + 2, false, Integer.MIN_VALUE);
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        check(reg.count(OWNER) == 0, "aircraft cleanup: no test vehicles left in the registry");
        afPhase = AF_DONE;
        cvAt = ticks + 20;                                      // stage C next
    }

    // ------------------------------------------------------------------ stage C: cargo crawler + trailer, drill vehicle

    private static final int CV_DONE = 99, CY = 120, CV_CHECK_AUTO = 320, CV_CHECK_DRILL = 640;
    private int cvAt = -1, cvPhase;
    private int cx0, cz0;
    private EntityVehicleSVA truck, drill, oreDrill;
    private net.minecraft.tileentity.TileEntityChest chestA, chestB;
    private double drillEu0;

    /** Clears (and with floor lays stone at CY) a box of the stage C area, CY .. CY + 8. */
    private void cvBox(World w, int x0, int z0, int x1, int z1, boolean floor) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = CY; y <= CY + 8; y++) {
                    if (w.getTileEntity(x, y, z) instanceof net.minecraft.inventory.IInventory) {
                        net.minecraft.inventory.IInventory inv = (net.minecraft.inventory.IInventory) w.getTileEntity(x, y, z);
                        for (int i = 0; i < inv.getSizeInventory(); i++) {
                            inv.setInventorySlotContents(i, null);       // no chest contents spilled
                        }
                    }
                    if (!w.isAirBlock(x, y, z)) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
                if (floor) {
                    w.setBlock(x, CY, z, Blocks.stone, 0, 2);
                }
            }
        }
    }

    private EntityVehicleSVA cvSpawn(World w, VehicleType t, double x, double z) {
        VehicleState st = new VehicleState(t);
        st.energy = st.maxEnergy();
        st.fillAllGases();
        return ItemVehicleSVA.spawn(w, ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, t.ordinal()), st),
                x, CY + 1, z, -90F, OWNER, "WorldTest");                 // facing +x
    }

    private void stageCTick(World w) {
        int t = ticks - cvAt;
        if (t == 0) {
            stageCSetup(w);
        } else if (t == CV_CHECK_AUTO) {
            autoCheck();
        } else if (t == CV_CHECK_DRILL) {
            drillCheck(w);
            stageCFinish(w);
        }
    }

    private void stageCSetup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        cx0 = sp.posX - 70;
        cz0 = sp.posZ + 40;
        cvPhase = 1;
        // ---- auto-delivery: chest A at x0, chest B 22 blocks east, the route on the ground beside them
        cvBox(w, cx0 - 6, cz0 - 5, cx0 + 30, cz0 + 6, true);
        w.setBlock(cx0, CY + 1, cz0 + 3, Blocks.chest, 0, 3);
        w.setBlock(cx0 + 22, CY + 1, cz0 + 3, Blocks.chest, 0, 3);
        chestA = (net.minecraft.tileentity.TileEntityChest) w.getTileEntity(cx0, CY + 1, cz0 + 3);
        chestB = (net.minecraft.tileentity.TileEntityChest) w.getTileEntity(cx0 + 22, CY + 1, cz0 + 3);
        chestA.setInventorySlotContents(0, new ItemStack(Items.iron_ingot, 64));
        chestA.setInventorySlotContents(5, new ItemStack(Blocks.cobblestone, 32));
        truck = cvSpawn(w, VehicleType.TRUCK, cx0 + 0.5, cz0 + 0.5);
        check(truck != null && truck.getSizeInventory() == 54 && truck.state.gas(HE) == 2000, "crawler: placed (54 cargo, He 2000)");
        if (truck != null) {
            boolean b1 = truck.setRoutePoint(new int[] {cx0, CY + 1, cz0, 0});
            boolean b2 = truck.setRoutePoint(new int[] {cx0 + 22, CY + 1, cz0, 0});
            check(!b1 && b2 && truck.state.routeA[0] == cx0 && truck.state.routeB[0] == cx0 + 22, "auto: coordinator points set A, then B");
            check(truck.setAuto(true) && truck.state.autoState == com.sva.vehicle.AutoDelivery.TO_A, "auto: switched on - heading for A");
        }
        // ---- trailer: hitch, cargo round trip through the pick-up, unhitch
        trailerRoundTrip(w);
        cargoV2World(w);
        // ---- drill: a 13 x 7 x 5 stone hill with ores, a lava pocket, bedrock + a chest at depth 11
        int dx0 = cx0, dz0 = cz0 + 18;
        cvBox(w, dx0 - 6, dz0 - 5, dx0 + 22, dz0 + 5, true);
        for (int x = dx0 + 3; x <= dx0 + 15; x++) {
            for (int z = dz0 - 3; z <= dz0 + 3; z++) {
                for (int y = CY + 1; y <= CY + 5; y++) {
                    w.setBlock(x, y, z, Blocks.stone, 0, 2);
                }
            }
        }
        w.setBlock(dx0 + 5, CY + 1, dz0, Blocks.iron_ore, 0, 2);
        w.setBlock(dx0 + 8, CY + 2, dz0 + 1, Blocks.coal_ore, 0, 2);
        w.setBlock(dx0 + 10, CY + 2, dz0 - 1, Blocks.lava, 0, 2);
        w.setBlock(dx0 + 13, CY + 2, dz0, Blocks.bedrock, 0, 2);
        w.setBlock(dx0 + 13, CY + 3, dz0 - 1, Blocks.chest, 0, 3);
        drill = cvSpawn(w, VehicleType.DRILL, dx0 + 0.5, dz0 + 0.5);
        check(drill != null && drill.getSizeInventory() == 110 && drill.state.gas(AR) == 2000, "drill: placed (108 cargo + head + module, Ar 2000)");
        if (drill != null) {
            ItemStack head = new ItemStack(com.sc.init.ModItems.DRILL_HEADS.get(0));       // steel: 1 b/s, 2000 blocks
            check(drill.isItemValidForSlot(drill.headSlot(), head) && !drill.isItemValidForSlot(drill.headSlot(), new ItemStack(Items.diamond)),
                    "drill: the head slot takes a core drill head only");
            drill.setInventorySlotContents(drill.headSlot(), head);
            drill.storeCargo(new ItemStack(Blocks.torch, 4));
            drill.drillOn = true;
            drillEu0 = drill.state.energy;
            drill.debugDrive(EntityVehicleSVA.IN_FWD);
        }
        // ---- ore only: a stone wall with a coal ore in the face
        int ox0 = cx0, oz0 = cz0 - 14;
        cvBox(w, ox0 - 6, oz0 - 4, ox0 + 10, oz0 + 4, true);
        for (int x = ox0 + 3; x <= ox0 + 5; x++) {
            for (int z = oz0 - 2; z <= oz0 + 2; z++) {
                for (int y = CY + 1; y <= CY + 4; y++) {
                    w.setBlock(x, y, z, Blocks.stone, 0, 2);
                }
            }
        }
        w.setBlock(ox0 + 3, CY + 2, oz0, Blocks.coal_ore, 0, 2);
        oreDrill = cvSpawn(w, VehicleType.DRILL, ox0 + 0.5, oz0 + 0.5);
        if (oreDrill != null) {
            oreDrill.setInventorySlotContents(oreDrill.headSlot(), new ItemStack(com.sc.init.ModItems.DRILL_HEADS.get(0)));
            oreDrill.state.drillMode = com.sva.vehicle.DrillMath.MODE_ORE;
            oreDrill.drillOn = true;
            oreDrill.debugDrive(EntityVehicleSVA.IN_FWD);
        }
    }

    private void trailerRoundTrip(World w) {
        net.minecraftforge.common.util.FakePlayer owner = net.minecraftforge.common.util.FakePlayerFactory.get((net.minecraft.world.WorldServer) w,
                new com.mojang.authlib.GameProfile(java.util.UUID.fromString(OWNER), "WorldTest"));
        net.minecraftforge.common.util.FakePlayer other = net.minecraftforge.common.util.FakePlayerFactory.get((net.minecraft.world.WorldServer) w,
                new com.mojang.authlib.GameProfile(java.util.UUID.fromString("00000000-5bad-4e57-0000-0000000000ff"), "Stranger"));
        EntityVehicleSVA c = cvSpawn(w, VehicleType.TRUCK, cx0 + 12.5, cz0 - 3.0);
        if (c == null) {
            check(false, "trailer: a crawler placed");
            return;
        }
        ItemStack[] tc = new ItemStack[54];
        tc[0] = new ItemStack(Items.diamond, 5);
        tc[40] = new ItemStack(Items.gold_ingot, 17);
        ItemStack trailer = com.sva.item.ItemTrailerSVA.withCargo(new ItemStack(ModContentSVA.trailer), tc);
        check(c.attachTrailer(other, trailer) == com.sva.vehicle.CrawlerMath.NOT_OWNER && !c.hasTrailer(), "trailer: a stranger can't hitch it");
        check(c.attachTrailer(owner, trailer) == com.sva.vehicle.CrawlerMath.OK && c.hasTrailer() && c.getSizeInventory() == 108
                && c.getStackInSlot(54) != null && c.getStackInSlot(54).stackSize == 5 && c.getStackInSlot(94).stackSize == 17,
                "trailer: hitched - 108 slots, its cargo in slots 54..107");
        check(c.attachTrailer(owner, trailer) == com.sva.vehicle.CrawlerMath.ALREADY, "trailer: a second one refused");
        c.setInventorySlotContents(2, new ItemStack(Items.coal, 9));
        ItemStack item = c.toItemStack();
        c.setDead();
        VehicleState st = ItemVehicleSVA.stateOf(item);
        check(st.trailer && st.cargo.length == 108 && st.cargo[54].stackSize == 5 && st.cargo[2].stackSize == 9,
                "trailer: picked up - the item keeps the trailer and all 108 slots");
        EntityVehicleSVA again = ItemVehicleSVA.spawn(w, item, cx0 + 12.5, CY + 1, cz0 - 3.0, -90F, OWNER, "WorldTest");
        check(again != null && again.hasTrailer() && again.getStackInSlot(94) != null && again.getStackInSlot(94).stackSize == 17,
                "trailer: placed again with the trailer and its cargo");
        if (again != null) {
            ItemStack back = again.detachTrailer();
            ItemStack[] bc = com.sva.item.ItemTrailerSVA.cargoOf(back);
            check(!again.hasTrailer() && again.getSizeInventory() == 54 && again.getStackInSlot(2) != null && bc[0].stackSize == 5 && bc[40].stackSize == 17,
                    "trailer: unhitched - 54 slots, the trailer item holds its cargo");
            again.setDead();
        }
    }

    private void autoCheck() {
        if (truck == null) {
            return;
        }
        int iron = 0, cobble = 0;
        for (int i = 0; i < chestB.getSizeInventory(); i++) {
            ItemStack s = chestB.getStackInSlot(i);
            if (s != null && s.getItem() == Items.iron_ingot) {
                iron += s.stackSize;
            } else if (s != null && s.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.cobblestone)) {
                cobble += s.stackSize;
            }
        }
        boolean aEmpty = true;
        for (int i = 0; i < chestA.getSizeInventory(); i++) {
            aEmpty &= chestA.getStackInSlot(i) == null;
        }
        check(iron == 64 && cobble == 32 && aEmpty, "auto: delivered chest A -> chest B 22 blocks away (iron " + iron + ", cobble " + cobble + ")");
        int s = truck.state.autoState;
        check(truck.cargoEmpty() && (s == com.sva.vehicle.AutoDelivery.TO_A || s == com.sva.vehicle.AutoDelivery.LOADING),
                "auto: empty, back to A (state " + s + ", x+" + Math.round((truck.posX - cx0) * 10) / 10.0 + ")");
        check(truck.state.energy < truck.state.maxEnergy() && Math.abs(truck.posZ - (cz0 + 0.5)) < 1.5, "auto: drove on its own EU, kept to the line");
        truck.setAuto(false);
        check(truck.state.autoState == com.sva.vehicle.AutoDelivery.OFF, "auto: switched off");
    }

    private void drillCheck(World w) {
        int dx0 = cx0, dz0 = cz0 + 18;
        if (drill != null) {
            boolean tunnel = true;
            for (int x = dx0 + 3; x <= dx0 + 12; x++) {
                for (int z = dz0 - 1; z <= dz0 + 1; z++) {
                    for (int y = CY + 1; y <= CY + 3; y++) {
                        tunnel &= w.isAirBlock(x, y, z) || w.getBlock(x, y, z) == Blocks.torch;
                    }
                }
            }
            check(tunnel, "drill: a 3x3 tunnel 10 blocks into the stone (front at x+" + Math.round((drill.posX - dx0) * 10) / 10.0 + ", dug "
                    + drill.state.dug + ")");
            check(w.getBlock(dx0 + 8, CY + 2, dz0 + 2) == Blocks.stone && w.getBlock(dx0 + 8, CY + 4, dz0) == Blocks.stone
                    && w.getBlock(dx0 + 8, CY, dz0) == Blocks.stone, "drill: walls, ceiling and floor stay - 3 wide, 3 high");
            check(w.getBlock(dx0 + 13, CY + 2, dz0) == Blocks.bedrock && w.getBlock(dx0 + 13, CY + 3, dz0 - 1) == Blocks.chest,
                    "drill: bedrock and the chest (tile entity) in the way are not broken");
            check(w.getBlock(dx0 + 10, CY + 2, dz0 - 1).getMaterial() != net.minecraft.block.material.Material.lava
                    && countLiquid(w, dx0 + 2, dz0 - 3, dx0 + 15, dz0 + 3) == 0, "drill: the lava pocket was walled up with cobble and dug - no liquid left");
            int cobble = countCargo(drill, net.minecraft.item.Item.getItemFromBlock(Blocks.cobblestone));
            int ironOre = countCargo(drill, net.minecraft.item.Item.getItemFromBlock(Blocks.iron_ore));
            int coal = countCargo(drill, Items.coal);
            check(cobble >= 60 && ironOre >= 1 && coal >= 1, "drill: cargo got cobblestone " + cobble + ", iron ore " + ironOre + ", coal " + coal);
            ItemStack head = drill.state.drillHead;
            check(head != null && head.getItemDamage() == drill.state.dug && drill.state.dug >= 90, "drill: the steel head wore " + (head == null ? -1 : head.getItemDamage())
                    + " for " + drill.state.dug + " blocks");
            double spent = drillEu0 - drill.state.energy;
            check(spent >= drill.state.dug * 53, "drill: spent " + Math.round(spent) + " EU (>= 53 a block of stone)");
            check(drill.posX < dx0 + 13 - 1.0 && drill.posX > dx0 + 9, "drill: stopped at the bedrock (x+" + Math.round((drill.posX - dx0) * 10) / 10.0 + ")");
            check(drill.isFieldOn() && drill.state.gas(AR) <= 2000 - 25, "drill: the argon field runs (Ar " + drill.state.gas(AR) + " mB)");
            if (ConfigSVA.wear) {
                float hp = drill.state.hp;
                drill.damageHull(100F);
                check(Math.abs((hp - drill.state.hp) - 60F) < 0.01F, "drill: a 100-point hit takes 60 through the argon field");
            }
            drill.debugDrive(0);
        }
        if (oreDrill != null) {
            int ox0 = cx0, oz0 = cz0 - 14;
            check(w.isAirBlock(ox0 + 3, CY + 2, oz0) && w.getBlock(ox0 + 3, CY + 1, oz0) == Blocks.stone && w.getBlock(ox0 + 3, CY + 3, oz0) == Blocks.stone
                    && countCargo(oreDrill, Items.coal) >= 1 && oreDrill.posX < ox0 + 3 - 1.0,
                    "drill: ore only - took the coal ore, the stone stays, the vehicle can't pass");
            oreDrill.debugDrive(0);
        }
    }

    private static int countCargo(EntityVehicleSVA v, net.minecraft.item.Item item) {
        int n = 0;
        for (ItemStack s : v.state.cargo) {
            if (s != null && s.getItem() == item) {
                n += s.stackSize;
            }
        }
        return n;
    }

    private static int countLiquid(World w, int x0, int z0, int x1, int z1) {
        int n = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = CY + 1; y <= CY + 5; y++) {
                    if (w.getBlock(x, y, z).getMaterial().isLiquid()) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private void stageCFinish(World w) {
        cvPhase = CV_DONE;
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {truck, drill, oreDrill}) {
            if (v != null) {
                v.debugDrive(0);
                v.setDead();
            }
        }
        truck = drill = oreDrill = null;
        if (cx0 != 0 || cz0 != 0) {
            cvBox(w, cx0 - 6, cz0 - 5, cx0 + 30, cz0 + 6, false);
            cvBox(w, cx0 - 6, cz0 + 13, cx0 + 22, cz0 + 23, false);
            cvBox(w, cx0 - 6, cz0 - 18, cx0 + 10, cz0 - 10, false);
            for (int x = cx0 - 6; x <= cx0 + 30; x++) {
                for (int z = cz0 - 18; z <= cz0 + 23; z++) {
                    if (w.getBlock(x, CY, z) == Blocks.stone) {
                        w.setBlock(x, CY, z, Blocks.air, 0, 2);
                    }
                }
            }
        }
        java.util.List<?> items = w.getEntitiesWithinAABB(net.minecraft.entity.item.EntityItem.class,
                net.minecraft.util.AxisAlignedBB.getBoundingBox(cx0 - 10, CY - 2, cz0 - 22, cx0 + 34, CY + 12, cz0 + 27));
        for (Object o : items) {
            ((net.minecraft.entity.Entity) o).setDead();
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        check(reg.count(OWNER) == 0, "stage C cleanup: no test vehicles left in the registry");
        sdAt = ticks + 20;                                      // stage D next
    }

    // ------------------------------------------------------------------ stage D: jet and tiltrotor (phase 7)

    /**
     * A run stopped half way (server stopped mid-test) leaves its test vehicles in the save: kills the loaded ones of
     * the test owner (in `box`, or anywhere) and forgets the rest in the registry.
     */
    private void purgeLeftovers(World w, net.minecraft.util.AxisAlignedBB box) {
        int n = 0;
        java.util.List<?> list = box == null ? new java.util.ArrayList<Object>(w.loadedEntityList)
                : w.getEntitiesWithinAABB(EntityVehicleSVA.class, box);
        for (Object o : list) {
            if (o instanceof EntityVehicleSVA && OWNER.equals(((EntityVehicleSVA) o).state.ownerId) && !((EntityVehicleSVA) o).isDead) {
                ((EntityVehicleSVA) o).dismountAll();
                ((EntityVehicleSVA) o).setDead();
                n++;
            }
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (box == null && reg != null) {
            n += reg.removeOwner(OWNER);
        }
        if (n > 0) {
            System.out.println("[SVA-WORLDTEST] note: removed " + n + " leftover test vehicle(s) of an interrupted run");
        }
    }

    private static final int SD_DONE = 99, SD_HALF = 172;
    private int sdAt = -1, sdPhase, sdT0;
    private int sx0, sz;
    private EntityVehicleSVA jet, tilt;
    private net.minecraft.entity.passive.EntityVillager tiltPilot;
    private int tiltInput;
    private double sdLift = -1, sdEu0, sdY0, sdYMin, sdYMax, sdV0, sdHp0;
    private int sdH2;

    /** The stage D lane: z = sz +- 2, x = spawn +- SD_HALF, cleared AY .. AY + 30, stone at AY, runway blocks for the first 30. */
    private void sdLane(World w, boolean build) {
        ChunkCoordinates sp = w.getSpawnPoint();
        for (int x = sp.posX - SD_HALF; x <= sp.posX + SD_HALF; x++) {
            for (int z = sz - 2; z <= sz + 2; z++) {
                for (int y = AY; y <= AY + 30; y++) {
                    if (!w.isAirBlock(x, y, z)) {
                        w.setBlockToAir(x, y, z);
                    }
                }
                if (build) {
                    if (x <= sx0 + 30) {
                        w.setBlock(x, AY, z, ModContentSVA.runway, Math.abs(z - sz) == 2 ? 1 | 2 : 2, 2);
                    } else {
                        w.setBlock(x, AY, z, Blocks.stone, 0, 2);
                    }
                }
            }
        }
    }

    private void stageDTick(World w) {
        if (sdPhase == 0) {
            ChunkCoordinates sp = w.getSpawnPoint();
            sx0 = sp.posX - SD_HALF + 3;
            sz = sp.posZ - 120;
            sdLane(w, true);
            purgeLeftovers(w, net.minecraft.util.AxisAlignedBB.getBoundingBox(sp.posX - SD_HALF - 40, AY - 5, sz - 40,
                    sp.posX + SD_HALF + 40, AY + 60, sz + 40));
            jet = afSpawn(w, VehicleType.JET, sx0 + 0.5, sz + 0.5);
            check(jet != null && jet.onRunway() && jet.state.gas(H2) == 4000 && jet.state.gas(HE) == 2000,
                    "jet: placed on the runway, full (16M EU, H2 4000, He 2000)");
            if (jet == null) {
                tiltSetup(w);
                return;
            }
            jet.debugDrive(EntityVehicleSVA.IN_FWD | EntityVehicleSVA.IN_BRAKE);   // full throttle, stick back
            sdPhase = 1;
            sdT0 = ticks;
            sdLift = -1;
            return;
        }
        keepLoaded(w, sdPhase < 20 ? jet : tilt);
        if (sdPhase < 20) {
            jetTick(w);
        } else {
            tiltTick(w);
        }
    }

    /**
     * The lane reaches past the always-loaded spawn chunks (a fast jet needs ~270 blocks): the chunks within 3 of the
     * flying vehicle are loaded every tick (an entity only updates with its +-32 blocks loaded).
     */
    private static void keepLoaded(World w, EntityVehicleSVA v) {
        if (v == null || !(w instanceof net.minecraft.world.WorldServer)) {
            return;
        }
        int cx = MathHelper.floor_double(v.posX) >> 4, cz = MathHelper.floor_double(v.posZ) >> 4;
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        for (int x = cx - 3; x <= cx + 3; x++) {
            for (int z = cz - 3; z <= cz + 3; z++) {
                cp.loadChunk(x, z);
            }
        }
    }

    // ---- the jet: runway take-off, climb, cruise, afterburner, descent, landing and rollout on stone

    private void jetTick(World w) {
        if (jet == null) {
            tiltSetup(w);
            return;
        }
        int pt = ticks - sdT0;
        double h = jet.posY - (AY + 1);
        boolean air = jet.airborneAt(jet.posX, jet.posY, jet.posZ);
        if (jet.posX > sx0 + 2 * SD_HALF - 6 || pt > 1200 || jet.isDead) {
            check(false, "jet: ran out of strip / time (phase " + sdPhase + ", x+" + Math.round(jet.posX - sx0) + ")");
            jetFinish(w);
            return;
        }
        if (sdPhase == 1) {
            if (sdLift < 0 && h > 0.05) {
                sdLift = jet.posX - (sx0 + 0.5);
                double want = PlaneMath.takeoffDistance(VehicleType.JET, true), ground = PlaneMath.takeoffDistance(VehicleType.JET, false);
                check(Math.abs(sdLift - want) < 6 && sdLift < ground * 0.7, "jet: lifted off the runway after " + Math.round(sdLift)
                        + " blocks (runway ~" + Math.round(want) + ", without ~" + Math.round(ground) + ")");
            }
            if (h >= 6) {
                check(!jet.isStalling() && jet.isLifting() && pt < 200, "jet: climbed 6 blocks in " + pt + " ticks");
                jet.debugDrive(EntityVehicleSVA.IN_FWD);
                sdPhase = 2;
                sdT0 = ticks;
            }
        } else if (sdPhase == 2) {
            if (pt == 10) {
                sdEu0 = jet.state.energy;
                sdYMin = sdYMax = jet.posY;
            } else if (pt > 10) {
                sdYMin = Math.min(sdYMin, jet.posY);
                sdYMax = Math.max(sdYMax, jet.posY);
            }
            if (pt == 30) {
                double perTick = (sdEu0 - jet.state.energy) / 20.0;
                check(sdYMax - sdYMin < 0.6 && air && !jet.isStalling(), "jet: level flight at Y " + Math.round(jet.posY) + ", "
                        + VehicleMath.kmh(jet.speed) + " km/h");
                check(perTick > 60 && perTick < 85, "jet: cruising at Y >= 100 costs " + Math.round(perTick) + " EU/t (90 - 20% = 72)");
                sdV0 = jet.speed;
                sdH2 = jet.state.gas(H2);
                sdY0 = jet.state.heat;
                jet.debugDrive(EntityVehicleSVA.IN_FWD | EntityVehicleSVA.IN_AFTER);   // «Форсаж»
                sdPhase = 3;
                sdT0 = ticks;
            }
        } else if (sdPhase == 3) {
            if (pt == 3) {
                check(jet.isBoosting(), "jet: afterburner lit (full throttle + «Форсаж»)");
            }
            if (pt == 40) {
                int burnt = sdH2 - jet.state.gas(H2);
                check(jet.speed > sdV0 + 0.3 && jet.speed <= com.sva.vehicle.FlightMath.SPEED_CAP + 1e-6, "jet: the afterburner took it from "
                        + VehicleMath.kmh(sdV0) + " to " + VehicleMath.kmh(jet.speed) + " km/h in 2 s (cap 180)");
                check(burnt >= 7 && burnt <= 9, "jet: the afterburner burnt " + burnt + " mB of H2 in 2 s (4 mB/s)");
                check(jet.state.heat > sdY0, "jet: the afterburner heats the engine (" + Math.round(sdY0 * 10) / 10.0 + " -> "
                        + Math.round(jet.state.heat * 10) / 10.0 + "%)");
                jet.debugDrive(EntityVehicleSVA.IN_DOWN | EntityVehicleSVA.IN_BACK);   // nose down, throttle back
                sdPhase = 4;
                sdT0 = ticks;
            }
        } else if (sdPhase == 4) {
            if (pt == 3) {
                check(!jet.isBoosting(), "jet: afterburner off with the throttle back");
            }
            if (h <= 3) {
                jet.debugDrive(EntityVehicleSVA.IN_BACK);   // flare
                sdHp0 = jet.state.hp;
                sdPhase = 5;
                sdT0 = ticks;
            }
        } else if (sdPhase == 5) {
            if (!air) {
                check(jet.state.hp > (float) sdHp0 - 1F, "jet: touched down at " + VehicleMath.kmh(jet.speed) + " km/h without landing damage ("
                        + Math.round(sdHp0 - jet.state.hp) + " HP)");
                sdPhase = 6;
                sdT0 = ticks;
            }
        } else if (sdPhase == 6) {
            if (jet.speed < 0.01 && pt > 3) {
                float max = VehicleType.JET.maxHp;
                check(Math.abs(jet.posY - (AY + 1)) < 0.05 && !jet.isWrecked() && jet.state.hp > max - 40, "jet: stopped on the strip after a "
                        + pt + "-tick rollout, x+" + Math.round(jet.posX - sx0) + ", hull " + Math.round(jet.state.hp) + " / " + Math.round(max));
                jetFinish(w);
            }
        }
    }

    private void jetFinish(World w) {
        if (jet != null) {
            jet.debugDrive(0);
            jet.setDead();
            jet = null;
        }
        tiltSetup(w);
    }

    // ---- the tiltrotor: vertical take-off, airplane mode in the air (and the refusals), faster, back, vertical landing

    private void tiltSetup(World w) {
        tilt = afSpawn(w, VehicleType.TILT, sx0 + 22.5, sz + 0.5);
        check(tilt != null && tilt.state.gas(H2) == 3000 && tilt.state.tiltStep == 0, "tilt: placed in vertical mode, full (32M EU, H2 3000, He 2000)");
        sdPhase = tilt == null ? 30 : 21;
        sdT0 = ticks;
        if (tilt != null) {
            tiltPilot = new net.minecraft.entity.passive.EntityVillager(w);
            tiltPilot.setPosition(tilt.posX, tilt.posY, tilt.posZ);
            w.spawnEntityInWorld(tiltPilot);
            tiltPilot.mountEntity(tilt);                  // a villager at the controls: the motor runs with neutral keys too
            tiltKeys(EntityVehicleSVA.IN_BRAKE);           // Space: collective up
            sdY0 = tilt.posY;
        }
    }

    private void tiltKeys(int in) {
        tiltInput = in;
        tilt.serverInput(in);
    }

    private void tiltTick(World w) {
        if (tilt == null || sdPhase >= 30) {
            stageDFinish(w);
            return;
        }
        tiltKeys(tiltInput);                               // the Input packet's path, refreshed every tick
        int pt = ticks - sdT0;
        double h = tilt.posY - (AY + 1);
        boolean air = tilt.airborneAt(tilt.posX, tilt.posY, tilt.posZ);
        if (tilt.posX > sx0 + 2 * SD_HALF - 6 || pt > 900 || tilt.isDead) {
            check(false, "tilt: ran out of strip / time (phase " + sdPhase + ", x+" + Math.round(tilt.posX - sx0) + ", h " + Math.round(h) + ")");
            stageDFinish(w);
            return;
        }
        if (sdPhase == 21) {
            if (pt == 1) {
                check(tilt.toggleFlightMode() == TiltMath.ON_GROUND && !tilt.state.tiltPlane, "tilt: airplane mode refused on the ground");
            }
            if (pt == 12 && h > 0.5 && h < 6) {
                check(tilt.toggleFlightMode() == TiltMath.TOO_SLOW_LOW && !tilt.state.tiltPlane, "tilt: airplane mode refused low and slow ("
                        + Math.round(h * 10) / 10.0 + " blocks up)");
            }
            if (h >= 8) {
                check(Math.abs(tilt.posX - (sx0 + 22.5)) < 1 && tilt.isLifting() && !tilt.planeNow(), "tilt: vertical take-off, +8 blocks in "
                        + pt + " ticks, drifted " + Math.round(Math.abs(tilt.posX - (sx0 + 22.5)) * 100) / 100.0);
                tiltKeys(0);                         // hover
                sdPhase = 22;
                sdT0 = ticks;
            }
        } else if (sdPhase == 22) {
            if (pt == 15) {
                sdEu0 = tilt.state.energy;
                sdYMin = sdYMax = tilt.posY;
            } else if (pt > 15) {
                sdYMin = Math.min(sdYMin, tilt.posY);
                sdYMax = Math.max(sdYMax, tilt.posY);
            }
            if (pt == 35) {
                double perTick = (sdEu0 - tilt.state.energy) / 20.0;
                check(sdYMax - sdYMin < 0.3 && perTick > 135 && perTick < 165, "tilt: hovers in vertical mode at " + Math.round(perTick)
                        + " EU/t (150), height held within " + Math.round((sdYMax - sdYMin) * 100) / 100.0);
                int r = tilt.toggleFlightMode();
                check(r == TiltMath.OK && tilt.state.tiltPlane, "tilt: «Режим» in the air, " + Math.round(h) + " blocks up - airplane mode (result " + r + ")");
                sdY0 = tilt.posY;
                sdYMin = sdYMax = tilt.posY;
                tiltKeys(EntityVehicleSVA.IN_FWD);
                sdPhase = 23;
                sdT0 = ticks;
            }
        } else if (sdPhase == 23) {
            sdYMin = Math.min(sdYMin, tilt.posY);
            sdYMax = Math.max(sdYMax, tilt.posY);
            if (pt == 20) {
                double pos = tilt.getTiltPos();
                check(pos > 0.4 && pos < 0.6 && !tilt.planeNow() && tilt.toggleFlightMode() == TiltMath.BUSY,
                        "tilt: the nacelles half way after 1 s (" + Math.round(pos * 100) + "%), no switching mid-turn");
            }
            if (pt == 45) {
                check(tilt.planeNow() && !tilt.isStalling() && tilt.speed > VehicleType.TILT.plane.stall && sdYMax - sdYMin < 1.0,
                        "tilt: airplane mode after 2 s at " + VehicleMath.kmh(tilt.speed) + " km/h, height held within "
                                + Math.round((sdYMax - sdYMin) * 100) / 100.0);
            }
            if (pt == 90) {
                sdEu0 = tilt.state.energy;
            }
            if (pt == 110) {
                double perTick = (sdEu0 - tilt.state.energy) / 20.0;
                check(tilt.speed > VehicleType.TILT.vtolTop * 1.5 && air, "tilt: flies faster as a plane - " + VehicleMath.kmh(tilt.speed)
                        + " km/h (vertical mode " + VehicleMath.kmh(VehicleType.TILT.vtolTop) + ")");
                check(perTick > 75 && perTick < 100, "tilt: airplane cruise at Y >= 100 " + Math.round(perTick) + " EU/t (110 - 20% = 88)");
                check(tilt.toggleFlightMode() == TiltMath.TOO_FAST && tilt.state.tiltPlane, "tilt: back to vertical refused at "
                        + VehicleMath.kmh(tilt.speed) + " km/h");
                tiltKeys(EntityVehicleSVA.IN_BACK);      // throttle back, level
                sdPhase = 24;
                sdT0 = ticks;
            }
        } else if (sdPhase == 24) {
            if (tilt.isStalling()) {
                check(false, "tilt: stalled while slowing down");
                stageDFinish(w);
                return;
            }
            if (tilt.speed <= VehicleType.TILT.plane.stall * 1.12) {
                int r = tilt.toggleFlightMode();
                check(r == TiltMath.OK && !tilt.state.tiltPlane, "tilt: slowed to " + VehicleMath.kmh(tilt.speed) + " km/h in " + pt
                        + " ticks - back to vertical mode (result " + r + ")");
                tiltKeys(0);
                sdYMin = sdYMax = tilt.posY;
                sdPhase = 25;
                sdT0 = ticks;
            }
        } else if (sdPhase == 25) {
            sdYMin = Math.min(sdYMin, tilt.posY);
            sdYMax = Math.max(sdYMax, tilt.posY);
            if (pt == 45) {
                check(!tilt.planeNow() && tilt.getTiltPos() == 0 && sdYMax - sdYMin < 1.0 && Math.abs(tilt.speed) <= VehicleType.TILT.vtolTop + 0.01,
                        "tilt: vertical mode again, height held within " + Math.round((sdYMax - sdYMin) * 100) / 100.0 + ", "
                                + VehicleMath.kmh(tilt.speed) + " km/h");
                sdHp0 = tilt.state.hp;
                tiltKeys(EntityVehicleSVA.IN_DOWN);     // collective down
                sdPhase = 26;
                sdT0 = ticks;
            }
        } else if (sdPhase == 26) {
            if (!air && pt > 3) {
                sdPhase = 27;
                sdT0 = ticks;
            }
        } else if (sdPhase == 27 && pt == 5) {
            check(Math.abs(tilt.posY - (AY + 1)) < 0.05 && tilt.state.hp > (float) sdHp0 - 0.5F && !tilt.planeNow(),
                    "tilt: landed vertically, no damage (" + Math.round(sdHp0 - tilt.state.hp) + " HP), x+" + Math.round(tilt.posX - sx0));
            stageDFinish(w);
        }
    }

    private void stageDFinish(World w) {
        sdPhase = SD_DONE;
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {jet, tilt}) {
            if (v != null) {
                v.debugDrive(0);
                v.setDead();
            }
        }
        jet = tilt = null;
        if (tiltPilot != null) {
            tiltPilot.mountEntity(null);
            tiltPilot.setDead();
            tiltPilot = null;
        }
        if (sz != 0 || sx0 != 0) {
            sdLane(w, false);
            ChunkCoordinates sp = w.getSpawnPoint();
            for (int x = sp.posX - SD_HALF; x <= sp.posX + SD_HALF; x++) {
                for (int z = sz - 2; z <= sz + 2; z++) {
                    w.setBlock(x, AY, z, Blocks.air, 0, 2);
                }
            }
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        check(reg.count(OWNER) == 0, "stage D cleanup: no test vehicles left in the registry");
        dDone = true;
        maybeSummary();
    }

    // ------------------------------------------------------------------ stage E: shuttle, Singular ship, bridge docking

    /*
     * Runs alongside the other phases from tick SE_START (its own area and its own test owner, so the other phases'
     * registry counts never see its vehicles): the shuttle climbs, flies, spends deuterium instead of EU in long-range
     * mode, lands and keeps its pilot breathing under water; the Singular Ship jumps 200 blocks to a Coordinator point
     * with a villager and a pig aboard and its cargo, refuses a jump on cooldown / too near / into rock, holds still at
     * anchor for 100 ticks and hides its crew from a zombie; then a real core Ground Bridge is built and opened and the
     * shuttle flies into its vortex - out at the far end with its riders and cargo - and goes to the Nether and back
     * by the same transfer routine.
     */
    private static final String OWNER_E = "00000000-5bad-4e57-0000-00000000e5e5";
    private static final int SE_DONE = 99, SE_START = 100, EY = 180;
    private int seAt = SE_START, sePhase, seT0;
    private boolean dDone, eDone;
    private final WorldTestWave1SVA wave1 = new WorldTestWave1SVA(this);
    private final WorldTestWave2SVA wave2 = new WorldTestWave2SVA(this);
    private final WorldTestWave3SVA wave3 = new WorldTestWave3SVA(this);
    private final WorldTestRegenSVA regen = new WorldTestRegenSVA(this);
    private final WorldTestDriveSVA drive = new WorldTestDriveSVA(this);
    private final WorldTestAirSVA air = new WorldTestAirSVA(this);
    private final WorldTestAirCSVA airC = new WorldTestAirCSVA(this);
    private final WorldTestAirD1SVA airD1 = new WorldTestAirD1SVA(this);
    private final WorldTestAirD2SVA airD2 = new WorldTestAirD2SVA(this);
    private final WorldTestAirD3SVA airD3 = new WorldTestAirD3SVA(this);
    private final WorldTestAirD4SVA airD4 = new WorldTestAirD4SVA(this);
    private final WorldTestFastChargeSVA fastCharge = new WorldTestFastChargeSVA(this);
    private final WorldTestFuelSVA fuel = new WorldTestFuelSVA(this);
    private int ex0, ez, shipX, destX, bX, bY, bZ, tX, tY, tZ;
    private EntityVehicleSVA shuttle, ship;
    private String seId;
    private net.minecraft.entity.passive.EntityVillager sePilot;
    private net.minecraft.entity.passive.EntityPig sePig;
    private net.minecraft.entity.monster.EntityZombie seZombie;
    private int seInput, seGas0;
    private double seEu0, seX0, seY0, seZ0;
    private com.sc.tileentity.TileEntityBridgeControllerSC seBridge;
    private static final com.sc.util.ArmorGasSC.Gas D2 = com.sc.util.ArmorGasSC.Gas.DEUTERIUM, O2 = com.sc.util.ArmorGasSC.Gas.OXYGEN,
            SM = com.sc.util.ArmorGasSC.Gas.SINGULAR_MATTER;

    /** Prints the SUMMARY once stage D, stage E and waves 1 - 3 are all done. */
    private void maybeSummary() {
        if (dDone && eDone && wave1.done && wave2.done && wave3.done && fastCharge.done && regen.done && drive.done && air.done && airC.done && airD1.done && airD2.done && airD3.done && airD4.done && fuel.done) {
            System.out.println("[SVA-WORLDTEST] SUMMARY passed=" + passed + " failed=" + failed);
            System.out.println("[SVA-WORLDTEST] DONE");
        }
    }

    /** Sets every block of the box to `b` (air: clears), only where it differs. */
    private static void seFill(World w, int x0, int y0, int z0, int x1, int y1, int z1, net.minecraft.block.Block b) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    if (w.getBlock(x, y, z) != b) {
                        w.setBlock(x, y, z, b, 0, 2);
                    }
                }
            }
        }
    }

    private static void keepLoadedAt(World w, double px, double pz) {
        if (!(w instanceof net.minecraft.world.WorldServer)) {
            return;
        }
        int cx = MathHelper.floor_double(px) >> 4, cz = MathHelper.floor_double(pz) >> 4;
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        for (int x = cx - 3; x <= cx + 3; x++) {
            for (int z = cz - 3; z <= cz + 3; z++) {
                cp.loadChunk(x, z);
            }
        }
    }

    private EntityVehicleSVA seSpawn(World w, VehicleType t, double x, double y, double z, float yaw) {
        VehicleState st = new VehicleState(t);
        st.energy = st.maxEnergy();
        st.fillAllGases();
        return ItemVehicleSVA.spawn(w, ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, t.ordinal()), st), x, y, z, yaw,
                OWNER_E, "WorldTestE");
    }

    /** A villager at the controls (the motor runs; the keys come from the test). */
    private net.minecraft.entity.passive.EntityVillager seCrew(World w, EntityVehicleSVA v) {
        net.minecraft.entity.passive.EntityVillager p = new net.minecraft.entity.passive.EntityVillager(w);
        p.setPosition(v.posX, v.posY, v.posZ);
        w.spawnEntityInWorld(p);
        p.mountEntity(v);
        return p;
    }

    private net.minecraft.entity.passive.EntityPig sePassenger(World w, EntityVehicleSVA v) {
        net.minecraft.entity.passive.EntityPig p = new net.minecraft.entity.passive.EntityPig(w);
        p.setPosition(v.posX, v.posY, v.posZ);
        w.spawnEntityInWorld(p);
        EntitySeatSVA s = v.freeSeat();
        if (s != null) {
            p.mountEntity(s);
        }
        return p;
    }

    private void seKeys(EntityVehicleSVA v, int in) {
        seInput = in;
        if (v != null) {
            v.serverInput(in);
        }
    }

    /** The live vehicle with this id in w (a transfer makes a new entity), or null. */
    private static EntityVehicleSVA seFind(World w, String id) {
        if (w == null || id == null) {
            return null;
        }
        for (Object o : w.loadedEntityList) {
            if (o instanceof EntityVehicleSVA && !((EntityVehicleSVA) o).isDead && id.equals(((EntityVehicleSVA) o).state.vehId)) {
                return (EntityVehicleSVA) o;
            }
        }
        return null;
    }

    /** Seated: the driver is a `driver`-class entity and some passenger seat holds a `seated`-class one. */
    private static boolean crewAboard(EntityVehicleSVA v, Class<?> driver, Class<?> seated) {
        if (v == null || !driver.isInstance(v.riddenByEntity)) {
            return false;
        }
        for (net.minecraft.entity.Entity e : v.riders()) {
            if (seated.isInstance(e) && e.ridingEntity instanceof EntitySeatSVA && ((EntitySeatSVA) e.ridingEntity).getParent() == v) {
                return true;
            }
        }
        return false;
    }

    private static int countOf(EntityVehicleSVA v, net.minecraft.item.Item item) {
        int n = 0;
        for (ItemStack s : v.state.cargo) {
            if (s != null && s.getItem() == item) {
                n += s.stackSize;
            }
        }
        return n;
    }

    private void stageETick(World w) {
        int pt = ticks - seT0;
        if (sePhase == 0) {
            stageESetup(w);
            return;
        }
        if (sePhase < 10) {
            shuttleTick(w, pt);
        } else if (sePhase < 20) {
            shipTick(w, pt);
        } else if (sePhase < 30) {
            dockTick(w, pt);
        } else {
            stageEFinish(w);
        }
    }

    private void stageESetup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        ex0 = sp.posX - 60;
        ez = sp.posZ + 140;
        shipX = ex0 + 170;
        destX = shipX + 200;
        // leftovers of an interrupted run
        int n = 0;
        for (Object o : new java.util.ArrayList<Object>(w.loadedEntityList)) {
            if (o instanceof EntityVehicleSVA && OWNER_E.equals(((EntityVehicleSVA) o).state.ownerId) && !((EntityVehicleSVA) o).isDead) {
                ((EntityVehicleSVA) o).dismountAll();
                ((EntityVehicleSVA) o).setDead();
                n++;
            }
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        n += reg == null ? 0 : reg.removeOwner(OWNER_E);
        if (n > 0) {
            System.out.println("[SVA-WORLDTEST] note: removed " + n + " leftover stage E vehicle(s)");
        }
        keepLoadedAt(w, ex0 + 60, ez);
        keepLoadedAt(w, ex0 + 160, ez);
        seFill(w, ex0 - 8, EY + 1, ez - 6, shipX + 8, EY + 26, ez + 6, Blocks.air);
        seFill(w, ex0 - 8, EY, ez - 6, shipX + 8, EY, ez + 6, Blocks.stone);
        shuttle = seSpawn(w, VehicleType.SHUTTLE, ex0 + 0.5, EY + 1, ez + 0.5, -90F);
        check(shuttle != null && shuttle.state.gas(D2) == 4000 && shuttle.state.gas(O2) == 2000 && shuttle.state.energy == 32000000,
                "shuttle: placed full (32M EU, H2 / He 3000, O2 2000, D 4000)");
        if (shuttle == null) {
            sePhase = 10;
            seT0 = ticks;
            return;
        }
        sePilot = seCrew(w, shuttle);
        seKeys(shuttle, EntityVehicleSVA.IN_BRAKE);                // Space: up
        seY0 = shuttle.posY;
        seX0 = shuttle.posX;
        sePhase = 1;
        seT0 = ticks;
    }

    // ---- the shuttle: climb, flight, long-range mode, landing, cabin oxygen under water

    private void shuttleTick(World w, int pt) {
        if (shuttle == null || shuttle.isDead || pt > 400) {
            check(false, "shuttle: lost / out of time in phase " + sePhase);
            shuttleDone(w);
            return;
        }
        keepLoaded(w, shuttle);
        seKeys(shuttle, seInput);
        double h = shuttle.posY - (EY + 1);
        boolean air = shuttle.airborneAt(shuttle.posX, shuttle.posY, shuttle.posZ);
        if (sePhase == 1) {
            if (h >= 10) {
                check(pt <= 50 && shuttle.isLifting() && Math.abs(shuttle.posX - seX0) < 1, "shuttle: straight up 10 blocks in " + pt
                        + " ticks (climb 0.35 b/t)");
                seKeys(shuttle, EntityVehicleSVA.IN_FWD);
                sePhase = 2;
                seT0 = ticks;
            }
        } else if (sePhase == 2) {
            if (pt == 30) {
                seEu0 = shuttle.state.energy;
            }
            if (pt == 50) {
                double perTick = (seEu0 - shuttle.state.energy) / 20.0;
                check(shuttle.posX - seX0 > 25 && air && Math.abs(shuttle.speed - VehicleType.SHUTTLE.topSpeed) < 0.05,
                        "shuttle: flew forward " + Math.round(shuttle.posX - seX0) + " blocks in 2.5 s at " + VehicleMath.kmh(shuttle.speed) + " km/h");
                check(perTick > 185 && perTick < 215 && !shuttle.isLongRangeActive(), "shuttle: " + Math.round(perTick) + " EU/t in flight (200)");
                check(shuttle.toggleLongRange(), "shuttle: long-range mode switched on (Y " + Math.round(shuttle.posY) + ")");
                seGas0 = shuttle.state.gas(D2);
                sePhase = 3;
                seT0 = ticks;
            }
        } else if (sePhase == 3) {
            if (pt == 5) {
                seEu0 = shuttle.state.energy;
            }
            if (pt == 45) {
                int d = seGas0 - shuttle.state.gas(D2);
                check(shuttle.isLongRangeActive() && shuttle.state.energy == seEu0 && d >= 1 && d <= 3, "shuttle: long-range mode spends "
                        + d + " mB D in 2.25 s and 0 EU (" + Math.round(seEu0 - shuttle.state.energy) + ")");
                check(!shuttle.toggleLongRange(), "shuttle: long-range mode off");
                seKeys(shuttle, EntityVehicleSVA.IN_DOWN | EntityVehicleSVA.IN_BACK);  // brake and come down
                sePhase = 4;
                seT0 = ticks;
            }
        } else if (sePhase == 4) {
            if ((seInput & EntityVehicleSVA.IN_BACK) != 0 && shuttle.speed <= 0.05) {
                seKeys(shuttle, EntityVehicleSVA.IN_DOWN);
            }
            if (pt == 1) {
                seEu0 = shuttle.state.hp;
            }
            if (!air && pt > 3) {
                seKeys(shuttle, 0);
                sePhase = 5;
                seT0 = ticks;
            }
        } else if (sePhase == 5 && pt == 5) {
            check(shuttle.state.hp >= (float) seEu0 - 0.01F && Math.abs(shuttle.posY - (EY + 1)) < 0.05, "shuttle: landed at full descent rate, "
                    + "no damage (" + Math.round(seEu0 - shuttle.state.hp) + " HP), x+" + Math.round(shuttle.posX - ex0));
            // water over the pilot's head: the sealed cabin's O2 keeps him breathing
            int ix = MathHelper.floor_double(sePilot.posX), iy = MathHelper.floor_double(sePilot.posY + sePilot.getEyeHeight()),
                    iz = MathHelper.floor_double(sePilot.posZ);
            w.setBlock(ix, iy, iz, Blocks.water, 0, 3);
            sePilot.setAir(300);
            seGas0 = shuttle.state.gas(O2);
            sePhase = 6;
            seT0 = ticks;
        } else if (sePhase == 6 && pt == 45) {
            int used = seGas0 - shuttle.state.gas(O2);
            check(sePilot.isInsideOfMaterial(net.minecraft.block.material.Material.water) && sePilot.getAir() >= 298 && used >= 1 && used <= 3
                    && sePilot.ridingEntity == shuttle, "shuttle: the pilot under water keeps full air on the cabin's O2 (" + used
                    + " mB in 2.25 s, air " + sePilot.getAir() + ")");
            shuttleDone(w);
        }
    }

    private void seDrain(World w, int x0, int z0, int x1, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = EY + 1; y <= EY + 8; y++) {
                    if (w.getBlock(x, y, z).getMaterial() == net.minecraft.block.material.Material.water) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
            }
        }
    }

    private void shuttleDone(World w) {
        if (shuttle != null) {
            shuttle.dismountAll();
            seDrain(w, MathHelper.floor_double(shuttle.posX) - 12, ez - 6, MathHelper.floor_double(shuttle.posX) + 12, ez + 6);
            shuttle.setDead();
            shuttle = null;
        }
        if (sePilot != null) {
            sePilot.setDead();
            sePilot = null;
        }
        sePhase = 10;
        seT0 = ticks;
    }

    // ---- the Singular ship: a 200-block jump with its crew and cargo, refusals, the anchor, invisibility

    private void shipTick(World w, int pt) {
        if (sePhase == 10) {
            keepLoadedAt(w, destX, ez);
            seFill(w, destX - 8, EY, ez - 8, destX + 8, EY + 14, ez + 8, Blocks.air);
            ship = seSpawn(w, VehicleType.SHIP, shipX + 0.5, EY + 1, ez + 0.5, -90F);
            check(ship != null && ship.state.gas(SM) == 4000 && ship.state.energy == 256000000, "ship: placed full (256M EU, SM 4000)");
            if (ship == null) {
                sePhase = 20;
                seT0 = ticks;
                return;
            }
            ItemStack coord = new ItemStack(com.sc.init.ModItems.coordinator);
            com.sc.bridge.BridgeItemDataSC.setPoint(coord, destX, EY + 5, ez, 0, true);
            com.sc.bridge.BridgeItemDataSC.setPointName(coord, "Тест");
            ship.setInventorySlotContents(0, coord);
            ship.setInventorySlotContents(5, new ItemStack(Blocks.cobblestone, 37));
            sePilot = seCrew(w, ship);
            seKeys(ship, 0);
            sePhase = 11;
            seT0 = ticks;
            return;
        }
        if (ship == null || ship.isDead && sePhase != 12 || pt > 400) {
            check(false, "ship: lost / out of time in phase " + sePhase);
            shipDone(w);
            return;
        }
        keepLoaded(w, ship);
        keepLoadedAt(w, destX, ez);
        if (!ship.isDead) {
            seKeys(ship, seInput);
        }
        if (sePhase == 11) {
            if (pt == 3) {
                sePig = sePassenger(w, ship);
                check(crewAboard(ship, net.minecraft.entity.passive.EntityVillager.class, net.minecraft.entity.passive.EntityPig.class),
                        "ship: a villager at the controls, a pig on a passenger seat");
            }
            if (pt == 6) {
                seId = ship.state.vehId;
                seEu0 = ship.state.energy;
                seGas0 = ship.state.gas(SM);
                int r = ship.startJump();
                check(r == com.sva.vehicle.SpaceMath.OK && ship.portalTicks() == com.sva.vehicle.SpaceMath.PORTAL_TICKS && ship.isHeld(),
                        "ship: Jump to the Coordinator 200 blocks ahead - the portal opens, the ship held (result " + r + ")");
                seX0 = ship.posX;
                sePhase = 12;
                seT0 = ticks;
            }
        } else if (sePhase == 12) {
            if (!ship.isDead) {
                if (pt == 15 && Math.abs(ship.posX - seX0) > 1e-6) {
                    check(false, "ship: moved while the portal opened");
                }
                return;
            }
            EntityVehicleSVA n = seFind(w, seId);
            long jumpEu = com.sva.vehicle.SpaceMath.jumpEu(200, false);
            double spent = n == null ? -1 : seEu0 - n.state.energy;
            check(n != null && Math.abs(n.posX - (destX + 0.5)) < 0.1 && Math.abs(n.posY - (EY + 5)) < 0.1 && Math.abs(n.posZ - (ez + 0.5)) < 0.1,
                    "ship: jumped after " + pt + " ticks of portal to " + (n == null ? "nowhere" : Math.round(n.posX - shipX) + " blocks ahead, Y "
                            + Math.round(n.posY)));
            check(n != null && spent >= jumpEu && spent <= jumpEu + 2000 && seGas0 - n.state.gas(SM) == 12 && n.state.jumpCooldown > 590,
                    "ship: the jump took " + Math.round(spent) + " EU (20 400 000) and " + (n == null ? -1 : seGas0 - n.state.gas(SM))
                            + " mB SM (12), cooldown 30 s");
            ship = n;
            sePhase = 13;
            seT0 = ticks;
        } else if (sePhase == 13 && pt == 15) {
            check(crewAboard(ship, net.minecraft.entity.passive.EntityVillager.class, net.minecraft.entity.passive.EntityPig.class)
                    && ship.riddenByEntity == sePilot && sePig.ridingEntity instanceof EntitySeatSVA, "ship: arrived with the villager still at the "
                    + "controls and the pig on its seat");
            check(countOf(ship, net.minecraft.item.Item.getItemFromBlock(Blocks.cobblestone)) == 37 && com.sc.item.ItemCoordinatorSC.isCoordinator(ship.getStackInSlot(0)),
                    "ship: the cargo came along (37 cobblestone, the Coordinator)");
            check(ship.isLifting() && !ship.isHeld(), "ship: flying under its pilot again at the destination");
            // refusals: the cooldown, too near, into rock
            double x = ship.posX, eu = ship.state.energy;
            ItemStack coord = ship.getStackInSlot(0);
            com.sc.bridge.BridgeItemDataSC.setPoint(coord, destX - 100, EY + 5, ez, 0, true);
            int r1 = ship.startJump();
            ship.state.jumpCooldown = 0;
            com.sc.bridge.BridgeItemDataSC.setPoint(coord, destX + 5, EY + 5, ez, 0, true);
            int r2 = ship.startJump();
            seFill(w, destX - 61, EY + 4, ez - 1, destX - 59, EY + 6, ez + 1, Blocks.stone);
            com.sc.bridge.BridgeItemDataSC.setPoint(coord, destX - 60, EY + 5, ez, 0, true);
            int r3 = ship.startJump();
            check(r1 == com.sva.vehicle.SpaceMath.COOLDOWN && r2 == com.sva.vehicle.SpaceMath.TOO_NEAR && r3 == com.sva.vehicle.SpaceMath.BLOCKED
                    && ship.portalTicks() == 0 && ship.posX == x && ship.state.energy >= eu - 1000,
                    "ship: jumps refused on cooldown (" + r1 + "), 5 blocks away (" + r2 + "), into a block of stone (" + r3 + ") - it stays, nothing paid");
            // the anchor: forward + down held, a knock - it doesn't move
            seKeys(ship, EntityVehicleSVA.IN_FWD | EntityVehicleSVA.IN_DOWN);
            int a = ship.toggleAnchor();
            ship.addVelocity(2, 2, 2);
            seX0 = ship.posX;
            seY0 = ship.posY;
            seZ0 = ship.posZ;
            seEu0 = ship.state.energy;
            check(a == 1 && ship.isAnchored() && ship.isHeld(), "ship: gravity anchor on");
            sePhase = 14;
            seT0 = ticks;
        } else if (sePhase == 14 && pt == 100) {
            double drift = Math.abs(ship.posX - seX0) + Math.abs(ship.posY - seY0) + Math.abs(ship.posZ - seZ0);
            double perTick = (seEu0 - ship.state.energy) / 100.0;
            check(drift < 1e-6, "ship: the anchor held it for 100 ticks with W + down held and a knock (drift " + drift + ")");
            check(perTick > 38 && perTick < 42, "ship: the anchor costs " + Math.round(perTick * 10) / 10.0 + " EU/t (40)");
            check(ship.toggleAnchor() == 0 && !ship.isHeld(), "ship: anchor off");
            seKeys(ship, 0);
            // invisibility: a zombie that picks the pilot drops him
            check(ship.toggleInvisible() == 1, "ship: invisibility on");
            seGas0 = ship.state.gas(SM);
            seZombie = new net.minecraft.entity.monster.EntityZombie(w);
            seZombie.setPosition(ship.posX + 3, ship.posY, ship.posZ);
            w.spawnEntityInWorld(seZombie);
            seZombie.setAttackTarget(sePilot);
            check(seZombie.getAttackTarget() == null, "ship: invisible - a zombie can't target the pilot");
            sePhase = 15;
            seT0 = ticks;
        } else if (sePhase == 15 && pt == 40) {
            int used = seGas0 - ship.state.gas(SM);
            check(used >= 1 && used <= 3 && seZombie.getAttackTarget() == null, "ship: invisibility spends " + used + " mB SM in 2 s (1 mB/s)");
            check(ship.toggleInvisible() == 0, "ship: invisibility off");
            seZombie.setAttackTarget(sePilot);
            check(seZombie.getAttackTarget() == sePilot, "ship: visible again - the zombie targets the pilot");
            shipDone(w);
        }
    }

    private void shipDone(World w) {
        if (seZombie != null) {
            seZombie.setDead();
            seZombie = null;
        }
        EntityVehicleSVA s = ship != null && ship.isDead ? seFind(w, seId) : ship;
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {ship, s}) {
            if (v != null) {
                for (net.minecraft.entity.Entity e : v.riders()) {
                    e.mountEntity(null);
                    e.setDead();
                }
                v.setDead();
            }
        }
        ship = null;
        for (net.minecraft.entity.Entity e : new net.minecraft.entity.Entity[] {sePilot, sePig}) {
            if (e != null) {
                e.setDead();
            }
        }
        sePilot = null;
        sePig = null;
        if (destX != 0) {
            seFill(w, destX - 61, EY + 4, ez - 1, destX - 59, EY + 6, ez + 1, Blocks.air);
        }
        sePhase = 20;
        seT0 = ticks;
    }

    // ---- docking: a real core Ground Bridge opened 200 blocks; the shuttle flies into the ring; then the Nether and back

    private void buildBridge(World w) {
        for (int u = -2; u <= 2; u++) {
            for (int v = 1; v <= 5; v++) {
                if (com.sc.bridge.BridgeStructureSC.isRing(5, u, v)) {
                    w.setBlock(bX + u, bY + v, bZ, com.sc.init.ModBlocks.gravityCoil);
                }
            }
        }
        w.setBlock(bX, bY, bZ, com.sc.init.ModBlocks.bridge, com.sc.block.BlockBridgeSC.CONTROLLER, 3);
        w.setBlock(bX - 1, bY, bZ, com.sc.init.ModBlocks.bridge, com.sc.block.BlockBridgeSC.CAPACITOR, 3);
        w.setBlock(bX - 2, bY, bZ, com.sc.init.ModBlocks.bridge, com.sc.block.BlockBridgeSC.CAPACITOR, 3);
        w.setBlock(bX + 1, bY, bZ, com.sc.init.ModBlocks.bridge, com.sc.block.BlockBridgeSC.ENERGY_PORT, 3);
        w.setBlock(bX + 2, bY, bZ, com.sc.init.ModBlocks.bridge, com.sc.block.BlockBridgeSC.GAS_PORT, 3);
        w.setBlock(bX, bY - 1, bZ, com.sc.init.ModBlocks.bridge, com.sc.block.BlockBridgeSC.MASS, 3);
        TileEntity te = w.getTileEntity(bX, bY, bZ);
        seBridge = te instanceof com.sc.tileentity.TileEntityBridgeControllerSC ? (com.sc.tileentity.TileEntityBridgeControllerSC) te : null;
        if (seBridge == null) {
            return;
        }
        seBridge.setPowerOn(true);
        int[] fill = {20000, 10000, 4000, 8000, 8000, 8000};
        for (int i = 0; i < fill.length; i++) {
            seBridge.putTankForTest(i, fill[i]);
        }
        for (int x = bX - 2; x <= bX - 1; x++) {
            TileEntity c = w.getTileEntity(x, bY, bZ);
            if (c instanceof com.sc.tileentity.TileEntityBridgeCapacitorSC) {
                ((com.sc.tileentity.TileEntityBridgeCapacitorSC) c).setEnergy(com.sc.bridge.BridgeMathSC.CAPACITOR_EU);
            }
        }
    }

    private void dockTick(World w, int pt) {
        if (sePhase == 20) {
            bX = ex0 + 20;
            bY = EY + 2;
            bZ = ez + 40;
            tX = bX + 200;
            tY = bY + 1;
            tZ = bZ;
            keepLoadedAt(w, bX, bZ);
            keepLoadedAt(w, tX, tZ);
            seFill(w, bX - 4, bY - 2, bZ - 10, bX + 4, bY + 8, bZ + 3, Blocks.air);
            seFill(w, tX - 4, tY - 1, tZ - 3, tX + 4, tY + 6, tZ + 12, Blocks.air);
            seFill(w, tX - 2, tY - 1, tZ - 2, tX + 2, tY - 1, tZ + 8, Blocks.stone);
            buildBridge(w);
            com.sc.bridge.BridgeStructureSC.Scan s = seBridge == null ? null : seBridge.rescan();
            if (seBridge != null) {
                seBridge.setTarget(tX, tY, tZ, 0);
                seBridge.calibrate(null);
                seBridge.setTarget(tX, tY, tZ, 0);
            }
            com.sc.bridge.BridgeMsgSC m = seBridge == null ? null : seBridge.tryOpen(null);
            check(s != null && s.valid && seBridge.isOpen() && m == null && w.getBlock(bX, bY + 3, bZ) == com.sc.init.ModBlocks.bridgeVortex,
                    "dock: a core Ground Bridge built, calibrated and opened 200 blocks away" + (m == null ? "" : " - refused: " + m.key));
            shuttle = seSpawn(w, VehicleType.SHUTTLE, bX + 0.5, bY + 2, bZ - 4.5, 0F);
            check(shuttle != null, "dock: shuttle hovering 4.5 blocks before the ring, facing it");
            if (shuttle == null || seBridge == null || !seBridge.isOpen()) {
                stageEFinish(w);
                return;
            }
            sePilot = seCrew(w, shuttle);
            shuttle.setInventorySlotContents(3, new ItemStack(Blocks.glass, 11));
            seKeys(shuttle, 0);
            sePhase = 21;
            seT0 = ticks;
            return;
        }
        if (sePhase == 21) {
            if (shuttle == null || pt > 120) {
                check(false, "dock: the shuttle never came through the vortex (z " + (shuttle == null ? "-" : Math.round((shuttle.posZ - bZ) * 10) / 10.0) + ")");
                stageEFinish(w);
                return;
            }
            if (pt == 3) {
                sePig = sePassenger(w, shuttle);
                seId = shuttle.state.vehId;
                seEu0 = shuttle.state.energy;
                seKeys(shuttle, EntityVehicleSVA.IN_FWD);
            }
            if (!shuttle.isDead) {
                keepLoaded(w, shuttle);
                seKeys(shuttle, seInput);
                return;
            }
            EntityVehicleSVA n = seFind(w, seId);
            double toll = com.sc.bridge.BridgeMathSC.massEu(com.sva.vehicle.SpaceMath.dockMass(new int[] {20, 20}), true);
            double spent = n == null ? -1 : seEu0 - n.state.energy;
            check(n != null && Math.abs(n.posX - (tX + 0.5)) < 0.2 && Math.abs(n.posY - tY) < 0.2 && Math.abs(n.posZ - (tZ + 4.5)) < 0.2
                    && n.rotationYaw == 0F, "dock: flew into the ring after " + pt + " ticks - out at the far end "
                    + (n == null ? "-" : Math.round(n.posX - bX) + " / " + Math.round((n.posZ - tZ) * 10) / 10.0 + " blocks out"));
            check(n != null && spent >= toll && spent <= toll + 20000, "dock: the shuttle paid the mass toll " + Math.round(spent) + " EU (4M: hull + 2 mobs, "
                    + "halved by the Mass Compensator)");
            shuttle = n;
            sePhase = 22;
            seT0 = ticks;
            return;
        }
        if (shuttle == null || shuttle.isDead || pt > 200) {
            check(false, "dock: lost the shuttle in phase " + sePhase);
            stageEFinish(w);
            return;
        }
        keepLoaded(shuttle.worldObj, shuttle);
        if (shuttle.worldObj instanceof net.minecraft.world.WorldServer) {
            ((net.minecraft.world.WorldServer) shuttle.worldObj).resetUpdateEntityTick();
        }
        seKeys(shuttle, 0);
        if (sePhase == 22 && pt == 15) {
            check(crewAboard(shuttle, net.minecraft.entity.passive.EntityVillager.class, net.minecraft.entity.passive.EntityPig.class)
                    && shuttle.riddenByEntity == sePilot && countOf(shuttle, net.minecraft.item.Item.getItemFromBlock(Blocks.glass)) == 11,
                    "dock: through the bridge with the villager at the controls, the pig on its seat and the cargo (11 glass)");
            net.minecraft.server.MinecraftServer srv = net.minecraft.server.MinecraftServer.getServer();
            World nether = srv == null ? null : srv.worldServerForDimension(-1);
            EntityVehicleSVA n = nether == null ? null : com.sva.entity.VehicleTransferSVA.transfer(shuttle, -1, (tX >> 3) + 0.5, 140, (tZ >> 3) + 0.5, 0F);
            check(n != null && n.worldObj.provider.dimensionId == -1, "transfer: the shuttle with its crew to the Nether (Y 140)");
            if (n == null) {
                stageEFinish(w);
                return;
            }
            shuttle = n;
            sePhase = 23;
            seT0 = ticks;
        } else if (sePhase == 23 && pt == 15) {
            check(crewAboard(shuttle, net.minecraft.entity.passive.EntityVillager.class, net.minecraft.entity.passive.EntityPig.class)
                    && countOf(shuttle, net.minecraft.item.Item.getItemFromBlock(Blocks.glass)) == 11 && shuttle.pendingCount() == 0,
                    "transfer: in the Nether with a villager at the controls, a pig seated (copies across the dimension), cargo intact");
            EntityVehicleSVA n = com.sva.entity.VehicleTransferSVA.transfer(shuttle, 0, tX + 0.5, tY + 3, tZ + 6.5, 180F);
            check(n != null && n.worldObj == w && seFind(DimensionManager.getWorld(-1), seId) == null, "transfer: back to the overworld");
            if (n == null) {
                stageEFinish(w);
                return;
            }
            shuttle = n;
            sePhase = 24;
            seT0 = ticks;
        } else if (sePhase == 24 && pt == 15) {
            check(crewAboard(shuttle, net.minecraft.entity.passive.EntityVillager.class, net.minecraft.entity.passive.EntityPig.class)
                    && countOf(shuttle, net.minecraft.item.Item.getItemFromBlock(Blocks.glass)) == 11 && shuttle.rotationYaw == 180F,
                    "transfer: home again - crew seated, cargo intact");
            stageEFinish(w);
        }
    }

    private void stageEFinish(World w) {
        sePhase = SE_DONE;
        if (seZombie != null) {
            seZombie.setDead();
        }
        for (World ww : new World[] {w, DimensionManager.getWorld(-1)}) {
            EntityVehicleSVA v = seFind(ww, seId);
            for (EntityVehicleSVA x : new EntityVehicleSVA[] {v, shuttle, ship}) {
                if (x != null) {
                    for (net.minecraft.entity.Entity e : x.riders()) {
                        e.mountEntity(null);
                        e.setDead();
                    }
                    x.setDead();
                }
            }
        }
        shuttle = ship = null;
        for (net.minecraft.entity.Entity e : new net.minecraft.entity.Entity[] {sePilot, sePig}) {
            if (e != null) {
                e.setDead();
            }
        }
        if (seBridge != null) {
            if (seBridge.isOpen()) {
                seBridge.closePortal("sc.bridge.journal.timeout");
            }
            seFill(w, bX - 4, bY - 2, bZ - 10, bX + 4, bY + 8, bZ + 3, Blocks.air);
            seFill(w, tX - 4, tY - 1, tZ - 3, tX + 4, tY + 6, tZ + 12, Blocks.air);
            seBridge = null;
        }
        if (ex0 != 0 || ez != 0) {
            seFill(w, ex0 - 8, EY, ez - 6, shipX + 8, EY + 26, ez + 6, Blocks.air);
            seFill(w, destX - 61, EY + 4, ez - 1, destX - 59, EY + 6, ez + 1, Blocks.air);
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        check(reg.count(OWNER_E) == 0, "stage E cleanup: no test vehicles left in the registry");
        eDone = true;
        maybeSummary();
    }

    /**
     * Cargo screen v2 in the world: a drill vehicle with a messy bunker is sorted (merged, ordered), its junk thrown out
     * next to it (dropped item count, the cobblestone stock kept), a drill head put in by right-clicking it (owner: into
     * the head slot, the old head into the hand; a stranger: refused; sneaking: opens the screen instead - not tested).
     */
    private void cargoV2World(World w) {
        net.minecraftforge.common.util.FakePlayer owner = net.minecraftforge.common.util.FakePlayerFactory.get((net.minecraft.world.WorldServer) w,
                new com.mojang.authlib.GameProfile(java.util.UUID.fromString(OWNER), "WorldTest"));
        net.minecraftforge.common.util.FakePlayer other = net.minecraftforge.common.util.FakePlayerFactory.get((net.minecraft.world.WorldServer) w,
                new com.mojang.authlib.GameProfile(java.util.UUID.fromString("00000000-5bad-4e57-0000-0000000000ff"), "Stranger"));
        EntityVehicleSVA d = cvSpawn(w, VehicleType.DRILL, cx0 + 12.5, cz0 - 9.0);
        if (d == null) {
            check(false, "cargo v2: a drill vehicle placed");
            return;
        }
        try {
            net.minecraft.item.Item cob = net.minecraft.item.Item.getItemFromBlock(Blocks.cobblestone);
            d.setInventorySlotContents(0, new ItemStack(cob, 50));
            d.setInventorySlotContents(5, new ItemStack(Items.diamond, 3));
            d.setInventorySlotContents(9, new ItemStack(cob, 50));
            d.setInventorySlotContents(30, new ItemStack(Blocks.dirt, 40));
            d.setInventorySlotContents(60, new ItemStack(Items.diamond, 4));
            d.setInventorySlotContents(70, new ItemStack(cob, 50));
            d.setInventorySlotContents(100, new ItemStack(Blocks.gravel, 12));
            d.setInventorySlotContents(107, new ItemStack(Items.coal, 7));
            int stacks = d.sortCargo();
            ItemStack s0 = d.getStackInSlot(0), s1 = d.getStackInSlot(1), s2 = d.getStackInSlot(2), s3 = d.getStackInSlot(3);
            int used = d.state.cargoUsed();
            boolean packed = true;
            for (int i = used; i < d.state.cargo.length; i++) {
                packed &= d.state.cargo[i] == null;
            }
            int dia = 0;
            for (ItemStack s : d.state.cargo) {
                if (s != null && s.getItem() == Items.diamond) {
                    dia += s.stackSize;
                    packed &= s.stackSize == 7;
                }
            }
            check(stacks == 7 && used == 7 && packed && dia == 7 && s0 != null && s0.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.dirt)
                    && s1 != null && s1.getItem() == cob && s1.stackSize == 64 && s2 != null && s2.getItem() == cob && s2.stackSize == 64 && s3 != null
                    && s3.getItem() == cob && s3.stackSize == 22,
                    "cargo v2: a messy bunker (8 stacks over both pages) sorted - dirt (id 3) first, 150 cobblestone 64/64/22, 3 + 4 diamonds -> 7, no gaps (" + stacks + " stacks)");
            java.util.List<?> before = w.getEntitiesWithinAABB(net.minecraft.entity.item.EntityItem.class, d.boundingBox.expand(5, 3, 5));
            int dropped = d.dumpJunk();
            java.util.List<?> after = w.getEntitiesWithinAABB(net.minecraft.entity.item.EntityItem.class, d.boundingBox.expand(5, 3, 5));
            int onGround = 0;
            for (Object o : after) {
                if (!before.contains(o)) {
                    onGround += ((net.minecraft.entity.item.EntityItem) o).getEntityItem().stackSize;
                }
            }
            int cobLeft = 0, junkLeft = 0;
            for (ItemStack s : d.state.cargo) {
                if (s != null && s.getItem() == cob) {
                    cobLeft += s.stackSize;
                } else if (s != null && (s.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.dirt)
                        || s.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.gravel))) {
                    junkLeft += s.stackSize;
                }
            }
            check(dropped == 86 + 40 + 12 && onGround == dropped && cobLeft == EntityVehicleSVA.JUNK_KEEP_COBBLE && junkLeft == 0,
                    "cargo v2: junk dropped next to the drill - " + dropped + " items on the ground (" + onGround + "), 64 cobblestone kept for lava");
            for (Object o : after) {
                if (!before.contains(o)) {
                    ((net.minecraft.entity.Entity) o).setDead();
                }
            }
            // a drill head by right click
            ItemStack steel = new ItemStack(com.sc.init.ModItems.DRILL_HEADS.get(0));
            ItemStack diamondHead = new ItemStack(com.sc.init.ModItems.DRILL_HEADS.get(Math.min(2, com.sc.init.ModItems.DRILL_HEADS.size() - 1)));
            other.setSneaking(false);
            other.inventory.currentItem = 0;
            other.inventory.setInventorySlotContents(0, steel.copy());
            d.interactFirst(other);
            check(d.getStackInSlot(d.headSlot()) == null && other.inventory.getStackInSlot(0) != null,
                    "cargo v2: a stranger's right click with a head does not install it");
            other.inventory.setInventorySlotContents(0, null);
            owner.setSneaking(false);
            owner.inventory.currentItem = 0;
            owner.inventory.setInventorySlotContents(0, steel.copy());
            d.interactFirst(owner);
            ItemStack in = d.getStackInSlot(d.headSlot());
            check(in != null && in.getItem() == steel.getItem() && owner.inventory.getStackInSlot(0) == null && d.ridersNow() == 0,
                    "cargo v2: the owner's right click with a steel head installs it (hand empty, nobody seated)");
            owner.inventory.setInventorySlotContents(0, diamondHead.copy());
            d.interactFirst(owner);
            in = d.getStackInSlot(d.headSlot());
            ItemStack hand = owner.inventory.getStackInSlot(0);
            check(in != null && in.getItem() == diamondHead.getItem() && hand != null && hand.getItem() == steel.getItem(),
                    "cargo v2: a second head swaps - the diamond head in the slot, the steel one back in the hand");
            owner.inventory.setInventorySlotContents(0, null);
            // Shift+click from the player's inventory: a head into a free head slot
            d.setInventorySlotContents(d.headSlot(), null);
            ContainerCargoProbe probe = new ContainerCargoProbe(owner, d);
            check(probe.headByShiftClick(steel.copy()), "cargo v2: shift+click a head in the player's inventory -> the head slot");
            owner.inventory.clearInventory(null, -1);
        } finally {
            d.setDead();
        }
    }

    /** Drives ContainerCargoSVA.transferStackInSlot with a real container (server side). */
    private static final class ContainerCargoProbe {
        private final net.minecraft.entity.player.EntityPlayer p;
        private final EntityVehicleSVA v;

        ContainerCargoProbe(net.minecraft.entity.player.EntityPlayer p, EntityVehicleSVA v) {
            this.p = p;
            this.v = v;
        }

        boolean headByShiftClick(ItemStack head) {
            p.inventory.setInventorySlotContents(9, head);                 // first main-inventory slot
            com.sva.inventory.ContainerCargoSVA c = new com.sva.inventory.ContainerCargoSVA(p.inventory, v);
            int own = v.getSizeInventory();
            c.transferStackInSlot(p, own);                                  // that slot's index in the container
            ItemStack in = v.getStackInSlot(v.headSlot());
            return in != null && in.getItem() == head.getItem() && p.inventory.getStackInSlot(9) == null;
        }
    }
}
