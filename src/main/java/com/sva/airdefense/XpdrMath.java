package com.sva.airdefense;

import com.sva.vehicle.VehicleType;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Air-defence wave (plan-next §1, mock-up veh_ad_1_transponder): the transponder (IFF) - pure, the dedicated server's
 * self-test checks it.
 * <p>
 * Modes as in real aviation: ВЫКЛ / ДЕЖ (on, silent) - no reply, the radar sees only a blip; A - the code (4 octal
 * digits 0000..7777: 8 and 9 do not exist); C - the code and the altitude; S - plus the board number (the air defence's
 * Mode S white list). Special codes: 7700 «авария» (never fired at while it is heard - the board's power on, not a wreck:
 * AirDefenseSVA.xpdrOn; flashes red on every scope), 7600 «нет связи» (no fire while the command post decides - its
 * airdefense.noRadioDecisionSeconds; a launcher without a working post gives NO_RADIO_GRACE, counted over all of the
 * vehicle's 7600 episodes - NoRadioMath, AirDefenseDataSVA.freeNoRadio). 7500 - the post's alarm. IDENT makes the blip
 * flash for IDENT_TICKS on every radar.
 * <p>
 * «Ключ дня»: the air defence owner's secret word and the game day give the day's code (dayCode) - the command post prints
 * it on a «Карта кодов»; a transponder with «Ключ дня» on takes the code from the card in the pilot's inventory by itself
 * (the КЛЮЧ ДНЯ lamp lights while the card is today's). Yesterday's card gives yesterday's code - a stranger's.
 * Codes are kept as ints holding the octal number (07700 is «7700»).
 * <p>
 * Code-only wave (docs/proposal-ad-strict-iff.md): the «day» is a key period on airdefense.keyClock (period - the world's
 * total time by default: /time set no longer brings an old card back); the day code mixes in the server's pepper and the
 * owner's key generation (dayCode(secret, pepper, gen, period)); the card carries no code - the transponder remembers whose
 * key set its code (Xpdr.cardOwner / cardGen / cardFirst / cardUntil), when the code was set (codeSince - the guessing
 * guard) and the ОПОЗН lamp (idCaution); the code a card set goes back to 7000 when the card is gone (dropCard). The own
 * codes of a post are never reserved ones (ownCodeAllowed); fitted(t, all) - every vehicle with allTransponders.
 * <p>
 * Decision 3 (a transponder on every vehicle): fitted(t) follows airdefense.allTransponders (the server's value on a
 * client); where it shows (screenLine), whom a tower hears (towerHears) and who is warned on boarding (seenByRadars).
 */
public final class XpdrMath {

    public static final int OFF = 0, STBY = 1, A = 2, C = 3, S = 4, MODES = 5;
    /** Lang keys sva.xpdr.mode.* (the cockpits' existing ones). */
    public static final String[] MODE_KEYS = {"off", "stby", "a", "c", "s"};
    /** 7000 - the usual code «flying on my own», 7700 emergency, 7600 radio failure, 7500 (kept out of the day codes). */
    public static final int VFR = 07000, EMERGENCY = 07700, NO_RADIO = 07600, HIJACK = 07500, MAX = 07777;
    /** IDENT: the blip flashes 18 s; the ОТВЕТ lamp burns 1 s after each interrogation. */
    public static final int IDENT_TICKS = 18 * 20, REPLY_TICKS = 20;
    /**
     * Code-only wave: the ОПОЗН lamp stays lit this long after a code-only air defence's interrogation without a match - two
     * surveillance rounds and a bit (each renews it), so it does not blink out between them.
     */
    public static final int ID_CAUTION_TICKS = 15 * 20;
    /** A game day (the day starts at 6:00, world time 0). */
    public static final long DAY_TICKS = 24000L;
    /** 7600: no fire for 2 minutes after it was first seen. */
    public static final int NO_RADIO_GRACE = 2 * 60 * 20;
    /** Audit review 2026-10-22: a command post's 7500 alarm (the log, the sound, the chat) once a vehicle in this many ticks. */
    public static final long HIJACK_REPEAT = 5 * 60 * 20;

    private XpdrMath() {
    }

    /**
     * Audit review 2026-10-22: a 7500 heard again - is the alarm told again (`last`: when it was told for this vehicle, -1
     * never)? Once in HIJACK_REPEAT; a clock gone back (another world's time) tells it.
     */
    public static boolean hijackAlarm(long last, long now) {
        return last < 0 || now < last || now - last >= HIJACK_REPEAT;
    }

    /** A code 0000..7777 (octal). */
    public static boolean valid(int code) {
        return code >= 0 && code <= MAX;
    }

    /** Digit i (0 = the leftmost) of the code, 0..7. */
    public static int digit(int code, int i) {
        return code >> (3 * (3 - i)) & 7;
    }

    /** The code with digit i stepped by d (wraps 0..7, the other digits untouched). */
    public static int step(int code, int i, int d) {
        if (i < 0 || i > 3) {
            return code & MAX;
        }
        int sh = 3 * (3 - i);
        int v = ((digit(code, i) + d) % 8 + 8) % 8;
        return (code & MAX & ~(7 << sh)) | v << sh;
    }

    /** "2471" -> 02471; anything but four digits 0..7 -> -1. */
    public static int parse(String s) {
        if (s == null) {
            return -1;
        }
        s = s.trim();
        if (s.length() != 4) {
            return -1;
        }
        int v = 0;
        for (int i = 0; i < 4; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '7') {
                return -1;
            }
            v = v << 3 | (c - '0');
        }
        return v;
    }

    /**
     * 02471 -> "2471" (always four digits). Review 2026-10-27 (batch 3): a code out of 0000-7777 (-1: not told to this rider,
     * riderCode) -> "----".
     */
    public static String format(int code) {
        if (code < 0 || code > MAX) {
            return "----";
        }
        StringBuilder b = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            b.append((char) ('0' + digit(code, i)));
        }
        return b.toString();
    }

    /**
     * Review 2026-10-27 (batch 3): the code a rider's copy of the transponder carries (D_XPDR «C») - whole for who works it
     * (xpdrOperator), else only the public ones (7000, 7500, 7600, 7700), -1 for the rest: a passenger left knowing the key's
     * code, and with «Ручной набор кода» on by default dialling it made his own aircraft a friend till the period's end.
     */
    public static int riderCode(boolean operator, int code) {
        if (operator || code == VFR || code == HIJACK || code == NO_RADIO || code == EMERGENCY) {
            return code;
        }
        return -1;
    }

    /** The game day of a world time (negative: day 0). */
    public static long day(long worldTime) {
        return worldTime < 0 ? 0 : worldTime / DAY_TICKS;
    }

    /**
     * Code-only wave (proposal-ad-strict-iff item 19): the clock the «ключ дня» runs on - airdefense.keyClock. TOTAL (the
     * default): the world's total ticks (World.getTotalWorldTime) - /time set, sleeping and doDaylightCycle=false no longer
     * bring an old card back to life (the audit's hole 4.2); DAY: the time of day as before (the key changes at 6:00).
     */
    public static final int CLOCK_DAY = 0, CLOCK_TOTAL = 1;
    public static final String[] CLOCK_KEYS = {"day", "total"};
    /** One key period: a game day's length (20 min of real time). */
    public static final long PERIOD_TICKS = DAY_TICKS;

    /** "day" -> CLOCK_DAY; anything else (the default "total") -> CLOCK_TOTAL. */
    public static int keyClock(String s) {
        return s != null && CLOCK_KEYS[CLOCK_DAY].equalsIgnoreCase(s.trim()) ? CLOCK_DAY : CLOCK_TOTAL;
    }

    /** The ticks the key's clock reads (never negative). */
    public static long clockTicks(int clock, long worldTime, long totalTime) {
        return Math.max(0, clock == CLOCK_DAY ? worldTime : totalTime);
    }

    /**
     * The key period now - everything that names «today's» code goes through this (the post's day code and its log, the
     * КЛЮЧ ДНЯ lamp, the tower's mayReplace, the card's tooltip, the post's picture): one switch for all of them.
     */
    public static long period(int clock, long worldTime, long totalTime) {
        return clockTicks(clock, worldTime, totalTime) / PERIOD_TICKS;
    }

    /** Ticks into the current period (the overlap window after its start) and to the next one (the post's «смена ключа через»). */
    public static long intoPeriod(int clock, long worldTime, long totalTime) {
        return clockTicks(clock, worldTime, totalTime) % PERIOD_TICKS;
    }

    public static long toNextPeriod(int clock, long worldTime, long totalTime) {
        return PERIOD_TICKS - intoPeriod(clock, worldTime, totalTime);
    }

    /** Codes a day key never gives: 0000, 7000 (everyone's own), 7500 / 7600 / 7700 and the whole 77xx block. */
    public static boolean reserved(int code) {
        return code == 0 || code == VFR || code == HIJACK || code == NO_RADIO || code == EMERGENCY || code >> 6 == 077;
    }

    /**
     * Code-only wave (item 35, the usual mode too): a command post's own code may be any valid code but a reserved one - the
     * own codes took 0000 / 7000 / 7500 / 7600 / 7700, and 7000 among them made every new aircraft (it squawks 7000 out of the
     * box) the post's friend. An older save's such code is dropped when read (AirDefenseDataSVA.KpInfo.ownCode, the post).
     */
    public static boolean ownCodeAllowed(int code) {
        return valid(code) && !reserved(code);
    }

    /** The special codes the air defence never fires at on their own (7700; 7600 during its grace). */
    public static boolean special(int code) {
        return code == EMERGENCY || code == NO_RADIO;
    }

    /**
     * The day's code from the secret word and the day: a 64-bit FNV-1a of the secret mixed with the day (SplitMix64's
     * finaliser), its bits taken 12 at a time until a code that is not reserved comes out. Same secret + day - same code on
     * every server start; the next day - another one; an empty secret still works (but everyone can guess it).
     */
    public static int dayCode(String secret, long day) {
        return dayCode(secret, "", 0, day);
    }

    /**
     * Code-only wave: the day's code with the server's «перец» and the owner's key generation (items 24, 36). The pepper - a
     * random 128-bit string the server keeps in AirDefenseDataSVA and never sends to a client: without it a few codes read off
     * the towers' screens let the 8-letter (40-bit) secret be found offline and every future code known. The generation -
     * «Сменить ключ» on the post: every card printed before stops working and every code changes. No pepper and generation
     * 0 - the old dayCode(secret, day) to the bit (old self-tests, an empty server); after the update every code changes once.
     */
    public static int dayCode(String secret, String pepper, int gen, long day) {
        long h = 0xcbf29ce484222325L;
        String s = secret == null ? "" : secret;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        String p = pepper == null ? "" : pepper;
        if (p.length() > 0 || gen != 0) {
            h ^= 0xFF;                                              // a separator: "ab"+"c" is not "a"+"bc"
            h *= 0x100000001b3L;
            for (int i = 0; i < p.length(); i++) {
                h ^= p.charAt(i);
                h *= 0x100000001b3L;
            }
            h = mix(h ^ (gen + 0x51L) * 0xD1B54A32D192ED03L);
        }
        h = mix(h ^ (day + 1) * 0x9E3779B97F4A7C15L);
        for (int k = 0; k < 16; k++) {
            int code = (int) (h >>> (k % 5 * 12) & MAX);
            if (!reserved(code)) {
                return code;
            }
            if (k % 5 == 4) {
                h = mix(h + 0x632BE59BD9B4E019L);
            }
        }
        return 01234;
    }

    static long mix(long z) {
        z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
        z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
        return z ^ z >>> 31;
    }

    /** The transponder answers an interrogation at all (A, C, S). */
    public static boolean replies(int mode) {
        return mode >= A && mode <= S;
    }

    /** Its answer carries the altitude (C, S). */
    public static boolean givesAltitude(int mode) {
        return mode == C || mode == S;
    }

    /** Its answer carries the board number (S). */
    public static boolean givesBoard(int mode) {
        return mode == S;
    }

    /**
     * Vehicles with a transponder on this server (air-defence wave: everything that really flies). Code-only wave (the user's
     * decision 3): airdefense.allTransponders (on by default) fits one to every vehicle - fitted(t, all) with the server's
     * value: a client takes it from ServerConfigSVA (its own config until the server's arrives), the server its config. The
     * «Ответчик» row and its panel, the IDENT key, the code card, the interrogations and the screens all ask this one.
     */
    public static boolean fitted(VehicleType t) {
        return fitted(t, com.sva.net.ServerConfigSVA.adAllTransponders());
    }

    /**
     * Code-only wave (the user's decision 3, instead of «техника без ответчика»): with `all` (airdefense.allTransponders, the
     * default) every vehicle has a transponder, the ground ones too - the buggy, the hoverbike, the cargo crawler, the drill
     * vehicle (the crawler's trailers are parts of it, not vehicles: none of their own); off - only what flies. The radar
     * rules stay (RadarMath.rcs, MIN_AGL): the wheeled and tracked vehicles are never seen, the hoverbike only 4 blocks or
     * more over the ground - a ground vehicle's transponder is asked only where a radar sees it (the surveillance radars, a
     * launcher's own eyes, a tower's IFF: they all start from a radar's contact); towerHears, seenByRadars.
     */
    public static boolean fitted(VehicleType t, boolean all) {
        return t != null && (all || t.movement.flies);
    }

    /**
     * Code-only wave (decision 3): a radar can see this type at all (RadarMath.rcs > 0 - the aircraft and the hoverbike; the
     * buggy, the crawler and the drill vehicle never). The boarding warning «Нет карты кодов: КП ... примет борт за чужого»
     * is said only there - no post ever takes a buggy for anyone.
     */
    public static boolean seenByRadars(VehicleType t) {
        return RadarMath.rcs(t) > 0;
    }

    /**
     * Code-only wave (decision 3): a tower hears this vehicle's transponder (its traffic list, TowerMath.TRAFFIC_RANGE) - an
     * aircraft's always, as before (taxiing too); a ground vehicle's / the hoverbike's only while a radar could see it
     * (`radarSeen`: AirDefenseSVA.radarVisible) - a truck on the apron or a drill under the ground never shows on a tower.
     */
    public static boolean towerHears(VehicleType t, boolean all, boolean radarSeen) {
        return fitted(t, all) && (t.movement.flies || radarSeen);
    }

    /**
     * Audit 2026-10-22, code-only wave (decision 3): the transponder is a line («ОТВ 7000 C») on the main screen - and on the
     * HUD when that is out of sight - not a panel of its own: every type but the light plane (its MFD / vertical panel), the
     * helicopter (its MFD page) and the liner (the pedestal) - the jet, the tiltrotor, the shuttle, the ship and now the
     * buggy, the hoverbike, the crawler and the drill vehicle (each has a main screen: CockpitDials.SCREEN_MAIN).
     */
    public static boolean screenLine(VehicleType t) {
        return t != null && t != VehicleType.PLANE && t != VehicleType.HELI && t != VehicleType.LINER;
    }

    /** The next mode on the knob (ВЫКЛ - ДЕЖ - A - C - S - ВЫКЛ). */
    public static int nextMode(int m) {
        return (Math.max(0, Math.min(MODES - 1, m)) + 1) % MODES;
    }

    // ------------------------------------------------------------------ audit 2026-10-22: the panel's changes, its looks

    /**
     * Audit 2026-10-22 (bug 12): which of the panel's fields a XPDR_SET carries (its "Ch" bits) - only the ones the pilot
     * touched. The panel used to send all three from its own copy, 0..19 ticks old: a mode pressed right after «Ключ дня»
     * sent the old code back, the server took it as a code dialled by hand and switched the day key off; a tower's code and
     * a 7600 set through the command post were reverted the same way. CH_ALL: an old client's packet (no "Ch").
     */
    public static final int CH_MODE = 1, CH_CODE = 2, CH_KEY = 4, CH_ALL = 7;

    /**
     * Review 2026-10-27 (the guessing guard): the panel changes the code at most once in this many ticks - a quicker change
     * waits for its second (Xpdr.heldCode, the latest wins; releaseHeld). Clicks are never lost: the pilot stepping 7000 to
     * 2563 with «+» sees his code arrive a second after the last click. A macro or a modified client sending a new code every
     * 5 ticks no longer gets them on the air at that rate.
     */
    public static final int CODE_RATE_TICKS = 20;

    /**
     * The panel's change applied (the server's setXpdr): the mode when CH_MODE; the code when CH_CODE (a code dialled by
     * hand switches «Ключ дня» off unless the same packet turns it on); «Ключ дня» when CH_KEY. Fields not in `changed` stay
     * as the server has them (the card's code, the tower's, 7600 from the post).
     * <p>
     * Review 2026-10-27: a new code within CODE_RATE_TICKS of the last one the panel set waits (with the packet's key part;
     * the mode goes at once) - a code the same as the one on the air, or «Ключ дня» pressed on, drops a waiting one.
     */
    public static void applyPanel(Xpdr x, int changed, int mode, int code, boolean dayKey, long now) {
        if ((changed & CH_MODE) != 0) {
            x.mode = Math.max(0, Math.min(MODES - 1, mode));
        }
        boolean codeSent = (changed & CH_CODE) != 0 && valid(code);
        if (codeSent && code != x.code && x.handSoon(now)) {
            x.heldCode = code;                                      // too soon after the last one: it waits for its second
            x.heldChanged = changed & (CH_CODE | CH_KEY);
            x.heldKey = dayKey;
            return;
        }
        if (codeSent || (changed & CH_KEY) != 0 && dayKey) {
            x.heldCode = -1;                                        // a newer code (or the same again), the day key on: none waits
        }
        keyAndCode(x, changed, code, dayKey, now);
    }

    /**
     * Review 2026-10-27: the code that waited for its second (applyPanel) goes on the air once CODE_RATE_TICKS passed since the
     * last one (the server's 5-tick vehicle round and every interrogation ask). @return it went now
     */
    public static boolean releaseHeld(Xpdr x, long now) {
        if (x.heldCode < 0 || x.handSoon(now)) {
            return false;
        }
        int c = x.heldCode;
        x.heldCode = -1;
        keyAndCode(x, x.heldChanged, c, x.heldKey, now);
        return true;
    }

    /**
     * The code and «Ключ дня» part of a panel's change. Review 2026-10-27 (a hijacker keeps the key): «Ключ дня» switched off
     * while the code came from the card and the same change sets no new code - the card's code goes back to 7000 (dropCard
     * with the reset), as when the card itself is gone. Before, the key's code stayed and counted as one dialled by hand: a
     * thief one click on «Ключ дня» ahead of the next card round (under a second) was a friend at every post of the owner
     * till the period's end, and «верный код у чужого борта» never said so.
     */
    private static void keyAndCode(Xpdr x, int changed, int code, boolean dayKey, long now) {
        boolean wasCard = x.fromCard();
        boolean key = (changed & CH_KEY) != 0 ? dayKey : x.dayKey;
        boolean dialled = false;
        if ((changed & CH_CODE) != 0 && valid(code) && code != x.code) {
            x.setCode(code, now);
            x.handAt = now;
            dialled = true;
            if ((changed & CH_KEY) == 0 || dayKey == x.dayKey) {
                key = false;                                        // a code dialled by hand - the card no longer sets it
            }
        }
        if (!key) {
            x.dropCard(wasCard && !dialled, now);                   // while dayKey is still the old one: fromCard() holds
        }
        x.dayKey = key;
    }

    /** How a cockpit's code window shows the code: dark (no power), dim (ВЫКЛ / ДЕЖ: set, not sent), amber, red (7700 sent). */
    public static final int SHOW_DARK = 0, SHOW_DIM = 1, SHOW_AMBER = 2, SHOW_RED = 3;

    /**
     * Audit 2026-10-22 (the cockpits): a code that is not sent (ВЫКЛ / ДЕЖ) is shown dimmed, never in the active amber
     * - the pilot sees at a glance that no radar gets it; 7700 being sent is red.
     */
    public static int show(boolean powered, int mode, int code) {
        if (!powered) {
            return SHOW_DARK;
        }
        if (!replies(mode)) {
            return SHOW_DIM;
        }
        return code == EMERGENCY ? SHOW_RED : SHOW_AMBER;
    }

    // ------------------------------------------------------------------ a vehicle's transponder (VehicleState.xpdr)

    /** The transponder of one vehicle (server: the vehicle's NBT; client: the copy sent to its riders). */
    public static final class Xpdr {
        public static final String K_MODE = "XMode", K_CODE = "XCode", K_KEY = "XKey", K_CARD_DAY = "XCardDay";
        /** Code-only wave: the card the code came from (whose key, its generation, its periods [first, until)). */
        public static final String K_CARD_OWN = "XCardOwn", K_CARD_GEN = "XCardGen", K_CARD_FIRST = "XCardFirst", K_CARD_UNTIL = "XCardUntil";

        /** A new vehicle: mode C with 7000 (the altitude for the zones, «flying on my own»), «Ключ дня» on. */
        public int mode = C, code = VFR;
        public boolean dayKey = true;
        /** The key period (XpdrMath.period) of the card the code was last taken from (-1: none) - the КЛЮЧ ДНЯ lamp. */
        public long cardDay = -1;
        /**
         * Code-only wave (item 1): the card that set the code - its key's owner ("" none, or an old card that carries its code
         * itself: no owner, good in the usual mode only), the key generation and the periods it is good for, [cardFirst,
         * cardUntil). The command post in the code-only mode takes a card-set code only from its own owner's card of the
         * current generation and period (CodeOnlyMath.cardState); a code dialled by hand counts only with the post's «Ручной
         * набор кода» (on by default - the user's decision 1).
         */
        public String cardOwner = "";
        public int cardGen;
        public long cardFirst = -1, cardUntil = -1;
        /** Server ticks: IDENT flashing until, the last interrogation (the ОТВЕТ lamp); not saved. */
        public long identUntil = -1, lastInterrogated = -1;
        /** The tick 7600 was first set (its grace), -1 while another code is set; not saved. */
        public long noRadioSince = -1;
        /**
         * Code-only wave, not saved: when the code now squawked was set (-1: long ago - a loaded vehicle) - a guess counts only
         * once the code stood CodeOnlyMath.PROBE_STABLE_TICKS (item 11); the ОПОЗН lamp («не опознан», item 22): lit until this
         * tick by every interrogation of a code-only air defence that found no match.
         */
        public long codeSince = -1, idCautionUntil = -1;
        /**
         * Code-only wave (the command post / launcher integration), not saved: the interrogation tick of the latest match - an
         * older answer without one (a post's track still holds the survey round before the card's code was set) no longer
         * lights the ОПОЗН lamp, and a match heard at or after the lamp's interrogation puts it out.
         */
        public long idMatchAt = -1;
        /**
         * Review 2026-10-27, not saved: when the panel last set a code (applyPanel; -1 never) and the code waiting for its
         * second (-1 none) with its packet's key part (CODE_RATE_TICKS, releaseHeld).
         */
        public long handAt = -1;
        public int heldCode = -1, heldChanged;
        public boolean heldKey;

        /** Review 2026-10-27: the panel set a code less than CODE_RATE_TICKS ago (a clock gone back: no). */
        public boolean handSoon(long now) {
            return handAt >= 0 && now >= handAt && now - handAt < CODE_RATE_TICKS;
        }

        public boolean replying() {
            return replies(mode);
        }

        public boolean identOn(long now) {
            return replying() && now < identUntil;
        }

        /** The КЛЮЧ ДНЯ lamp: «Ключ дня» on and the code from today's card. */
        public boolean keyLamp(long today) {
            return dayKey && cardDay >= 0 && cardDay == today;
        }

        /** The ОТВЕТ lamp: interrogated within REPLY_TICKS. */
        public boolean replyLamp(long now) {
            return replying() && lastInterrogated >= 0 && now - lastInterrogated < REPLY_TICKS && now >= lastInterrogated;
        }

        /**
         * Code-only wave (item 22): the ОПОЗН lamp - a code-only air defence asked within ID_CAUTION_TICKS and found no match
         * (the same for a wrong code, no card or no answer: useless for guessing). A clock gone back puts it out.
         */
        public boolean idCaution(long now) {
            return idCautionUntil >= 0 && now < idCautionUntil && now >= idCautionUntil - ID_CAUTION_TICKS;
        }

        /**
         * Code-only wave: a code-only air defence found no match in the interrogation at `at` - the ОПОЗН lamp lit (renewed);
         * not by an answer older than the latest match (idMatchAt).
         */
        public void caution(long at) {
            if (at > idMatchAt && at + ID_CAUTION_TICKS > idCautionUntil) {
                idCautionUntil = at + ID_CAUTION_TICKS;
            }
        }

        /** Code-only wave: a code-only air defence found a match in the interrogation at `at` - the lamp out if lit by an older one. */
        public void identified(long at) {
            idMatchAt = Math.max(idMatchAt, at);
            if (idCautionUntil >= 0 && at >= idCautionUntil - ID_CAUTION_TICKS) {
                idCautionUntil = -1;
            }
        }

        /** A new code from the pilot (or the card): 7600's grace starts when it is first set; codeSince when it changes. */
        public void setCode(int c, long now) {
            c = valid(c) ? c : code;
            if (c == NO_RADIO && code != NO_RADIO) {
                noRadioSince = now;
            } else if (c != NO_RADIO) {
                noRadioSince = -1;
            }
            if (c != code) {
                codeSince = now;
            }
            code = c;
        }

        /**
         * Code-only wave: the code from a good card (AirDefenseSVA.cardCode, every second): the key's code of this period set,
         * the card remembered (whose key, generation, periods), the КЛЮЧ ДНЯ lamp lit for `period`.
         */
        public void takeCard(int c, String owner, int gen, long first, long until, long period, long now) {
            if (valid(c) && c != code) {
                setCode(c, now);
            }
            cardOwner = owner == null ? "" : owner;
            cardGen = gen;
            cardFirst = first;
            cardUntil = until;
            cardDay = period;
        }

        /**
         * Code-only wave (items 20, 30): no good card with the pilot any more. `reset`: the code the card set goes back to
         * 7000 - a hijacker without the card loses the key within a second of boarding, and a code the card left behind is no
         * «code dialled by hand» that would count (decision 1). A code the pilot dialled himself (the day key off: applyPanel),
         * a tower's code (setSquawk turns the key off) and 7500 / 7600 / 7700 are never touched. Review 2026-10-27: «Ключ дня»
         * switched off on the panel over the card's code resets it the same way (applyPanel).
         */
        public void dropCard(boolean reset, long now) {
            if (reset && fromCard() && code != NO_RADIO && code != EMERGENCY && code != HIJACK) {
                setCode(VFR, now);
            }
            cardDay = -1;
            cardOwner = "";
            cardGen = 0;
            cardFirst = cardUntil = -1;
        }

        /** The code squawked was set by a card (the day key on, a card's code taken and not dropped since). */
        public boolean fromCard() {
            return dayKey && cardDay >= 0;
        }

        /**
         * The transponder's own 7600 timer (since it was last set). Audit 2026-10-22 (4.1): the air defence does not use it - a
         * code change restarts it; the launchers count NoRadioMath's episodes by the vehicle's persistent id instead. Kept for
         * the self-test and old callers.
         */
        public boolean noRadioGrace(long now) {
            return replying() && code == NO_RADIO && (noRadioSince < 0 || now - noRadioSince < NO_RADIO_GRACE);
        }

        public void write(NBTTagCompound t) {
            t.setByte(K_MODE, (byte) mode);
            t.setShort(K_CODE, (short) code);
            t.setBoolean(K_KEY, dayKey);
            t.setLong(K_CARD_DAY, cardDay);
            if (cardOwner.length() > 0) {
                t.setString(K_CARD_OWN, cardOwner);
                t.setInteger(K_CARD_GEN, cardGen);
                t.setLong(K_CARD_FIRST, cardFirst);
                t.setLong(K_CARD_UNTIL, cardUntil);
            }
        }

        /** Reads a saved transponder; a missing / bad key keeps the default. */
        public void read(NBTTagCompound t) {
            if (t == null) {
                return;
            }
            if (t.hasKey(K_MODE)) {
                mode = Math.max(0, Math.min(MODES - 1, t.getByte(K_MODE)));
            }
            if (t.hasKey(K_CODE)) {
                int c = t.getShort(K_CODE) & 0xFFFF;
                code = valid(c) ? c : VFR;
            }
            if (t.hasKey(K_KEY)) {
                dayKey = t.getBoolean(K_KEY);
            }
            if (t.hasKey(K_CARD_DAY)) {
                cardDay = t.getLong(K_CARD_DAY);
            }
            cardOwner = t.getString(K_CARD_OWN);                     // an older save: no card's owner (the next second sets it)
            cardGen = t.getInteger(K_CARD_GEN);
            cardFirst = t.hasKey(K_CARD_FIRST) ? t.getLong(K_CARD_FIRST) : -1;
            cardUntil = t.hasKey(K_CARD_UNTIL) ? t.getLong(K_CARD_UNTIL) : -1;
        }
    }
}
