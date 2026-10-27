package com.sva.debug;

import java.util.Set;

import com.sva.ConfigSVA;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.XpdrMath;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.CockpitDials;
import com.sva.vehicle.CockpitLayout;
import com.sva.vehicle.TuningMath;
import com.sva.vehicle.VehicleFn;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Code-only wave self-test, the user's decision 3 (plan-next §10: instead of «техника без ответчика» - a transponder on
 * every vehicle, the ground ones too): pure logic and data, the dedicated server runs it:
 * <ul>
 * <li>who has one: every type with airdefense.allTransponders (the default; fitted(t) follows the server's value), only
 * what flies without it; the crawler's trailers are parts of it, not vehicles - nothing of their own;</li>
 * <li>where it shows: a line on the main screen / HUD (screenLine) for every type but the light plane, the helicopter and
 * the liner (their panels) - and every such cockpit has a main screen for it;</li>
 * <li>the radar rules stay: the buggy, the crawler and the drill vehicle are never seen (no boarding warning there -
 * seenByRadars), a tower hears a ground vehicle's transponder only while a radar could see it (towerHears);</li>
 * <li>the IDENT key and the driver's right on every vehicle; a ground vehicle keeps its transponder in its NBT; the RWR /
 * radar-absorbing coat stay the aircraft's (a radar never sees a ground vehicle); the lang.</li>
 * </ul>
 */
final class SelfTestAdXpdrAllSVA {

    private SelfTestAdXpdrAllSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws Exception {
        fitted();
        screens();
        radarRules();
        keysAndState();
        lang();
    }

    // ---------------------------------------------------------------- who has one

    private static void fitted() {
        int all = 0, flying = 0, server = 0;
        StringBuilder ground = new StringBuilder();
        for (VehicleType t : VehicleType.values()) {
            all += XpdrMath.fitted(t, true) ? 1 : 0;
            flying += XpdrMath.fitted(t, false) ? 1 : 0;
            server += XpdrMath.fitted(t) == XpdrMath.fitted(t, ConfigSVA.adAllTransponders) ? 1 : 0;
            if (!t.movement.flies && XpdrMath.fitted(t)) {
                ground.append(' ').append(t.key);
            }
        }
        check(all == VehicleType.values().length && flying == 7 && server == VehicleType.values().length && ConfigSVA.adAllTransponders
                && " buggy hover truck drill".equals(ground.toString()),
                "ad xpdr all (decision 3): by default a transponder on all " + VehicleType.values().length + " types - the ground ones too:" + ground
                        + "; without allTransponders only the 7 that fly");
        boolean was = ConfigSVA.adAllTransponders;
        ConfigSVA.adAllTransponders = false;                         // the dedicated server: fitted(t) reads its own config
        boolean off = !XpdrMath.fitted(VehicleType.BUGGY) && !XpdrMath.fitted(VehicleType.HOVER) && XpdrMath.fitted(VehicleType.JET)
                && !VehicleFn.IDENT.appliesTo(VehicleType.DRILL);
        ConfigSVA.adAllTransponders = was;
        check(off && XpdrMath.fitted(VehicleType.DRILL) && VehicleFn.IDENT.appliesTo(VehicleType.DRILL),
                "ad xpdr all: airdefense.allTransponders=false - the buggy / hoverbike / drill have none again (no IDENT key), the jet keeps its");
    }

    // ---------------------------------------------------------------- where it shows

    private static void screens() {
        StringBuilder line = new StringBuilder(), noMain = new StringBuilder();
        for (VehicleType t : VehicleType.values()) {
            if (!XpdrMath.screenLine(t)) {
                continue;
            }
            line.append(' ').append(t.key);
            CockpitLayout l = CockpitLayout.of(t);
            boolean main = false;
            for (boolean hsi : new boolean[] {false, true}) {
                int[] roles = CockpitDials.screenRoles(l.screens, t.movement.flies, t.hasDrill, hsi && t == VehicleType.JET);
                boolean m = false;
                for (int r : roles) {
                    m |= r == CockpitDials.SCREEN_MAIN;
                }
                main = hsi ? main && m : m;
            }
            if (!main) {
                noMain.append(' ').append(t.key);
            }
        }
        check(" buggy hover truck drill jet tilt shuttle ship".equals(line.toString()) && !XpdrMath.screenLine(VehicleType.PLANE)
                && !XpdrMath.screenLine(VehicleType.HELI) && !XpdrMath.screenLine(VehicleType.LINER) && !XpdrMath.screenLine(null),
                "ad xpdr all (decision 3): the «ОТВ 7000 C» line on the main screen / HUD -" + line + "; the light plane, the helicopter and the liner have panels");
        check(noMain.length() == 0, "ad xpdr all: every cockpit with the line has a main screen for it (the hoverbike's one, the drill's beside its ДОБЫЧА console)"
                + (noMain.length() == 0 ? "" : " - none on" + noMain));
    }

    // ---------------------------------------------------------------- the radar rules stay

    private static void radarRules() {
        StringBuilder seen = new StringBuilder();
        for (VehicleType t : VehicleType.values()) {
            if (!t.movement.flies && XpdrMath.seenByRadars(t)) {
                seen.append(' ').append(t.key);
            }
        }
        boolean aircraft = true;
        for (VehicleType t : VehicleType.values()) {
            if (t.movement.flies) {
                aircraft &= XpdrMath.seenByRadars(t) && XpdrMath.towerHears(t, true, false) && XpdrMath.towerHears(t, false, false);
            }
        }
        check(" hover".equals(seen.toString()) && aircraft && RadarMath.rcs(VehicleType.BUGGY) == 0 && RadarMath.rcs(VehicleType.TRUCK) == 0
                && RadarMath.rcs(VehicleType.DRILL) == 0 && RadarMath.visible(RadarMath.rcs(VehicleType.HOVER), 3.9, false) == false
                && RadarMath.visible(RadarMath.rcs(VehicleType.HOVER), 4, false),
                "ad xpdr all (radar rules unchanged): of the ground vehicles a radar may see only the hoverbike, 4+ blocks over the ground - "
                        + "the boarding warning «Нет карты кодов» only there and in aircraft");
        check(!XpdrMath.towerHears(VehicleType.BUGGY, true, false) && XpdrMath.towerHears(VehicleType.HOVER, true, true)
                && !XpdrMath.towerHears(VehicleType.HOVER, true, false) && !XpdrMath.towerHears(VehicleType.HOVER, false, true)
                && !XpdrMath.towerHears(VehicleType.DRILL, true, false) && XpdrMath.towerHears(VehicleType.PLANE, true, false)
                && !XpdrMath.towerHears(null, true, true),
                "ad xpdr all: a tower hears an aircraft's transponder always (taxiing too), a ground vehicle's only while a radar could see it "
                        + "(no truck on the apron, no drill under the ground on its screen)");
        check(TuningMath.fits(VehicleType.JET, TuningMath.RWR) && !TuningMath.fits(VehicleType.BUGGY, TuningMath.RWR) && !TuningMath.fits(VehicleType.HOVER, TuningMath.RWR)
                && !TuningMath.fits(VehicleType.TRUCK, TuningMath.RAM_COAT),
                "ad xpdr all: the RWR and the radar-absorbing coat stay the aircraft's (a transponder is no tuning module; radars never paint a ground vehicle)");
    }

    // ---------------------------------------------------------------- the key, the driver, the NBT

    private static void keysAndState() {
        boolean ident = true;
        for (VehicleType t : VehicleType.values()) {
            ident &= VehicleFn.IDENT.appliesTo(t) && VehicleFn.IDENT.action(t) == EntityVehicleSVA.A_IDENT;
        }
        check(ident && VehicleFn.IDENT.driverOnly && EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_IDENT, false, true)
                && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_IDENT, true, false),
                "ad xpdr all: the IDENT key on every vehicle (the buggy, the drill...) - the driver's, not the owner's from outside");
        boolean nbt = true;
        for (VehicleType t : new VehicleType[] {VehicleType.BUGGY, VehicleType.HOVER, VehicleType.TRUCK, VehicleType.DRILL}) {
            VehicleState st = new VehicleState(t);
            boolean fresh = st.xpdr.mode == XpdrMath.C && st.xpdr.code == XpdrMath.VFR && st.xpdr.dayKey;
            st.xpdr.mode = XpdrMath.S;
            st.xpdr.dayKey = false;
            st.xpdr.setCode(02471, 100);
            NBTTagCompound tag = new NBTTagCompound();
            st.writeNBT(tag);
            VehicleState back = new VehicleState(t);
            back.readNBT(tag);
            nbt &= fresh && tag.hasKey(VehicleState.K_XPDR) && back.xpdr.mode == XpdrMath.S && back.xpdr.code == 02471 && !back.xpdr.dayKey;
        }
        check(nbt, "ad xpdr all: a ground vehicle's transponder starts at C 7000 with the day key on and keeps its mode / code in its NBT (the item too)");
    }

    // ---------------------------------------------------------------- the lang

    private static void lang() throws Exception {
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        java.util.Map<String, String> mr = LangKeysSVA.map(LangKeysSVA.RU), me = LangKeysSVA.map(LangKeysSVA.EN);
        String r = mr.get("sva.vmenu.xpdr.tipGround"), e = me.get("sva.vmenu.xpdr.tipGround");
        check(ru.contains("sva.vmenu.xpdr.tipGround") && en.contains("sva.vmenu.xpdr.tipGround") && r != null && r.contains("4") && e != null && e.contains("4"),
                "ad xpdr all lang: the ground vehicles' «Ответчик» tip (when radars see them) in ru and en");
    }
}
