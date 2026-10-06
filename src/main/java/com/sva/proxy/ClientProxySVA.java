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
    }

    /** «Форсаж» (hoverbike boost; the sprint key works too). Unbound by default - the core's armour dash has R. */
    public static final net.minecraft.client.settings.KeyBinding KEY_BOOST =
            new net.minecraft.client.settings.KeyBinding("key.sva.boost", org.lwjgl.input.Keyboard.KEY_NONE, "key.categories.sva");

    @Override
    public void init(FMLInitializationEvent e) {
        super.init(e);
        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(KEY_BOOST);
        MinecraftForge.EVENT_BUS.register(new HudVehicleSVA());
        FMLCommonHandler.instance().bus().register(this);
    }

    @Override
    public EntityPlayer localPlayer() {
        return Minecraft.getMinecraft().thePlayer;
    }

    /** W/S throttle / reverse, A/D steer, Space brake (hover: higher), sprint / «Форсаж» boost (Shift stays vanilla: get out). */
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
        if (mc.currentScreen == null && (mc.gameSettings.keyBindSprint.getIsKeyPressed() || KEY_BOOST.getIsKeyPressed())) {
            f |= EntityVehicleSVA.IN_BOOST;
        }
        return f;
    }

    @Override
    public void queueCorrection(NetSVA.Correct m) {
        if (corrections.size() < 16) {
            corrections.add(m);
        }
    }

    private EntityVehicleSVA viewVehicle;
    private float viewYaw;

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
        if (e.phase != TickEvent.Phase.START) {
            return;
        }
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        EntityVehicleSVA v = p == null ? null : ridden(p);
        if (v == null || v.isDead) {
            viewVehicle = null;
            return;
        }
        float yaw = v.prevRotationYaw + net.minecraft.util.MathHelper.wrapAngleTo180_float(v.rotationYaw - v.prevRotationYaw) * e.renderTickTime;
        if (viewVehicle != v) {
            viewVehicle = v;
            viewYaw = yaw;
            return;
        }
        float d = net.minecraft.util.MathHelper.wrapAngleTo180_float(yaw - viewYaw);
        viewYaw = yaw;
        if (Math.abs(d) < 45F) {                                    // a correction snap is not followed by the view
            p.rotationYaw += d;
            p.prevRotationYaw += d;
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START) {
            return;
        }
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
