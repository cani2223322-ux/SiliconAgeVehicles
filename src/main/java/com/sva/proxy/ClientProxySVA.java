package com.sva.proxy;

import java.util.concurrent.ConcurrentLinkedQueue;

import com.sva.client.HudVehicleSVA;
import com.sva.client.RenderSeatSVA;
import com.sva.client.RenderVehicleSVA;
import com.sva.entity.EntitySeatSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.net.NetSVA;

import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovementInput;
import net.minecraftforge.common.MinecraftForge;

public class ClientProxySVA extends CommonProxySVA {

    private final ConcurrentLinkedQueue<NetSVA.Correct> corrections = new ConcurrentLinkedQueue<NetSVA.Correct>();

    @Override
    public void preInit(FMLPreInitializationEvent e) {
        super.preInit(e);
        RenderingRegistry.registerEntityRenderingHandler(EntityVehicleSVA.class, new RenderVehicleSVA());
        RenderingRegistry.registerEntityRenderingHandler(EntitySeatSVA.class, new RenderSeatSVA());
        cpw.mods.fml.client.registry.ClientRegistry.bindTileEntitySpecialRenderer(com.sva.block.TileEntityWorkbenchSVA.class,
                new com.sva.client.RenderWorkbenchSVA());
        cpw.mods.fml.client.registry.ClientRegistry.bindTileEntitySpecialRenderer(com.sva.block.TileEntityGarageSVA.class,
                new com.sva.client.RenderGarageSVA());                  // wave 2, ★7: the repair hologram
        // ★11: road signs, traffic light, guard rail - boxes in the chunk mesh
        com.sva.block.RoadsSVA.renderId = RenderingRegistry.getNextAvailableRenderId();
        RenderingRegistry.registerBlockHandler(new com.sva.client.RenderRoadSVA(com.sva.block.RoadsSVA.renderId));
    }

    /**
     * «Форсаж»: the hoverbike's boost (the sprint key works there too) and - stage D - the jet's / tiltrotor's
     * afterburner (IN_AFTER; in aircraft the sprint key means «down»). Default F (stage D; was unbound): free in vanilla
     * 1.7.10 and the core (K, R, P, H, J, G) and in this addon (X, B, N); R stays the core armour's dash.
     */
    public static final net.minecraft.client.settings.KeyBinding KEY_BOOST =
            new net.minecraft.client.settings.KeyBinding("key.sva.boost", org.lwjgl.input.Keyboard.KEY_F, "key.categories.sva");

    /**
     * «Снижение» (aircraft: helicopter collective down, plane nose down; the sprint key works too). Default X - free in
     * vanilla 1.7.10 and in the core (K, R, P, H, J, G); Shift stays the vanilla «get out».
     */
    public static final net.minecraft.client.settings.KeyBinding KEY_DOWN =
            new net.minecraft.client.settings.KeyBinding("key.sva.down", org.lwjgl.input.Keyboard.KEY_X, "key.categories.sva");

    /** «Бур: вкл/выкл» (the drill vehicle; its driver). Default B - free in vanilla 1.7.10 and the core. */
    public static final net.minecraft.client.settings.KeyBinding KEY_DRILL =
            new net.minecraft.client.settings.KeyBinding("key.sva.drill", org.lwjgl.input.Keyboard.KEY_B, "key.categories.sva");

    /** «Режим»: the drill's Tunnel / Ore only / Level; the tiltrotor's vertical <-> airplane mode (stage D). Default N. */
    public static final net.minecraft.client.settings.KeyBinding KEY_DRILL_MODE =
            new net.minecraft.client.settings.KeyBinding("key.sva.drillMode", org.lwjgl.input.Keyboard.KEY_N, "key.categories.sva");

    /**
     * Stage E, the Singular Ship (its pilot): «Прыжок» V, «Цель прыжка» Y (Coordinator / home), «Гравитационный якорь» Z,
     * «Невидимость» C. All free in vanilla 1.7.10, the core (K, R, P, H, J, G) and this addon (F, X, B, N).
     * The shuttle's / ship's «Дальний режим» is the «Режим» key (N).
     */
    public static final net.minecraft.client.settings.KeyBinding KEY_JUMP =
            new net.minecraft.client.settings.KeyBinding("key.sva.jump", org.lwjgl.input.Keyboard.KEY_V, "key.categories.sva");
    public static final net.minecraft.client.settings.KeyBinding KEY_TARGET =
            new net.minecraft.client.settings.KeyBinding("key.sva.jumpTarget", org.lwjgl.input.Keyboard.KEY_Y, "key.categories.sva");
    public static final net.minecraft.client.settings.KeyBinding KEY_ANCHOR =
            new net.minecraft.client.settings.KeyBinding("key.sva.anchor", org.lwjgl.input.Keyboard.KEY_Z, "key.categories.sva");
    public static final net.minecraft.client.settings.KeyBinding KEY_CLOAK =
            new net.minecraft.client.settings.KeyBinding("key.sva.cloak", org.lwjgl.input.Keyboard.KEY_C, "key.categories.sva");

    /**
     * «Багажник»: opens the cargo / bunker screen of the vehicle the player sits in (driver or a seat) - Shift (the
     * vanilla «get out») can't be used for Shift+RMB while seated. Default L: free in vanilla 1.7.10, the core
     * (K, R, P, H, J, G) and this addon (F, X, B, N, V, Y, Z, C).
     */
    public static final net.minecraft.client.settings.KeyBinding KEY_CARGO =
            new net.minecraft.client.settings.KeyBinding("key.sva.cargo", org.lwjgl.input.Keyboard.KEY_L, "key.categories.sva");

    /** The current key of «Багажник» as shown to the player (HUD / tooltips). */
    public static String cargoKeyName() {
        int c = KEY_CARGO.getKeyCode();
        return c == 0 ? "-" : net.minecraft.client.settings.GameSettings.getKeyDisplayString(c);
    }

    @Override
    public void init(FMLInitializationEvent e) {
        super.init(e);
        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(KEY_BOOST);
        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(KEY_DOWN);
        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(KEY_DRILL);
        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(KEY_DRILL_MODE);
        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(KEY_JUMP);
        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(KEY_TARGET);
        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(KEY_ANCHOR);
        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(KEY_CLOAK);
        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(KEY_CARGO);
        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(com.sva.client.CruiseClientSVA.KEY_CRUISE);   // ★4
        // ★16: vehicle / trailer items as 3D models (each type declines itself with vehicles.icons3d = false)
        net.minecraftforge.client.MinecraftForgeClient.registerItemRenderer(com.sva.init.ModContentSVA.vehicle,
                new com.sva.client.RenderItemVehicleSVA(false));
        net.minecraftforge.client.MinecraftForgeClient.registerItemRenderer(com.sva.init.ModContentSVA.trailer,
                new com.sva.client.RenderItemVehicleSVA(true));
        MinecraftForge.EVENT_BUS.register(new HudVehicleSVA());
        MinecraftForge.EVENT_BUS.register(new com.sva.client.RiderAttitudeSVA());   // riders lean with the body
        MinecraftForge.EVENT_BUS.register(new WorldUnload());
        MinecraftForge.EVENT_BUS.register(new Wheel());                            // ★3 / ★4: the mouse wheel while riding
        FMLCommonHandler.instance().bus().register(this);
        // ★6: headlights light the blocks ahead (client world only; restored on leave / unload)
        com.sva.client.DynamicLightSVA light = new com.sva.client.DynamicLightSVA();
        FMLCommonHandler.instance().bus().register(light);
        MinecraftForge.EVENT_BUS.register(light);
    }

    @Override
    public EntityPlayer localPlayer() {
        return Minecraft.getMinecraft().thePlayer;
    }

    /**
     * W/S throttle / reverse, A/D steer, Space brake (hover: higher; heli: collective up; plane: pull up), sprint /
     * «Форсаж» boost (aircraft: down), «Снижение» down (Shift stays vanilla: get out).
     */
    @Override
    public int driverInput() {
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        if (!(p instanceof EntityPlayerSP)) {
            return 0;
        }
        MovementInput in = ((EntityPlayerSP) p).movementInput;
        int f = 0;
        if (in.moveForward > 0.1F) {
            f |= EntityVehicleSVA.IN_FWD;
        } else if (in.moveForward < -0.1F) {
            f |= EntityVehicleSVA.IN_BACK;
        }
        if (in.moveStrafe > 0.1F) {
            f |= EntityVehicleSVA.IN_LEFT;
        } else if (in.moveStrafe < -0.1F) {
            f |= EntityVehicleSVA.IN_RIGHT;
        }
        if (in.jump) {
            f |= EntityVehicleSVA.IN_BRAKE;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen == null && mc.gameSettings.keyBindSprint.getIsKeyPressed()) {
            f |= EntityVehicleSVA.IN_BOOST;
        }
        if (mc.currentScreen == null && KEY_BOOST.getIsKeyPressed()) {
            f |= EntityVehicleSVA.IN_AFTER;
        }
        if (mc.currentScreen == null && KEY_DOWN.getIsKeyPressed()) {
            f |= EntityVehicleSVA.IN_DOWN;
        }
        if (p.ridingEntity instanceof EntityVehicleSVA) {
            f = com.sva.client.CruiseClientSVA.apply((EntityVehicleSVA) p.ridingEntity, f);   // ★4: + IN_CRUISE and its targets
        }
        return f;
    }

    /**
     * The mouse wheel while riding (no screen open): with cruise engaged it sets the cruise speed (+-5 km/h a notch, up =
     * faster); else in third person it zooms the vehicle camera (up = closer). Either way the hotbar keeps still; on
     * foot / in first person without cruise the wheel scrolls the hotbar as always.
     */
    public static class Wheel {
        @SubscribeEvent
        public void onMouse(net.minecraftforge.client.event.MouseEvent e) {
            Minecraft mc = Minecraft.getMinecraft();
            if (e.dwheel == 0 || mc.currentScreen != null || mc.thePlayer == null || ridden(mc.thePlayer) == null) {
                return;
            }
            int notch = e.dwheel > 0 ? 1 : -1;
            if (com.sva.client.CruiseClientSVA.wheel(mc, notch) || com.sva.client.VehicleCameraSVA.wheel(mc, -notch)) {
                e.setCanceled(true);
            }
        }
    }

    /** The charging plates' look belongs to the world being left. */
    public static class WorldUnload {
        @SubscribeEvent
        public void onUnload(net.minecraftforge.event.world.WorldEvent.Unload e) {
            if (e.world.isRemote) {
                com.sva.block.PlateStatesSVA.clear();
            }
        }
    }

    private volatile String stationOwner;

    @Override
    public void stationInfo(String owner) {
        stationOwner = owner;                                       // applied on the client thread
    }

    /** ★10: the last access list from the server, opened on the client thread. */
    private volatile NetSVA.AccessInfo accessPending;

    @Override
    public void accessInfo(NetSVA.AccessInfo m) {
        accessPending = m;
    }

    @Override
    public void queueCorrection(NetSVA.Correct m) {
        if (corrections.size() < 16) {
            corrections.add(m);
        }
    }

    private EntityVehicleSVA viewVehicle;
    private float viewYaw;
    /** ★3: the vehicle's turn the third-person view still has to follow (deg), and the last frame's time. */
    private float yawLag;
    private long lagNanos;

    /** The vehicle the local player sits in (driver or a seat), or null. */
    private static EntityVehicleSVA ridden(EntityPlayer p) {
        Entity r = p.ridingEntity;
        if (r instanceof EntityVehicleSVA) {
            return (EntityVehicleSVA) r;
        }
        if (r instanceof EntitySeatSVA) {
            return ((EntitySeatSVA) r).getParent();
        }
        return null;
    }

    /**
     * Turns the rider's view with the vehicle once a frame by the vehicle's interpolated yaw (both rotationYaw and
     * prevRotationYaw, so the vanilla camera interpolation stays continuous) - smooth turning, no per-tick jumps.
     */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        if (e.phase != TickEvent.Phase.START) {
            com.sva.client.VehicleCameraSVA.frameEnd(mc);           // ★3: the look pitch back after the frame
            return;
        }
        followYaw(e.renderTickTime);
        com.sva.client.VehicleCameraSVA.frameStart(mc);             // ★3: distance, the back view's height
    }

    /**
     * The view turns with the vehicle (see onRenderTick). ★3: in the third-person vehicle camera the turn is followed with
     * a lag (CameraMath.YAW_LAG): the camera swings round behind the vehicle smoothly instead of being bolted to it.
     */
    private void followYaw(float renderTickTime) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer p = mc.thePlayer;
        EntityVehicleSVA v = p == null ? null : ridden(p);
        long now = System.nanoTime();
        double dt = lagNanos == 0 ? 0 : (now - lagNanos) / 1e9;
        lagNanos = now;
        if (v == null || v.isDead) {
            viewVehicle = null;
            yawLag = 0;
            return;
        }
        float yaw = v.prevRotationYaw + net.minecraft.util.MathHelper.wrapAngleTo180_float(v.rotationYaw - v.prevRotationYaw) * renderTickTime;
        if (viewVehicle != v) {
            viewVehicle = v;
            viewYaw = yaw;
            yawLag = 0;
            return;
        }
        float d = net.minecraft.util.MathHelper.wrapAngleTo180_float(yaw - viewYaw);
        viewYaw = yaw;
        if (Math.abs(d) >= 45F) {                                   // a correction snap is not followed by the view
            return;
        }
        if (com.sva.client.VehicleCameraSVA.active(mc) && mc.gameSettings.thirdPersonView == 1) {
            yawLag = net.minecraft.util.MathHelper.clamp_float(yawLag + d, -90F, 90F);
            d = (float) (yawLag * com.sva.vehicle.CameraMath.follow(dt, com.sva.vehicle.CameraMath.YAW_LAG));
            yawLag -= d;
        } else if (yawLag != 0) {
            d += yawLag;                                            // left the lagging view: catch up at once
            yawLag = 0;
        }
        p.rotationYaw += d;
        p.prevRotationYaw += d;
    }

    /** The drill keys while driving the drill vehicle: one Action packet a press. */
    private static void drillKeys() {
        Minecraft mc = Minecraft.getMinecraft();
        if (KEY_CARGO.isPressed() && mc.thePlayer != null && mc.currentScreen == null) {
            EntityVehicleSVA cv = ridden(mc.thePlayer);                 // driver or a passenger seat
            if (cv != null && !cv.isDead) {
                NetSVA.CHANNEL.sendToServer(new NetSVA.Action(cv.getEntityId(), EntityVehicleSVA.A_CARGO));
            }
        }
        boolean toggle = KEY_DRILL.isPressed(), mode = KEY_DRILL_MODE.isPressed();
        boolean jump = KEY_JUMP.isPressed(), target = KEY_TARGET.isPressed(), anchor = KEY_ANCHOR.isPressed(), cloak = KEY_CLOAK.isPressed();
        if (mc.thePlayer == null || mc.currentScreen != null || !(toggle || mode || jump || target || anchor || cloak)) {
            return;
        }
        Entity r = mc.thePlayer.ridingEntity;
        if (!(r instanceof EntityVehicleSVA)) {
            return;
        }
        com.sva.vehicle.VehicleType t = ((EntityVehicleSVA) r).getVehicleType();
        if (t.hasDrill) {
            NetSVA.CHANNEL.sendToServer(new NetSVA.Action(r.getEntityId(), toggle ? EntityVehicleSVA.A_DRILL_TOGGLE : EntityVehicleSVA.A_DRILL_MODE));
        } else if (mode && t.movement == com.sva.vehicle.Movement.TILT) {
            NetSVA.CHANNEL.sendToServer(new NetSVA.Action(r.getEntityId(), EntityVehicleSVA.A_FLIGHT_MODE));     // «Режим»
        } else if (mode && t.longRange) {
            NetSVA.CHANNEL.sendToServer(new NetSVA.Action(r.getEntityId(), EntityVehicleSVA.A_LONG_RANGE));      // «Дальний режим»
        }
        if (t.singular) {
            int a = jump ? EntityVehicleSVA.A_JUMP : target ? EntityVehicleSVA.A_JUMP_TARGET : anchor ? EntityVehicleSVA.A_ANCHOR
                    : cloak ? EntityVehicleSVA.A_INVIS : -1;
            if (a >= 0) {
                NetSVA.CHANNEL.sendToServer(new NetSVA.Action(r.getEntityId(), a));
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START) {
            return;
        }
        String owner = stationOwner;
        if (owner != null) {
            stationOwner = null;
            EntityPlayer pl = Minecraft.getMinecraft().thePlayer;
            if (pl != null && pl.openContainer instanceof com.sva.inventory.ContainerChargerSVA) {
                ((com.sva.inventory.ContainerChargerSVA) pl.openContainer).vehicleOwner = owner;
            }
        }
        NetSVA.AccessInfo ai = accessPending;
        if (ai != null) {
            accessPending = null;
            com.sva.client.GuiAccessSVA.show(ai);                   // ★10: open, or refresh the open one
        }
        drillKeys();
        Minecraft mcc = Minecraft.getMinecraft();
        com.sva.client.CruiseClientSVA.tick(mcc);                    // ★4: the «Круиз» key
        com.sva.client.VehicleSoundsSVA.tick(mcc);                   // ★1: engine loops
        com.sva.client.CockpitInstrumentsSVA.clientTick();
        NetSVA.Correct m;
        while ((m = corrections.poll()) != null) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null) {
                continue;
            }
            Entity v = mc.theWorld.getEntityByID(m.entity);
            if (v instanceof EntityVehicleSVA) {
                ((EntityVehicleSVA) v).applyCorrection(m.x, m.y, m.z, m.yaw);
            }
        }
    }
}
