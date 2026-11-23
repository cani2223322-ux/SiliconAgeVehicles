package com.sva.compat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sc.embassy.api.AirfieldInfoSC;
import com.sc.embassy.api.EmbassyAirApiSC;
import com.sc.embassy.api.IEmbassyAirHookSC;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

/**
 * «Лётное поле посольства» (docs/plan-embassy-airfield-addon.md §2.1; the core's docs/plan-embassy-airfield.md §3.1): the ONLY class of
 * the add-on that names the core's public API com.sc.embassy.api (EmbassyAirApiSC, AirfieldInfoSC, IEmbassyAirHookSC - API_VERSION 1).
 * Everything else of the add-on asks here, with the add-on's own types (EmbassyFieldSVA - a copy of AirfieldInfoSC), so an older core
 * without the API still runs the add-on: init() looks the API up by Class.forName in a try, checks its API_VERSION and registers the
 * hook; without it (or with another version) on() stays false and every question answers "none" (empty list, null, false, -1, 0) - the
 * embassies' airfields simply do not exist for the add-on. A LinkageError later (a core changed under us) switches it off the same way.
 * <p>
 * The API is the server's (on a client it answers empty) except isRunwaySurface / isEmbassyBlock (block identities - both sides: the
 * runway flag RunwaySurfaceSVA, the ILS obstacle list). A FakePlayer is never served by the core (pay, pass, logbook refuse it).
 * <p>
 * The hook (IEmbassyAirHookSC, registered once in init): the column's service window, the Dispatcher's tab rows and button, the ATIS line
 * - handed to the add-on's classes (block.EmbassyServicesSVA, airport.EmbassyAtcSVA); airfieldsChanged (called from the world generation)
 * only marks airport.EmbassyAirportSVA dirty. The hook never throws (the core would forget it for that call).
 * <p>
 * Loading safety (an older core): no field or signature of this class names an API type the verifier would have to load (the info
 * object is kept as Object in EmbassyFieldSVA.handle and cast back where used; the hook is made typed as its interface inside Hook) and
 * every call is behind on() and a catch of LinkageError.
 * <p>
 * Written whole by the coordinator; the numbers below mirror the API's (SelfTestEmbassySVA compares them while on()).
 */
public final class EmbassyCompatSVA {

    /** The API version this add-on was built against (EmbassyAirApiSC.API_VERSION must equal it). */
    public static final int API_VERSION = 1;
    /** The API's class (looked up by name: an older core has none). */
    public static final String API_CLASS = "com.sc.embassy.api.EmbassyAirApiSC";

    /** The airfield kinds (AirfieldInfoSC.HELI / SHORT / FULL). */
    public static final int HELI = 1, SHORT = 2, FULL = 3;
    public static final String[] KIND_KEYS = {"none", "heli", "short", "full"};
    /** The stands' kinds (AirfieldInfoSC.SPOT_*). */
    public static final int SPOT_LINER = 0, SPOT_SMALL = 1, SPOT_HELI = 2;
    /** pay / quote (EmbassyAirApiSC.PAY_*; the core's lines sc.airfield.pay.<PAY_KEYS[code]>). */
    public static final int PAY_OK = 0, PAY_ENEMY = 1, PAY_LEVEL = 2, PAY_LIMIT = 3, PAY_CHIPS = 4, PAY_NO_FIELD = 5, PAY_UNKNOWN = 6;
    public static final String[] PAY_KEYS = {"ok", "enemy", "level", "limit", "chips", "noField", "unknown"};
    /** requestPass (EmbassyAirApiSC.PASS_*). */
    public static final int PASS_GRANTED = 0, PASS_RUNNING = 1, PASS_USED = 2, PASS_NO_FIELD = 3, PASS_ADMITTED = 4;
    /** adLine / tell (EmbassyAirApiSC.AD_*; the core's lines sc.airfield.ad.<AD_KEYS[line]>). */
    public static final int AD_WARN = 0, AD_FIRE = 1, AD_PASS = 2, AD_PASS_USED = 3, AD_PASS_OVER = 4, AD_LEFT = 5, AD_NEAR = 6;
    public static final String[] AD_KEYS = {"warn", "fire", "pass", "passUsed", "passOver", "left", "near"};
    /** The services (the core's EmbassyAirRulesSC.SERVICE_KEYS, in its order; the core's names sc.airfield.service.<key>). */
    public static final int S_PARK = 0, S_EMERGENCY = 1, S_CHARGE = 2, S_HYDROGEN = 3, S_ARMOUR_GAS = 4, S_REPAIR = 5, S_ELECTROLYTE = 6,
            S_TOW = 7, S_CHART = 8;
    public static final String[] SERVICE_KEYS = {"park", "emergency", "charge", "hydrogen", "armourGas", "repair", "electrolyte", "tow", "chart"};
    /** The core's reputation levels (ReputationSC; levelOf answers one, -1 none) and their lang keys sc.settle.rep.<key>. */
    public static final int ENEMY = 0, VIOLATOR = 1, STRANGER = 2, ACQUAINTANCE = 3, PARTNER = 4, OWN = 5, HONOURED = 6;
    public static final String[] LEVEL_KEYS = {"enemy", "violator", "stranger", "acquaintance", "partner", "own", "honoured"};
    /** The core's flush lights (BlockAirfieldSC metas) - EmbassyFieldSVA.lights(meta). */
    public static final int LIGHT_EDGE = 5, LIGHT_THRESHOLD = 6, LIGHT_END = 7, LIGHT_TAXI = 8;
    /** The Dispatcher's tab: at most this many rows; its one button's action. */
    public static final int TAB_ROWS = 8;
    public static final String TAB_ACTION_SERVICE = "service";
    /** The core's owner name of a settlement («[Поселение-N]» - never a Minecraft name: those are [A-Za-z0-9_] only). */
    public static final String OWNER_PREFIX = "[Поселение-", OWNER_SUFFIX = "]";

    private static volatile boolean on;
    private static volatile String why = "not initialised";
    /** id -> the add-on's copy of the API's current info (made again when the API hands another info object). Server thread. */
    private static final Map<Integer, EmbassyFieldSVA> FIELDS = new HashMap<Integer, EmbassyFieldSVA>();

    private EmbassyCompatSVA() {
    }

    // ================================================================== the switch

    /** The add-on's init (CommonProxySVA.init, both sides): the API looked up, its version checked, the hook registered. */
    public static void init() {
        int v;
        try {
            // the core's version read at run time: EmbassyAirApiSC.API_VERSION is a compile-time constant - named here, javac would put
            // this add-on's 1 in its place and a core of another version would pass the check (integration 2026-10-27)
            Class<?> api = Class.forName(API_CLASS, false, EmbassyCompatSVA.class.getClassLoader());
            v = api.getField("API_VERSION").getInt(null);
        } catch (Throwable t) {
            off("the core has no embassy airfield API (" + t + ") - embassy airfields off");
            return;
        }
        try {
            if (v != API_VERSION) {
                off("the core's embassy airfield API is version " + v + ", this add-on wants " + API_VERSION + " - embassy airfields off");
                return;
            }
            on = true;
            why = "";
            if (!EmbassyAirApiSC.registerHook(Hook.make())) {      // make(): typed as the API's interface inside Hook only
                System.out.println("[SVA-EMB] the core refused the add-on's hook - the column / the tab serve the core's own way");
            }
            System.out.println("[SVA-EMB] embassy airfield API " + v + " found: embassy airfields on");
        } catch (LinkageError e) {
            off("the core's embassy airfield API does not link (" + e + ") - embassy airfields off");
        }
    }

    /** The API is there and of our version (else every question answers "none"). */
    public static boolean on() {
        return on;
    }

    /** Why it is off ("" while on) - the self-test's and the command's line. */
    public static String why() {
        return why;
    }

    /** Server stopped: the copies go (the hook stays - registered once). */
    public static void clear() {
        synchronized (FIELDS) {
            FIELDS.clear();
        }
    }

    private static void off(String reason) {
        boolean was = on;
        on = false;
        why = reason;
        System.out.println("[SVA-EMB] " + reason);
        if (was) {
            clear();
        }
    }

    // ================================================================== where

    /** Every airfield of world `w` (the Overworld's; a client, another world, off: empty). */
    public static List<EmbassyFieldSVA> fields(World w) {
        List<EmbassyFieldSVA> out = new ArrayList<EmbassyFieldSVA>();
        if (!on || w == null || w.isRemote) {
            return out;
        }
        try {
            for (AirfieldInfoSC i : EmbassyAirApiSC.airfields(w)) {
                EmbassyFieldSVA f = copyOf(i);
                if (f != null) {
                    out.add(f);
                }
            }
        } catch (LinkageError e) {
            off("airfields: " + e);
        }
        return out;
    }

    /** Embassy `id`'s airfield in `w` (null: none, off). */
    public static EmbassyFieldSVA field(World w, int id) {
        if (!on || w == null || w.isRemote) {
            return null;
        }
        try {
            return copyOf(EmbassyAirApiSC.airfield(w, id));
        } catch (LinkageError e) {
            off("airfield: " + e);
            return null;
        }
    }

    /** The airfield whose protected zone holds the column (x, z) (null: none). */
    public static EmbassyFieldSVA fieldAt(World w, int x, int z) {
        if (!on || w == null || w.isRemote) {
            return null;
        }
        try {
            return copyOf(EmbassyAirApiSC.airfieldAt(w, x, z));
        } catch (LinkageError e) {
            off("airfieldAt: " + e);
            return null;
        }
    }

    /** The embassy (id) whose dome holds the column, any height (-1: none). */
    public static int domeAt(World w, double x, double z) {
        if (!on || w == null || w.isRemote) {
            return -1;
        }
        try {
            return EmbassyAirApiSC.domeAt(w, x, z);
        } catch (LinkageError e) {
            off("domeAt: " + e);
            return -1;
        }
    }

    /** The players' air defence holds fire here (an airfield embassy's neutral sky; never over a player's base). Cheap off the embassies' land. */
    public static boolean neutralAt(World w, double x, double y, double z) {
        if (!on || w == null || w.isRemote) {
            return false;
        }
        try {
            return EmbassyAirApiSC.neutralAt(w, x, y, z);
        } catch (LinkageError e) {
            off("neutralAt: " + e);
            return false;
        }
    }

    /** Block `b` of `meta` is an embassy runway's surface (paving, white marks, edge / threshold / end lights). Both sides. */
    public static boolean isRunwaySurface(Block b, int meta) {
        if (!on || b == null) {
            return false;
        }
        try {
            return EmbassyAirApiSC.isRunwaySurface(b, meta);
        } catch (LinkageError e) {
            off("isRunwaySurface: " + e);
            return false;
        }
    }

    /** Block `b` is one of an embassy's own (no ILS obstacle). Both sides. */
    public static boolean isEmbassyBlock(Block b) {
        if (!on || b == null) {
            return false;
        }
        try {
            return EmbassyAirApiSC.isEmbassyBlock(b);
        } catch (LinkageError e) {
            off("isEmbassyBlock: " + e);
            return false;
        }
    }

    /** Moves on whenever an airfield is made or renewed (-1: off). */
    public static int revision(World w) {
        if (!on || w == null) {
            return -1;
        }
        try {
            return EmbassyAirApiSC.revision(w);
        } catch (LinkageError e) {
            off("revision: " + e);
            return -1;
        }
    }

    /** The field's protected zone holds the column (the core's shape). */
    public static boolean inZone(EmbassyFieldSVA f, int x, int z) {
        if (!on || f == null) {
            return false;
        }
        try {
            return ((AirfieldInfoSC) f.handle).inZone(x, z);
        } catch (LinkageError e) {
            off("inZone: " + e);
            return false;
        }
    }

    /** The column is under the field's neutral sky (the level disc, the zone, the approach corridors - the core's shape). */
    public static boolean neutral(EmbassyFieldSVA f, int x, int z) {
        if (!on || f == null) {
            return false;
        }
        try {
            return ((AirfieldInfoSC) f.handle).neutral(x, z);
        } catch (LinkageError e) {
            off("neutral: " + e);
            return false;
        }
    }

    /** The core's owner name of embassy `id` («[Поселение-N]»: the virtual tower's owner). */
    public static String ownerOf(int id) {
        return OWNER_PREFIX + id + OWNER_SUFFIX;
    }

    // ================================================================== who

    /** `p`'s level at embassy `id` (ENEMY .. HONOURED; -1: none, a client, off). */
    public static int levelOf(EntityPlayer p, int id) {
        if (!on || p == null) {
            return -1;
        }
        try {
            return EmbassyAirApiSC.levelOf(p, id);
        } catch (LinkageError e) {
            off("levelOf: " + e);
            return -1;
        }
    }

    /** `p` knows embassy `id` (has been there or is within 1024 of it) - the MCDU, the ND and the map show it only then. */
    public static boolean knows(EntityPlayer p, int id) {
        if (!on || p == null) {
            return false;
        }
        try {
            return EmbassyAirApiSC.knows(p, id);
        } catch (LinkageError e) {
            off("knows: " + e);
            return false;
        }
    }

    /** `p` holds a valid talon of embassy `id` (parking free). */
    public static boolean hasTalon(EntityPlayer p, int id) {
        if (!on || p == null) {
            return false;
        }
        try {
            return EmbassyAirApiSC.hasTalon(p, id);
        } catch (LinkageError e) {
            off("hasTalon: " + e);
            return false;
        }
    }

    // ================================================================== services

    /** What `amount` (the service's measure) of service `key` costs `p` at `id`: chips >= 0, or -PAY_* (refused). Nothing changes. */
    public static int quote(EntityPlayer p, int id, String key, long amount, boolean free) {
        if (!on || p == null) {
            return -PAY_NO_FIELD;
        }
        try {
            return EmbassyAirApiSC.quote(p, id, key, amount, free);
        } catch (LinkageError e) {
            off("quote: " + e);
            return -PAY_NO_FIELD;
        }
    }

    /** Pays (the core checks «Враг», the level, the caps, takes the chips): PAY_OK or why not (nothing taken). */
    public static int pay(EntityPlayer p, int id, String key, long amount, boolean free) {
        if (!on || p == null) {
            return PAY_NO_FIELD;
        }
        try {
            return EmbassyAirApiSC.pay(p, id, key, amount, free);
        } catch (LinkageError e) {
            off("pay: " + e);
            return PAY_NO_FIELD;
        }
    }

    /** «Лётная книжка»: `p`'s first landing at `id` (true: given now; the core gives it once an embassy a player). */
    public static boolean firstLanding(EntityPlayer p, int id) {
        if (!on || p == null) {
            return false;
        }
        try {
            return EmbassyAirApiSC.firstLanding(p, id);
        } catch (LinkageError e) {
            off("firstLanding: " + e);
            return false;
        }
    }

    // ================================================================== the embassy's air defence

    /** The air defence zone's radius across from the core (0: off). */
    public static int adRadius() {
        if (!on) {
            return 0;
        }
        try {
            return EmbassyAirApiSC.adRadius();
        } catch (LinkageError e) {
            off("adRadius: " + e);
            return 0;
        }
    }

    /** The core's seconds to turn away (0: off). */
    public static int warnSeconds() {
        if (!on) {
            return 0;
        }
        try {
            return EmbassyAirApiSC.warnSeconds();
        } catch (LinkageError e) {
            off("warnSeconds: " + e);
            return 0;
        }
    }

    /** The embassy (id) whose air defence zone holds (x, z) - never over a player's base (-1: none). */
    public static int adZoneAt(World w, double x, double z) {
        if (!on || w == null || w.isRemote) {
            return -1;
        }
        try {
            return EmbassyAirApiSC.adZoneAt(w, x, z);
        } catch (LinkageError e) {
            off("adZoneAt: " + e);
            return -1;
        }
    }

    /**
     * The embassy (id) whose air defence warning circle holds (x, z) - the guns fire only in adZoneAt (ЛП-1 of the core audit
     * 2026-11-04); -1 none or over a player's base. An older core without it: adZoneAt (its zone was the whole circle).
     */
    public static int adWarnZoneAt(World w, double x, double z) {
        if (!on || w == null || w.isRemote) {
            return -1;
        }
        try {
            return EmbassyAirApiSC.adWarnZoneAt(w, x, z);
        } catch (NoSuchMethodError e) {
            return adZoneAt(w, x, z);
        } catch (LinkageError e) {
            off("adWarnZoneAt: " + e);
            return -1;
        }
    }

    /** `p` is admitted by embassy `id`'s air defence («Знакомый» or higher, not its «Враг»). */
    public static boolean admitted(EntityPlayer p, int id) {
        if (!on || p == null) {
            return false;
        }
        try {
            return EmbassyAirApiSC.admitted(p, id);
        } catch (LinkageError e) {
            off("admitted: " + e);
            return false;
        }
    }

    /** An admitted squad mate of `p` near him (his cover), or null. */
    public static EntityPlayerMP escortOf(EntityPlayer p, int id) {
        if (!on || p == null) {
            return null;
        }
        try {
            return EmbassyAirApiSC.escortOf(p, id);
        } catch (LinkageError e) {
            off("escortOf: " + e);
            return null;
        }
    }

    /** 7700: a hold-fire pass for `p` at `id` - PASS_*. */
    public static int requestPass(EntityPlayer p, int id) {
        if (!on || p == null) {
            return PASS_NO_FIELD;
        }
        try {
            return EmbassyAirApiSC.requestPass(p, id);
        } catch (LinkageError e) {
            off("requestPass: " + e);
            return PASS_NO_FIELD;
        }
    }

    /** The world time `p`'s 7700 pass at `id` ends (0: none). */
    public static long passUntil(EntityPlayer p, int id) {
        if (!on || p == null) {
            return 0L;
        }
        try {
            return EmbassyAirApiSC.passUntil(p, id);
        } catch (LinkageError e) {
            off("passUntil: " + e);
            return 0L;
        }
    }

    /**
     * The warning of `p` at `id` (the core's ledger: one a player an embassy a game day): the world time the turn-away ends - in the
     * future for the day's first, in the past for a later one (no new seconds); 0: none / admitted / a FakePlayer / off.
     */
    public static long requestWarn(EntityPlayer p, int id) {
        if (!on || p == null) {
            return 0L;
        }
        try {
            return EmbassyAirApiSC.requestWarn(p, id);
        } catch (LinkageError e) {
            off("requestWarn: " + e);
            return 0L;
        }
    }

    /** The world time `p`'s warning at `id` today ends (0: none today). */
    public static long warnUntil(EntityPlayer p, int id) {
        if (!on || p == null) {
            return 0L;
        }
        try {
            return EmbassyAirApiSC.warnUntil(p, id);
        } catch (LinkageError e) {
            off("warnUntil: " + e);
            return 0L;
        }
    }

    /** `p` (pilot, passenger or crew) makes his machine no target of `id` now: admitted, covered, his pass running, over a base. */
    public static boolean holdFire(EntityPlayer p, int id) {
        if (!on || p == null) {
            return false;
        }
        try {
            return EmbassyAirApiSC.holdFire(p, id);
        } catch (LinkageError e) {
            off("holdFire: " + e);
            return false;
        }
    }

    /** The core's chat line AD_* of embassy `id` to board `callsign` (null: none, off) - the add-on's radio shows the same. */
    public static IChatComponent adLine(World w, int id, int line, String callsign) {
        if (!on || w == null) {
            return null;
        }
        try {
            return EmbassyAirApiSC.adLine(w, id, line, callsign);
        } catch (LinkageError e) {
            off("adLine: " + e);
            return null;
        }
    }

    /** Sends adLine to `p`'s chat (never a FakePlayer); `callsign` null - his name. */
    public static void tell(EntityPlayer p, int id, int line, String callsign) {
        if (!on || p == null) {
            return;
        }
        try {
            EmbassyAirApiSC.tell(p, id, line, callsign);
        } catch (LinkageError e) {
            off("tell: " + e);
        }
    }

    // ================================================================== world tests

    /** World tests: the API's own answer of a constant (null: off / no such) - SelfTestEmbassySVA compares the mirrors above. */
    public static int[] debugApiConstants() {
        if (!on) {
            return null;
        }
        try {
            return new int[] {EmbassyAirApiSC.API_VERSION, EmbassyAirApiSC.PAY_OK, EmbassyAirApiSC.PAY_ENEMY, EmbassyAirApiSC.PAY_LEVEL,
                EmbassyAirApiSC.PAY_LIMIT, EmbassyAirApiSC.PAY_CHIPS, EmbassyAirApiSC.PAY_NO_FIELD, EmbassyAirApiSC.PAY_UNKNOWN,
                EmbassyAirApiSC.PASS_GRANTED, EmbassyAirApiSC.PASS_RUNNING, EmbassyAirApiSC.PASS_USED, EmbassyAirApiSC.PASS_NO_FIELD,
                EmbassyAirApiSC.PASS_ADMITTED, EmbassyAirApiSC.AD_WARN, EmbassyAirApiSC.AD_FIRE, EmbassyAirApiSC.AD_PASS,
                EmbassyAirApiSC.AD_PASS_USED, EmbassyAirApiSC.AD_PASS_OVER, EmbassyAirApiSC.AD_LEFT, EmbassyAirApiSC.TAB_ROWS,
                AirfieldInfoSC.HELI, AirfieldInfoSC.SHORT, AirfieldInfoSC.FULL, AirfieldInfoSC.SPOT_LINER, AirfieldInfoSC.SPOT_SMALL,
                AirfieldInfoSC.SPOT_HELI};
        } catch (LinkageError e) {
            off("constants: " + e);
            return null;
        }
    }

    /** The mirrors of debugApiConstants() in its order. */
    public static int[] mirrorConstants() {
        return new int[] {API_VERSION, PAY_OK, PAY_ENEMY, PAY_LEVEL, PAY_LIMIT, PAY_CHIPS, PAY_NO_FIELD, PAY_UNKNOWN, PASS_GRANTED, PASS_RUNNING,
            PASS_USED, PASS_NO_FIELD, PASS_ADMITTED, AD_WARN, AD_FIRE, AD_PASS, AD_PASS_USED, AD_PASS_OVER, AD_LEFT, TAB_ROWS, HELI, SHORT, FULL,
            SPOT_LINER, SPOT_SMALL, SPOT_HELI};
    }

    /** The core's hook now is ours (world tests). */
    public static boolean debugHookIsOurs() {
        if (!on) {
            return false;
        }
        try {
            IEmbassyAirHookSC h = EmbassyAirApiSC.hook();
            return h instanceof Hook;
        } catch (LinkageError e) {
            off("hook: " + e);
            return false;
        }
    }

    // ================================================================== inside

    /** The add-on's copy of an info (cached while the API hands the same info object; null for null). */
    private static EmbassyFieldSVA copyOf(AirfieldInfoSC i) {
        if (i == null) {
            return null;
        }
        Integer k = Integer.valueOf(i.id);
        synchronized (FIELDS) {
            EmbassyFieldSVA f = FIELDS.get(k);
            if (f != null && f.handle == i) {
                return f;
            }
            int n = i.spots();
            int[][] spotBoxes = new int[n][];
            int[] spotKinds = new int[n];
            String[] spotKeys = new String[n];
            for (int s = 0; s < n; s++) {
                spotBoxes[s] = i.spotBox(s);
                spotKinds[s] = i.spotKind(s);
                spotKeys[s] = i.spotKey(s);
            }
            f = new EmbassyFieldSVA(i, i.id, i.dim, i.kind, i.version, i.side, i.icao, i.name(), i.coreX, i.coreZ, i.floorY, i.radius, i.gateDir,
                    i.alongX, i.width, i.length, i.headingLow, i.headingHigh, i.numberLow, i.numberHigh, i.ilsLow, i.ilsHigh, i.adRadius,
                    i.runwayBox(), i.stripBox(), i.taxiBox(), i.apronBox(), i.towerDesk(), i.beacon(), i.terminalBox(), i.towerBox(),
                    i.hangarBox(), i.monumentBox(), i.helipadBox(), i.windsock(), i.zoneBox(), i.funnelBoxes(), i.approachBoxes(), spotBoxes,
                    spotKinds, spotKeys, i.columns(), i.adPosts());
            for (int e = 0; e < 2; e++) {
                boolean low = e == 0;
                f.setEnd(low, i.end(low), i.threshold(low), i.thresholdKey(low), i.landingDistance(low), i.localizer(low), i.glideSlope(low),
                        i.padBox(low));
            }
            for (int m = LIGHT_EDGE; m <= LIGHT_TAXI; m++) {
                f.setLights(m, i.lights(m));
            }
            FIELDS.put(k, f);
            return f;
        }
    }

    /**
     * The add-on's hook: the core calls it on the server thread with a real player; never throws (a body's throw is caught and logged
     * here, the answer then "none" - the core serves the column itself).
     */
    private static final class Hook implements IEmbassyAirHookSC {

        /**
         * The hook typed as IEmbassyAirHookSC here, so EmbassyCompatSVA's own bytecode never asks the verifier whether Hook is one
         * (that would load the API's interface while verifying this class - an older core without it would then fail to load the
         * add-on's compat class at all instead of switching the embassies off).
         */
        static IEmbassyAirHookSC make() {
            return new Hook();
        }

        @Override
        public int apiVersion() {
            return API_VERSION;
        }

        @Override
        public boolean openService(EntityPlayerMP p, AirfieldInfoSC field, int column) {
            try {
                EmbassyFieldSVA f = copyOf(field);
                return f != null && com.sva.block.EmbassyServicesSVA.INSTANCE.openService(p, f, column);
            } catch (Throwable t) {
                log("openService", t);
                return false;
            }
        }

        @Override
        public List<IChatComponent> serviceRows(EntityPlayerMP p, AirfieldInfoSC field) {
            try {
                EmbassyFieldSVA f = copyOf(field);
                return f == null ? null : com.sva.block.EmbassyServicesSVA.INSTANCE.serviceRows(p, f);
            } catch (Throwable t) {
                log("serviceRows", t);
                return null;
            }
        }

        @Override
        public IChatComponent atisLine(World w, AirfieldInfoSC field) {
            try {
                EmbassyFieldSVA f = copyOf(field);
                return f == null ? null : com.sva.airport.EmbassyAtcSVA.atisLine(w, f);
            } catch (Throwable t) {
                log("atisLine", t);
                return null;
            }
        }

        @Override
        public void tabAction(EntityPlayerMP p, AirfieldInfoSC field, String action) {
            try {
                EmbassyFieldSVA f = copyOf(field);
                if (f != null) {
                    com.sva.block.EmbassyServicesSVA.INSTANCE.tabAction(p, f, action);
                }
            } catch (Throwable t) {
                log("tabAction", t);
            }
        }

        @Override
        public void airfieldsChanged(World w, int revision) {
            try {
                com.sva.airport.EmbassyAirportSVA.INSTANCE.markDirty(w, revision);  // from the generation: only marked here
            } catch (Throwable t) {
                log("airfieldsChanged", t);
            }
        }

        private static void log(String what, Throwable t) {
            System.out.println("[SVA-EMB] hook " + what + " threw " + t);
            t.printStackTrace(System.out);
        }
    }
}
