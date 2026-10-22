package com.sva.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.sva.ConfigSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneEditMath;
import com.sva.airdefense.ZoneMath;
import com.sva.vehicle.CockpitLayout;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.VehicleType;

/**
 * Audit 2026-10-22 self-test (cockpits, the RWR, the transponder's screen and the client fixes) - pure logic, the dedicated
 * server runs it:
 * <ul>
 * <li>bug 11: the RWR scope's radius - a near radar at the centre, a far one at the edge (the old test held it turned over);</li>
 * <li>the RWR's gaps: a launcher's lock its own kind (a lock, not a search), the launching site a launch; the lamps ОБЗОР /
 * ЗАХВАТ / ПУСК each for its own kinds; the sounds - a ping once for a new search radar, the lock's tone, the launch's warble;</li>
 * <li>bug 12: the transponder panel's change applied field by field - a mode pressed with a stale copy no longer puts the
 * old code back over the card's / the tower's / 7600 and no longer switches the day key off (the old full-state packet did:
 * shown too); a hand code switches the key off; the cockpits' code window - OFF / STBY dimmed, 7700 red;</li>
 * <li>bug 13: the zone editor's undo - the entries carry their zone, choosing another zone clears them, a paste's undo goes
 * back to the zone it was pasted over, a new zone's entries follow it when saved;</li>
 * <li>bug 14: a corridor quiets only its own post's forbidden zones (Alice's corridor across Bob's zone does not), its own
 * aerodrome too, a restricted zone never - the client's copy of each post's violated();</li>
 * <li>the jet's / tiltrotor's RWR unit on the glare shield (over the main screen's left end, facing the pilot); the
 * airdefense.* settings' trip to the client; the chat line's lang-key arguments; the liner's live transponder control;
 * the config default and the new lang keys.</li>
 * </ul>
 */
final class SelfTestAdClientSVA {

    private SelfTestAdClientSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    private static boolean near(double a, double b, double tol) {
        return Math.abs(a - b) <= tol;
    }

    static void run() throws Exception {
        rwrScope();
        rwrKinds();
        panel();
        undo();
        corridors();
        unit();
        rest();
    }

    // ---------------------------------------------------------------- bug 11

    private static void rwrScope() {
        boolean mono = true;
        for (int d = 0; d < 700; d += 25) {
            mono &= RadarMath.rwrRadius(d + 25) >= RadarMath.rwrRadius(d);
        }
        check(near(RadarMath.rwrRadius(0), 0.33, 1e-9) && near(RadarMath.rwrRadius(RadarMath.RWR_RANGE), 0.83, 1e-9)
                && near(RadarMath.rwrRadius(5000), 0.83, 1e-9) && RadarMath.rwrRadius(20) < RadarMath.rwrRadius(590) && mono,
                "ad rwr (bug 11): a radar 20 blocks off near the centre, one 590 off near the edge, beyond 600 on the outer ring");
    }

    // ---------------------------------------------------------------- the RWR's gaps

    private static void rwrKinds() {
        check(RadarMath.threatLevel(RadarMath.T_LAUNCHER) == 2 && RadarMath.threatLevel(RadarMath.T_LOCK) == 2
                && RadarMath.threatLevel(RadarMath.T_LAUNCH) == 3 && RadarMath.threatLevel(RadarMath.T_SEARCH) == 1
                && RadarMath.threatLevel(RadarMath.T_HEIGHT) == 1 && RadarMath.rwrLevel(new int[] {RadarMath.T_SEARCH, RadarMath.T_LAUNCHER}) == 2
                && RadarMath.T_LAUNCHER != RadarMath.T_LOCK, "ad rwr: a launcher's lock is its own kind and a lock; the launching site a launch");
        check(RadarMath.rwrLamps(new int[] {RadarMath.T_SEARCH}) == RadarMath.LAMP_SEARCH
                && RadarMath.rwrLamps(new int[] {RadarMath.T_LAUNCHER}) == RadarMath.LAMP_LOCK
                && RadarMath.rwrLamps(new int[] {RadarMath.T_SEARCH, RadarMath.T_LOCK}) == (RadarMath.LAMP_SEARCH | RadarMath.LAMP_LOCK)
                && RadarMath.rwrLamps(new int[] {RadarMath.T_MISSILE, RadarMath.T_LAUNCH, RadarMath.T_HEIGHT}) == (RadarMath.LAMP_LAUNCH | RadarMath.LAMP_SEARCH)
                && RadarMath.rwrLamps(new int[0]) == 0 && RadarMath.rwrLamps(null) == 0,
                "ad rwr: the lamps ОБЗОР / ЗАХВАТ / ПУСК each lit by its own kinds");
        check(RadarMath.rwrPing(1, 0, 1) && !RadarMath.rwrPing(1, 1, 1) && RadarMath.rwrPing(2, 1, 1) && !RadarMath.rwrPing(0, 1, 0)
                && !RadarMath.rwrPing(2, 1, 2) && RadarMath.rwrTone(1) == RadarMath.TONE_NONE && RadarMath.rwrTone(2) == RadarMath.TONE_LOCK
                && RadarMath.rwrTone(3) == RadarMath.TONE_LAUNCH && RadarMath.rwrTone(0) == RadarMath.TONE_NONE,
                "ad rwr: the sounds - one ping per new search radar (not over a lock), a lock's continuous tone, a launch's warble");
    }

    // ---------------------------------------------------------------- bug 12

    private static XpdrMath.Xpdr xp(int mode, int code, boolean key, long cardDay) {
        XpdrMath.Xpdr x = new XpdrMath.Xpdr();
        x.mode = mode;
        x.setCode(code, 0);
        x.dayKey = key;
        x.cardDay = cardDay;
        return x;
    }

    private static void panel() {
        // the report's scenario: «Ключ дня» pressed, the card set 2471; the pilot presses S within half a second - the screen's
        // copy still says 7000 and the key off
        XpdrMath.Xpdr x = xp(XpdrMath.C, 02471, true, 5);
        XpdrMath.applyPanel(x, XpdrMath.CH_MODE, XpdrMath.S, XpdrMath.VFR, false, 100);
        XpdrMath.Xpdr old = xp(XpdrMath.C, 02471, true, 5);
        XpdrMath.applyPanel(old, XpdrMath.CH_ALL, XpdrMath.S, XpdrMath.VFR, true, 100);
        check(x.mode == XpdrMath.S && x.code == 02471 && x.dayKey && x.cardDay == 5 && old.code == XpdrMath.VFR && !old.dayKey,
                "ad xpdr (bug 12): a mode change keeps the card's code and the day key (the old full packet put 7000 back and the key off)");
        XpdrMath.Xpdr tw = xp(XpdrMath.C, 04100, false, -1), nr = xp(XpdrMath.C, XpdrMath.NO_RADIO, false, -1);
        XpdrMath.applyPanel(tw, XpdrMath.CH_MODE, XpdrMath.S, XpdrMath.VFR, false, 100);
        XpdrMath.applyPanel(nr, XpdrMath.CH_KEY, XpdrMath.C, XpdrMath.VFR, false, 100);
        check(tw.code == 04100 && tw.mode == XpdrMath.S && nr.code == XpdrMath.NO_RADIO && nr.mode == XpdrMath.C,
                "ad xpdr (bug 12): a tower's code and a 7600 set from the post stay over a stale mode / key press");
        XpdrMath.Xpdr h = xp(XpdrMath.C, 02471, true, 5);
        XpdrMath.applyPanel(h, XpdrMath.CH_CODE, XpdrMath.A, 01234, true, 100);
        XpdrMath.Xpdr k = xp(XpdrMath.C, 01234, false, -1);
        XpdrMath.applyPanel(k, XpdrMath.CH_KEY, XpdrMath.OFF, 07777, true, 100);
        XpdrMath.Xpdr same = xp(XpdrMath.C, 02471, true, 5);
        XpdrMath.applyPanel(same, XpdrMath.CH_CODE, XpdrMath.C, 02471, true, 100);
        check(h.code == 01234 && !h.dayKey && h.cardDay == -1 && h.mode == XpdrMath.C && k.dayKey && k.code == 01234 && k.mode == XpdrMath.C
                && same.dayKey && same.cardDay == 5, "ad xpdr (bug 12): a code dialled by hand switches the day key off; the key alone touches only the key");
        check(XpdrMath.show(true, XpdrMath.OFF, 02471) == XpdrMath.SHOW_DIM && XpdrMath.show(true, XpdrMath.STBY, XpdrMath.EMERGENCY) == XpdrMath.SHOW_DIM
                && XpdrMath.show(true, XpdrMath.C, 02471) == XpdrMath.SHOW_AMBER && XpdrMath.show(true, XpdrMath.S, XpdrMath.EMERGENCY) == XpdrMath.SHOW_RED
                && XpdrMath.show(false, XpdrMath.C, 02471) == XpdrMath.SHOW_DARK,
                "ad xpdr: the cockpits' code window - OFF / STBY dimmed (not sent), sent amber, 7700 sent red, no power dark");
    }

    // ---------------------------------------------------------------- bug 13

    private static ZoneMath.Zone zone(String name, int x0) {
        ZoneMath.Zone z = new ZoneMath.Zone();
        z.name = name;
        z.shape = ZoneMath.RECT;
        z.x0 = x0;
        z.x1 = x0 + 99;
        z.z0 = 0;
        z.z1 = 99;
        return z;
    }

    private static void undo() {
        ZoneEditMath.History h = new ZoneEditMath.History();
        h.select(0);
        h.push(0, zone("A", 0));
        h.push(0, zone("A", 10));
        h.select(1);                                                // the report: edit A, choose B ...
        ZoneEditMath.History.Entry e = h.undoEntry();              // ... «Отменить» - nothing of A's comes into B's fields
        h.select(1);
        h.push(1, zone("B", 500));
        ZoneEditMath.History.Entry eb = h.undoEntry();
        check(e == null && eb != null && eb.zone == 1 && "B".equals(eb.before.name) && h.size() == 0,
                "ad zone editor (bug 13): choosing another zone clears the undo stack; an entry carries its zone's index");
        h.select(2);
        h.push(2, zone("C", 0));
        h.moveTo(-1);                                               // a paste over C: a new zone now
        h.push(-1, zone("C-2", 300));
        ZoneEditMath.History.Entry p1 = h.undoEntry(), p2 = h.undoEntry();
        check(p1 != null && p1.zone == -1 && p2 != null && p2.zone == 2 && "C".equals(p2.before.name) && h.zone() == 2,
                "ad zone editor (bug 13): a paste's undo goes back to the zone it was pasted over (its index), not the new one");
        ZoneEditMath.History n = new ZoneEditMath.History();
        n.select(-1);
        n.push(-1, zone("N", 0));
        n.saved(4);
        ZoneEditMath.History.Entry ns = n.undoEntry();
        n.push(zone("old", 0));                                     // the old one-argument push: the zone being edited
        ZoneMath.Zone u = n.undo();
        check(ns != null && ns.zone == 4 && n.zone() == 4 && u != null && "old".equals(u.name),
                "ad zone editor (bug 13): a new zone's entries belong to its index once saved; the old push / undo still work");
    }

    // ---------------------------------------------------------------- bug 14

    private static ZoneMath.Zone kpZone(int type, int kx, int x0, int z0, int x1, int z1) {
        ZoneMath.Zone z = new ZoneMath.Zone();
        z.type = type;
        z.shape = ZoneMath.RECT;
        z.x0 = x0;
        z.z0 = z0;
        z.x1 = x1;
        z.z1 = z1;
        z.kx = kx;
        z.ky = 64;
        z.kz = 0;
        z.name = type + "@" + kx;
        return z;
    }

    private static void corridors() {
        // Alice's post at x 0: a corridor running east (course 090) across x -500..500; Bob's post at x 900: a forbidden zone
        // over x 100..200 inside Alice's corridor; Alice's own forbidden zone over x -200..-100
        ZoneMath.Zone cor = kpZone(ZoneMath.CORRIDOR, 0, -500, -20, 500, 20);
        cor.course = 90;
        ZoneMath.Zone bob = kpZone(ZoneMath.FORBIDDEN, 900, 100, -50, 200, 50), alice = kpZone(ZoneMath.FORBIDDEN, 0, -200, -50, -100, 50);
        ZoneMath.Zone bobR = kpZone(ZoneMath.RESTRICTED, 0, 300, -50, 400, 50);
        List<ZoneMath.Zone> all = new ArrayList<ZoneMath.Zone>();
        all.add(cor);
        all.add(bob);
        all.add(alice);
        all.add(bobR);
        // the server: each post its own list
        List<ZoneMath.Zone> bobs = new ArrayList<ZoneMath.Zone>();
        bobs.add(bob);
        List<ZoneMath.Zone> alices = new ArrayList<ZoneMath.Zone>();
        alices.add(cor);
        alices.add(alice);
        boolean serverBob = ZoneMath.violated(bobs, 150, 64, 0, 90, true) == bob, serverAlice = ZoneMath.violated(alices, -150, 64, 0, 90, true) == null;
        check(serverBob && serverAlice && !ZoneMath.opened(all, bob, 150, 64, 0, 90, true) && ZoneMath.opened(all, alice, -150, 64, 0, 90, true),
                "ad zones (bug 14): Alice's corridor quiets her own zone, not Bob's - as each post judges (Bob's sees a violator)");
        check(!ZoneMath.opened(all, alice, -150, 64, 0, 180, true) && !ZoneMath.opened(all, bobR, 350, 64, 0, 90, true)
                && !ZoneMath.opened(all, cor, 0, 64, 0, 90, true),
                "ad zones (bug 14): off the corridor's course nothing is opened; a restricted zone (the post warns in it) and a corridor never");
        ZoneMath.Zone apt = kpZone(ZoneMath.AERODROME, 900, 140, -10, 160, 10);
        all.add(apt);
        bobs.add(apt);
        check(ZoneMath.opened(all, bob, 150, 64, 0, 0, true) == (ZoneMath.violated(bobs, 150, 64, 0, 0, true) == null)
                && ZoneMath.opened(all, bob, 150, 64, 0, 0, true) && !ZoneMath.opened(all, alice, -150, 64, 0, 0, true),
                "ad zones (bug 14): a post's own aerodrome zone clears its forbidden zone on the pilot's side as on the post's");
    }

    // ---------------------------------------------------------------- the RWR unit

    private static void unit() {
        boolean ok = true;
        StringBuilder why = new StringBuilder();
        for (VehicleType t : new VehicleType[] {VehicleType.JET, VehicleType.TILT}) {
            CockpitLayout l = CockpitLayout.of(t);
            CockpitLayout.Screen u = l.rwrUnitFace(), m = l.screens.isEmpty() ? null : l.screens.get(0);
            if (u == null || m == null) {
                ok = false;
                why.append(' ').append(t.key).append(" none");
                continue;
            }
            double topY = m.c[1] + m.up[1] * m.h / 2, bottomY = u.c[1] - u.up[1] * u.h / 2;
            double side = 0, left = 0;
            for (int i = 0; i < 3; i++) {
                side += (u.c[i] - m.c[i]) * m.right[i];
            }
            left = side - u.w / 2;
            float[] eye = CockpitLayout.eye(t);
            double facing = 0;
            for (int i = 0; i < 3; i++) {
                facing += u.n[i] * (eye[i] - u.c[i]);
            }
            boolean good = bottomY > topY + 0.015 && side < 0 && left >= -m.w / 2 - 1e-4 && facing > 0 && u.h >= CockpitLayout.RWR_H_MIN - 1e-6
                    && u.h <= CockpitLayout.RWR_H_MAX + 1e-6;
            ok &= good;
            if (!good) {
                why.append(' ').append(t.key).append(" bottom ").append(bottomY).append(" top ").append(topY).append(" side ").append(side);
            }
        }
        // the measured shield tops (OBJ): the jet's 0.821, the tiltrotor's 0.783 - the unit stands on them, not in them
        CockpitLayout.Screen j = CockpitLayout.of(VehicleType.JET).rwrUnitFace(), tl = CockpitLayout.of(VehicleType.TILT).rwrUnitFace();
        ok &= j != null && j.c[1] - j.up[1] * j.h / 2 > 0.821 && tl != null && tl.c[1] - tl.up[1] * tl.h / 2 > 0.783;
        check(ok && CockpitLayout.rwrUnit(VehicleType.JET) && CockpitLayout.rwrUnit(VehicleType.TILT) && !CockpitLayout.rwrUnit(VehicleType.PLANE)
                && !CockpitLayout.rwrUnit(VehicleType.HELI) && !CockpitLayout.rwrUnit(VehicleType.LINER),
                "ad rwr: the jet's / tiltrotor's unit on the glare shield - over the main screen's left end, above the shield, facing the pilot" + why);
    }

    // ---------------------------------------------------------------- the rest

    private static void rest() throws Exception {
        String cfg = com.sva.net.ServerConfigSVA.selfTest();
        check(cfg.length() == 0, "ad config sync: airdefense.* (enabled, upgrades, keepModules, dayKey, fires, zoneWarnSeconds, limits) "
                + "reach the client; an older server's packet keeps the client's own" + cfg);
        Object[] a = AirDefenseSVA.debugChatArgs(new AirDefenseSVA.LangKey("sva.iff.reason.noReply"), "UBTW", "#");
        boolean tr = a[0] instanceof net.minecraft.util.ChatComponentTranslation
                && "sva.iff.reason.noReply".equals(((net.minecraft.util.ChatComponentTranslation) a[0]).getKey());
        check(tr && "UBTW".equals(a[1]) && "#".equals(a[2]), "ad chat (7.3): a lang-key argument goes into the chat line translated, not raw");
        // audit review 2026-10-22: a player's text that starts with «#» (a post named «#chat.type.text», the operator's message)
        // stays text - it was a translation without its arguments, and the pilot's client threw on it in the chat (a crash)
        Object[] b = AirDefenseSVA.debugChatArgs("#chat.type.text", "#sva.ad.msg.lock");
        boolean hud = AirDefenseSVA.hudArg(new AirDefenseSVA.LangKey("sva.iff.reason.noReply")).equals("#sva.iff.reason.noReply")
                && AirDefenseSVA.hudArg("#chat.type.text").equals("##chat.type.text") && AirDefenseSVA.hudArg("UBTW").equals("UBTW")
                && AirDefenseSVA.hudText(AirDefenseSVA.hudArg("#chat.type.text")).equals("#chat.type.text")
                && AirDefenseSVA.hudText("#sva.iff.reason.noReply") == null && AirDefenseSVA.hudText("#").equals("#")
                && AirDefenseSVA.hudText(AirDefenseSVA.hudArg(Integer.valueOf(7))).equals("7");
        net.minecraft.util.ChatComponentTranslation atis = com.sva.block.AtcServerSVA.debugTranslated("sva.atis.head", "#chat.type.text",
                "#sva.atis.letter.3", "12:00");
        Object[] aa = atis.getFormatArgs();
        boolean atc = aa[0] instanceof String && aa[1] instanceof net.minecraft.util.ChatComponentTranslation && aa[2] instanceof String
                && com.sva.block.AtcServerSVA.keyArg("#sva.cpdlc.rep.2") && !com.sva.block.AtcServerSVA.keyArg("#sva.ad.msg.lock");
        // the pilot's client's way: the chat packet's JSON read back and its text built (it threw: «Error parsing: chat.type.text»)
        String crash = "";
        for (net.minecraft.util.IChatComponent c : new net.minecraft.util.IChatComponent[] {
                new net.minecraft.util.ChatComponentTranslation("sva.ad.msg.forbidden", AirDefenseSVA.debugChatArgs("#chat.type.text", "#chat.type.text", "090")),
                new net.minecraft.util.ChatComponentTranslation("chat.type.text", AirDefenseSVA.debugChatArgs("#chat.type.text", "hi")),
                com.sva.block.AtcServerSVA.debugTranslated("chat.type.text", "#chat.type.text", "hi"), atis}) {
            try {
                net.minecraft.util.IChatComponent.Serializer.func_150699_a(net.minecraft.util.IChatComponent.Serializer.func_150696_a(c)).getUnformattedText();
            } catch (RuntimeException e) {
                crash = " - " + e;
            }
        }
        check(b[0] instanceof String && b[1] instanceof String && hud && atc && crash.length() == 0,
                "ad chat (review): a player's «#...» text stays text in the chat and on the HUD (a nested translation without arguments crashed the "
                        + "client's chat) - only an explicit key is translated; the ATIS / CPDLC only their own letter / report keys" + crash);
        LinerControls.Ctl x = LinerControls.byId("xpdr");
        check(x != null && x == LinerControls.XPDR && x.panel == LinerControls.PED && x.kind == LinerControls.INFO
                && LinerControls.get(LinerControls.count() - 1) == x && LinerControls.hit(LinerControls.PED, 520, 1352) == x
                && LinerControls.hit(LinerControls.PED, 660, 1356) == x,
                "ad liner: the pedestal's transponder is a control (its window and knob open the panel), last in the table");
        check(ConfigSVA.rwrCockpit, "ad config: vehicles.rwrCockpit on by default");
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        String[] keys = {"sva.ad.rwr.letter.4", "sva.ad.rwr.letter.5", "sva.ad.rwr.noModule", "sva.ad.rwr.lamp.search", "sva.ad.rwr.lamp.lock",
                "sva.ad.rwr.lamp.launch", "sva.ad.rwr.nd.search", "sva.ad.rwr.nd.lock", "sva.ad.rwr.nd.launch", "sva.ad.xpdr.line", "sva.ad.ecam.lock",
                "sva.ad.ecam.launch", "sva.liner.ctl.xpdr", "sva.liner.gui.hint.xpdr", "sva.liner.du.ident", "sva.liner.du.dayKey",
                "sva.liner.du.xpdrAlt", "sva.iff.msg.call", "sva.iff.msg.callNoHandoff"};
        StringBuilder miss = new StringBuilder();
        for (String k : keys) {
            if (!ru.contains(k) || !en.contains(k)) {
                miss.append(' ').append(k);
            }
        }
        check(miss.length() == 0, "ad client: the new lang keys in ru_RU and en_US" + miss);
    }
}
