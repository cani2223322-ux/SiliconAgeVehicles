package com.sva;

import com.sva.proxy.CommonProxySVA;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;

@Mod(modid = ReferenceSVA.MODID, name = ReferenceSVA.NAME, version = ReferenceSVA.VERSION, dependencies = ReferenceSVA.CORE_DEP)
public class SVAMod {

    @Mod.Instance(ReferenceSVA.MODID)
    public static SVAMod instance;

    @SidedProxy(clientSide = ReferenceSVA.CLIENT_PROXY, serverSide = ReferenceSVA.COMMON_PROXY)
    public static CommonProxySVA proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        com.sva.net.ServerConfigSVA.init();            // ТО-3: the server's settings for the client's tooltips
        proxy.preInit(e);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        proxy.init(e);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent e) {
        proxy.postInit(e);
        if (Boolean.getBoolean("sva.selftest")) {
            com.sva.debug.SelfTestSVA.run();            // developer check only
        }
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent e) {
        e.registerServerCommand(new CommandSVA());                // /sva vehicles (operators: the vehicle registry)
        if (Boolean.getBoolean("sva.worldtest")) {
            FMLCommonHandler.instance().bus().register(new com.sva.debug.WorldTestSVA());
        }
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent e) {
        com.sva.net.ServerQueueSVA.INSTANCE.clear();
    }
}
