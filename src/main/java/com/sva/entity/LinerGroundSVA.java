package com.sva.entity;

import com.sva.ConfigSVA;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerLights;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.Movement;
import com.sva.vehicle.VehicleType;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Vec3;

/**
 * Liner wave 7 (plan-next §7: ground handling and passengers): the SA-320's ground side next to LinerLogicSVA - server
 * only, all on the entity:
 * <ul>
 * <li><b>Doors</b>: a right-click ON a door (the player's look ray passes it - DOORS' centres in the model frame, within
 * DOOR_PICK of the ray) opens / shuts it: L1 and R1 open on the first click, then the next boards through them; the cargo
 * doors (right side, the belly) open / shut; with a cargo door open, Shift + right-click on it is the cargo hold
 * (GuiCargo) from outside. All open only standing on the ground and shut themselves once it rolls / flies.</li>
 * <li><b>Chocks</b> («Колодки», ItemBatteryToolSVA.CHOCKS): right-click on a main gear with them - in; an empty hand on a
 * main gear - out (back into the inventory). The aircraft can not roll with them in (LinerFlight).</li>
 * <li><b>Towing</b>: the liner's БУКСИР switch ON, on the ground, its parking brake off: a player's ground vehicle with a
 * hitch (the truck - trailer slots - or a crawler) standing with its front or rear hitch within HITCH_RANGE of the nose
 * gear for COUPLE_TICKS couples (a dedicated tow bar: the trailer system hitches item trailers, not vehicles). Then the
 * liner follows the hitch every server tick (the nose gear on it, the main gear trailing - pushback or pull), the nose
 * wheel turned as the bar; the pilot's client stops its own physics (towed is synced, its moves are refused). Uncoupled
 * by the switch OFF, the parking brake, the chocks, thrust, the tug's driver gone, the bar stretched past BAR_BREAK.</li>
 * <li><b>Seat belts</b>: in the air with the «РЕМНИ» sign lit a passenger is fastened - Shift does not get him out; a
 * second Shift within BELT_CONFIRM ticks does (EntitySeatSVA calls belts() before the rider's own update).</li>
 * <li><b>Sidestick priority</b>: the F/O's ПРИОРИТЕТ (pressed by the F/O) makes the F/O seat's player the flying pilot -
 * the two riders swap their mounts (the driver's input source), the seats stay where they are (seatPos swaps the two
 * pilots' places while foPriority); the captain's button takes it back.</li>
 * </ul>
 */
public final class LinerGroundSVA {

    private LinerGroundSVA() {
    }

    /** The doors' centres in the model frame {x forward, z right, y up} (L1, R1, the forward / aft cargo doors: D_* order). */
    public static final double[][] DOORS = {{5.30, -1.25, 1.75}, {5.30, 1.25, 1.75}, {3.80, 1.08, 0.85}, {-2.60, 1.08, 0.85}};
    /** The main gear (each side) and the nose gear in the model frame {x, z, y}. */
    public static final double[][] MAIN_GEAR = {{-0.55, -1.95, 0.35}, {-0.55, 1.95, 0.35}};
    public static final double[] NOSE_GEAR = {6.55, 0, 0.3};
    /** A look ray passing a door's / a gear's centre within this (blocks) picks it; the eye within REACH. */
    public static final double DOOR_PICK = 1.1, GEAR_PICK = 1.4, REACH = 6.5;
    /** Towing: the hitch's range to the nose gear (blocks), the ticks standing there to couple, the bar's break, the wheelbase. */
    public static final double HITCH_RANGE = 1.6, BAR_BREAK = 3.0, WHEELBASE = 7.1;
    public static final int COUPLE_TICKS = 40;
    /** Seat belts: the second Shift within this (ticks) gets the passenger out. */
    public static final int BELT_CONFIRM = 60;

    // ---------------------------------------------------------------- picking

    /** A model point {x, z, y} in the world (x, y, z). */
    static double[] world(EntityVehicleSVA v, double[] m) {
        double[] w = v.toWorld(m[0], m[1]);
        return new double[] {v.posX + w[0], v.posY + m[2], v.posZ + w[1]};
    }

    /** The distance from the point to the player's look ray (from the eye, forward up to REACH); MAX_VALUE behind / too far. */
    static double rayDist(EntityPlayer p, double[] q) {
        Vec3 look = p.getLook(1F);
        double ex = p.posX, ey = p.posY + p.getEyeHeight(), ez = p.posZ;
        if (p.worldObj != null && p.worldObj.isRemote) {
            ey = p.posY;                                            // the client player's posY is its eye
        }
        double dx = q[0] - ex, dy = q[1] - ey, dz = q[2] - ez;
        double t = dx * look.xCoord + dy * look.yCoord + dz * look.zCoord;
        if (t < 0 || t > REACH) {
            return Double.MAX_VALUE;
        }
        double px = dx - look.xCoord * t, py = dy - look.yCoord * t, pz = dz - look.zCoord * t;
        return Math.sqrt(px * px + py * py + pz * pz);
    }

    /** The door (0..3, D_* bit index) the player's look points at, -1 none. */
    public static int doorAt(EntityVehicleSVA v, EntityPlayer p) {
        int best = -1;
        double bd = DOOR_PICK;
        for (int k = 0; k < DOORS.length; k++) {
            double d = rayDist(p, world(v, DOORS[k]));
            if (d < bd) {
                bd = d;
                best = k;
            }
        }
        return best;
    }

    /** The player looks at a main gear (its wheels). */
    public static boolean atMainGear(EntityVehicleSVA v, EntityPlayer p) {
        for (double[] g : MAIN_GEAR) {
            if (rayDist(p, world(v, g)) < GEAR_PICK) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the right-click

    /**
     * The liner's part of interactFirst (a player outside): the chocks, the doors, the cargo from outside. @return handled
     * (else the boarding goes on - through an open L1 / R1 the player looks at or stands by).
     */
    static boolean interact(EntityVehicleSVA v, EntityPlayer p, ItemStack held, int rights, boolean canCargo) {
        LinerSystems L = v.state.liner();
        if (L == null) {
            return false;
        }
        boolean crew = (rights & (com.sva.vehicle.AccessMath.DRIVE | com.sva.vehicle.AccessMath.PASSENGER)) != 0;
        // the chocks: in with the item, out with an empty hand (on a main gear)
        if (com.sva.item.ItemBatteryToolSVA.kind(held) == com.sva.item.ItemBatteryToolSVA.CHOCKS) {
            if (!atMainGear(v, p)) {
                v.msgTo(p, "sva.liner.msg.chocks.aim");
            } else if (L.chocks) {
                v.msgTo(p, "sva.liner.msg.chocks.already");
            } else if (v.wasAirborneNow() || Math.abs(v.speed) > 0.05) {
                v.msgTo(p, "sva.liner.msg.chocks.moving");
            } else {
                L.chocks = true;
                if (!p.capabilities.isCreativeMode) {
                    held.stackSize--;
                    if (held.stackSize <= 0) {
                        p.inventory.setInventorySlotContents(p.inventory.currentItem, null);
                    }
                }
                v.vanillaSound("dig.wood", 0.8F, 0.9F);
                v.msgTo(p, "sva.liner.msg.chocks.in");
                v.syncNow();
            }
            return true;
        }
        if (held == null && L.chocks && !p.isSneaking() && atMainGear(v, p)) {
            L.chocks = false;
            if (com.sva.init.ModContentSVA.batteryTool != null
                    && !p.inventory.addItemStackToInventory(com.sva.init.ModContentSVA.batteryTool.stackOf(com.sva.item.ItemBatteryToolSVA.CHOCKS, 1))) {
                p.dropPlayerItemWithRandomChoice(com.sva.init.ModContentSVA.batteryTool.stackOf(com.sva.item.ItemBatteryToolSVA.CHOCKS, 1), false);
            }
            v.vanillaSound("dig.wood", 0.8F, 1.2F);
            v.msgTo(p, "sva.liner.msg.chocks.out");
            v.syncNow();
            return true;
        }
        int d = doorAt(v, p);
        if (p.isSneaking()) {
            // the cargo hold from outside: through an open cargo door
            int ca = cargoAccess(v, p);
            if (ca != CARGO_NONE) {
                if (ca == CARGO_SHUT) {
                    v.msgTo(p, "sva.liner.msg.cargoShut");
                } else if (canCargo) {
                    p.openGui(com.sva.SVAMod.instance, com.sva.inventory.GuiHandlerSVA.CARGO, v.worldObj, v.getEntityId(), 0, 0);
                    v.oneShot("cargo", 0.5F, 1F);
                } else {
                    v.lockedMsgTo(p);
                }
            } else {
                v.msgTo(p, "sva.liner.msg.cargoDoor");
            }
            return true;
        }
        if (d < 0) {
            return false;
        }
        if (!crew && !(d >= 2 && canCargo)) {
            v.lockedMsgTo(p);
            return true;
        }
        boolean open = L.doorOpen(1 << d);
        if (d <= 1 && open) {
            return false;                                           // an open L1 / R1: the click boards
        }
        door(v, p, d, !open);
        return true;
    }

    /** The cargo hold from outside: not at a cargo door, at a shut one, at an open one (the hold's screen). */
    public static final int CARGO_NONE = 0, CARGO_SHUT = 1, CARGO_OPEN = 2;

    /** Where the player's look is for the cargo hold (CARGO_*). */
    public static int cargoAccess(EntityVehicleSVA v, EntityPlayer p) {
        LinerSystems L = v.state.liner();
        int d = doorAt(v, p);
        if (L == null || d != 2 && d != 3) {
            return CARGO_NONE;
        }
        return L.doorOpen(1 << d) ? CARGO_OPEN : CARGO_SHUT;
    }

    /** Door k (D_* bit index) opened / shut by a player (outside or the menu): not in the air, not rolling. */
    static void door(EntityVehicleSVA v, EntityPlayer p, int k, boolean open) {
        LinerSystems L = v.state.liner();
        if (L == null) {
            return;
        }
        if (open && (v.wasAirborneNow() || Math.abs(v.speed) > LinerSystems.DOOR_SPEED)) {
            if (p != null) {
                v.msgTo(p, "sva.liner.door.air");
            }
            return;
        }
        L.setDoor(1 << k, open);
        v.oneShot("door", k >= 2 ? 0.8F : 0.6F, open ? (k >= 2 ? 0.7F : 1.0F) : 0.8F);
        v.syncNow();
    }

    /** Server, every tick: the doors shut themselves once it rolls fast or flies (L1 had it since wave 1). */
    static void doorsTick(EntityVehicleSVA v, LinerSystems L) {
        if (L.doorsOpen() != 0 && (v.wasAirborneNow() || Math.abs(v.speed) > LinerSystems.DOOR_SPEED)) {
            L.door = L.doorR1 = L.cargoF = L.cargoA = false;
            v.oneShot("door", 0.5F, 0.8F);
        }
    }

    /** Is the player by the R1 door (open; its side, within LinerLogicSVA.BOARD_RANGE of it)? - the boarding through R1. */
    static boolean byR1(EntityVehicleSVA v, EntityPlayer p) {
        LinerSystems L = v.state.liner();
        if (L == null || !L.doorR1) {
            return false;
        }
        double[] d = world(v, new double[] {LinerLogicSVA.DOOR_X, 2.9, 0});
        double[] e = world(v, DOORS[1]);
        double dx = p.posX - d[0], dz = p.posZ - d[2], ex = p.posX - e[0], ez = p.posZ - e[2];
        double r = LinerLogicSVA.BOARD_RANGE;
        return dx * dx + dz * dz < r * r || ex * ex + ez * ez < r * r;
    }

    // ---------------------------------------------------------------- seat belts

    /**
     * EntitySeatSVA, server, before its rider's own update: a passenger in the air with the belts sign lit is fastened - his
     * Shift is taken back (no dismount) and he is told; a second press within BELT_CONFIRM gets him out. @return the Shift
     * was taken back
     */
    public static boolean belts(EntitySeatSVA s, EntityPlayer p) {
        EntityVehicleSVA v = s.getParent();
        LinerSystems L = v == null ? null : v.state.liner();
        if (L == null || s.getIndex() < LinerLights.FIRST_PAX) {
            return false;
        }
        boolean sneak = p.isSneaking();
        long[] r = beltRule(fastened(v, L), sneak, s.beltSneak, v.worldObj.getTotalWorldTime(), s.beltConfirm);
        s.beltSneak = sneak;
        s.beltConfirm = r[1];
        if (r[2] != 0) {
            v.msgTo(p, "sva.liner.msg.belts");
        }
        if (r[0] != 0) {
            p.setSneaking(false);                                   // fastened: no dismount this tick
            return true;
        }
        return false;
    }

    /** The passengers are fastened: in the air with the «РЕМНИ» sign lit. */
    public static boolean fastened(EntityVehicleSVA v, LinerSystems L) {
        return v.wasAirborneNow() && (L.lights.signs & LinerLights.S_BELTS) != 0;
    }

    // ---------------------------------------------------------------- the sidestick priority

    /**
     * A priority button pressed (validated: the captain's by the left seat's player, the F/O's by the right one's): the F/O's
     * makes the right seat's player the driver (the mounts swap; foPriority keeps both where they sit). @return changed
     */
    static boolean priority(EntityVehicleSVA v, EntityPlayer p, boolean right) {
        LinerSystems L = v.state.liner();
        if (L == null) {
            return false;
        }
        Entity drv = v.riddenByEntity;
        EntitySeatSVA s1 = v.seatEntity(1) instanceof EntitySeatSVA ? (EntitySeatSVA) v.seatEntity(1) : null;
        Entity other = s1 == null ? null : s1.riddenByEntity;
        // who sits left now: the driver, unless the F/O flies (then the seat's rider)
        Entity left = L.foPriority ? other : drv, rightP = L.foPriority ? drv : other;
        if (right ? rightP != p : left != p) {
            v.msgTo(p, "sva.liner.msg.priorityOwn");
            return false;
        }
        boolean want = right;
        if (L.foPriority == want) {
            return false;
        }
        if (s1 == null || p.ridingEntity == v) {
            return false;
        }
        // swap the mounts: the presser to the driver's entity, the driver to the seat
        if (drv != null) {
            drv.ridingEntity = s1;
        }
        s1.riddenByEntity = drv;
        p.ridingEntity = v;
        v.riddenByEntity = p;
        L.foPriority = want;
        v.vanillaSound("note.pling", 0.6F, want ? 1.4F : 1.1F);
        v.tellRidersKey(want ? "sva.liner.msg.priorityFo" : "sva.liner.msg.priorityCapt");
        v.syncNow();
        return true;
    }

    /** The pilots' places while the F/O flies: the driver (the F/O) in the right seat, seat 1's rider (the captain) in the left. */
    public static int seatSwap(EntityVehicleSVA v, int seat) {
        LinerSystems L = v.liner();
        if (L != null && L.foPriority && seat <= 1) {
            return 1 - seat;
        }
        return seat;
    }

    // ---------------------------------------------------------------- towing

    /** A ground vehicle with a hitch: the truck (trailer slots) or a crawler. */
    public static boolean tug(VehicleType t) {
        return t != null && (t.trailerSlots > 0 || t.movement == Movement.CRAWLER) && t != VehicleType.LINER;
    }

    /** A tug's hitch point (world x, z): its front (sign +1) or rear (-1) end. */
    static double[] hitch(EntityVehicleSVA tug, int sign) {
        double[] w = tug.toWorld(sign * (tug.getVehicleType().halfLength + 0.3), 0);
        return new double[] {tug.posX + w[0], tug.posZ + w[1]};
    }

    /**
     * Server, every tick: couple a tug standing at the nose gear, keep the towed liner on its hitch, uncouple. @return the
     * liner was moved by the tug this tick (its own physics skipped)
     */
    static boolean towTick(EntityVehicleSVA v) {
        LinerSystems L = v.state.liner();
        if (L == null) {
            return false;
        }
        boolean switchOn = L.at(LinerControls.TOW) == 0;
        if (!L.towed) {
            if (!switchOn || v.wasAirborneNow() || L.on(LinerControls.PARK_BRK) || L.chocks || Math.abs(v.speed) > 0.05) {
                v.towCouple = 0;
                return false;
            }
            EntityVehicleSVA t = nearTug(v);
            v.towCouple = t == null ? 0 : v.towCouple + 1;
            if (t != null && v.towCouple >= COUPLE_TICKS) {
                double[] n = world(v, NOSE_GEAR);
                double[] f = hitch(t, 1), r = hitch(t, -1);
                L.tugSign = d2(f, n) <= d2(r, n) ? 1 : -1;
                L.tugId = t.getEntityId();
                L.towed = true;
                v.towCouple = 0;
                v.vanillaSound("random.anvil_land", 0.4F, 1.6F);
                v.tellRidersKey("sva.liner.msg.towOn");
                if (t.riddenByEntity instanceof EntityPlayer) {
                    v.msgTo((EntityPlayer) t.riddenByEntity, "sva.liner.msg.towOn");
                }
                v.syncNow();
            }
            return false;
        }
        Entity e = v.worldObj.getEntityByID(L.tugId);
        EntityVehicleSVA t = e instanceof EntityVehicleSVA ? (EntityVehicleSVA) e : null;
        String why = null;
        if (t == null || t.isDead || !(t.riddenByEntity instanceof EntityPlayer) && !t.debugDriver) {
            why = "tug";
        } else if (!switchOn) {
            why = "switch";
        } else if (L.on(LinerControls.PARK_BRK)) {
            why = "park";
        } else if (L.chocks) {
            why = "chocks";
        } else if (L.thrust() > 0.15 || v.wasAirborneNow()) {
            why = "thrust";
        }
        if (why == null) {
            double[] h = hitch(t, L.tugSign);
            double[] n = world(v, NOSE_GEAR);
            if (Math.sqrt(d2(h, n)) > BAR_BREAK) {
                why = "bar";
            } else {
                follow(v, L, t, h);
                return true;
            }
        }
        uncouple(v, L, t, why);
        return false;
    }

    /** The towed liner onto the hitch: the nose gear there, the main gear trailing along the bar's line (pushback or pull). */
    static void follow(EntityVehicleSVA v, LinerSystems L, EntityVehicleSVA t, double[] h) {
        double[] m = world(v, new double[] {MAIN_GEAR[0][0], 0, 0});
        double[] q = towPose(h[0], h[1], m[0], m[2]);
        if (q == null) {
            return;
        }
        double ux = q[3], uz = q[4];
        float yaw = (float) q[2];
        double mx = q[0] - v.posX, mz = q[1] - v.posZ;
        if (Math.abs(mx) > 1 || Math.abs(mz) > 1) {
            mx = Math.max(-1, Math.min(1, mx));                     // a tug's step at most a block a tick
            mz = Math.max(-1, Math.min(1, mz));
        }
        v.rotationYaw = yaw;
        v.prevRotationYaw = yaw;
        v.motionX = mx;
        v.motionZ = mz;
        v.motionY = -0.08;
        v.moveEntity(mx, -0.08, mz);
        v.motionY = 0;
        v.speed = mx * ux + mz * uz;
        double steer = com.sva.vehicle.CruiseMath.wrap(t.rotationYaw - yaw);
        if (L.tugSign < 0) {
            steer = com.sva.vehicle.CruiseMath.wrap(steer + 180);   // pulled by the rear hitch: the bar points along the tug
        }
        L.towSteer = Math.max(-75, Math.min(75, steer));
    }

    /**
     * The towed pose (pure - the self-test's): the nose gear on the hitch (hx, hz), the main gear (mx, mz) trailing along
     * the bar's line at WHEELBASE. @return {origin x, origin z, yaw (deg, Minecraft), heading x, heading z}; null degenerate
     */
    public static double[] towPose(double hx, double hz, double mx, double mz) {
        double dx = hx - mx, dz = hz - mz, len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-3) {
            return null;
        }
        double ux = dx / len, uz = dz / len;
        double nmx = hx - ux * WHEELBASE, nmz = hz - uz * WHEELBASE;
        // the entity's origin is 0.55 ahead of the main gear along the heading
        return new double[] {nmx - ux * MAIN_GEAR[0][0], nmz - uz * MAIN_GEAR[0][0], Math.toDegrees(Math.atan2(-ux, uz)), ux, uz};
    }

    /**
     * The seat belts' rule (pure): fastened, Shift now / last tick, the time, the confirmation's last tick. @return {hold (1 /
     * 0), the new confirmation tick, tell (1 / 0)} - hold: the Shift taken back
     */
    public static long[] beltRule(boolean fastened, boolean sneak, boolean before, long now, long confirm) {
        boolean edge = sneak && !before;
        if (!sneak || !fastened) {
            return new long[] {0, confirm, 0};
        }
        if (edge && now <= confirm) {
            return new long[] {0, 0, 0};
        }
        if (edge) {
            return new long[] {1, now + BELT_CONFIRM, 1};
        }
        return new long[] {1, confirm, 0};
    }

    static void uncouple(EntityVehicleSVA v, LinerSystems L, EntityVehicleSVA t, String why) {
        L.towed = false;
        L.tugId = -1;
        L.towSteer = 0;
        v.speed = 0;
        v.vanillaSound("random.anvil_land", 0.3F, 1.9F);
        v.tellRidersKey("sva.liner.msg.towOff." + why);
        if (t != null && t.riddenByEntity instanceof EntityPlayer) {
            v.msgTo((EntityPlayer) t.riddenByEntity, "sva.liner.msg.towOff." + why);
        }
        v.syncNow();
    }

    /** A manned tug standing (its hitch within HITCH_RANGE of the nose gear, nearly level), or null. */
    static EntityVehicleSVA nearTug(EntityVehicleSVA v) {
        double[] n = world(v, NOSE_GEAR);
        java.util.List<?> l = v.worldObj.getEntitiesWithinAABB(EntityVehicleSVA.class,
                net.minecraft.util.AxisAlignedBB.getBoundingBox(n[0] - 8, n[1] - 3, n[2] - 8, n[0] + 8, n[1] + 3, n[2] + 8));
        for (Object o : l) {
            EntityVehicleSVA t = (EntityVehicleSVA) o;
            if (t == v || t.isDead || !tug(t.getVehicleType()) || !(t.riddenByEntity instanceof EntityPlayer) && !t.debugDriver
                    || Math.abs(t.speed) > 0.05 || Math.abs(t.posY - v.posY) > 2) {
                continue;
            }
            if (d2(hitch(t, 1), n) < HITCH_RANGE * HITCH_RANGE || d2(hitch(t, -1), n) < HITCH_RANGE * HITCH_RANGE) {
                return t;
            }
        }
        return null;
    }

    /** Squared horizontal distance of a hitch {x, z} and a world point {x, y, z}. */
    static double d2(double[] h, double[] n) {
        double dx = h[0] - n[0], dz = h[1] - (n.length > 2 ? n[2] : n[1]);
        return dx * dx + dz * dz;
    }

    /** The towing config (vehicles.linerTow) - the coupling on / off. */
    static boolean enabled() {
        return ConfigSVA.linerTow;
    }

    /** The F/O's / captain's priority press from the control packet (validated there): the swap. */
    static void priorityPacket(EntityVehicleSVA v, EntityPlayerMP p, boolean right) {
        priority(v, p, right);
    }
}
