package com.sva.client;

import java.util.List;

import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * Client: clicks on the whole vehicle, not only on its collision box. A vehicle's entity box is a square of
 * VehicleType.width (1.7.10 boxes don't turn) - 3x2 blocks in the middle of a 15-block jet, ship or shuttle - so a
 * right / left click on a wing, the nose or the tail found nothing. At the start of every client tick (before the
 * game handles the mouse buttons) the look ray is also tested against each nearby vehicle's turned model box: the
 * OBJ's bounds (ObjModelSVA.bbMin / bbMax, x the model scale, the crawler's modelOffsetX) turned by the vehicle's yaw
 * as RenderVehicleSVA draws it. When the vanilla ray found nothing nearer (no entity, no block or a block behind the
 * hull) the vehicle becomes objectMouseOver - the usual click, attack and WAILA paths then see it. Pitch / roll of a
 * flying vehicle are ignored (the box stays level), the own vehicle is skipped. The server still checks its own
 * distance to the entity's centre (vanilla: 6 blocks), so the far tips of the biggest vehicles stay out of reach.
 */
public class VehiclePickSVA {

    /** Vehicles whose centre is farther than the reach plus this are not tested (the ship's half-diagonal ~10). */
    static final double SEARCH = 12;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase == TickEvent.Phase.START) {
            pick(Minecraft.getMinecraft());
        }
    }

    static void pick(Minecraft mc) {
        EntityLivingBase eye = mc.renderViewEntity;
        if (mc.theWorld == null || mc.playerController == null || eye == null) {
            return;
        }
        MovingObjectPosition mo = mc.objectMouseOver;
        if (mo != null && mo.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
            return;                                                 // vanilla already points at an entity
        }
        // vanilla's entity reach: 3 blocks in survival, 6 in creative (EntityRenderer.getMouseOver)
        double reach = mc.playerController.extendedReach() ? 6 : Math.min(3, mc.playerController.getBlockReachDistance());
        Vec3 from = eye.getPosition(1F);
        Vec3 look = eye.getLook(1F);
        Vec3 to = from.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
        double blockDist = mo != null && mo.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK && mo.hitVec != null
                ? from.distanceTo(mo.hitVec) : Double.MAX_VALUE;
        EntityVehicleSVA own = HudVehicleSVA.vehicleOf(mc.thePlayer);
        double r = reach + SEARCH;
        List<?> near = mc.theWorld.getEntitiesWithinAABB(EntityVehicleSVA.class, eye.boundingBox.expand(r, r, r));
        EntityVehicleSVA best = null;
        Vec3 bestHit = null;
        double bestDist = Math.min(reach, blockDist);
        for (Object o : near) {
            EntityVehicleSVA v = (EntityVehicleSVA) o;
            if (v == own || v.isDead) {
                continue;
            }
            Vec3 hit = hit(v, from, to);
            if (hit == null) {
                continue;
            }
            double d = from.distanceTo(hit);
            if (d < bestDist) {
                bestDist = d;
                best = v;
                bestHit = hit;
            }
        }
        if (best != null) {
            mc.objectMouseOver = new MovingObjectPosition(best, bestHit);
            mc.pointedEntity = best;
        }
    }

    /** Where the segment from..to enters the vehicle's turned model box (world coordinates), or null. */
    static Vec3 hit(EntityVehicleSVA v, Vec3 from, Vec3 to) {
        double[] box = box(v);
        if (box == null) {
            return null;
        }
        float yaw = v.rotationYaw * (float) Math.PI / 180F;
        double fx = -MathHelper.sin(yaw), fz = MathHelper.cos(yaw);          // forward (model +x)
        double rx = -MathHelper.cos(yaw), rz = -MathHelper.sin(yaw);         // right (model +z)
        Vec3 a = local(v, from, fx, fz, rx, rz), b = local(v, to, fx, fz, rx, rz);
        AxisAlignedBB bb = AxisAlignedBB.getBoundingBox(box[0], box[1], box[2], box[3], box[4], box[5]);
        Vec3 h;
        if (bb.isVecInside(a)) {
            h = a;                                                  // the eye is inside the hull's box (a wing over the head)
        } else {
            MovingObjectPosition m = bb.calculateIntercept(a, b);
            if (m == null || m.hitVec == null) {
                return null;
            }
            h = m.hitVec;
        }
        return Vec3.createVectorHelper(v.posX + h.xCoord * fx + h.zCoord * rx, v.posY + h.yCoord, v.posZ + h.xCoord * fz + h.zCoord * rz);
    }

    /** A world point in the vehicle's frame: x forward, y up, z right (blocks). */
    private static Vec3 local(EntityVehicleSVA v, Vec3 p, double fx, double fz, double rx, double rz) {
        double dx = p.xCoord - v.posX, dz = p.zCoord - v.posZ;
        return Vec3.createVectorHelper(dx * fx + dz * fz, p.yCoord - v.posY, dx * rx + dz * rz);
    }

    /** {minX, minY, minZ, maxX, maxY, maxZ} of the model in the vehicle's frame (blocks), or null (no model). */
    static double[] box(EntityVehicleSVA v) {
        VehicleType t = v.getVehicleType();
        ObjModelSVA m = ObjModelSVA.get(t.model);
        if (m == null || m.bbMin[0] > m.bbMax[0]) {
            return null;
        }
        double s = t.modelScale;
        double back = (m.bbMin[0] - t.modelOffsetX) * s;
        if (t.trailerSlots > 0 && !v.hasTrailer()) {
            back = Math.max(back, -t.halfLength);                   // truck.obj holds the trailer too - drawn only when hitched
        }
        return new double[] {back, Math.min(0, m.bbMin[1] * s), m.bbMin[2] * s,
                (m.bbMax[0] - t.modelOffsetX) * s, m.bbMax[1] * s, m.bbMax[2] * s};
    }
}
