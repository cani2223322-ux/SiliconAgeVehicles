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

    private static final com.sc.util.ArmorGasSC.Gas H2 = com.sc.util.ArmorGasSC.Gas.HYDROGEN, HE = com.sc.util.ArmorGasSC.Gas.HELIUM;

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
        System.out.println("[SVA-WORLDTEST] SUMMARY passed=" + passed + " failed=" + failed);
        System.out.println("[SVA-WORLDTEST] DONE");
    }

}
