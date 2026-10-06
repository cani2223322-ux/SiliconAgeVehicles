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
    }

    @Override
    public void init(FMLInitializationEvent e) {
        super.init(e);
        MinecraftForge.EVENT_BUS.register(new HudVehicleSVA());
        FMLCommonHandler.instance().bus().register(this);
    }

    @Override
    public EntityPlayer localPlayer() {
        return Minecraft.getMinecraft().thePlayer;
    }

    /** W/S throttle / reverse, A/D steer, Space brake (Shift stays vanilla: get out). */
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
        return f;
    }

    @Override
    public void queueCorrection(NetSVA.Correct m) {
        if (corrections.size() < 16) {
            corrections.add(m);
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
