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
 * <p>
 * Audit 2026-10-22: the air defence's settings a client acts on go along too (none did): airdefense.enabled, upgrades (the
 * module slots of radars, launchers, the command post, the ILS shelter and the tower take a module on both sides - the
 * client's own value let the slots disagree), keepModules, dayKey, zoneWarnSeconds (the pilots' entry prediction),
 * maxLaunchers / maxRadars and whether the air defence fires.
 * <p>
 * Code-only wave (proposal-ad-strict-iff item 28): codeOnly (the post's switch, «запрещено сервером»), codeOnlyManual (its
 * option «Ручной набор кода»), allTransponders (the «Ответчик» row, the HUD line and the cockpits of the ground vehicles),
 * keyClock (the code card's «действует ещё ~N мин»), codeCardMaxPeriods (the post's «Срок карты»), codeOnlyArmSeconds /
 * codeOnlyMinWarningSeconds / codeOnlyEmergencySeconds (the screens' texts) - in a block of their own after the air
 * defence's; an older server's packet has none: the mode off, the day clock, aircraft only. Review 2026-10-27 (batch 2):
 * the texts now read them (AirDefenseClientSVA.emPhrase, the launcher's rules, the post's mode and option tips) - they said
 * 180 s / 20 s / 60 s whatever the server set; codeOnlyProbeCodes / codeOnlyProbeMinutes joined the block for the
 * «Ручной набор кода» tip.
 */
public final class ServerConfigSVA {

    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("SAVehiclesCfg");

    private static volatile boolean received;
    private static volatile boolean wear, tuning, cruise, autopilot, trailerKinds, locks, hydrogen;
    private static volatile float consumption, chargerRate;
    /** Audit 2026-10-22: the air defence's (airdefense.*). */
    private static volatile boolean adEnabled, adUpgrades, adKeepModules, adDayKey, adFires;
    private static volatile int adZoneWarnSeconds, adMaxLaunchers, adMaxRadars;
    /** Code-only wave (airdefense.codeOnly*, keyClock, allTransponders). */
    private static volatile boolean adCodeOnly, adCodeOnlyManual, adAllTransponders;
    private static volatile int adKeyClock, adCodeCardMaxPeriods, adCodeOnlyArmSeconds, adCodeOnlyMinWarningSeconds, adCodeOnlyEmergencySeconds;
    /** Review 2026-10-27 (batch 2): the guessing guard's numbers (airdefense.codeOnlyProbeCodes / codeOnlyProbeMinutes). */
    private static volatile int adCodeOnlyProbeCodes, adCodeOnlyProbeMinutes;

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

    /** Audit 2026-10-22: airdefense.enabled. */
    public static boolean adEnabled() {
        return received ? adEnabled : ConfigSVA.adEnabled;
    }

    /** Audit 2026-10-22: airdefense.upgrades - the module slots take a module (both sides must agree). */
    public static boolean adUpgrades() {
        return received ? adUpgrades : ConfigSVA.adUpgrades;
    }

    /** Audit 2026-10-22: airdefense.keepModules. */
    public static boolean adKeepModules() {
        return received ? adKeepModules : ConfigSVA.adKeepModules;
    }

    /** Audit 2026-10-22: airdefense.dayKey. */
    public static boolean adDayKey() {
        return received ? adDayKey : ConfigSVA.adDayKey;
    }

    /** Audit 2026-10-22: the air defence fires (airdefense.mode fire and enabled). */
    public static boolean adFires() {
        return received ? adFires : ConfigSVA.adFires();
    }

    /** Audit 2026-10-22: airdefense.zoneWarnSeconds - the pilots' «zone entry in N s» looks this far ahead. */
    public static int adZoneWarnSeconds() {
        return received ? adZoneWarnSeconds : ConfigSVA.adZoneWarnSeconds;
    }

    /** Audit 2026-10-22: airdefense.maxLaunchers. */
    public static int adMaxLaunchers() {
        return received ? adMaxLaunchers : ConfigSVA.adMaxLaunchers;
    }

    /** Audit 2026-10-22: airdefense.maxRadars. */
    public static int adMaxRadars() {
        return received ? adMaxRadars : ConfigSVA.adMaxRadars;
    }

    /** Code-only wave: airdefense.codeOnly - the posts may switch to «ПО КОДУ». */
    public static boolean adCodeOnly() {
        return received ? adCodeOnly : ConfigSVA.adCodeOnly;
    }

    /** Code-only wave: airdefense.codeOnlyManual - a code dialled by hand may count (the post's option 3б). */
    public static boolean adCodeOnlyManual() {
        return received ? adCodeOnlyManual : ConfigSVA.adCodeOnlyManual;
    }

    /** Code-only wave (the user's decision 3): airdefense.allTransponders - every vehicle has a transponder. */
    public static boolean adAllTransponders() {
        return received ? adAllTransponders : ConfigSVA.adAllTransponders;
    }

    /** Code-only wave: airdefense.keyClock as XpdrMath.CLOCK_*. */
    public static int adKeyClock() {
        return received ? adKeyClock : com.sva.airdefense.XpdrMath.keyClock(ConfigSVA.adKeyClock);
    }

    /** Code-only wave: airdefense.codeCardMaxPeriods. */
    public static int adCodeCardMaxPeriods() {
        return received ? adCodeCardMaxPeriods : ConfigSVA.adCodeCardMaxPeriods;
    }

    /** Code-only wave: airdefense.codeOnlyArmSeconds. */
    public static int adCodeOnlyArmSeconds() {
        return received ? adCodeOnlyArmSeconds : ConfigSVA.adCodeOnlyArmSeconds;
    }

    /** Code-only wave: airdefense.codeOnlyMinWarningSeconds. */
    public static int adCodeOnlyMinWarningSeconds() {
        return received ? adCodeOnlyMinWarningSeconds : ConfigSVA.adCodeOnlyMinWarningSeconds;
    }

    /** Code-only wave: airdefense.codeOnlyEmergencySeconds. */
    public static int adCodeOnlyEmergencySeconds() {
        return received ? adCodeOnlyEmergencySeconds : ConfigSVA.adCodeOnlyEmergencySeconds;
    }

    /** Review 2026-10-27 (batch 2): airdefense.codeOnlyProbeCodes. */
    public static int adCodeOnlyProbeCodes() {
        return received ? adCodeOnlyProbeCodes : ConfigSVA.adCodeOnlyProbeCodes;
    }

    /** Review 2026-10-27 (batch 2): airdefense.codeOnlyProbeMinutes. */
    public static int adCodeOnlyProbeMinutes() {
        return received ? adCodeOnlyProbeMinutes : ConfigSVA.adCodeOnlyProbeMinutes;
    }

    /**
     * Self-test (audit 2026-10-22): the settings as the server writes them, read back as a client would - "" when the air
     * defence's part comes through whole, else what differs; an older server's packet (no air-defence part) leaves the
     * client's own values.
     */
    public static String selfTest() {
        Settings a = Settings.now();
        a.adZoneWarnSeconds = 17;                                   // not the default: a real round trip
        a.adMaxRadars = 9;
        a.adUpgrades = !ConfigSVA.adUpgrades;
        a.adCodeOnlyManual = !ConfigSVA.adCodeOnlyManual;           // code-only wave: its block too
        a.adKeyClock = com.sva.airdefense.XpdrMath.CLOCK_DAY;
        a.adCodeCardMaxPeriods = 5;
        a.adCodeOnlyEmergencySeconds = 77;
        a.adCodeOnlyProbeCodes = 6;                                 // review 2026-10-27 (batch 2): the guessing guard's too
        a.adCodeOnlyProbeMinutes = 9;
        io.netty.buffer.ByteBuf b = io.netty.buffer.Unpooled.buffer();
        a.toBytes(b);
        Settings c = new Settings();
        c.fromBytes(b);
        StringBuilder bad = new StringBuilder();
        if (c.adZoneWarnSeconds != 17 || c.adMaxRadars != 9 || c.adMaxLaunchers != a.adMaxLaunchers || c.adUpgrades != a.adUpgrades
                || c.adEnabled != a.adEnabled || c.adKeepModules != a.adKeepModules || c.adDayKey != a.adDayKey || c.adFires != a.adFires
                || c.wear != a.wear || c.consumption != a.consumption || c.chargerRate != a.chargerRate || c.hydrogen != a.hydrogen) {
            bad.append(" round trip");
        }
        if (c.adCodeOnly != a.adCodeOnly || c.adCodeOnlyManual != a.adCodeOnlyManual || c.adAllTransponders != a.adAllTransponders
                || c.adKeyClock != com.sva.airdefense.XpdrMath.CLOCK_DAY || c.adCodeCardMaxPeriods != 5 || c.adCodeOnlyArmSeconds != a.adCodeOnlyArmSeconds
                || c.adCodeOnlyMinWarningSeconds != a.adCodeOnlyMinWarningSeconds || c.adCodeOnlyEmergencySeconds != 77 || c.adCodeOnlyProbeCodes != 6
                || c.adCodeOnlyProbeMinutes != 9) {
            bad.append(" code-only round trip");
        }
        if (b.readableBytes() != 0) {
            bad.append(" left ").append(b.readableBytes());
        }
        io.netty.buffer.ByteBuf o = io.netty.buffer.Unpooled.buffer();
        o.writeByte(1);
        o.writeFloat(1F);
        o.writeFloat(1F);
        Settings d = new Settings();
        d.fromBytes(o);
        if (d.adZoneWarnSeconds != ConfigSVA.adZoneWarnSeconds || d.adUpgrades != ConfigSVA.adUpgrades || !d.wear) {
            bad.append(" old packet");
        }
        if (d.adCodeOnly || d.adAllTransponders || d.adKeyClock != com.sva.airdefense.XpdrMath.CLOCK_DAY) {
            bad.append(" old packet code-only");                    // an older server: no code-only, the day clock, aircraft only
        }
        // an air-defence block without the code-only one (the server one version back)
        io.netty.buffer.ByteBuf o2 = io.netty.buffer.Unpooled.buffer();
        Settings n = Settings.now();
        n.toBytes(o2);
        o2.writerIndex(o2.writerIndex() - CODE_ONLY_BYTES);
        Settings e = new Settings();
        e.fromBytes(o2);
        if (e.adMaxRadars != n.adMaxRadars || e.adCodeOnly || e.adAllTransponders || o2.readableBytes() != 0) {
            bad.append(" packet before code-only");
        }
        return bad.toString();
    }

    /** The code-only block's size on the wire (a flags byte, the clock byte, six ints). */
    static final int CODE_ONLY_BYTES = 2 + 4 * 6;

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
        boolean adEnabled, adUpgrades, adKeepModules, adDayKey, adFires;
        int adZoneWarnSeconds, adMaxLaunchers, adMaxRadars;
        boolean adCodeOnly, adCodeOnlyManual, adAllTransponders;
        int adKeyClock, adCodeCardMaxPeriods, adCodeOnlyArmSeconds, adCodeOnlyMinWarningSeconds, adCodeOnlyEmergencySeconds;
        int adCodeOnlyProbeCodes, adCodeOnlyProbeMinutes;

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
            m.adEnabled = ConfigSVA.adEnabled;
            m.adUpgrades = ConfigSVA.adUpgrades;
            m.adKeepModules = ConfigSVA.adKeepModules;
            m.adDayKey = ConfigSVA.adDayKey;
            m.adFires = ConfigSVA.adFires();
            m.adZoneWarnSeconds = ConfigSVA.adZoneWarnSeconds;
            m.adMaxLaunchers = ConfigSVA.adMaxLaunchers;
            m.adMaxRadars = ConfigSVA.adMaxRadars;
            m.adCodeOnly = ConfigSVA.adCodeOnly;
            m.adCodeOnlyManual = ConfigSVA.adCodeOnlyManual;
            m.adAllTransponders = ConfigSVA.adAllTransponders;
            m.adKeyClock = com.sva.airdefense.XpdrMath.keyClock(ConfigSVA.adKeyClock);
            m.adCodeCardMaxPeriods = ConfigSVA.adCodeCardMaxPeriods;
            m.adCodeOnlyArmSeconds = ConfigSVA.adCodeOnlyArmSeconds;
            m.adCodeOnlyMinWarningSeconds = ConfigSVA.adCodeOnlyMinWarningSeconds;
            m.adCodeOnlyEmergencySeconds = ConfigSVA.adCodeOnlyEmergencySeconds;
            m.adCodeOnlyProbeCodes = ConfigSVA.adCodeOnlyProbeCodes;
            m.adCodeOnlyProbeMinutes = ConfigSVA.adCodeOnlyProbeMinutes;
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
            if (b.readableBytes() >= 13) {                          // audit 2026-10-22: the air defence's
                int a = b.readUnsignedByte();
                adEnabled = (a & 1) != 0;
                adUpgrades = (a & 2) != 0;
                adKeepModules = (a & 4) != 0;
                adDayKey = (a & 8) != 0;
                adFires = (a & 16) != 0;
                adZoneWarnSeconds = b.readInt();
                adMaxLaunchers = b.readInt();
                adMaxRadars = b.readInt();
            } else {
                adEnabled = ConfigSVA.adEnabled;                    // an older server: the client's own
                adUpgrades = ConfigSVA.adUpgrades;
                adKeepModules = ConfigSVA.adKeepModules;
                adDayKey = ConfigSVA.adDayKey;
                adFires = ConfigSVA.adFires();
                adZoneWarnSeconds = ConfigSVA.adZoneWarnSeconds;
                adMaxLaunchers = ConfigSVA.adMaxLaunchers;
                adMaxRadars = ConfigSVA.adMaxRadars;
            }
            if (b.readableBytes() >= CODE_ONLY_BYTES) {             // code-only wave
                int f2 = b.readUnsignedByte();
                adCodeOnly = (f2 & 1) != 0;
                adCodeOnlyManual = (f2 & 2) != 0;
                adAllTransponders = (f2 & 4) != 0;
                adKeyClock = b.readUnsignedByte() == com.sva.airdefense.XpdrMath.CLOCK_DAY ? com.sva.airdefense.XpdrMath.CLOCK_DAY
                        : com.sva.airdefense.XpdrMath.CLOCK_TOTAL;
                adCodeCardMaxPeriods = b.readInt();
                adCodeOnlyArmSeconds = b.readInt();
                adCodeOnlyMinWarningSeconds = b.readInt();
                adCodeOnlyEmergencySeconds = b.readInt();
                adCodeOnlyProbeCodes = b.readInt();
                adCodeOnlyProbeMinutes = b.readInt();
            } else {
                adCodeOnly = false;                                 // an older server: no such mode, the day clock, aircraft only
                adCodeOnlyManual = true;
                adAllTransponders = false;
                adKeyClock = com.sva.airdefense.XpdrMath.CLOCK_DAY;
                adCodeCardMaxPeriods = ConfigSVA.adCodeCardMaxPeriods;
                adCodeOnlyArmSeconds = ConfigSVA.adCodeOnlyArmSeconds;
                adCodeOnlyMinWarningSeconds = ConfigSVA.adCodeOnlyMinWarningSeconds;
                adCodeOnlyEmergencySeconds = ConfigSVA.adCodeOnlyEmergencySeconds;
                adCodeOnlyProbeCodes = ConfigSVA.adCodeOnlyProbeCodes;
                adCodeOnlyProbeMinutes = ConfigSVA.adCodeOnlyProbeMinutes;
            }
        }

        @Override
        public void toBytes(ByteBuf b) {
            b.writeByte((wear ? 1 : 0) | (tuning ? 2 : 0) | (cruise ? 4 : 0) | (autopilot ? 8 : 0) | (trailerKinds ? 16 : 0) | (locks ? 32 : 0) | (hydrogen ? 64 : 0));
            b.writeFloat(consumption);
            b.writeFloat(chargerRate);
            b.writeByte((adEnabled ? 1 : 0) | (adUpgrades ? 2 : 0) | (adKeepModules ? 4 : 0) | (adDayKey ? 8 : 0) | (adFires ? 16 : 0));
            b.writeInt(adZoneWarnSeconds);
            b.writeInt(adMaxLaunchers);
            b.writeInt(adMaxRadars);
            b.writeByte((adCodeOnly ? 1 : 0) | (adCodeOnlyManual ? 2 : 0) | (adAllTransponders ? 4 : 0));
            b.writeByte(adKeyClock);
            b.writeInt(adCodeCardMaxPeriods);
            b.writeInt(adCodeOnlyArmSeconds);
            b.writeInt(adCodeOnlyMinWarningSeconds);
            b.writeInt(adCodeOnlyEmergencySeconds);
            b.writeInt(adCodeOnlyProbeCodes);
            b.writeInt(adCodeOnlyProbeMinutes);
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
            adEnabled = m.adEnabled;
            adUpgrades = m.adUpgrades;
            adKeepModules = m.adKeepModules;
            adDayKey = m.adDayKey;
            adFires = m.adFires;
            adZoneWarnSeconds = Math.max(5, Math.min(120, m.adZoneWarnSeconds));
            adMaxLaunchers = Math.max(0, m.adMaxLaunchers);
            adMaxRadars = Math.max(0, m.adMaxRadars);
            adCodeOnly = m.adCodeOnly;
            adCodeOnlyManual = m.adCodeOnlyManual;
            adAllTransponders = m.adAllTransponders;
            adKeyClock = m.adKeyClock;
            adCodeCardMaxPeriods = Math.max(1, Math.min(72, m.adCodeCardMaxPeriods));
            adCodeOnlyArmSeconds = Math.max(0, Math.min(600, m.adCodeOnlyArmSeconds));
            adCodeOnlyMinWarningSeconds = Math.max(0, Math.min(60, m.adCodeOnlyMinWarningSeconds));
            adCodeOnlyEmergencySeconds = Math.max(0, Math.min(3600, m.adCodeOnlyEmergencySeconds));
            adCodeOnlyProbeCodes = Math.max(2, Math.min(32, m.adCodeOnlyProbeCodes));
            adCodeOnlyProbeMinutes = Math.max(1, Math.min(120, m.adCodeOnlyProbeMinutes));
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
