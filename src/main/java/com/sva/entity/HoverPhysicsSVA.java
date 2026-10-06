package com.sva.entity;

import com.sva.vehicle.HoverMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Movement.HOVER physics (driver client and the server's fallback alike, like the ground physics): holds
 * HoverMath's height over ground / water / lava under the centre and the nose, never higher than HOVER_CAP;
 * thrust only with lift; without lift it glides down and lands - on a liquid it floats on the surface.
 * Stages B-E add their own classes like this one (HeliPhysics, PlanePhysics) and a case in physicsStep.
 */
public final class HoverPhysicsSVA {

    private HoverPhysicsSVA() {
    }

    /**
     * The first surface at or below y (from y + 0.5 down HoverMath.SCAN blocks) at (x, z): {top Y, 1 if a liquid}.
     * {NaN, 0} when there is none.
     */
    public static double[] surface(World w, double x, double y, double z) {
        int bx = MathHelper.floor_double(x), bz = MathHelper.floor_double(z);
        int top = MathHelper.floor_double(y + 0.5);
        for (int by = top; by >= top - HoverMath.SCAN - 1 && by >= 0; by--) {
            Block b = w.getBlock(bx, by, bz);
            if (b.getMaterial().isLiquid()) {
                int meta = w.getBlockMetadata(bx, by, bz);
                return new double[] {by + 1 - BlockLiquid.getLiquidHeightPercent(meta), 1};
            }
            AxisAlignedBB bb = b.getCollisionBoundingBoxFromPool(w, bx, by, bz);
            if (bb != null && bb.maxY <= y + 0.5) {
                return new double[] {bb.maxY, 0};
            }
        }
        return new double[] {Double.NaN, 0};
    }

    /** Height of the bottom at (x, y, z) over the surface under it (NaN: none within the scan). */
    public static double heightAt(World w, double x, double y, double z) {
        return y - surface(w, x, y, z)[0];
    }

    public static void step(EntityVehicleSVA v, VehicleType t, int in) {
        boolean fwd = (in & EntityVehicleSVA.IN_FWD) != 0, back = (in & EntityVehicleSVA.IN_BACK) != 0,
                left = (in & EntityVehicleSVA.IN_LEFT) != 0, right = (in & EntityVehicleSVA.IN_RIGHT) != 0,
                high = (in & EntityVehicleSVA.IN_BRAKE) != 0, boostKey = (in & EntityVehicleSVA.IN_BOOST) != 0;
        boolean lift = v.hasLift() && v.canThrust();
        boolean boost = lift && boostKey && fwd;
        double top = HoverMath.maxSpeed(t, lift, boost);
        double speed = v.speed;
        if (lift && fwd && !back) {
            speed += speed < 0 ? EntityVehicleSVA.BRAKE : t.accel * (boost ? 1.5 : 1);
        } else if (lift && back && !fwd) {
            speed -= speed > 0 ? EntityVehicleSVA.BRAKE : t.accel * 0.6;
        } else {
            speed = toward0(speed, v.onGround ? EntityVehicleSVA.BRAKE : EntityVehicleSVA.ROLL);
        }
        if (speed > top) {
            speed = Math.max(top, speed - EntityVehicleSVA.BRAKE);
        }
        if (speed < -top * EntityVehicleSVA.REVERSE) {
            speed = Math.min(-top * EntityVehicleSVA.REVERSE, speed + EntityVehicleSVA.BRAKE);
        }
        int steer = (right ? 1 : 0) - (left ? 1 : 0);
        if (steer != 0 && speed != 0) {
            v.rotationYaw += steer * t.turnRate * Math.min(1, Math.abs(speed) / 0.12) * Math.signum(speed);
        }
        double r = Math.toRadians(v.rotationYaw);
        double fx = -Math.sin(r), fz = Math.cos(r);
        // the surface under the centre and under the nose (or the tail when reversing)
        World w = v.worldObj;
        double[] c = surface(w, v.posX, v.posY, v.posZ);
        double probe = t.halfLength * 0.75 * (speed < -0.01 ? -1 : 1);
        double[] f = surface(w, v.posX + fx * probe, v.posY, v.posZ + fz * probe);
        double target = HoverMath.targetY(c[0], f[0], high);
        double vy = HoverMath.verticalSpeed(v.posY, target, lift, v.motionY);
        v.motionX = fx * speed;
        v.motionZ = fz * speed;
        v.fallDistance = 0;                                   // a controlled descent: no landing damage
        double px = v.posX, pz = v.posZ;
        v.moveEntity(v.motionX, vy, v.motionZ);
        v.motionY = v.isCollidedVertically ? 0 : vy;
        v.fallDistance = 0;
        if (!lift && c[1] > 0 && !Double.isNaN(c[0]) && v.posY < c[0] + HoverMath.FLOAT) {
            v.setPosition(v.posX, c[0] + HoverMath.FLOAT, v.posZ);   // came down on water / lava: float on it
            v.motionY = 0;
            v.onGround = true;
        }
        if (v.isCollidedHorizontally) {
            speed = (v.posX - px) * fx + (v.posZ - pz) * fz;
        }
        v.speed = speed;
    }

    private static double toward0(double v, double step) {
        return v > 0 ? Math.max(0, v - step) : Math.min(0, v + step);
    }
}
