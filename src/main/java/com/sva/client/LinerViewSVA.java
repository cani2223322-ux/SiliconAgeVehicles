package com.sva.client;

import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.LinerView;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.FOVUpdateEvent;
import net.minecraftforge.client.event.RenderHandEvent;

/**
 * The SA-320's cockpit view on the client (plan-next §8; the maths: vehicle.LinerView).
 * <ul>
 * <li>The hand: RenderHandEvent is cancelled while the local player sits in a pilot seat in the first-person view
 * (vehicles.linerHideHand) - no arm / held item over the lower ECAM. Passengers and every other vehicle keep it.</li>
 * <li>Quick views («Вид кабины», VehicleFn.VIEW; vehicles.linerQuickViews): the key's press / release and the wheel while
 * it is held drive LinerView.Picker; a new preset eases LinerView.Blend over 0.3 s to the offset from the rider's real
 * look to the preset. The offset is a LOOK ONLY: added to the rider's yaw / pitch at RenderTickEvent START (after the
 * vehicle's turn is followed, ClientProxySVA.followYaw) and taken off again at END - like VehicleCameraSVA's back-view
 * pitch. So the client tick, the packets and the server see the real look (and the liner is steered by keys, never by
 * the look); the frame - the world, the cockpit, the 3D cursor's matrices (LinerClickSVA.saveMatrices) and vanilla's
 * mouse-over - is drawn with the turned one, so the click ray follows the view. The mouse still looks around on top. The
 * zoom is a FOV factor (FOVUpdateEvent: vanilla eases the FOV toward it), 1 outside the presets.</li>
 * </ul>
 * Anything else (leaving the seat, third person, another vehicle, the option off) snaps the view back at once.
 */
@SideOnly(Side.CLIENT)
public final class LinerViewSVA {

    private static final LinerView.Picker PICK = new LinerView.Picker();
    private static final LinerView.Blend BLEND = new LinerView.Blend();
    /** The preset BLEND turns to now. */
    private static int shown = LinerView.FWD;
    /** The key was down last tick. */
    private static boolean keyWas;
    /** The offset added this frame (taken off at its end). */
    private static float addYaw, addPitch;
    private static long lastNanos;

    private LinerViewSVA() {
    }

    /** The liner the local player pilots in the first-person view (the quick views' / the hand's condition), or null. */
    static EntityVehicleSVA pilotedLiner(Minecraft mc) {
        EntityPlayer p = mc.thePlayer;
        if (p == null || mc.gameSettings.thirdPersonView != 0) {
            return null;
        }
        EntityVehicleSVA v = HudVehicleSVA.vehicleOf(p);
        return v != null && !v.isDead && v.getVehicleType() == VehicleType.LINER && LinerClickSVA.pilotSeat(p, v) ? v : null;
    }

    /** The F/O's seat (the mirror image) - not the driver's. */
    static boolean firstOfficer(Minecraft mc, EntityVehicleSVA v) {
        return mc.thePlayer.ridingEntity != v;
    }

    /** Client tick (after VehicleKeyBindsSVA.tick): the key's press / release. */
    public static void tick(Minecraft mc) {
        EntityVehicleSVA v = ConfigSVA.linerQuickViews ? pilotedLiner(mc) : null;
        boolean down = v != null && mc.currentScreen == null && VehicleKeyBindsSVA.viewHeld();
        if (v == null) {
            PICK.reset();
        } else if (down && !keyWas) {
            PICK.press();
        } else if (!down && keyWas) {
            PICK.release();
        }
        keyWas = down;
        if (v != null && PICK.current != told) {
            // the view's name over the hotbar (the record line: no chat)
            mc.ingameGUI.func_110326_a(com.sc.manual.Lang.tr("sva.liner.view.title", com.sc.manual.Lang.tr("sva.liner.view." + LinerView.KEYS[PICK.current])),
                    false);
        }
        told = PICK.current;
    }

    /** The preset last named over the hotbar. */
    private static int told = LinerView.FWD;

    /** The wheel while «Вид кабины» is held picks the view. @return used (the hotbar keeps still) */
    public static boolean wheel(Minecraft mc, int notches) {
        if (!ConfigSVA.linerQuickViews || pilotedLiner(mc) == null || !PICK.held()) {
            return false;
        }
        return PICK.wheel(-notches);                                // wheel down = the next view
    }

    /** ItemRenderer's equip progress (now / last tick): 0 = the hand lowered out of the view (an item swap's bottom). */
    private static java.lang.reflect.Field equipNow, equipPrev;
    private static boolean equipTried;

    /**
     * The autopilot-fix wave: the hand's second line of defence. RenderHandEvent (Events.onHand) is Forge's hook in
     * EntityRenderer.renderWorld - a renderer that draws the hand its own way (OptiFine's shaders pass) never posts it, and
     * the user still saw the arm from the captain's seat. So for a pilot in the first person the ItemRenderer's equip
     * progress is held at 0 each frame as well: vanilla's own item-swap pose with the arm / item fully below the view
     * (whoever draws the hand, it is drawn there). Off the seat vanilla raises it again by itself (updateEquippedItem).
     */
    static void lowerHand(Minecraft mc) {
        if (!equipTried) {
            equipTried = true;
            try {
                equipNow = cpw.mods.fml.relauncher.ReflectionHelper.findField(net.minecraft.client.renderer.ItemRenderer.class, "equippedProgress",
                        "field_78454_c");
                equipPrev = cpw.mods.fml.relauncher.ReflectionHelper.findField(net.minecraft.client.renderer.ItemRenderer.class,
                        "prevEquippedProgress", "field_78451_d");
            } catch (RuntimeException ex) {
                equipNow = equipPrev = null;
            }
        }
        if (equipNow == null || equipPrev == null || mc.entityRenderer == null || mc.entityRenderer.itemRenderer == null) {
            return;
        }
        try {
            equipNow.setFloat(mc.entityRenderer.itemRenderer, 0F);
            equipPrev.setFloat(mc.entityRenderer.itemRenderer, 0F);
        } catch (IllegalAccessException ex) {
            equipNow = null;
        }
    }

    /** The hand hidden now: the option, a pilot of a liner, the first-person view. */
    static boolean handHidden(Minecraft mc) {
        return LinerView.hideHand(ConfigSVA.linerHideHand, true, pilotedLiner(mc) != null, mc.gameSettings.thirdPersonView == 0);
    }

    /** RenderTickEvent START: the eased offset added to the rider's look for this frame. */
    public static void frameStart(Minecraft mc, float pt) {
        if (mc.thePlayer != null && handHidden(mc)) {
            lowerHand(mc);
        }
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : Math.min(0.25, (now - lastNanos) / 1e9);
        lastNanos = now;
        addYaw = addPitch = 0;
        EntityVehicleSVA v = ConfigSVA.linerQuickViews ? pilotedLiner(mc) : null;
        if (v == null) {
            if (shown != LinerView.FWD || !BLEND.idle()) {
                BLEND.snap(0F, 0F, 1F);
                shown = LinerView.FWD;
            }
            return;
        }
        EntityPlayer p = mc.thePlayer;
        if (PICK.current != shown) {
            shown = PICK.current;
            float vy = v.prevRotationYaw + MathHelper.wrapAngleTo180_float(v.rotationYaw - v.prevRotationYaw) * pt;
            float[] o = LinerView.offset(shown, firstOfficer(mc, v), LinerView.wrap(p.rotationYaw - vy), p.rotationPitch);
            BLEND.retarget(o[0], o[1], LinerView.ZOOM[shown]);
        }
        BLEND.advance(dt);
        float y = BLEND.yaw(), pi = BLEND.pitch();
        pi = Math.max(-90F - p.rotationPitch, Math.min(90F - p.rotationPitch, pi));   // vanilla's look stays within -90..90
        if (y == 0F && pi == 0F) {
            return;
        }
        addYaw = y;
        addPitch = pi;
        p.rotationYaw += y;
        p.prevRotationYaw += y;
        p.rotationPitch += pi;
        p.prevRotationPitch += pi;
    }

    /** RenderTickEvent END: the real look back (the mouse's turn during the frame kept). */
    public static void frameEnd(Minecraft mc) {
        EntityPlayer p = mc.thePlayer;
        if (p != null && (addYaw != 0F || addPitch != 0F)) {
            p.rotationYaw -= addYaw;
            p.prevRotationYaw -= addYaw;
            float real = Math.max(-90F, Math.min(90F, p.rotationPitch - addPitch));
            p.prevRotationPitch += real - p.rotationPitch;
            p.rotationPitch = real;
        }
        addYaw = addPitch = 0;
    }

    /** Forge bus: the hand and the zoom. */
    public static final class Events {
        @SubscribeEvent
        public void onHand(RenderHandEvent e) {
            Minecraft mc = Minecraft.getMinecraft();
            if (handHidden(mc)) {
                e.setCanceled(true);
            }
        }

        @SubscribeEvent
        public void onFov(FOVUpdateEvent e) {
            float z = BLEND.zoom();
            if (z != 1F && ConfigSVA.linerQuickViews) {
                e.newfov *= z;
            }
        }
    }
}
