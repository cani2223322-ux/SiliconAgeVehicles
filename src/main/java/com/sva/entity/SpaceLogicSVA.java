package com.sva.entity;

import java.util.List;

import com.sc.bridge.BridgeItemDataSC;
import com.sc.bridge.BridgeMathSC;
import com.sc.bridge.BridgeStructureSC;
import com.sc.init.ModBlocks;
import com.sc.item.ItemCoordinatorSC;
import com.sc.tileentity.TileEntityBridgeControllerSC;
import com.sc.tileentity.TileEntityBridgeVortexSC;
import com.sc.tileentity.TileEntityFieldGeneratorSC;
import com.sc.util.ArmorGasSC.Gas;
import com.sva.vehicle.SpaceMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * Stage E, server side: the shuttle's and the ship's cabin oxygen, invisibility for mobs, the gravity anchor's upkeep,
 * docking with an open core bridge vortex and the Singular Ship's own portal «Прыжок». The numbers are SpaceMath's,
 * the moving VehicleTransferSVA's. Called from EntityVehicleSVA.serverUpdate / serverAction.
 */
public final class SpaceLogicSVA {

    private SpaceLogicSVA() {
    }

    /**
     * Once a tick (server). @return true when the vehicle has just been moved away (docking or a jump) - the old
     * entity is dead then and its update must stop.
     */
    static boolean tick(EntityVehicleSVA v, VehicleType t) {
        if (v.dockCooldown > 0) {
            v.dockCooldown--;
        }
        v.state.jumpCooldown = SpaceMath.cooldownStep(v.state.jumpCooldown);
        if (t.sealed) {
            breathe(v);
        }
        if (t.singular) {
            if (v.state.invisible) {
                if (v.state.gas(Gas.SINGULAR_MATTER) <= 0) {
                    v.state.invisible = false;
                    v.tellAll("sva.msg.invis.empty");
                } else {
                    v.state.drainGasFraction(Gas.SINGULAR_MATTER, SpaceMath.INVIS_SM_PER_SECOND / 20.0);
                    if (v.ticksExisted % 10 == 0) {
                        clearTargets(v);
                    }
                }
            }
            if (v.state.anchor && v.state.energy <= 0) {
                v.state.anchor = false;
                v.tellAll("sva.msg.anchor.empty");
            }
            if (v.portalTicks > 0) {
                v.portalTicks--;
                if (v.portalTicks % 10 == 0) {
                    v.worldObj.playSoundAtEntity(v, "portal.trigger", 0.6F, 1.6F);
                }
                if (v.portalTicks == 0) {
                    return finishJump(v);
                }
            }
        }
        if (t.bridgeDock) {
            if (v.ticksExisted % 20 == 0) {
                v.dockNear = !v.riders().isEmpty() && vortexWithin(v, SpaceMath.DOCK_HINT_RADIUS);
            }
            if (v.dockCooldown == 0 && !v.isHeld() && v.pendingRiders == null) {
                return tryDock(v);
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ cabin oxygen

    /** Riders under water breathe the cabin's O2 (1 mB/s each) - their air stays full while it lasts. */
    static void breathe(EntityVehicleSVA v) {
        int breathing = 0;
        boolean o2 = v.state.gas(Gas.OXYGEN) > 0;
        for (Entity e : v.riders()) {
            if (e instanceof EntityLivingBase && e.isInsideOfMaterial(Material.water)) {
                breathing++;
                if (o2) {
                    e.setAir(300);
                }
            }
        }
        v.breathing = breathing;
        if (o2 && breathing > 0) {
            int before = v.state.gas(Gas.OXYGEN);
            v.state.drainGasFraction(Gas.OXYGEN, breathing * SpaceMath.O2_PER_SECOND / 20.0);
            if (before > 0 && v.state.gas(Gas.OXYGEN) == 0) {
                v.tellAll("sva.msg.o2.empty");
            }
        }
    }

    // ------------------------------------------------------------------ invisibility

    /** Is `e` seated in a vehicle whose invisibility is on (server)? */
    public static boolean cloaked(Entity e) {
        EntityVehicleSVA v = vehicleOf(e);
        return v != null && !v.isDead && v.state.invisible;
    }

    static EntityVehicleSVA vehicleOf(Entity e) {
        if (e == null) {
            return null;
        }
        if (e.ridingEntity instanceof EntityVehicleSVA) {
            return (EntityVehicleSVA) e.ridingEntity;
        }
        if (e.ridingEntity instanceof EntitySeatSVA) {
            return ((EntitySeatSVA) e.ridingEntity).getParent();
        }
        return null;
    }

    /** Hostile mobs within 32 blocks forget the riders (the old AI's entityToAttack too; EventsSVA stops new targets). */
    static void clearTargets(EntityVehicleSVA v) {
        List<?> list = v.worldObj.getEntitiesWithinAABB(EntityLiving.class, v.boundingBox.expand(32, 16, 32));
        for (Object o : list) {
            if (!(o instanceof IMob)) {
                continue;
            }
            EntityLiving m = (EntityLiving) o;
            if (m.getAttackTarget() != null && vehicleOf(m.getAttackTarget()) == v) {
                m.setAttackTarget(null);
            }
            if (m instanceof EntityCreature && ((EntityCreature) m).getEntityToAttack() != null
                    && vehicleOf(((EntityCreature) m).getEntityToAttack()) == v) {
                ((EntityCreature) m).setTarget(null);
            }
        }
    }

    // ------------------------------------------------------------------ bridge docking

    /** An open bridge vortex cell within r blocks (the HUD hint). */
    static boolean vortexWithin(EntityVehicleSVA v, int r) {
        int x0 = MathHelper.floor_double(v.posX), y0 = MathHelper.floor_double(v.posY), z0 = MathHelper.floor_double(v.posZ);
        for (int x = x0 - r; x <= x0 + r; x++) {
            for (int z = z0 - r; z <= z0 + r; z++) {
                for (int y = y0 - 2; y <= y0 + r; y++) {
                    if (v.worldObj.getBlock(x, y, z) == ModBlocks.bridgeVortex) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The vehicle's box touches an open vortex: through to the other end. @return moved */
    static boolean tryDock(EntityVehicleSVA v) {
        AxisAlignedBB bb = v.boundingBox.expand(0.25, 0.25, 0.25);
        int x0 = MathHelper.floor_double(bb.minX), x1 = MathHelper.floor_double(bb.maxX), y0 = MathHelper.floor_double(bb.minY),
                y1 = MathHelper.floor_double(bb.maxY), z0 = MathHelper.floor_double(bb.minZ), z1 = MathHelper.floor_double(bb.maxZ);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (v.worldObj.getBlock(x, y, z) != ModBlocks.bridgeVortex) {
                        continue;
                    }
                    TileEntity te = v.worldObj.getTileEntity(x, y, z);
                    if (te instanceof TileEntityBridgeVortexSC) {
                        TileEntityBridgeControllerSC c = ((TileEntityBridgeVortexSC) te).controller();
                        if (c != null) {
                            return dock(v, c, ((TileEntityBridgeVortexSC) te).getEnd());
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * The other end of the open portal for something that entered end `end`: {dim, x, y, z, yaw} - the bottom middle of
     * the vortex plane `out` blocks out along its normal (the side the core lets players out), facing away from it.
     */
    public static double[] exitOf(TileEntityBridgeControllerSC c, int end, double out) {
        int dim, x, y, z, axis, d;
        if (end == 0) {
            int[] b = c.getEndB();
            dim = b[0];
            x = b[1];
            y = b[2];
            z = b[3];
            axis = b[4];
            d = 1;
        } else if (c.getEndA() != null) {
            int[] a = c.getEndA();
            dim = a[0];
            x = a[1];
            y = a[2];
            z = a[3];
            axis = a[4];
            d = 1;
        } else {
            BridgeStructureSC.Scan s = c.getScan();
            axis = s == null ? 0 : s.axis;
            d = SpaceMath.ringExitSide(axis, c.getFacing());
            int[] p = BridgeStructureSC.at(c.xCoord, c.yCoord, c.zCoord, axis, 0, 2, 0);
            dim = c.getWorldObj().provider.dimensionId;
            x = p[0];
            y = p[1];
            z = p[2];
        }
        double nx = axis == 0 ? 0 : d, nz = axis == 0 ? d : 0;
        return new double[] {dim, x + 0.5 + nx * out, y, z + 0.5 + nz * out, SpaceMath.exitYaw(axis, d)};
    }

    /** Docks through controller c's open portal (entered at `end`): access, the far side's room and claims, the mass toll. */
    static boolean dock(EntityVehicleSVA v, TileEntityBridgeControllerSC c, int end) {
        VehicleType t = v.state.type;
        EntityPlayer pilot = v.riddenByEntity instanceof EntityPlayer ? (EntityPlayer) v.riddenByEntity : null;
        boolean access = pilot != null ? c.allowed(pilot) : c.ownerless() || c.getAccess() == BridgeMathSC.ACCESS_PUBLIC;
        if (!access) {
            refuse(v, "sva.msg.dock.access", c.getOwner());
            return false;
        }
        double[] ex = exitOf(c, end, t.width / 2.0 + 2.5);
        int dim = (int) ex[0];
        WorldServer to = VehicleTransferSVA.world(v.worldObj, dim);
        if (to == null) {
            refuse(v, "sva.msg.dock.noDim", Integer.valueOf(dim));
            return false;
        }
        VehicleTransferSVA.loadAround(to, ex[1], ex[3], 1);
        double y = -1;
        for (int up = 0; up <= 3 && y < 0; up++) {
            AxisAlignedBB box = AxisAlignedBB.getBoundingBox(ex[1] - t.width / 2.0, ex[2] + up, ex[3] - t.width / 2.0,
                    ex[1] + t.width / 2.0, ex[2] + up + t.height, ex[3] + t.width / 2.0).contract(0.05, 0.05, 0.05);
            if (to.func_147461_a(box).isEmpty() && !to.isMaterialInBB(box, Material.lava)) {
                y = ex[2] + up;
            }
        }
        if (y < 0) {
            refuse(v, "sva.msg.dock.blocked");
            return false;
        }
        if (foreignField(to, pilot, MathHelper.floor_double(ex[1]), MathHelper.floor_double(y), MathHelper.floor_double(ex[3]))) {
            refuse(v, "sva.msg.dock.field");
            return false;
        }
        List<Entity> riders = v.riders();
        int[] masses = new int[riders.size()];
        for (int i = 0; i < masses.length; i++) {
            masses[i] = TileEntityBridgeControllerSC.massOf(riders.get(i));
        }
        BridgeStructureSC.Scan s = c.getScan();
        long eu = BridgeMathSC.massEu(SpaceMath.dockMass(masses), s != null && s.mass);
        if (v.state.energy < eu) {
            refuse(v, "sva.msg.dock.eu", fmt(eu));
            return false;
        }
        v.state.energy -= eu;
        World from = v.worldObj;
        double fx = v.posX, fy = v.posY, fz = v.posZ;
        String name = c.getBridgeName();
        EntityVehicleSVA n = VehicleTransferSVA.transfer(v, dim, ex[1], y, ex[3], (float) ex[4]);
        if (n == null) {
            v.state.energy += eu;
            refuse(v, "sva.msg.dock.failed");
            return false;
        }
        from.playSoundEffect(fx, fy, fz, "mob.endermen.portal", 1.0F, 0.8F);
        to.playSoundEffect(ex[1], y, ex[3], "mob.endermen.portal", 1.0F, 0.8F);
        n.pendingMsg = new ChatComponentTranslation("sva.msg.dock.done", name == null || name.length() == 0 ? "—" : name,
                Integer.valueOf(MathHelper.floor_double(ex[1])), Integer.valueOf(MathHelper.floor_double(y)),
                Integer.valueOf(MathHelper.floor_double(ex[3])), fmt(eu));
        return true;
    }

    /** A private field at the spot refuses this pilot (a non-player pilot: any private field refuses). */
    static boolean foreignField(World w, EntityPlayer pilot, int x, int y, int z) {
        if (pilot != null) {
            return com.sc.ShieldEventHandler.privateFor(w, pilot, x, y, z);
        }
        return TileEntityFieldGeneratorSC.fieldWith(w, TileEntityFieldGeneratorSC.F_PRIVATE, x + 0.5, y + 0.5, z + 0.5) != null;
    }

    private static void refuse(EntityVehicleSVA v, String key, Object... args) {
        v.dockCooldown = 40;                                        // no message every tick while it sits in the vortex
        v.warnDriver(key, args);
    }

    // ------------------------------------------------------------------ the Singular Ship's jump

    /** A jump target: {x, y, z, dim}, its name ("" for none), «дом» or not; y may be AUTO (Integer.MIN_VALUE): the surface. */
    public static final class Target {
        public final int x, y, z, dim;
        public final String name;
        public final boolean home;

        Target(int x, int y, int z, int dim, String name, boolean home) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
            this.name = name == null ? "" : name;
            this.home = home;
        }
    }

    /** The ship's target now: «дом» (the pilot's / owner's bed, else the world spawn) or the first Coordinator with a point in the cargo. */
    public static Target target(EntityVehicleSVA v) {
        if (v.state.jumpHome) {
            EntityPlayer p = owner(v);
            ChunkCoordinates bed = p != null ? p.getBedLocation(0) : null;
            if (bed == null) {
                World w0 = net.minecraftforge.common.DimensionManager.getWorld(0);
                bed = w0 == null ? null : w0.getSpawnPoint();
            }
            return bed == null ? null : new Target(bed.posX, Integer.MIN_VALUE, bed.posZ, 0, "", true);
        }
        for (ItemStack s : v.state.cargo) {
            if (ItemCoordinatorSC.isCoordinator(s)) {
                int[] pt = BridgeItemDataSC.point(s);
                if (pt != null) {
                    return new Target(pt[0], pt[1], pt[2], pt[3], BridgeItemDataSC.pointName(s), false);
                }
            }
        }
        return null;
    }

    /** The pilot if a player, else the owner when online. */
    static EntityPlayer owner(EntityVehicleSVA v) {
        if (v.riddenByEntity instanceof EntityPlayer) {
            return (EntityPlayer) v.riddenByEntity;
        }
        MinecraftServer s = MinecraftServer.getServer();
        if (s == null || v.state.ownerId == null || v.state.ownerId.length() == 0) {
            return null;
        }
        for (Object o : s.getConfigurationManager().playerEntityList) {
            EntityPlayer p = (EntityPlayer) o;
            if (v.state.isOwner(p.getUniqueID())) {
                return p;
            }
        }
        return null;
    }

    /** {code, dist, eu, sm} of a jump to `tg` now (no world changes: the destination's room is checked at the jump). */
    public static long[] preview(EntityVehicleSVA v, Target tg) {
        boolean has = tg != null;
        boolean other = has && tg.dim != v.worldObj.provider.dimensionId;
        boolean dimOk = has && (!other || net.minecraftforge.common.DimensionManager.isDimensionRegistered(tg.dim));
        long dist = has && !other ? SpaceMath.distance(MathHelper.floor_double(v.posX), MathHelper.floor_double(v.posZ), tg.x, tg.z) : 0;
        int code = SpaceMath.jumpCheck(has, dimOk, other, dist, v.state.jumpCooldown, v.state.energy, v.state.gas(Gas.SINGULAR_MATTER));
        return new long[] {code, dist, has ? SpaceMath.jumpEu(dist, other) : 0, has ? SpaceMath.jumpSm(dist, other) : 0};
    }

    /** The destination's y: the target's, or (AUTO) the surface there. */
    static int arrivalY(World w, Target tg) {
        if (tg.y != Integer.MIN_VALUE) {
            return tg.y;
        }
        return w.getTopSolidOrLiquidBlock(tg.x, tg.z);
    }

    /** Free space for the ship at the destination (SpaceMath.freeSpace): air, plants, water - not solid blocks or lava. */
    public static boolean freeAt(final World w, int x, int y, int z) {
        if (y < 1 || y + SpaceMath.JUMP_HEIGHT > w.getHeight()) {
            return false;
        }
        return SpaceMath.freeSpace(new SpaceMath.Solid() {
            @Override
            public boolean blocked(int bx, int by, int bz) {
                Block b = w.getBlock(bx, by, bz);
                if (b.getMaterial() == Material.lava) {
                    return true;
                }
                return !b.getMaterial().isLiquid() && b.getCollisionBoundingBoxFromPool(w, bx, by, bz) != null;
            }
        }, x, y, z);
    }

    /** The destination as it must be for the jump: {code, y}. Loads its chunks. */
    static int[] destination(EntityVehicleSVA v, Target tg) {
        WorldServer to = VehicleTransferSVA.world(v.worldObj, tg.dim);
        if (to == null) {
            return new int[] {SpaceMath.NO_DIM, 0};
        }
        VehicleTransferSVA.loadAround(to, tg.x + 0.5, tg.z + 0.5, 1);
        int y = arrivalY(to, tg);
        if (!freeAt(to, tg.x, y, tg.z)) {
            return new int[] {SpaceMath.BLOCKED, y};
        }
        EntityPlayer pilot = v.riddenByEntity instanceof EntityPlayer ? (EntityPlayer) v.riddenByEntity : null;
        if (foreignField(to, pilot, tg.x, y, tg.z)) {
            return new int[] {SpaceMath.FIELD, y};
        }
        return new int[] {SpaceMath.OK, y};
    }

    /**
     * «Прыжок» (the pilot's key; the world test calls it): checks the target, cooldown, EU / SM, the destination's room
     * and claims, then opens the portal ahead (PORTAL_TICKS, the ship held) - finishJump takes it through.
     * @return SpaceMath.OK or why not (the riders are told)
     */
    public static int startJump(EntityVehicleSVA v) {
        VehicleType t = v.state.type;
        if (!t.singular) {
            return SpaceMath.NOT_SHIP;
        }
        if (v.portalTicks > 0 || v.pendingRiders != null) {
            return SpaceMath.BUSY;
        }
        Target tg = target(v);
        long[] pv = preview(v, tg);
        int code = (int) pv[0];
        if (code == SpaceMath.OK) {
            int[] d = destination(v, tg);
            code = d[0];
        }
        if (code != SpaceMath.OK) {
            v.tellAll("sva.msg.jump.no." + code, Integer.valueOf(v.state.jumpCooldown / 20 + 1), fmt(pv[2]), fmt(pv[3]),
                    Integer.valueOf(SpaceMath.MIN_JUMP));
            return code;
        }
        v.portalTicks = SpaceMath.PORTAL_TICKS;
        v.portalTarget = tg;
        v.worldObj.playSoundAtEntity(v, "portal.trigger", 1.0F, 1.2F);
        v.tellAll("sva.msg.jump.opening", Long.valueOf(pv[1]), fmt(pv[2]), fmt(pv[3]));
        return SpaceMath.OK;
    }

    /** The portal is open: checked again, paid, through. @return moved */
    static boolean finishJump(EntityVehicleSVA v) {
        Target tg = v.portalTarget;
        v.portalTarget = null;
        if (tg == null) {
            return false;
        }
        long[] pv = preview(v, tg);
        int code = (int) pv[0];
        int y = 0;
        if (code == SpaceMath.OK) {
            int[] d = destination(v, tg);
            code = d[0];
            y = d[1];
        }
        if (code != SpaceMath.OK) {
            v.tellAll("sva.msg.jump.no." + code, Integer.valueOf(v.state.jumpCooldown / 20 + 1), fmt(pv[2]), fmt(pv[3]),
                    Integer.valueOf(SpaceMath.MIN_JUMP));
            return false;
        }
        double eu0 = v.state.energy;
        int sm0 = v.state.gas(Gas.SINGULAR_MATTER);
        v.state.energy -= pv[2];
        v.state.drainGas(Gas.SINGULAR_MATTER, (int) pv[3], false);
        v.state.jumpCooldown = SpaceMath.JUMP_COOLDOWN;
        World from = v.worldObj;
        double fx = v.posX, fy = v.posY, fz = v.posZ;
        EntityVehicleSVA n = VehicleTransferSVA.transfer(v, tg.dim, tg.x + 0.5, y, tg.z + 0.5, v.rotationYaw);
        if (n == null) {
            v.state.energy = eu0;
            v.state.fillGas(Gas.SINGULAR_MATTER, sm0 - v.state.gas(Gas.SINGULAR_MATTER), false);
            v.state.jumpCooldown = 0;
            v.tellAll("sva.msg.jump.failed");
            return false;
        }
        from.playSoundEffect(fx, fy, fz, "mob.endermen.portal", 1.2F, 0.6F);
        n.worldObj.playSoundEffect(n.posX, n.posY, n.posZ, "mob.endermen.portal", 1.2F, 0.6F);
        n.pendingMsg = new ChatComponentTranslation("sva.msg.jump.done", Integer.valueOf(tg.x), Integer.valueOf(y), Integer.valueOf(tg.z),
                fmt(pv[2]), fmt(pv[3]));
        return true;
    }

    /** The HUD's line (synced): "riders;code;dist;eu;sm;cooldown s;dim;home;name". */
    static String hudString(EntityVehicleSVA v, VehicleType t) {
        StringBuilder b = new StringBuilder();
        b.append(v.riders().size()).append(';');
        if (!t.singular) {
            return b.append("0;0;0;0;0;0;0;").toString();
        }
        Target tg = target(v);
        long[] pv = preview(v, tg);
        b.append(pv[0]).append(';').append(pv[1]).append(';').append(pv[2]).append(';').append(pv[3]).append(';')
                .append((v.state.jumpCooldown + 19) / 20).append(';').append(tg == null ? 0 : tg.dim).append(';')
                .append(tg != null && tg.home ? 1 : 0).append(';');
        if (tg != null) {
            b.append(tg.name.replace(';', ','));
        }
        return b.toString();
    }

    static String fmt(long n) {
        return com.sva.FormatSVA.group(n);
    }
}
