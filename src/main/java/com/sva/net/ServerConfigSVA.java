package com.sva.net;

import com.sva.ConfigSVA;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * ТО-3: the server's settings that change what the client shows (tooltips, screens): wear, consumption, chargerRate,
 * tuning, cruise, autopilot, trailerKinds, locks. The server sends them to each player who logs in (its own channel,
 * one message); the client keeps them apart from its own config and drops them when it leaves the server. Until they
 * arrive (and on the server itself) every getter answers with the local ConfigSVA value.
 * init() goes in preInit on both sides (SVAMod).
 */
public final class ServerConfigSVA {

    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("SAVehiclesCfg");

    private static volatile boolean received;
    private static volatile boolean wear, tuning, cruise, autopilot, trailerKinds, locks, hydrogen;
    private static volatile float consumption, chargerRate;

    private ServerConfigSVA() {
    }

    public static void init() {
        CHANNEL.registerMessage(SettingsHandler.class, Settings.class, 0, Side.CLIENT);
        FMLCommonHandler.instance().bus().register(new Events());
    }

    // ------------------------------------------------------------------ what the client should show

    public static boolean wear() {
        return received ? wear : ConfigSVA.wear;
    }

    public static float consumption() {
        return received ? consumption : ConfigSVA.consumption;
    }

    public static float chargerRate() {
        return received ? chargerRate : ConfigSVA.chargerRate;
    }

    public static boolean tuning() {
        return received ? tuning : ConfigSVA.tuning;
    }

    public static boolean cruise() {
        return received ? cruise : ConfigSVA.cruise;
    }

    public static boolean autopilot() {
        return received ? autopilot : ConfigSVA.autopilot;
    }

    public static boolean trailerKinds() {
        return received ? trailerKinds : ConfigSVA.trailerKinds;
    }

    public static boolean locks() {
        return received ? locks : ConfigSVA.locks;
    }

    /** Hydrogen wave: the server's vehicles.fuelMode is HYDROGEN (FuelMath). */
    public static boolean hydrogen() {
        return received ? hydrogen : ConfigSVA.hydrogen;
    }

    /** The server's values arrived (tests). */
    public static boolean isReceived() {
        return received;
    }

    /** Back to the local config (the client left the server). */
    public static void reset() {
        received = false;
    }

    // ------------------------------------------------------------------ the message

    public static final class Settings implements IMessage {
        boolean wear, tuning, cruise, autopilot, trailerKinds, locks, hydrogen;
        float consumption, chargerRate;

        public Settings() {
        }

        /** The server's config now. */
        static Settings now() {
            Settings m = new Settings();
            m.wear = ConfigSVA.wear;
            m.tuning = ConfigSVA.tuning;
            m.cruise = ConfigSVA.cruise;
            m.autopilot = ConfigSVA.autopilot;
            m.trailerKinds = ConfigSVA.trailerKinds;
            m.locks = ConfigSVA.locks;
            m.hydrogen = ConfigSVA.hydrogen;
            m.consumption = ConfigSVA.consumption;
            m.chargerRate = ConfigSVA.chargerRate;
            return m;
        }

        @Override
        public void fromBytes(ByteBuf b) {
            int f = b.readUnsignedByte();
            wear = (f & 1) != 0;
            tuning = (f & 2) != 0;
            cruise = (f & 4) != 0;
            autopilot = (f & 8) != 0;
            trailerKinds = (f & 16) != 0;
            locks = (f & 32) != 0;
            hydrogen = (f & 64) != 0;
            consumption = b.readFloat();
            chargerRate = b.readFloat();
        }

        @Override
        public void toBytes(ByteBuf b) {
            b.writeByte((wear ? 1 : 0) | (tuning ? 2 : 0) | (cruise ? 4 : 0) | (autopilot ? 8 : 0) | (trailerKinds ? 16 : 0) | (locks ? 32 : 0) | (hydrogen ? 64 : 0));
            b.writeFloat(consumption);
            b.writeFloat(chargerRate);
        }
    }

    public static final class SettingsHandler implements IMessageHandler<Settings, IMessage> {
        @Override
        public IMessage onMessage(Settings m, MessageContext ctx) {
            wear = m.wear;
            tuning = m.tuning;
            cruise = m.cruise;
            autopilot = m.autopilot;
            trailerKinds = m.trailerKinds;
            locks = m.locks;
            hydrogen = m.hydrogen;
            consumption = ConfigSVA.sanitize(m.consumption, 1F, 0F, 100F);
            chargerRate = ConfigSVA.sanitize(m.chargerRate, 1F, 0F, 100F);
            received = true;
            return null;
        }
    }

    // ------------------------------------------------------------------ when it is sent / dropped

    public static final class Events {
        @SubscribeEvent
        public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
            if (e.player instanceof EntityPlayerMP) {
                CHANNEL.sendTo(Settings.now(), (EntityPlayerMP) e.player);
            }
        }

        @SubscribeEvent
        public void onLeave(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
            reset();
        }
    }
}
