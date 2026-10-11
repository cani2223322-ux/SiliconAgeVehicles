package com.sva.airdefense;

import com.sva.vehicle.VehicleType;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Air-defence wave (plan-next §1, mock-up veh_ad_1_transponder): the transponder (IFF) - pure, the dedicated server's
 * self-test checks it.
 * <p>
 * Modes as in real aviation: ВЫКЛ / ДЕЖ (on, silent) - no reply, the radar sees only a blip; A - the code (4 octal
 * digits 0000..7777: 8 and 9 do not exist); C - the code and the altitude; S - plus the board number (the air defence's
 * Mode S white list). Special codes: 7700 «авария» (never fired at, flashes red on every scope), 7600 «нет связи» (no fire
 * for NO_RADIO_GRACE while the command post decides). IDENT makes the blip flash for IDENT_TICKS on every radar.
 * <p>
 * «Ключ дня»: the air defence owner's secret word and the game day give the day's code (dayCode) - the command post prints
 * it on a «Карта кодов»; a transponder with «Ключ дня» on takes the code from the card in the pilot's inventory by itself
 * (the КЛЮЧ ДНЯ lamp lights while the card is today's). Yesterday's card gives yesterday's code - a stranger's.
 * Codes are kept as ints holding the octal number (07700 is «7700»).
 */
public final class XpdrMath {

    public static final int OFF = 0, STBY = 1, A = 2, C = 3, S = 4, MODES = 5;
    /** Lang keys sva.xpdr.mode.* (the cockpits' existing ones). */
    public static final String[] MODE_KEYS = {"off", "stby", "a", "c", "s"};
    /** 7000 - the usual code «flying on my own», 7700 emergency, 7600 radio failure, 7500 (kept out of the day codes). */
    public static final int VFR = 07000, EMERGENCY = 07700, NO_RADIO = 07600, HIJACK = 07500, MAX = 07777;
    /** IDENT: the blip flashes 18 s; the ОТВЕТ lamp burns 1 s after each interrogation. */
    public static final int IDENT_TICKS = 18 * 20, REPLY_TICKS = 20;
    /** A game day (the day starts at 6:00, world time 0). */
    public static final long DAY_TICKS = 24000L;
    /** 7600: no fire for 2 minutes after it was first seen. */
    public static final int NO_RADIO_GRACE = 2 * 60 * 20;

    private XpdrMath() {
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

    /** 02471 -> "2471" (always four digits). */
    public static String format(int code) {
        StringBuilder b = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            b.append((char) ('0' + digit(code, i)));
        }
        return b.toString();
    }

    /** The game day of a world time (negative: day 0). */
    public static long day(long worldTime) {
        return worldTime < 0 ? 0 : worldTime / DAY_TICKS;
    }

    /** Codes a day key never gives: 0000, 7000 (everyone's own), 7500 / 7600 / 7700 and the whole 77xx block. */
    public static boolean reserved(int code) {
        return code == 0 || code == VFR || code == HIJACK || code == NO_RADIO || code == EMERGENCY || code >> 6 == 077;
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
        long h = 0xcbf29ce484222325L;
        String s = secret == null ? "" : secret;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
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

    /** Vehicles with a transponder: everything that really flies (ground vehicles and the hoverbike have none). */
    public static boolean fitted(VehicleType t) {
        return t != null && t.movement.flies;
    }

    /** The next mode on the knob (ВЫКЛ - ДЕЖ - A - C - S - ВЫКЛ). */
    public static int nextMode(int m) {
        return (Math.max(0, Math.min(MODES - 1, m)) + 1) % MODES;
    }

    // ------------------------------------------------------------------ a vehicle's transponder (VehicleState.xpdr)

    /** The transponder of one vehicle (server: the vehicle's NBT; client: the copy sent to its riders). */
    public static final class Xpdr {
        public static final String K_MODE = "XMode", K_CODE = "XCode", K_KEY = "XKey", K_CARD_DAY = "XCardDay";

        /** A new vehicle: mode C with 7000 (the altitude for the zones, «flying on my own»), «Ключ дня» on. */
        public int mode = C, code = VFR;
        public boolean dayKey = true;
        /** The day of the card the code was last taken from (-1: none) - the КЛЮЧ ДНЯ lamp. */
        public long cardDay = -1;
        /** Server ticks: IDENT flashing until, the last interrogation (the ОТВЕТ lamp); not saved. */
        public long identUntil = -1, lastInterrogated = -1;
        /** The tick 7600 was first set (its grace), -1 while another code is set; not saved. */
        public long noRadioSince = -1;

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

        /** A new code from the pilot (or the card): 7600's grace starts when it is first set. */
        public void setCode(int c, long now) {
            c = valid(c) ? c : code;
            if (c == NO_RADIO && code != NO_RADIO) {
                noRadioSince = now;
            } else if (c != NO_RADIO) {
                noRadioSince = -1;
            }
            code = c;
        }

        /** 7600 is still in its no-fire grace. */
        public boolean noRadioGrace(long now) {
            return replying() && code == NO_RADIO && (noRadioSince < 0 || now - noRadioSince < NO_RADIO_GRACE);
        }

        public void write(NBTTagCompound t) {
            t.setByte(K_MODE, (byte) mode);
            t.setShort(K_CODE, (short) code);
            t.setBoolean(K_KEY, dayKey);
            t.setLong(K_CARD_DAY, cardDay);
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
        }
    }
}
