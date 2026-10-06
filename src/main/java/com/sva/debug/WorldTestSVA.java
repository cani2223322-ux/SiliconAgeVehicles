package com.sva.debug;

import com.sva.ConfigSVA;
import com.sva.block.TileEntityChargerSVA;
import com.sva.block.TileEntityGasColumnSVA;
import com.sva.vehicle.HoverMath;
import com.sva.block.TileEntityWorkbenchSVA;
import com.sva.entity.EntityVehicleSVA;
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
                    System.out.println("[SVA-WORLDTEST] SUMMARY passed=" + passed + " failed=" + failed);
                }
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
        System.out.println("[SVA-WORLDTEST] SUMMARY passed=" + passed + " failed=" + failed);
        System.out.println("[SVA-WORLDTEST] DONE");
    }

}
