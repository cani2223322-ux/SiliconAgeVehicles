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
        com.sva.client.VehicleKeyBindsSVA.load(e.getModConfigurationDirectory(), Minecraft.getMinecraft().mcDataDir);
        com.sva.client.MfdPagesSVA.load(e.getModConfigurationDirectory());      // cockpit wave: the MFD page per vehicle
        RenderingRegistry.registerEntityRenderingHandler(EntityVehicleSVA.class, new RenderVehicleSVA());
        RenderingRegistry.registerEntityRenderingHandler(EntitySeatSVA.class, new RenderSeatSVA());
        cpw.mods.fml.client.registry.ClientRegistry.bindTileEntitySpecialRenderer(com.sva.block.TileEntityWorkbenchSVA.class,
                new com.sva.client.RenderWorkbenchSVA());
        cpw.mods.fml.client.registry.ClientRegistry.bindTileEntitySpecialRenderer(com.sva.block.TileEntityGarageSVA.class,
                new com.sva.client.RenderGarageSVA());                  // wave 2, ★7: the repair hologram
        cpw.mods.fml.client.registry.ClientRegistry.bindTileEntitySpecialRenderer(com.sva.block.TileEntityAirfieldSVA.class,
                new com.sva.client.RenderAirfieldSVA());                // stage D1: PAPI, approach lights
        // multiblock wave: the air-defence structures as models (one renderer, on the master)
        com.sva.client.RenderAirDefenseSVA ad = new com.sva.client.RenderAirDefenseSVA();
        cpw.mods.fml.client.registry.ClientRegistry.bindTileEntitySpecialRenderer(com.sva.block.TileEntityRadarSVA.class, ad);
        cpw.mods.fml.client.registry.ClientRegistry.bindTileEntitySpecialRenderer(com.sva.block.TileEntityLauncherSVA.class, ad);
        cpw.mods.fml.client.registry.ClientRegistry.bindTileEntitySpecialRenderer(com.sva.block.TileEntityCommandPostSVA.class, ad);
        // ★11: road signs, traffic light, guard rail - boxes in the chunk mesh
        com.sva.block.RoadsSVA.renderId = RenderingRegistry.getNextAvailableRenderId();
        RenderingRegistry.registerBlockHandler(new com.sva.client.RenderRoadSVA(com.sva.block.RoadsSVA.renderId));
    }

    /*
     * Fix 2026-10: the vehicle functions (Форсаж F, Снижение X, Бур B, Режим N, Прыжок V, Цель Y, Якорь Z, Невидимость C,
     * Круиз O, Багажник L, Карта M) are no longer KeyBindings - their long names ran off the controls list and other
     * mods' bindings on the same letters took the presses (1.7.10's KeyBinding.hash: one binding a key). They are set
     * in the «Техника» menu and read raw while riding (client.VehicleKeyBindsSVA); Controls keeps only the menu key.
     */
    /** The current key of «Багажник» as shown to the player (HUD / tooltips). */
    public static String cargoKeyName() {
        return com.sva.client.VehicleKeyBindsSVA.name(com.sva.vehicle.VehicleFn.CARGO);
    }

    @Override
    public void init(FMLInitializationEvent e) {
        super.init(e);
        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(com.sva.client.VehicleKeyBindsSVA.KEY_MENU);   // the only one
        // ★16: vehicle / trailer items as 3D models (each type declines itself with vehicles.icons3d = false)
        net.minecraftforge.client.MinecraftForgeClient.registerItemRenderer(com.sva.init.ModContentSVA.vehicle,
                new com.sva.client.RenderItemVehicleSVA(false));
        net.minecraftforge.client.MinecraftForgeClient.registerItemRenderer(com.sva.init.ModContentSVA.trailer,
                new com.sva.client.RenderItemVehicleSVA(true));
        // multiblock wave: the air-defence items as their models (airdefense.models = false: the block icons)
        com.sva.client.RenderItemAirDefenseSVA adItem = new com.sva.client.RenderItemAirDefenseSVA();
        for (net.minecraft.block.Block b : new net.minecraft.block.Block[] {com.sva.init.ModContentSVA.radar, com.sva.init.ModContentSVA.launcher,
                com.sva.init.ModContentSVA.commandPost}) {
            net.minecraftforge.client.MinecraftForgeClient.registerItemRenderer(net.minecraft.item.Item.getItemFromBlock(b), adItem);
        }
        MinecraftForge.EVENT_BUS.register(new HudVehicleSVA());
        MinecraftForge.EVENT_BUS.register(new com.sva.client.RiderAttitudeSVA());   // riders lean with the body
        MinecraftForge.EVENT_BUS.register(new WorldUnload());
        FMLCommonHandler.instance().bus().register(new com.sva.client.VehicleKeyBindsSVA.Taps());  // short taps between ticks
        FMLCommonHandler.instance().bus().register(new com.sva.client.VehiclePickSVA());       // clicks on the whole model of big vehicles
        MinecraftForge.EVENT_BUS.register(new Wheel());                            // ★3 / ★4: the mouse wheel while riding
        MinecraftForge.EVENT_BUS.register(new com.sva.client.RunwayLightsSVA());   // stage D1: runway marks / inlaid lights
        com.sva.client.AirDefenseClientSVA ad = new com.sva.client.AirDefenseClientSVA();   // air-defence wave: messages, zones, RWR
        MinecraftForge.EVENT_BUS.register(ad);
        FMLCommonHandler.instance().bus().register(ad);
        FMLCommonHandler.instance().bus().register(this);
        // ★6: headlights light the blocks ahead (client world only; restored on leave / unload)
        com.sva.client.DynamicLightSVA light = new com.sva.client.DynamicLightSVA();
        FMLCommonHandler.instance().bus().register(light);
        MinecraftForge.EVENT_BUS.register(light);
        com.sva.client.HiResSVA.register();                // 2K / 4K wave: a resource reload / language change re-bakes the cockpit art
    }

    @Override
    public void postInit(cpw.mods.fml.common.event.FMLPostInitializationEvent e) {
        super.postInit(e);
        com.sva.client.RenderItemVehicleSVA.preloadModels();   // every vehicle / trailer model read now: no stall on the first look
    }

    @Override
    public EntityPlayer localPlayer() {
        return Minecraft.getMinecraft().thePlayer;
    }

    /**
     * W/S throttle / reverse, A/D steer, Space brake (hover: higher; heli: collective up; plane: pull up), sprint /
     * «Форсаж» boost (aircraft: down), «Снижение» down (Shift stays vanilla: get out). «Форсаж» / «Снижение» are the
     * raw held state of their binds (VehicleKeyBindsSVA, latched each client tick while riding with no screen open).
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
        if (mc.currentScreen == null && com.sva.client.VehicleKeyBindsSVA.boostHeld()) {
            f |= EntityVehicleSVA.IN_AFTER;
        }
        if (mc.currentScreen == null && com.sva.client.VehicleKeyBindsSVA.downHeld()) {
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

    /** ★19: the last map rows from the server, handed to the open map on the client thread. */
    private volatile NetSVA.MapInfo mapPending;

    @Override
    public void mapInfo(NetSVA.MapInfo m) {
        mapPending = m;
    }

    /** D4: the flight plans received, applied on the client thread (the map's route tab, the driver's copy). */
    private final java.util.concurrent.ConcurrentLinkedQueue<NetSVA.RouteInfo> routes = new java.util.concurrent.ConcurrentLinkedQueue<NetSVA.RouteInfo>();

    @Override
    public void routeInfo(NetSVA.RouteInfo m) {
        if (routes.size() < 16) {
            routes.add(m);
        }
    }

    @Override
    public void mcduData(int entity, int kind, String data) {
        com.sva.client.McduClientSVA.put(entity, kind, data);    // liner wave 5 (a concurrent map: safe from the network thread)
    }

    @Override
    public void openIls(com.sva.block.TileEntityIlsSVA te) {
        net.minecraft.client.Minecraft.getMinecraft().displayGuiScreen(new com.sva.client.GuiIlsSVA(te));   // liner wave 4
    }

    @Override
    public void adData(int kind, net.minecraft.nbt.NBTTagCompound data) {
        com.sva.client.AirDefenseClientSVA.receive(kind, data);    // queued, applied on the client thread
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
        NetSVA.MapInfo mi = mapPending;
        if (mi != null) {
            mapPending = null;
            com.sva.client.GuiMapSVA.receive(mi);                   // ★19
        }
        NetSVA.RouteInfo ri;
        while ((ri = routes.poll()) != null) {
            // D4: the driver flies his copy of the plan; the map's route tab shows / edits it
            Minecraft mc = Minecraft.getMinecraft();
            Entity re = mc.theWorld == null ? null : mc.theWorld.getEntityByID(ri.entity);
            if (re instanceof EntityVehicleSVA) {                    // (its route target is read only while he drives it)
                ((EntityVehicleSVA) re).setClientPlan(ri.plan());
            }
            com.sva.client.GuiMapSVA.receiveRoute(ri);
        }
        Minecraft mcc = Minecraft.getMinecraft();
        com.sva.client.VehicleKeyBindsSVA.tick(mcc);                 // the menu key; riding: the function keys (raw)
        com.sva.client.LinerClickSVA.tick(mcc);                      // liner wave 1: «Курсор кабины» held - the cockpit's cursor
        com.sva.client.CruiseClientSVA.tick(mcc);                    // ★4: off when the driver left
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
