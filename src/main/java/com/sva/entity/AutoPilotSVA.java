package com.sva.entity;

import com.sva.vehicle.AutoDelivery;
import com.sva.vehicle.VehicleState;

import net.minecraft.block.BlockChest;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * The cargo crawler's auto-delivery in the world (AutoDelivery has the state machine and the steering): no driver,
 * the server drives it through the same physics as a player's keys. Loaded chunks only (it never loads one): the chunk
 * 4 blocks ahead not loaded - it pauses (autoReason R_UNLOADED, motor off), looks again every PAUSE_CHECK ticks and
 * drives on by itself once a player brings the chunk in; stuck for 10 s - it stops (the owner is told if online,
 * both times). At A it loads from the nearest chest
 * (any IInventory within AutoDelivery.SEARCH blocks; double chests whole), at B it unloads into one. A chest in a
 * private field that refuses the owner, or one a claim mod won't let him open, is not touched.
 */
public final class AutoPilotSVA {

    /** Paused at an unloaded chunk: the chunk ahead is looked at again this often (ticks). */
    public static final int PAUSE_CHECK = 20;

    private AutoPilotSVA() {
    }

    /** The point the state works with ({x, y, z, dim}) or null. */
    static int[] point(VehicleState st) {
        int t = AutoDelivery.target(st.autoState);
        return t == 0 ? st.routeA : t == 1 ? st.routeB : null;
    }

    /** Server, no driver, auto-delivery on: one tick. @return the keys to drive with. `moved`: blocks moved last tick. */
    public static int tick(EntityVehicleSVA v, double moved) {
        VehicleState st = v.state;
        int s = st.autoState;
        if (s == AutoDelivery.OFF || s == AutoDelivery.STOPPED) {
            return 0;
        }
        int[] p = point(st);
        if (p == null || p[3] != v.worldObj.provider.dimensionId) {
            stop(v, p == null ? AutoDelivery.R_ROUTE : AutoDelivery.R_DIMENSION);
            return 0;
        }
        double tx = p[0] + 0.5, tz = p[2] + 0.5;
        v.autoDist = AutoDelivery.distance(v.posX, v.posZ, tx, tz);
        if (AutoDelivery.driving(s)) {
            if (AutoDelivery.arrived(tx - v.posX, tz - v.posZ)) {
                st.autoState = AutoDelivery.onArrive(s);
                v.autoTimer = 0;
                v.autoBlocked = 0;
                return EntityVehicleSVA.IN_BRAKE;
            }
            boolean paused = v.autoReason == AutoDelivery.R_UNLOADED;
            if (paused && ++v.autoTimer % PAUSE_CHECK != 0) {
                return 0;                                           // paused: look again every PAUSE_CHECK ticks
            }
            double[] ahead = v.toWorld(4, 0);
            int ax = MathHelper.floor_double(v.posX + ahead[0]), az = MathHelper.floor_double(v.posZ + ahead[1]);
            if (!v.worldObj.blockExists(ax, MathHelper.floor_double(v.posY), az)) {
                if (!paused) {
                    pause(v);
                }
                return 0;
            }
            if (paused) {
                v.autoReason = AutoDelivery.R_NONE;                 // the chunk came in: on our way again
                v.autoTimer = 0;
                v.autoBlocked = 0;
            }
            int in = AutoDelivery.steer(v.rotationYaw, v.posX, v.posZ, tx, tz);
            v.autoBlocked = AutoDelivery.blockedTicks(v.autoBlocked, (in & EntityVehicleSVA.IN_FWD) != 0, moved);
            if (AutoDelivery.stuck(v.autoBlocked)) {
                stop(v, AutoDelivery.R_BLOCKED);
                return 0;
            }
            return in;
        }
        // at an end: a transfer step every TRANSFER_EVERY ticks
        if (++v.autoTimer % AutoDelivery.TRANSFER_EVERY == 0) {
            IInventory chest = findChest(v);
            boolean loading = s == AutoDelivery.LOADING;
            boolean movedAny = chest != null && (loading ? load(chest, v) : unload(v, chest));
            if (movedAny) {
                chest.markDirty();
                v.markDirty();
            }
            st.autoState = AutoDelivery.afterTransfer(s, movedAny, v.cargoEmpty(), !v.cargoHasRoom());
            if (st.autoState != s) {
                v.autoTimer = 0;
            }
        }
        return EntityVehicleSVA.IN_BRAKE;
    }

    /** The chunk ahead is not loaded: wait on the spot (still on the way), the owner told once. */
    static void pause(EntityVehicleSVA v) {
        v.autoReason = AutoDelivery.R_UNLOADED;
        v.autoTimer = 0;
        v.autoBlocked = 0;
        EntityPlayer owner = ownerOnline(v);
        if (owner != null) {
            owner.addChatComponentMessage(new ChatComponentTranslation("sva.msg.auto.paused",
                    Integer.valueOf(MathHelper.floor_double(v.posX)), Integer.valueOf(MathHelper.floor_double(v.posY)),
                    Integer.valueOf(MathHelper.floor_double(v.posZ))));
        }
    }

    static void stop(EntityVehicleSVA v, int reason) {
        v.state.autoState = AutoDelivery.STOPPED;
        v.autoReason = reason;
        v.autoBlocked = 0;
        EntityPlayer owner = ownerOnline(v);
        if (owner != null) {
            owner.addChatComponentMessage(new ChatComponentTranslation("sva.msg.auto.stopped." + reason,
                    Integer.valueOf(MathHelper.floor_double(v.posX)), Integer.valueOf(MathHelper.floor_double(v.posY)),
                    Integer.valueOf(MathHelper.floor_double(v.posZ))));
        }
    }

    static EntityPlayer ownerOnline(EntityVehicleSVA v) {
        for (Object o : v.worldObj.playerEntities) {
            EntityPlayer p = (EntityPlayer) o;
            if (v.state.isOwner(p.getUniqueID()) && !(p instanceof net.minecraftforge.common.util.FakePlayer)) {
                return p;
            }
        }
        return null;
    }

    /** The nearest inventory block within SEARCH of the hull (y -1..+2), not in a private field or claim against the owner. */
    static IInventory findChest(EntityVehicleSVA v) {
        World w = v.worldObj;
        int cx = MathHelper.floor_double(v.posX), cy = MathHelper.floor_double(v.posY), cz = MathHelper.floor_double(v.posZ);
        int r = AutoDelivery.SEARCH;
        IInventory best = null;
        double bestD = Double.MAX_VALUE;
        net.minecraftforge.common.util.FakePlayer fake = null;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int y = cy - 1; y <= cy + 2; y++) {
                    if (!w.blockExists(x, y, z)) {
                        continue;
                    }
                    TileEntity te = w.getTileEntity(x, y, z);
                    if (!(te instanceof IInventory)) {
                        continue;
                    }
                    double d = (x + 0.5 - v.posX) * (x + 0.5 - v.posX) + (z + 0.5 - v.posZ) * (z + 0.5 - v.posZ);
                    if (d >= bestD) {
                        continue;
                    }
                    if (fake == null) {
                        fake = DrillLogicSVA.owner(v);
                    }
                    if (com.sc.ShieldEventHandler.privateFieldAgainst(w, fake, x, y, z) != null || !mayOpen(w, fake, x, y, z)) {
                        continue;
                    }
                    IInventory inv = (IInventory) te;
                    if (te instanceof TileEntityChest && w.getBlock(x, y, z) instanceof BlockChest) {
                        IInventory big = ((BlockChest) w.getBlock(x, y, z)).func_149951_m(w, x, y, z);
                        if (big != null) {
                            inv = big;
                        }
                    }
                    best = inv;
                    bestD = d;
                }
            }
        }
        return best;
    }

    /** Would claim mods let the owner open the block (a right click from his fake player, as by hand)? */
    static boolean mayOpen(World w, net.minecraftforge.common.util.FakePlayer fake, int x, int y, int z) {
        net.minecraftforge.event.entity.player.PlayerInteractEvent ev = new net.minecraftforge.event.entity.player.PlayerInteractEvent(
                fake, net.minecraftforge.event.entity.player.PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK, x, y, z, 1, w);
        return !net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(ev)
                && ev.useBlock != cpw.mods.fml.common.eventhandler.Event.Result.DENY;
    }

    /** Chest -> cargo, up to STACKS_PER_STEP stacks. @return anything moved */
    static boolean load(IInventory chest, EntityVehicleSVA v) {
        int stacks = 0;
        boolean any = false;
        for (int i = 0; i < chest.getSizeInventory() && stacks < AutoDelivery.STACKS_PER_STEP; i++) {
            ItemStack s = chest.getStackInSlot(i);
            if (s == null || !canExtract(chest, i, s) || !v.isItemValidForSlot(0, s)) {
                continue;
            }
            ItemStack moving = s.copy();
            int before = moving.stackSize;
            v.storeCargo(moving);
            int went = before - moving.stackSize;
            if (went > 0) {
                chest.decrStackSize(i, went);
                any = true;
                stacks++;
            }
            if (!v.cargoHasRoom() && moving.stackSize > 0) {
                break;
            }
        }
        return any;
    }

    /** Cargo -> chest, up to STACKS_PER_STEP stacks. @return anything moved */
    static boolean unload(EntityVehicleSVA v, IInventory chest) {
        int stacks = 0;
        boolean any = false;
        ItemStack[] cargo = v.state.cargo;
        for (int i = 0; i < cargo.length && stacks < AutoDelivery.STACKS_PER_STEP; i++) {
            if (cargo[i] == null) {
                continue;
            }
            int before = cargo[i].stackSize;
            int left = insert(chest, cargo[i]);
            if (left < before) {
                any = true;
                stacks++;
            }
            if (left <= 0) {
                cargo[i] = null;
            } else {
                cargo[i].stackSize = left;
            }
        }
        return any;
    }

    private static boolean canExtract(IInventory inv, int slot, ItemStack s) {
        return !(inv instanceof ISidedInventory) || ((ISidedInventory) inv).canExtractItem(slot, s, 1);
    }

    private static boolean canInsert(IInventory inv, int slot, ItemStack s) {
        return inv.isItemValidForSlot(slot, s) && (!(inv instanceof ISidedInventory) || ((ISidedInventory) inv).canInsertItem(slot, s, 1));
    }

    /** Puts a copy of `s` into `inv` (merging first). @return how many are left over */
    static int insert(IInventory inv, ItemStack s) {
        int left = s.stackSize;
        int limit = Math.min(inv.getInventoryStackLimit(), s.getMaxStackSize());
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int i = 0; i < inv.getSizeInventory() && left > 0; i++) {
                ItemStack have = inv.getStackInSlot(i);
                if (pass == 0 && have != null && have.isItemEqual(s) && ItemStack.areItemStackTagsEqual(have, s) && have.stackSize < limit
                        && canInsert(inv, i, s)) {
                    int put = Math.min(left, limit - have.stackSize);
                    have.stackSize += put;
                    inv.setInventorySlotContents(i, have);
                    left -= put;
                } else if (pass == 1 && have == null && canInsert(inv, i, s)) {
                    ItemStack put = s.copy();
                    put.stackSize = Math.min(left, limit);
                    inv.setInventorySlotContents(i, put);
                    left -= put.stackSize;
                }
            }
        }
        return left;
    }
}
