package com.sva.block;

import java.util.ArrayList;
import java.util.List;

import com.sva.compat.EmbassyCompatSVA;
import com.sva.compat.EmbassyFieldSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.EmbassyServiceMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * PACKAGE S (docs/plan-embassy-airfield-addon.md §3.8): where EmbassyServicesSVA moves a machine at an embassy's field - a free stand of
 * its kind (EmbassyServiceMath.fits; not on the books - EmbassyParkingDataSVA - and no other machine standing in its box) or the
 * «Отстой» lot: FULL - the row along the «Аэровокзал»'s side of the apron (EmbassyServiceMath.lotRow, outside the stands and the
 * monument), then beside the helipad «Н»; SHORT / HELI - beside «Н» on its outer sides from the dome, in the field's zone
 * (EmbassyCompatSVA.inZone); a place a machine stands closer than LOT_TAKEN to is taken - the next one; none - «Н» itself.
 * <p>
 * A move is a teleport on the server (setLocationAndAngles on the paving, floorY + 1; the entity tracker sends it to the clients - the
 * seats follow the body on its next tick), only into loaded chunks and only where its box meets no block, liquid or other machine (the
 * same test as ItemVehicleSVA.spawn) - a place that does not fit is skipped, a machine that fits nowhere stays where it is (the next
 * look tries again). A machine is never removed. Server only.
 */
final class EmbassyServiceLotSVA {

    private EmbassyServiceLotSVA() {
    }

    /** The loaded machines of world `w` at field `f` (its zone's box and 32 round it, or under its dome). */
    static List<EntityVehicleSVA> near(World w, EmbassyFieldSVA f) {
        List<EntityVehicleSVA> out = new ArrayList<EntityVehicleSVA>();
        if (w == null || f == null) {
            return out;
        }
        for (Object o : w.loadedEntityList) {
            if (o instanceof EntityVehicleSVA && !((EntityVehicleSVA) o).isDead && at(f, (EntityVehicleSVA) o)) {
                out.add((EntityVehicleSVA) o);
            }
        }
        return out;
    }

    /** Machine `v` is at field `f` (its zone's box grown by 32, or under the dome). */
    static boolean at(EmbassyFieldSVA f, EntityVehicleSVA v) {
        int x = MathHelper.floor_double(v.posX), z = MathHelper.floor_double(v.posZ);
        return EmbassyServiceMath.inGrown(f.zoneBox(), x, z, 32) || f.inDome(v.posX, v.posZ);
    }

    /** Stand `i` of `f` is free for machine `v`: not on the books (or booked by `v` itself) and nobody else standing in it. */
    static boolean standFree(EmbassyFieldSVA f, int i, EntityVehicleSVA v, String vid, EmbassyParkingDataSVA d, List<EntityVehicleSVA> others) {
        EmbassyParkingDataSVA.Stand s = d.stand(f.id, i);
        if (s != null && !s.vid.equals(vid)) {
            return false;
        }
        for (EntityVehicleSVA o : others) {
            if (o != v && !o.isDead && f.spotAt(o.posX, o.posZ) == i) {
                return false;
            }
        }
        return true;
    }

    /**
     * A free stand of `f` for machine `v` (its own kind first, then any it fits; `only` >= 0 - that one only), or -1. The owner's
     * SPOTS_PER_PLAYER is the caller's. Review 2026-10-27: never the stand it stands on now (a tow there would only pay and book again).
     */
    static int freeStand(EmbassyFieldSVA f, EntityVehicleSVA v, String vid, EmbassyParkingDataSVA d, List<EntityVehicleSVA> others, int only) {
        VehicleType t = v.getVehicleType();
        int own = EmbassyServiceMath.spotKindFor(t), here = f.spotAt(v.posX, v.posZ);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < f.spots(); i++) {
                if (only >= 0 && i != only || i == here) {
                    continue;
                }
                int k = f.spotKind(i);
                if (!EmbassyServiceMath.fits(t, k) || (pass == 0) != (k == own)) {
                    continue;
                }
                if (standFree(f, i, v, vid, d, others) && fits(v, f, f.spotBox(i), true)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Moves `v` onto stand `i`'s middle. @return moved */
    static boolean toStand(World w, EmbassyFieldSVA f, EntityVehicleSVA v, int i) {
        return i >= 0 && i < f.spots() && fits(v, f, f.spotBox(i), false);
    }

    /** The «Отстой» lot's places of `f` in order (see the class comment). {x, z} blocks. */
    static List<int[]> lotPlaces(EmbassyFieldSVA f) {
        List<int[]> out = new ArrayList<int[]>();
        if (f.full()) {
            int n = f.spots();
            int[][] avoid = new int[n + 1][];
            for (int i = 0; i < n; i++) {
                avoid[i] = f.spotBox(i);
            }
            avoid[n] = f.monumentBox();
            out.addAll(EmbassyServiceMath.lotRow(f.apronBox(), f.terminalBox(), avoid));
        }
        int[] pad = f.helipadBox();
        for (int[] q : EmbassyServiceMath.padSide(pad, f.coreX, f.coreZ)) {
            if (EmbassyCompatSVA.inZone(f, q[0], q[1]) && !f.inDome(q[0] + 0.5, q[1] + 0.5)) {
                out.add(q);
            }
        }
        if (pad != null) {
            out.add(EmbassyServiceMath.centre(pad));       // no place left: «Н» itself
        }
        return out;
    }

    /** Moves `v` to the first free place of the lot. @return moved */
    static boolean toLot(World w, EmbassyFieldSVA f, EntityVehicleSVA v, List<EntityVehicleSVA> others) {
        for (int[] q : lotPlaces(f)) {
            if (taken(q, v, others)) {
                continue;
            }
            if (place(w, v, q[0] + 0.5, f.floorY + 1, q[1] + 0.5, false)) {
                return true;
            }
        }
        return false;
    }

    /** A machine (not `v`) stands within LOT_TAKEN of the place. */
    static boolean taken(int[] q, EntityVehicleSVA v, List<EntityVehicleSVA> others) {
        double r = EmbassyServiceMath.LOT_TAKEN;
        for (EntityVehicleSVA o : others) {
            if (o != v && !o.isDead) {
                double dx = o.posX - (q[0] + 0.5), dz = o.posZ - (q[1] + 0.5);
                if (dx * dx + dz * dz < r * r) {
                    return true;
                }
            }
        }
        return false;
    }

    /** `v` fits in the middle of `box` on the paving (`dry`: only asked, it stays where it is). */
    static boolean fits(EntityVehicleSVA v, EmbassyFieldSVA f, int[] box, boolean dry) {
        if (box == null) {
            return false;
        }
        int[] c = EmbassyServiceMath.centre(box);
        return place(v.worldObj, v, c[0] + 0.5, f.floorY + 1, c[1] + 0.5, dry);
    }

    /**
     * `v` put at (x, y, z) when its box there meets no block, liquid or other machine and the place's chunk is loaded (`dry`: put back
     * after the test). Its yaw stays. @return it fits (and, not dry, stands there now)
     */
    static boolean place(World w, EntityVehicleSVA v, double x, double y, double z, boolean dry) {
        if (w == null || v == null || !w.blockExists(MathHelper.floor_double(x), MathHelper.floor_double(y), MathHelper.floor_double(z))) {
            return false;
        }
        double ox = v.posX, oy = v.posY, oz = v.posZ;
        float yaw = v.rotationYaw, pitch = v.rotationPitch;
        v.setLocationAndAngles(x, y, z, yaw, 0F);
        boolean ok = w.getCollidingBoundingBoxes(v, v.boundingBox.contract(0.02, 0.02, 0.02)).isEmpty() && !w.isAnyLiquid(v.boundingBox);
        if (!ok || dry) {
            v.setLocationAndAngles(ox, oy - v.yOffset, oz, yaw, pitch);
            return ok;
        }
        v.motionX = v.motionY = v.motionZ = 0;
        v.fallDistance = 0;
        return true;
    }
}
