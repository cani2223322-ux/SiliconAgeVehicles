package com.sva.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sc.item.ItemDrillHeadSC;
import com.sc.tileentity.TileEntityQuarrySC;
import com.sva.ConfigSVA;
import com.sva.item.ComponentType;
import com.sva.item.ItemComponentSVA;
import com.sva.vehicle.DrillMath;
import com.sva.vehicle.VehicleState;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * The drill vehicle's bore on the server (DrillMath has the numbers and the geometry): while the drill is on and the
 * vehicle is driven forward it digs the zone ahead with the core quarry drill head in its slot - drops into the cargo
 * (no empty slot: it stops and says so), EU from the vehicle, the head wears (Exo never). Liquids round the bore are
 * walled up with cobblestone from the cargo; a torch from the cargo goes down every 8 blocks in the dark.
 * Never broken: bedrock / unbreakable, blocks harder than the head, tile entities, another player's private field or
 * claim (a BreakEvent per block from the owner's fake player, as the core quarry), spawn protection, unloaded chunks.
 */
public final class DrillLogicSVA {

    /** Liquid blocks walled up a tick at most. */
    private static final int WALL_PER_TICK = 6;

    private DrillLogicSVA() {
    }

    public static ItemDrillHeadSC.Kind headKind(ItemStack head) {
        return head != null && head.getItem() instanceof ItemDrillHeadSC ? ((ItemDrillHeadSC) head.getItem()).kind : null;
    }

    public static boolean isHead(ItemStack s) {
        return headKind(s) != null;
    }

    public static boolean isWidener(ItemStack s) {
        return s != null && s.getItem() instanceof ItemComponentSVA && ItemComponentSVA.typeOf(s) == ComponentType.DRILL_WIDENER;
    }

    /** The «Расширитель бура» is in: a 5x5 bore. */
    public static boolean wide(VehicleState st) {
        return isWidener(st.drillModule);
    }

    /** The owner as a fake player (claims, private fields, spawn protection), like the core quarry. */
    static net.minecraftforge.common.util.FakePlayer owner(EntityVehicleSVA v) {
        WorldServer ws = (WorldServer) v.worldObj;
        net.minecraftforge.common.util.FakePlayer fake;
        UUID id = null;
        try {
            id = v.state.ownerId == null || v.state.ownerId.length() == 0 ? null : UUID.fromString(v.state.ownerId);
        } catch (IllegalArgumentException e) {
            id = null;
        }
        if (id == null) {
            fake = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(ws);
        } else {
            String name = v.state.ownerName == null || v.state.ownerName.length() == 0 ? "[SVA-Drill]" : v.state.ownerName;
            fake = net.minecraftforge.common.util.FakePlayerFactory.get(ws, new com.mojang.authlib.GameProfile(id, name));
        }
        if (fake.worldObj != ws) {
            fake.setWorld(ws);                                  // Forge caches it by profile only
        }
        fake.setPosition(v.posX, v.posY, v.posZ);
        return fake;
    }

    /** May the owner break / change the block (loaded, no private field or claim against him, no spawn protection)? */
    static boolean allowed(World w, net.minecraftforge.common.util.FakePlayer fake, int x, int y, int z) {
        boolean loaded = y >= 0 && y < 256 && w.blockExists(x, y, z);
        if (!loaded) {
            return false;
        }
        boolean field = com.sc.ShieldEventHandler.privateFieldAgainst(w, fake, x, y, z) != null;
        boolean spawn = !w.canMineBlock(fake, x, y, z);
        boolean cancel = false;
        if (!field && !spawn) {
            net.minecraftforge.event.world.BlockEvent.BreakEvent ev = new net.minecraftforge.event.world.BlockEvent.BreakEvent(
                    x, y, z, w, w.getBlock(x, y, z), w.getBlockMetadata(x, y, z), fake);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(ev);
            cancel = ev.isCanceled();
        }
        return DrillMath.allowed(true, field, cancel, spawn);
    }

    /** One tick of the bore (server; the drill is on and driven forward). `moved`: blocks driven this tick. */
    public static void tick(EntityVehicleSVA v, double moved) {
        World w = v.worldObj;
        VehicleState st = v.state;
        ItemStack head = st.drillHead;
        ItemDrillHeadSC.Kind k = headKind(head);
        if (k == null) {
            v.drillStop("sva.msg.drill.noHead");
            return;
        }
        boolean wide = wide(st);
        double per = DrillMath.perTick(k.blocksPerSecond) * (com.sva.ConfigSVA.tuning ? com.sva.vehicle.TuningMath.digMul(st.tuning) : 1);   // ★8 «Усиленный бур»
        v.digBudget = Math.min(v.digBudget + per, per * 2 + 1);
        int y0 = MathHelper.floor_double(v.posY + 0.01);
        net.minecraftforge.common.util.FakePlayer fake = owner(v);
        wallUp(v, fake, DrillMath.ring(v.posX, y0, v.posZ, v.rotationYaw, wide));
        List<int[]> zone = DrillMath.zone(v.posX, y0, v.posZ, v.rotationYaw, st.drillMode, wide);
        List<int[]> liquids = new ArrayList<int[]>();
        for (int[] b : zone) {
            if (v.digBudget < 1) {
                break;
            }
            int x = b[0], y = b[1], z = b[2];
            if (y < 0 || y > 255 || !w.blockExists(x, y, z)) {
                continue;
            }
            Block blk = w.getBlock(x, y, z);
            int meta = w.getBlockMetadata(x, y, z);
            boolean air = blk.isAir(w, x, y, z);
            boolean liquid = !air && blk.getMaterial().isLiquid();
            float hard = air || liquid ? 0 : blk.getBlockHardness(w, x, y, z);
            boolean tile = !air && !liquid && (blk.hasTileEntity(meta) || w.getTileEntity(x, y, z) != null);
            boolean ore = !air && !liquid && TileEntityQuarrySC.isOre(blk, meta);
            int j = DrillMath.judge(air, liquid, hard, k.maxHardness, tile, true, st.drillMode, ore);
            if (j == DrillMath.LIQUID) {
                liquids.add(b);
                continue;
            }
            if (j != DrillMath.DIG || !allowed(w, fake, x, y, z)) {
                continue;
            }
            int eu = DrillMath.euPerBlock(hard, k == ItemDrillHeadSC.Kind.EXO, ConfigSVA.consumption);
            if (st.energy < eu) {
                return;                                     // waits for EU (the hull crawls on)
            }
            if (!v.cargoHasRoom()) {
                v.drillStop("sva.msg.drill.full");
                return;
            }
            List<ItemStack> drops = blk.getDrops(w, x, y, z, meta, 0);
            w.playAuxSFX(2001, x, y, z, Block.getIdFromBlock(blk) + (meta << 12));
            if (ore && v.ticksExisted % 2 == 0) {
                v.oneShot("drill_ore", 0.8F, 0.9F + w.rand.nextFloat() * 0.2F);   // ★1: an ore cracks differently
            }
            w.setBlockToAir(x, y, z);
            for (ItemStack d : drops) {
                if (d != null && d.stackSize > 0 && v.storeCargo(d) > 0) {
                    w.spawnEntityInWorld(new EntityItem(w, v.posX, v.posY + 1, v.posZ, d));   // the last bits spill
                }
            }
            st.energy -= eu;
            v.digBudget -= 1;
            st.dug++;
            if (head.getMaxDamage() > 0) {
                head.setItemDamage(DrillMath.wear(head.getItemDamage(), head.getMaxDamage()));
                if (DrillMath.wornOut(head.getItemDamage(), head.getMaxDamage())) {
                    st.drillHead = null;
                    w.playSoundAtEntity(v, "random.break", 1F, 0.8F);
                    v.drillStop("sva.msg.drill.headBroken");
                    return;
                }
            }
        }
        if (!liquids.isEmpty()) {
            wallUp(v, fake, liquids);
        }
        torch(v, fake, moved, y0);
    }

    /** Liquid blocks among `blocks` become cobblestone from the cargo (WALL_PER_TICK a tick at most). */
    private static void wallUp(EntityVehicleSVA v, net.minecraftforge.common.util.FakePlayer fake, List<int[]> blocks) {
        World w = v.worldObj;
        int done = 0;
        Item cobble = Item.getItemFromBlock(Blocks.cobblestone);
        for (int[] b : blocks) {
            if (done >= WALL_PER_TICK) {
                return;
            }
            int x = b[0], y = b[1], z = b[2];
            if (y < 0 || y > 255 || !w.blockExists(x, y, z) || !w.getBlock(x, y, z).getMaterial().isLiquid()) {
                continue;
            }
            if (!allowed(w, fake, x, y, z)) {
                continue;
            }
            if (!v.takeCargo(cobble, 1)) {
                v.warnDriver("sva.msg.drill.noCobble");
                return;
            }
            w.setBlock(x, y, z, Blocks.cobblestone, 0, 3);
            done++;
        }
    }

    /** Every DrillMath.TORCH_EVERY blocks in the dark: a torch from the cargo on the tunnel floor behind the hull. */
    private static void torch(EntityVehicleSVA v, net.minecraftforge.common.util.FakePlayer fake, double moved, int y0) {
        if (moved > 0 && moved < 2) {
            v.torchDist += moved;
        }
        World w = v.worldObj;
        int cx = MathHelper.floor_double(v.posX), cz = MathHelper.floor_double(v.posZ);
        if (!DrillMath.torchDue(v.torchDist, w.getBlockLightValue(cx, y0 + 1, cz))) {
            return;
        }
        Item torch = Item.getItemFromBlock(Blocks.torch);
        double back = v.getVehicleType().halfLength + 0.5;
        for (double side : new double[] {1.0, -1.0, 0}) {
            double[] o = v.toWorld(-back, side);
            int x = MathHelper.floor_double(v.posX + o[0]), z = MathHelper.floor_double(v.posZ + o[1]);
            if (!w.blockExists(x, y0, z) || !w.isAirBlock(x, y0, z) || !World.doesBlockHaveSolidTopSurface(w, x, y0 - 1, z)
                    || !allowed(w, fake, x, y0, z)) {
                continue;
            }
            if (!v.takeCargo(torch, 1)) {
                v.torchDist = DrillMath.TORCH_EVERY - 2;       // none in the cargo: look again 2 blocks on
                return;
            }
            w.setBlock(x, y0, z, Blocks.torch, 5, 3);
            v.state.torches++;
            v.torchDist = 0;
            return;
        }
        v.torchDist = DrillMath.TORCH_EVERY - 2;
    }
}
