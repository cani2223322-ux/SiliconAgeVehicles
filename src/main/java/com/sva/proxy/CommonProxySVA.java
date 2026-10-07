package com.sva.proxy;

import java.io.File;

import com.sva.ConfigSVA;
import com.sva.SVAMod;
import com.sva.init.ModContentSVA;
import com.sva.inventory.GuiHandlerSVA;
import com.sva.net.NetSVA;
import com.sva.net.ServerQueueSVA;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import net.minecraft.entity.player.EntityPlayer;

public class CommonProxySVA {

    public void preInit(FMLPreInitializationEvent e) {
        ConfigSVA.load(new File(e.getModConfigurationDirectory(), "SiliconAgeVehicles.cfg"));
        ModContentSVA.preInit();
        NetSVA.init();
    }

    public void init(FMLInitializationEvent e) {
        ModContentSVA.recipes();
        NetworkRegistry.INSTANCE.registerGuiHandler(SVAMod.instance, new GuiHandlerSVA());
        FMLCommonHandler.instance().bus().register(ServerQueueSVA.INSTANCE);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new com.sva.entity.EventsSVA());     // stage E: invisibility
    }

    public void postInit(FMLPostInitializationEvent e) {
    }

    /** The client's own player (null on a dedicated server). */
    public EntityPlayer localPlayer() {
        return null;
    }

    /** The local driver's keys as EntityVehicleSVA.IN_* bits. */
    public int driverInput() {
        return 0;
    }

    /** The station screen's served vehicle owner (client). */
    public void stationInfo(String owner) {
    }

    /** Wave 2, ★10: a vehicle's access list arrived - open / refresh the access screen (client). */
    public void accessInfo(NetSVA.AccessInfo m) {
    }

    /** A server correction for the local driver (applied on the client thread). */
    public void queueCorrection(NetSVA.Correct m) {
    }
}
