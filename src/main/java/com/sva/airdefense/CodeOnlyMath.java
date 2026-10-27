package com.sva.airdefense;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Code-only wave (docs/proposal-ad-strict-iff.md, plan-next §10): the command post's «Опознавание: ПО КОДУ» - pure, the
 * self-test (SelfTestAdCodeOnlySVA) checks it. In this mode only an aircraft whose transponder answers with the code of
 * THIS air defence owner's key is a friend: whose vehicle it is no longer matters (the owner's own machines, his access
 * list, the Mode S white list, the trusted towers make nobody a friend), and anything else becomes a target once two
 * interrogations at least confirmSeconds apart missed - the launchers then work by their own rules.
 * <p>
 * What is here:
 * <ul>
 * <li><b>the key's clock</b> (item 19): XpdrMath.period on airdefense.keyClock - the total world time by default, so /time set
 * and sleeping do not bring old cards back; the overlap window (item 18): for codeOnlyOverlapSeconds after a period starts the
 * last period's code still counts (an interrogation late by a few seconds);</li>
 * <li><b>the card</b> (items 17, 18, 21, 30): a printed «Карта кодов» carries whose key, the key's generation and its periods
 * [first, first + 1 / 3 / 7) - never a code; the server works the code out. A card of another owner, of an older generation
 * («Сменить ключ») or past its periods gives nothing (cardState);</li>
 * <li><b>the match</b> (items 1-4, the user's decision 1): the key's code from a good card - or, with the post's «Ручной набор
 * кода» (on by default), the same code dialled by hand; the post's options «Свои коды действуют» / «Коды аэродрома - свои»
 * add the own and the airport codes (identify). RoeMath.classifyByCode puts the verdict together;</li>
 * <li><b>without haste</b> (items 5, 6): a target only after two misses in a row at least confirmSeconds apart (Confirm),
 * «нет ответа» (standby, off, dark, a wreck) is a miss too;</li>
 * <li><b>7700</b> (items 7, 31; the user's decision 2): no longer a shield for ever - codeOnlyEmergencySeconds (180) per
 * aircraft in all, counted over every episode and shared by all the posts of one air defence owner (NoRadioMath's episode
 * memory, AirDefenseDataSVA.emergency); the post decides «Свой / Проверить / Враждебный» as for 7600; 0 - never a target;</li>
 * <li><b>guessing</b> (item 11, on with the options «Свои коды» / «Ручной набор» - so on by default): a code counts only once it
 * stood 3 s, never 7000 / a tower's 41xx / the last period's code / a reserved one; codeOnlyProbeCodes different wrong codes
 * within 2 minutes from one aircraft or one pilot - the block «ПОДБОР» for codeOnlyProbeMinutes (per owner); the operator
 * lifts it («Свой» or «Снять»);</li>
 * <li><b>arming</b> (item 16): switched on by the owner, it takes effect codeOnlyArmSeconds later; off at once; never without
 * the day key or with the server's codeOnly=false;</li>
 * <li><b>the readiness line</b> (item 32) and the code-only warning floor (item 13: the same for everybody);</li>
 * <li><b>the post's and the launchers' small rules</b> (the integration): the post's readiness by its launchers' rule
 * (bestRoe), what counts as a guess, the ОПОЗН lamp, the boarding warning, the owner-only launchers (item 25), «верный код
 * у чужого борта» (item 30), «борт владельца без кода» (item 33), the arming's end (armedNow).</li>
 * <li><b>review 2026-10-27</b>: a code dialled by hand matches only once it stood 3 s at the interrogation, and a guess is
 * judged at the interrogation too (stood); 7700 / 7600 in their grace hold the fire without wiping the misses (hit); the
 * 7700 memory a day; the arming dropped with the day key / the server's switch and done anew (disarmOnKey, disarmOnServer,
 * rearm); with cards of several owners the one that matters here sets the code (cardRelevance, betterCard) and the
 * boarding pilot is told when it does not (boardingNote).</li>
 * <li><b>review 2026-10-27, batch 2</b>: a card's owner with a code-only post within CARD_REACH of the aircraft comes first
 * (the nearest post's owner alone lost to the guest's own card wherever a third owner's post was nearer); between equal
 * cards the pilot's inventory order decides (the hotbar first), no longer the longest-running one.</li>
 * </ul>
 */
public final class CodeOnlyMath {

    /**
     * The post's options (KpInfo.coOpts): 3а «Свои коды действуют», 3б «Ручной набор кода» (ON by default - the user's
     * decision 1), 3в «Коды аэродрома - свои», item 25 «Доверить ПУ списку доступа» (the launchers' rule and link).
     */
    public static final int OPT_OWN_CODES = 1, OPT_MANUAL = 2, OPT_AIRPORT = 4, OPT_LAUNCHERS_ACCESS = 8, OPT_ALL = 15;
    public static final int DEFAULT_OPTS = OPT_MANUAL;
    public static final String[] OPT_KEYS = {"ownCodes", "manual", "airport", "launchersAccess"};

    /** The card's lifetime in key periods: 1, 3 or 7 (about 20 min, 1 h, 2 h 20 min of real time); 3 by default. */
    public static final int[] CARD_PERIODS = {1, 3, 7};
    public static final int DEFAULT_CARD_PERIODS = 3;

    /** cardState: good now; good only for the last period (in the overlap window); none of this key; past its periods; an older key. */
    public static final int CARD_OK = 0, CARD_PREV = 1, CARD_NONE = 2, CARD_EXPIRED = 3, CARD_OLD_KEY = 4;
    public static final String[] CARD_KEYS = {"ok", "prev", "none", "expired", "oldKey"};

    /** Item 11: a code counts as a guess only after it stood this long; the guesses are counted over this window. */
    public static final int PROBE_STABLE_TICKS = 3 * 20, PROBE_WINDOW_TICKS = 2 * 60 * 20;
    /** A probe memory keeps at most this many codes (more never matter: the block comes at codeOnlyProbeCodes). */
    public static final int PROBE_MAX = 32;

    /**
     * Item 7: a vehicle's 7700 time is remembered this long after it last squawked it - long enough that waiting a little and
     * squawking 7700 again does not buy a fresh grace (the grace is meant per aircraft «in all»). Review 2026-10-27: a day of
     * the server's time (24 real hours of play), it was one hour - an hour away bought a fresh 180 s; the book says «за сутки».
     */
    public static final long EMERGENCY_MEMORY = 24L * 60 * 60 * 20;

    /** The mode on a post: off, arming (the countdown), on, forbidden by the server, no day key (normal classification). */
    public static final int CO_OFF = 0, CO_ARMING = 1, CO_ON = 2, CO_BANNED = 3, CO_NO_KEY = 4;
    public static final String[] CO_KEYS = {"off", "arming", "on", "banned", "noKey"};

    /** The readiness line (item 32): READY, else why not. */
    public static final int R_READY = 0, R_DISABLED = 1, R_WARN_ONLY = 2, R_PVP = 3, R_NO_KP = 4, R_KP_OFF = 5, R_NOT_CODE_ONLY = 6,
            R_HOLD = 7, R_VIOLATORS = 8;
    public static final String[] READY_KEYS = {"ready", "disabled", "warnOnly", "pvp", "noKp", "kpOff", "notCodeOnly", "hold", "violators"};

    /** What the pilot is told (item 22): the same for every wrong code - nothing to learn from it for guessing. */
    public static final int P_NO_CARD = 0, P_EXPIRED = 1, P_NO_REPLY = 2;
    public static final String[] PILOT_KEYS = {"noCard", "expired", "noReply"};

    private CodeOnlyMath() {
    }

    // ------------------------------------------------------------------ the key's clock (item 19), the overlap (item 18)

    /** The key period now (XpdrMath.period - the one switch for the post, the lamp, the tower, the card, the picture). */
    public static long period(int keyClock, long worldTime, long totalTime) {
        return XpdrMath.period(keyClock, worldTime, totalTime);
    }

    /**
     * Item 18: within `overlapTicks` after a period starts the last period's code still counts (a radar's round late by a
     * few seconds, a card's code changed under it). Never in period 0 (there is no last one).
     */
    public static boolean inOverlap(int keyClock, long worldTime, long totalTime, int overlapTicks) {
        return XpdrMath.period(keyClock, worldTime, totalTime) > 0 && XpdrMath.intoPeriod(keyClock, worldTime, totalTime) < Math.max(0, overlapTicks);
    }

    // ------------------------------------------------------------------ the card (items 17, 18, 21, 30)

    /** The card's periods as the post may print them: `asked` held within 1..the server's codeCardMaxPeriods. */
    public static int cardPeriods(int asked, int max) {
        return Math.max(1, Math.min(asked, Math.max(1, max)));
    }

    /** The post's «Срок карты» button: 1 - 3 - 7 - 1, the values over the server's maximum skipped (just 1 when it is 1). */
    public static int nextCardPeriods(int cur, int max) {
        for (int i = 0; i < CARD_PERIODS.length; i++) {
            int c = CARD_PERIODS[(indexOf(cur) + 1 + i) % CARD_PERIODS.length];
            if (c <= Math.max(1, max)) {
                return c;
            }
        }
        return 1;
    }

    private static int indexOf(int periods) {
        for (int i = 0; i < CARD_PERIODS.length; i++) {
            if (CARD_PERIODS[i] >= periods) {
                return CARD_PERIODS[i] == periods ? i : i - 1;
            }
        }
        return CARD_PERIODS.length - 1;
    }

    /**
     * What a card is worth to a key now.
     * @param cardOwner whose key the card carries ("" an old card that holds its code itself, or none)
     * @param until the first period it is no longer good for (first + periods)
     * @param keyOwner the air defence owner asking
     * @param keyGen his key's generation now
     * @param overlap within the overlap window (CARD_PREV: good only for the last period's code)
     */
    public static int cardState(String cardOwner, int cardGen, long first, long until, String keyOwner, int keyGen, long period, boolean overlap) {
        if (cardOwner == null || cardOwner.length() == 0 || keyOwner == null || !cardOwner.equalsIgnoreCase(keyOwner)) {
            return CARD_NONE;
        }
        if (cardGen != keyGen) {
            return CARD_OLD_KEY;
        }
        if (period >= first && period < until) {
            return CARD_OK;
        }
        if (overlap && period - 1 >= first && period - 1 < until) {
            return CARD_PREV;
        }
        return CARD_EXPIRED;
    }

    /** The card sets a code now (this period's for CARD_OK, the last one's for CARD_PREV). */
    public static boolean cardGood(int state) {
        return state == CARD_OK || state == CARD_PREV;
    }

    /** Ticks left on a card good until the period `until` (0: over). */
    public static long cardTicksLeft(long until, int keyClock, long worldTime, long totalTime) {
        return Math.max(0, until * XpdrMath.PERIOD_TICKS - XpdrMath.clockTicks(keyClock, worldTime, totalTime));
    }

    /**
     * Review 2026-10-27 (batch 3, item 22): the minutes the HUD, the transponder's title and the main screen show while the card
     * has 5 minutes or less - the real ones, rounded up (1..5; 0: over 5 left or already over). The «5 МИН» it showed for the
     * whole 5:00-1:01 read wrong with 1:40 left; the chat keeps the two messages (cardWarnMinutes).
     */
    public static int cardMinutesLeft(long ticksLeft) {
        if (ticksLeft <= 0 || ticksLeft > 5 * 60 * 20) {
            return 0;
        }
        return (int) ((ticksLeft + 60 * 20 - 1) / (60 * 20));
    }

    /** Item 22: the chat's «КАРТА КОДОВ: 5 МИН» / «1 МИН» (0: nothing to say - over 5 minutes left, or already over). */
    public static int cardWarnMinutes(long ticksLeft) {
        if (ticksLeft <= 0) {
            return 0;
        }
        if (ticksLeft <= 60 * 20) {
            return 1;
        }
        return ticksLeft <= 5 * 60 * 20 ? 5 : 0;
    }

    // ------------------------------------------------------------------ the match (items 1-4, decision 1)

    /** The server's codeOnlyManual and the post's option 3б: a code dialled by hand counts. */
    public static boolean manualCounts(int opts, boolean serverAllows) {
        return serverAllows && (opts & OPT_MANUAL) != 0;
    }

    /** Item 11: the guessing guard is on with the options «Свои коды» or «Ручной набор» (so on by default). */
    public static boolean probeGuard(int opts) {
        return (opts & (OPT_OWN_CODES | OPT_MANUAL)) != 0;
    }

    /** A stored option set read back (unknown bits dropped; an older save's: the defaults). */
    public static int opts(int raw) {
        return raw & OPT_ALL;
    }

    /**
     * Review 2026-10-27 (the guessing guard): the code heard at the interrogation `at` had stood PROBE_STABLE_TICKS by then
     * (`codeSince` -1: long ago - a loaded vehicle). Both what counts as a guess and what counts as a code dialled by hand
     * ask this one - and always at the interrogation's tick, never at the post's later round over the same answer.
     */
    public static boolean stood(long codeSince, long at) {
        return codeSince < 0 || at - codeSince >= PROBE_STABLE_TICKS;
    }

    /** identify for a code that stood (a card's code, the self-tests): see the full one. */
    public static int identify(boolean replied, int code, int keyNow, int keyPrev, boolean overlap, int card, boolean fromCard, boolean manual,
            boolean ownCode, boolean airportCode) {
        return identify(replied, code, keyNow, keyPrev, overlap, card, fromCard, manual, true, ownCode, airportCode);
    }

    /**
     * The key part of the verdict: a match (RoeMath.ID_KEY / ID_OWN_CODE / ID_AIRPORT) or why not (RoeMath.ID_NO_REPLY,
     * ID_NO_CARD, ID_EXPIRED, ID_OLD_KEY, ID_WRONG). 7700 / 7600 / 7500 and the operator's decisions are RoeMath.classifyByCode's.
     * <p>
     * Review 2026-10-27 (the guessing guard bypassed): a code dialled by hand - the key's (decision 1) or an own code (3а) -
     * counts only once it `stood` 3 s at the interrogation. Before, a wrong code was a guess only after 3 s while the right one
     * matched at once: codes changed every 5 ticks were never counted, and the first match told the guesser the key. A fresh
     * hand-dialled code is now classed exactly as any other code would be (a miss, never a guess) - nothing in the verdict, the
     * lamp or the warnings tells it apart from a wrong one. The card's code needs no wait (the server set it from the card).
     * @param keyNow the key's code of this period (-1: none - no secret, the day key off)
     * @param keyPrev the last period's (-1: none)
     * @param overlap within the overlap window (the last period's code counts too)
     * @param card the transponder's card for this key (cardState)
     * @param fromCard the code was set by the card (XpdrMath.Xpdr.fromCard)
     * @param manual a code dialled by hand counts (manualCounts)
     * @param stood the code stood 3 s at the interrogation (stood)
     * @param ownCode the post's option 3а and one of its own codes (reserved ones never are: XpdrMath.ownCodeAllowed)
     * @param airportCode the post's option 3в and a friendly airport's code (the usual check: not over a violation / handoff)
     */
    public static int identify(boolean replied, int code, int keyNow, int keyPrev, boolean overlap, int card, boolean fromCard, boolean manual,
            boolean stood, boolean ownCode, boolean airportCode) {
        if (!replied) {
            return RoeMath.ID_NO_REPLY;
        }
        boolean key = !XpdrMath.reserved(code) && XpdrMath.valid(code)
                && (keyNow >= 0 && code == keyNow || overlap && keyPrev >= 0 && code == keyPrev);
        if (key && (fromCard && cardGood(card) || manual && stood)) {
            return RoeMath.ID_KEY;
        }
        if (ownCode && stood && XpdrMath.ownCodeAllowed(code)) {
            return RoeMath.ID_OWN_CODE;
        }
        if (airportCode) {
            return RoeMath.ID_AIRPORT;
        }
        switch (card) {
            case CARD_EXPIRED:
                return RoeMath.ID_EXPIRED;
            case CARD_OLD_KEY:
                return RoeMath.ID_OLD_KEY;
            case CARD_OK:
            case CARD_PREV:
                return RoeMath.ID_WRONG;                            // a good card, another code (the day key off)
            default:
                return RoeMath.ID_NO_CARD;
        }
    }

    /** Item 22: what the pilot is told for a miss - nothing about the code itself. */
    public static int pilotReason(int miss) {
        if (miss == RoeMath.ID_NO_REPLY) {
            return P_NO_REPLY;
        }
        return miss == RoeMath.ID_EXPIRED || miss == RoeMath.ID_OLD_KEY ? P_EXPIRED : P_NO_CARD;
    }

    // ------------------------------------------------------------------ without haste (items 5, 6)

    /** One track's misses in a row: how many (0..2) and when the first was heard (an interrogation's tick). */
    public static final class Confirm {
        public long first = -1;
        public int misses;
    }

    /**
     * A miss heard at `at` (the interrogation's tick - the same answer counted again changes nothing): the first, then the
     * second once at least `gapTicks` after the first (at the post: the next survey round, 6 s; at a launcher: by the clock).
     * A clock gone back starts over. @return the misses in a row now (1 or 2)
     */
    public static int miss(Confirm c, long at, int gapTicks) {
        if (c.misses <= 0 || at < c.first) {
            c.misses = 1;
            c.first = at;
        } else if (c.misses == 1 && at - c.first >= Math.max(0, gapTicks)) {
            c.misses = 2;
        }
        return c.misses;
    }

    /**
     * A match or the operator's decision: the count starts over. Review 2026-10-27: no longer 7700 / 7600 in their grace - they
     * only hold the fire (RoeMath.classifyByCode leaves the count as it was): one interrogation on 7700 had wiped two misses and
     * the launchers' warnings, and 180 s of grace bought some thirty fresh starts.
     */
    public static void hit(Confirm c) {
        c.misses = 0;
        c.first = -1;
    }

    /** Two misses in a row: a target. */
    public static boolean confirmed(Confirm c) {
        return c != null && c.misses >= 2;
    }

    // ------------------------------------------------------------------ 7700 (items 7, 31; decision 2)

    /**
     * The 7700 state of a vehicle for one air defence owner (NoRadioMath.S_*): within its grace - PENDING (no fire); the
     * operator's «Свой» - FRIEND for its time, «Проверить» - CHECK (no fire while it squawks 7700), «Враждебный» - HOSTILE;
     * the grace used up - DEFAULT (a miss). `graceTicks` 0: 7700 is never a target (as in the usual mode) - always PENDING.
     */
    public static int emergencyState(NoRadioMath.Entry e, long now, int graceTicks) {
        if (graceTicks <= 0) {
            return NoRadioMath.S_PENDING;
        }
        return NoRadioMath.state(e, now, graceTicks);
    }

    /** Seconds of the 7700 grace left (the track's «7700 1:20»; 0 once used up or decided; -1: no limit). */
    public static int emergencyLeft(NoRadioMath.Entry e, long now, int graceTicks) {
        return graceTicks <= 0 ? -1 : NoRadioMath.secondsLeft(e, now, graceTicks);
    }

    // ------------------------------------------------------------------ guessing (item 11)

    /**
     * A wrong code worth counting as a guess: valid and not reserved (0000, 7000, 7500 / 7600 / 7700, 77xx), it stood
     * PROBE_STABLE_TICKS at the interrogation `at` (`codeSince` -1: long ago - stood), not the last period's code, not a
     * tower's code given to this aircraft. Review 2026-10-27: `at` is the interrogation's tick - it was the post's round,
     * which goes over one survey answer for up to 6 s: a code caught mid-dial (7000 stepped to 2563 with «+») became 3 s old
     * in the post's time and counted, and a pilot who forgot the card got «ПОДБОР» on the way to the right code.
     */
    public static boolean probeCounts(int code, long codeSince, long at, int keyPrev, boolean towerCode) {
        return XpdrMath.ownCodeAllowed(code) && stood(codeSince, at) && code != keyPrev && !towerCode;
    }

    /** One aircraft's / pilot's guesses: the wrong codes heard (and when) and the block. */
    public static final class Probe {
        public final List<Integer> codes = new ArrayList<Integer>();
        public final List<Long> at = new ArrayList<Long>();
        public long blockedUntil = -1;

        public void write(NBTTagCompound t) {
            NBTTagList l = new NBTTagList();
            for (int i = 0; i < codes.size(); i++) {
                NBTTagCompound c = new NBTTagCompound();
                c.setShort("C", (short) codes.get(i).intValue());
                c.setLong("T", at.get(i).longValue());
                l.appendTag(c);
            }
            t.setTag("L", l);
            t.setLong("B", blockedUntil);
        }

        public void read(NBTTagCompound t) {
            codes.clear();
            at.clear();
            NBTTagList l = t.getTagList("L", 10);
            for (int i = 0; i < l.tagCount() && i < PROBE_MAX; i++) {
                codes.add(Integer.valueOf(l.getCompoundTagAt(i).getShort("C") & 0xFFFF));
                at.add(Long.valueOf(l.getCompoundTagAt(i).getLong("T")));
            }
            blockedUntil = t.hasKey("B") ? t.getLong("B") : -1;
        }
    }

    /** The guesses older than the window (or from a clock gone back) forgotten. */
    static void dropOld(Probe p, long now) {
        for (int i = p.codes.size() - 1; i >= 0; i--) {
            long t = p.at.get(i).longValue();
            if (now - t > PROBE_WINDOW_TICKS || t > now) {
                p.codes.remove(i);
                p.at.remove(i);
            }
        }
    }

    /**
     * A wrong code heard (one that probeCounts): kept with its time (the same code again only renews it - «different»
     * codes count); `maxCodes` different ones within PROBE_WINDOW_TICKS - the block for `blockTicks`, the list starts over.
     * Nothing is counted while blocked. @return the block began now
     */
    public static boolean wrong(Probe p, int code, long now, int maxCodes, long blockTicks) {
        if (p == null || blocked(p, now)) {
            return false;
        }
        dropOld(p, now);
        int i = p.codes.indexOf(Integer.valueOf(code));
        if (i >= 0) {
            p.at.set(i, Long.valueOf(now));
        } else if (p.codes.size() < PROBE_MAX) {
            p.codes.add(Integer.valueOf(code));
            p.at.add(Long.valueOf(now));
        }
        if (p.codes.size() >= Math.max(1, maxCodes)) {
            p.blockedUntil = now + Math.max(1, blockTicks);
            p.codes.clear();
            p.at.clear();
            return true;
        }
        return false;
    }

    /** «ПОДБОР»: blocked now. */
    public static boolean blocked(Probe p, long now) {
        return p != null && p.blockedUntil > now;
    }

    /** The operator lifted it («Свой» or «Снять»): no block, the guesses so far forgotten. */
    public static void unblock(Probe p) {
        if (p != null) {
            p.blockedUntil = -1;
            p.codes.clear();
            p.at.clear();
        }
    }

    /** Nothing left to remember (no block, no guess within the window) - the memory may go. */
    public static boolean forgotten(Probe p, long now) {
        if (p == null) {
            return true;
        }
        if (blocked(p, now)) {
            return false;
        }
        dropOld(p, now);
        return p.codes.isEmpty();
    }

    // ------------------------------------------------------------------ arming (item 16)

    /** May the owner switch the mode on? Not with the server's codeOnly=false, nor without the day key (the post's or the server's). */
    public static boolean mayEnable(boolean serverAllows, boolean serverDayKey, boolean postDayKey) {
        return serverAllows && serverDayKey && postDayKey;
    }

    /** Switched on now: takes effect this tick (total world ticks). */
    public static long armAt(long now, int armSeconds) {
        return now + Math.max(0, armSeconds) * 20L;
    }

    /** The mode's state on a post (its stored switch and arming tick; the server's and the day key's word). */
    public static int state(boolean on, long armAt, long now, boolean serverAllows, boolean dayKey) {
        if (!on) {
            return CO_OFF;
        }
        if (!serverAllows) {
            return CO_BANNED;
        }
        if (!dayKey) {
            return CO_NO_KEY;
        }
        return armAt >= 0 && now >= armAt ? CO_ON : CO_ARMING;
    }

    /**
     * Review 2026-10-27 (batch 3, item 22): the post's zones are signed «ПО КОДУ» on the pilots' screens and its listed pilots
     * hear «Нет карты кодов» on boarding - the mode switched on and arming or on: not while the server forbids it nor without
     * the day key (the post's or the server's) - then it classes the usual way.
     */
    public static boolean marked(int state) {
        return state == CO_ARMING || state == CO_ON;
    }

    /** The code-only classification applies now (on, armed, allowed, with the day key). */
    public static boolean active(boolean on, long armAt, long now, boolean serverAllows, boolean dayKey) {
        return state(on, armAt, now, serverAllows, dayKey) == CO_ON;
    }

    /** Seconds of the arming countdown left (0: not arming). */
    public static int armLeft(boolean on, long armAt, long now) {
        return on && armAt > now ? (int) ((armAt - now + 19) / 20) : 0;
    }

    /**
     * Review 2026-10-27 (the arming bypassed): the post's own day key switched off under a switched-on mode - the arming is
     * dropped (armAt -1: state() says ARMING from then on, never ON); the key on again - the post arms it anew (rearm). Before,
     * NO_KEY went straight back to ON (armAt long past): no countdown, no chat, no fresh start of the launchers.
     */
    public static boolean disarmOnKey(boolean on, boolean keyWas, boolean keyNow) {
        return on && keyWas && !keyNow;
    }

    /**
     * Review 2026-10-27: the server's airdefense.codeOnly && dayKey as the save last saw them, and now (a restart with the config
     * changed) - off then and on now: every switched-on post loses its arming and arms anew, as above.
     */
    public static boolean disarmOnServer(boolean wasAllowed, boolean allowedNow) {
        return !wasAllowed && allowedNow;
    }

    /**
     * Review 2026-10-27: a switched-on mode without an arming tick (dropped above) and nothing forbidding it now - the post arms
     * it again: the countdown (codeOnlyArmSeconds), the log and the chat, then ARMING -> ON as for the owner's switch.
     */
    public static boolean rearm(boolean on, long armAt, int state) {
        return on && armAt < 0 && state == CO_ARMING;
    }

    // ------------------------------------------------------------------ the launcher (items 13, 15, 32)

    /** Item 13: the shortest wait to fire - in the code-only mode never under codeOnlyMinWarningSeconds, the same for all. */
    public static int minWarning(int serverMin, boolean codeOnly, int codeOnlyMin) {
        return codeOnly ? Math.max(serverMin, codeOnlyMin) : serverMin;
    }

    /**
     * Item 32: «Огонь по коду: ГОТОВ» or why not - the server's air defence off / warnings only / PvP off; no post linked (the
     * launcher works the usual way) / its post unpowered; the post not code-only; the launcher's rule holds fire / takes only
     * violators. The first that applies.
     */
    public static int readiness(boolean enabled, boolean fires, boolean pvpOk, boolean linked, boolean kpPowered, boolean codeOnly, int roe) {
        if (!enabled) {
            return R_DISABLED;
        }
        if (!fires) {
            return R_WARN_ONLY;
        }
        if (!pvpOk) {
            return R_PVP;
        }
        if (!linked) {
            return R_NO_KP;
        }
        if (!kpPowered) {
            return R_KP_OFF;
        }
        if (!codeOnly) {
            return R_NOT_CODE_ONLY;
        }
        if (roe == RoeMath.HOLD) {
            return R_HOLD;
        }
        return roe == RoeMath.VIOLATORS ? R_VIOLATORS : R_READY;
    }

    // ------------------------------------------------------------------ the post and the launchers (the integration)

    /**
     * The command post's readiness line (item 32) takes its launchers' rule that fires most: «по всем без кода», then «по
     * отметкам КП», then «по нарушителям»; none (or all «огонь запрещён») - HOLD.
     */
    public static int bestRoe(int[] roes) {
        int best = RoeMath.HOLD;
        for (int r : roes) {
            if (r == RoeMath.FOREIGN_ALL || best != RoeMath.FOREIGN_ALL && (r == RoeMath.KP_MARKS || r == RoeMath.VIOLATORS && best == RoeMath.HOLD)) {
                best = r;
            }
        }
        return best;
    }

    /** Item 11: a miss of the code itself - one worth counting as a guess (no card's code, a wrong one, an expired / old key's card's). */
    public static boolean guessed(int id) {
        return id == RoeMath.ID_NO_CARD || id == RoeMath.ID_WRONG || id == RoeMath.ID_EXPIRED || id == RoeMath.ID_OLD_KEY;
    }

    /**
     * Item 22: the ОПОЗН lamp - a code-only air defence interrogated it and found no match (a friend by the operator's word or
     * a match never lights it; a never-asked track neither). With the user's decision 1 (a code dialled by hand counts) the
     * lamp follows the match like the warnings do - what stops guessing is the «ПОДБОР» block (item 11), not the lamp.
     */
    public static boolean caution(boolean asked, int cls, int id) {
        return asked && cls != RoeMath.FRIEND && !RoeMath.idMatch(id);
    }

    /**
     * Item 22: the boarding pilot is told «Нет карты кодов» - he is the owner of a post switched to code-only (arming too) or
     * on its access list, and carries no card of its key good now. The rider is checked, not the vehicle's owner.
     */
    public static boolean boardingWarn(boolean listed, boolean codeOnlyOn, boolean goodCard) {
        return listed && codeOnlyOn && !goodCard;
    }

    /** What the boarding pilot is told besides (review 2026-10-27): nothing, no card, «Ключ дня» off, another key's card squawked. */
    public static final int B_NONE = 0, B_NO_CARD = 1, B_KEY_OFF = 2, B_OTHER_CARD = 3;

    /**
     * Review 2026-10-27 (two owners' cards): the boarding note for one listed code-only post - no good card of its key (item
     * 22); a card of it, but the transponder's «Ключ дня» off (no card sets the code); or, with keyed cards of several owners
     * carried, the code squawked from another owner's card (`keyOwner`: XpdrMath.Xpdr.cardOwner while fromCard, "" none).
     * Before, «has a card» said nothing, and the owner's own launchers fired on him for the ally's card he also carried.
     */
    public static int boardingNote(boolean listed, boolean codeOnlyOn, boolean goodCard, boolean dayKey, int keyedCards, String postOwner, String keyOwner) {
        if (!listed || !codeOnlyOn) {
            return B_NONE;
        }
        if (!goodCard) {
            return B_NO_CARD;
        }
        if (!dayKey) {
            return B_KEY_OFF;
        }
        return keyedCards > 1 && (keyOwner == null || !keyOwner.equalsIgnoreCase(postOwner)) ? B_OTHER_CARD : B_NONE;
    }

    /**
     * Review 2026-10-27, batch 2: a code-only post interrogates an aircraft this far from it (blocks, horizontally) at most -
     * the survey radar's reach on the reference RCS (RadarMath.RANGE) from a radar linked to it (BallisticsMath.LINK away).
     * A card whose owner has such a post this near is the card that matters here (cardRelevance 3).
     */
    public static final double CARD_REACH = maxRange() + BallisticsMath.LINK;

    private static double maxRange() {
        double m = 0;
        for (double r : RadarMath.RANGE) {
            m = Math.max(m, r);
        }
        return m;
    }

    /** cardRelevance before batch 2 (no reach) - the self-test's older checks. */
    public static int cardRelevance(boolean nearestPost, boolean listed) {
        return cardRelevance(false, nearestPost, listed);
    }

    /**
     * Review 2026-10-27 (two owners' cards): what a keyed card means here - 3 its owner has a switched-on code-only post within
     * CARD_REACH of the vehicle (batch 2: his posts see it), 2 his post is the nearest code-only one in this dimension, 1 a
     * holder is on the list of one of his switched-on code-only posts (the owner or the access list), 0 none of these.
     */
    public static int cardRelevance(boolean inReach, boolean nearestPost, boolean listed) {
        return inReach ? 3 : nearestPost ? 2 : listed ? 1 : 0;
    }

    /**
     * Review 2026-10-27: which of two cards sets the code - the one whose key matters here (cardRelevance), then good this
     * period over only the last one's (rank 3 / 2). Batch 2: between equals the one met first wins - the pilot's inventory
     * order (the hotbar first; the driver's before the F/O's), so the pilot chooses by where he keeps the card; the longest
     * running used to win whoever's key it was. @return a beats b (the one met later must be strictly better)
     */
    public static boolean betterCard(int relA, int rankA, int relB, int rankB) {
        if (relA != relB) {
            return relA > relB;
        }
        return rankA > rankB;
    }

    /**
     * Item 25: the launchers of a post switched to code-only (arming too) take their rule of fire and their link from the
     * owner only - the post's «Доверить ПУ списку доступа» gives them back to the list.
     */
    public static boolean ownerOnly(boolean codeOnlyOn, int opts) {
        return codeOnlyOn && (opts & OPT_LAUNCHERS_ACCESS) == 0;
    }

    /**
     * Review 2026-10-27 (batch 3, item 25): the link button refused - the launcher is linked to a post whose launchers are the
     * owner's alone (ownerOnly), or the next post in the cycle is one (a list member set the rule under a usual post and moved
     * the launcher into the code-only one: «по всем без кода (и ваши)» on the owner's behalf).
     */
    public static boolean relinkRefused(boolean lockedNow, boolean lockedNext) {
        return lockedNow || lockedNext;
    }

    /**
     * Item 30: the key's code from a card on an aircraft flown by a stranger to the air defence (a hijacker with the card):
     * a friend till «Сменить ключ», and the post's log says «верный код у чужого борта». The pilot counts (nobody aboard - the
     * vehicle's owner); rel: 1 the owner, 2 the access list, 0 a stranger.
     */
    public static boolean strangerWithKey(int id, boolean fromCard, boolean piloted, int pilotRel, int ownerRel) {
        return id == RoeMath.ID_KEY && fromCard && (piloted ? pilotRel : ownerRel) == 0;
    }

    /**
     * Item 33: the log line and the list's alarm «борт владельца без кода» - a target confirmed on a machine of the owner or of
     * his access list (information only: it changes nothing about the fire).
     */
    public static boolean ownWithoutCode(int rel, int cls) {
        return (rel == 1 || rel == 2) && RoeMath.hostile(cls);
    }

    /**
     * Item 16: the arming done (ARMING -> ON) - the post's log and chat say so and its launchers start their engagements
     * over. The first look after a load (`was` -1) says nothing.
     */
    public static boolean armedNow(int was, int now) {
        return was == CO_ARMING && now == CO_ON;
    }
}
