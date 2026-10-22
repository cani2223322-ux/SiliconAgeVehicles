package com.sva.debug;

import java.util.ArrayList;
import java.util.List;

import com.sc.init.ModItems;
import com.sc.machine.UpgradeType;
import com.sva.ConfigSVA;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.CoverageMath;
import com.sva.airdefense.CoverageSVA;
import com.sva.airdefense.IffMath;
import com.sva.airdefense.NoRadioMath;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneMath;
import com.sva.airport.Cpdlc;
import com.sva.block.AirportDataSVA;
import com.sva.block.AtcServerSVA;
import com.sva.block.BlockAirDefenseSVA;
import com.sva.block.BlockTowerSVA;
import com.sva.block.IffServerSVA;
import com.sva.block.ItemBlockAirDefenseSVA;
import com.sva.block.TileEntityAirDefenseSVA;
import com.sva.block.TileEntityCommandPostSVA;
import com.sva.block.TileEntityLauncherSVA;
import com.sva.block.TileEntityRadarSVA;
import com.sva.block.TileEntityTowerSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemAmmoSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.TuningMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Wave A world test (plan-next §9 A: the air defence finished), run by WorldTestSVA alongside its other phases from tick START
 * in its own area (x spawn + 6000, z spawn - 6000, a floor at Y 150), owner «TestADA»: a command post, the three radars, an AA
 * gun («по всем чужим») and a missile system («по нарушителям»), a control tower (no runways: its «аэродром» zone r 96 round
 * it) of the same owner; aircraft held in the air:
 * <ul>
 * <li>«codes» (audit 2026-10-22, bugs 5 / 9 - inverted): a stranger's liner L1 entering the airport's zone with mode C gets NO
 * code (nor does a stranger's plane P2); once its owner is on the tower's «коды: доверенные» list it gets «КОД ОТВЕТЧИКА
 * 41xx» by CPDLC, its WILCO sets it; the tower owner's plane P4 gets its code set at once; his 7700 (E7) and his running day
 * key (DK) are never replaced; a day key replaced (DR) comes back on once it leaves the airport; a stranger P3 on the ground
 * gets no code with the automatic tower's take-off clearance, one with the controller's; in «fire» the gun leaves L1 (a
 * friend: the code its tower gave it) and fires at a stranger S squawking 7000;</li>
 * <li>«IFF»: a silent plane N near the airport (out of the gun's reach) - the tower's alarm, then the handoff (airdefense.iffHandoff
 * on for the test): the post classes it a violator, the missile system takes it; the transponder switched on - resolved.
 * Audit 2026-10-22 (bug 6): U squawks a code of the range dialled by hand (no friend; R_UNKNOWN), V the code the tower gave
 * L1 (R_MISMATCH) - both handed over and violators for the post, the aerodrome code no longer cancels it;</li>
 * <li>«7600»: three planes squawking 7600 - pending (no fire), «проверить» (no fire), «свой» (a friend), and the one left to the
 * timer - the usual rules (the gun takes it); audit 2026-10-22 (bug 8): R4 switches to 7000 for a surveillance cycle and back -
 * the same entry, its timer paused not restarted; «враждебный» - a stranger at once;</li>
 * <li>«modules»: a launcher with overclockers broken - the item keeps them, placed again - they are back; a creative break -
 * loose; the missile system's model reload follows its overclockers;</li>
 * <li>«RCS»: a plane 380 blocks off unseen, with a drop tank (stores) seen; the liner's gear / cargo doors;</li>
 * <li>«coverage»: a stone hill west of the surveillance radar - a dead zone behind it on the map at 20 blocks, gone after the
 * hill is taken away (the map recomputed);</li>
 * <li>«prediction»: a plane flown at 2 b/t towards a forbidden zone - the entry prediction (2 s horizon) fires before the border,
 * its seconds right.</li>
 * </ul>
 */
final class WorldTestAdWaveASVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000ada0", OWNER_NAME = "TestADA";
    private static final String STRANGER = "00000000-5bad-4e57-0000-00000000ada1";
    static final int START = 1950;
    private static final int Y = 150, FLY = Y + 30, MAX_TICKS = 2600;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0, tx, tz;
    boolean done;
    private TileEntityCommandPostSVA kp;
    private TileEntityRadarSVA survey, track, height;
    private TileEntityLauncherSVA gun, sam;
    private TileEntityTowerSVA tower;
    private EntityVehicleSVA l1, s, n, p2, p3, r1, r2, r3, rcsPlane, m, liner2;
    // audit 2026-10-22: the tower owner's planes (P4 a code at once, E7 7700, DK a running day key, DR a day key replaced and
    // restored), U / V the IFF's unknown / mismatch codes, R4 the 7600 pause and «враждебный»
    private EntityVehicleSVA p4, e7, dk, dr, u, vm, r4;
    private boolean trustGiven, iffCodes, uChecked, vSet, drAway, drDone, r4Off, r4Back, r4Decided, r4Done;
    private int vSetAt = -1, drAwayAt = -1;
    private NoRadioMath.Entry r4Entry;
    private boolean sForeign, sEngaged;
    private final List<double[]> hold = new ArrayList<double[]>();
    private final List<EntityVehicleSVA> held = new ArrayList<EntityVehicleSVA>();
    // saved config
    private String mode0;
    private int minWarn0, nrDec0, iffResp0;
    private boolean pvp0, unmanned0, handoff0;
    // the stages
    private boolean wilco, friendSeen, fireOn, codesDone, p3Done, iffWarned, iffHanded, iffChecked, iffResolved, nrDecided, nrChecked, nrDefaultDone,
            modulesDone, rcsDone, covA, covB, predDone;
    private int wilcoAt = -1, fireAt = -1, handedAt = -1, resolveAt = -1, nrAt = -1, covRemovedAt = -1, mStart = -1, predFiredAt = -1, entryAt = -1;
    private double predAtFire = -1;
    private float sHp0;
    private int fx, fz;

    WorldTestAdWaveASVA(WorldTestSVA t) {
        this.t = t;
    }

    private void check(boolean ok, String name) {
        t.check(ok, name);
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = kp == null ? 9 : 1;
        }
        int k = ticks - t0;
        if (phase == 1) {
            feed();
            holdAll();
            run(w, k);
            if (k > MAX_TICKS) {
                report();
                phase = 9;
            }
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private void feed() {
        for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {kp, survey, track, height, gun, sam}) {
            if (te != null && !te.isInvalid()) {
                te.fillForTest();
            }
        }
        if (tower != null && !tower.isInvalid()) {
            tower.fillForTest();
        }
    }

    private void holdAll() {
        for (int i = 0; i < held.size(); i++) {
            EntityVehicleSVA v = held.get(i);
            double[] p = hold.get(i);
            if (v != null && !v.isDead && (v != m || mStart < 0)) {
                v.setPosition(p[0], p[1], p[2]);
                v.motionX = v.motionY = v.motionZ = 0;
            }
        }
    }

    // ---------------------------------------------------------------- the run

    private void run(World w, int k) {
        long now = w.getTotalWorldTime();
        AirportDataSVA d = AirportDataSVA.get(w);
        AirportDataSVA.Tower tw = d == null || tower == null ? null : d.tower(tower.key());
        if (k == 40) {
            modules(w);
            rcsLiner();
        }
        if (k == 60) {
            coverageBuild(w);
        }
        if (k >= 70 && !covA) {
            coverageA(w, k);
        }
        if (covA && !covB && covRemovedAt >= 0 && k - covRemovedAt >= CoverageSVA.RECHECK + 10) {
            coverageB(w);
        }
        if (k == 300) {
            rcsStep1();
        }
        if (k == 460 && !rcsDone) {
            rcsStep2();
        }
        if (tw != null) {
            codes(w, d, tw, k, now);
            iff(w, tw, k);
        }
        noRadio(w, k);
        if (k >= 500) {
            prediction(w, k);
        }
        if (codesDone && p3Done && iffResolved && nrDefaultDone && modulesDone && rcsDone && covB && predDone && iffCodes && drDone && r4Done) {
            phase = 9;
        }
    }

    // ---------------------------------------------------------------- «codes»

    private void codes(World w, AirportDataSVA d, AirportDataSVA.Tower tw, int k, long now) {
        AtcServerSVA.Session ls = AtcServerSVA.INSTANCE.session(l1.getEntityId());
        if (!trustGiven && k == 200) {
            // audit 2026-10-22 (bug 5), inverted: a stranger's aircraft entering the zone gets no code on its own
            boolean asked = false;
            for (Cpdlc.Msg msg : ls == null ? new ArrayList<Cpdlc.Msg>() : ls.log.msgs) {
                asked |= msg.up && msg.type == Cpdlc.U_SQUAWK;
            }
            AtcServerSVA.Session ps = AtcServerSVA.INSTANCE.session(p2.getEntityId());
            check(ls != null && !asked && ls.sqCode < 0 && l1.state.xpdr.code == XpdrMath.VFR && ps != null && ps.sqCode < 0 && p2.state.xpdr.code == XpdrMath.VFR,
                    "ad trust world «codes» (bug 5): a stranger's liner and plane entering the airport's zone get no code (" + XpdrMath.format(l1.state.xpdr.code)
                            + ", " + XpdrMath.format(p2.state.xpdr.code) + ")");
            tw.sqTrust.add("Guest");                               // the tower's «коды: доверенные»: the liner's owner
            trustGiven = true;
        }
        if (!wilco && ls != null) {
            for (Cpdlc.Msg msg : ls.log.msgs) {
                if (msg.up && msg.type == Cpdlc.U_SQUAWK && msg.open()) {
                    int code = msg.arg;
                    boolean ok = AtcServerSVA.INSTANCE.respond(l1, msg.id, Cpdlc.R_WILCO, now);
                    check(ok && trustGiven && code >= tw.codeLo && code <= tw.codeHi && l1.state.xpdr.code == code && !l1.state.xpdr.dayKey && ls.sqCode == code,
                            "ad wave A world «codes»: the liner of an owner the tower trusts got КОД ОТВЕТЧИКА " + XpdrMath.format(code)
                                    + " by CPDLC, its WILCO set it (" + XpdrMath.format(l1.state.xpdr.code) + ")");
                    wilco = true;
                    wilcoAt = k;
                    break;
                }
            }
        }
        if (k == 400) {
            AtcServerSVA.Session ps = AtcServerSVA.INSTANCE.session(p4.getEntityId());
            int c = p4.state.xpdr.code;
            check(ps != null && ps.sqCode == c && c >= tw.codeLo && c <= tw.codeHi && (ls == null || ls.sqCode != c) && p2.state.xpdr.code == XpdrMath.VFR,
                    "ad wave A world «codes»: the tower owner's light plane entering the zone has its code set at once (" + XpdrMath.format(c) + ", the liner's "
                            + (ls == null ? "-" : XpdrMath.format(ls.sqCode)) + "); the stranger's plane still none");
            check(wilco, "ad wave A world «codes»: the liner's squawk uplink came (by tick 400)");
            // audit 2026-10-22 (bug 9): 7700 and a running day key are never replaced; a day key replaced comes back later
            AtcServerSVA.Session ds = AtcServerSVA.INSTANCE.session(dr.getEntityId());
            check(e7.state.xpdr.code == XpdrMath.EMERGENCY && dk.state.xpdr.code == 02345 && dk.state.xpdr.dayKey
                    && ds != null && dr.state.xpdr.code == ds.sqCode && !dr.state.xpdr.dayKey && ds.sqKeyWas,
                    "ad trust world «codes» (bug 9): the tower's code never replaces 7700 (" + XpdrMath.format(e7.state.xpdr.code) + ") nor a running day key ("
                            + XpdrMath.format(dk.state.xpdr.code) + "); a day key without today's card is replaced (" + XpdrMath.format(dr.state.xpdr.code)
                            + ") and remembered");
            hold.get(held.indexOf(dr))[0] = tx + 280.5;             // DR leaves the airport (zone 96 + NEAR 160), still loaded
            drAway = true;
            drAwayAt = k;
        }
        if (drAway && !drDone && k - drAwayAt >= 40) {
            check(dr.state.xpdr.dayKey, "ad trust world «codes» (bug 9): away from the airport the day key the tower's code replaced is back on");
            drDone = true;
        }
        if (!p3Done && k >= 420) {
            AtcServerSVA.Session ss = AtcServerSVA.INSTANCE.session(p3.getEntityId());
            if (ss != null) {
                int before = p3.state.xpdr.code;
                AtcServerSVA.INSTANCE.uplink(w, d, tw, ss, Cpdlc.U_TAKEOFF, 0, "", -1, now);
                int auto = p3.state.xpdr.code;
                AtcServerSVA.INSTANCE.uplink(w, d, tw, ss, Cpdlc.U_TAKEOFF, 0, "", -1, now, true);
                int c = p3.state.xpdr.code;
                check(before == XpdrMath.VFR && auto == XpdrMath.VFR && c >= tw.codeLo && c <= tw.codeHi && ss.sqTower.equals(tw.key),
                        "ad wave A world «codes» (bug 5): a stranger's departure gets no code with the automatic tower's take-off clearance ("
                                + XpdrMath.format(auto) + "), the airport's code with the controller's (" + XpdrMath.format(before) + " -> " + XpdrMath.format(c) + ")");
                p3Done = true;
            } else if (k > 600) {
                check(false, "ad wave A world «codes»: no session for the plane on the ground");
                p3Done = true;
            }
        }
        if (wilco && !vSet) {
            vm.state.xpdr.mode = XpdrMath.C;                        // bug 6: V squawks the code the tower gave L1
            vm.state.xpdr.setCode(l1.state.xpdr.code, now);
            vSet = true;
            vSetAt = k;
        }
        if (wilco && !friendSeen) {
            AirDefenseSVA.Contact c = kp.tracks().get(Integer.valueOf(l1.getEntityId()));
            if (c != null && c.cls == RoeMath.FRIEND && c.airport.equals(tw.icao) && c.code == l1.state.xpdr.code) {
                friendSeen = true;
                ConfigSVA.adMode = ConfigSVA.AD_FIRE;               // now the launchers fire
                fireOn = true;
                fireAt = k;
                sHp0 = s.state.hp;
            } else if (k - wilcoAt > 400) {
                check(false, "ad wave A world «codes»: the post never took the liner for a friend (" + (c == null ? "no track" : "class " + c.cls + ", "
                        + c.airport) + ")");
                codesDone = true;
            }
        }
        if (fireOn && !codesDone) {
            // seen over the fire window, not only at its end: the gun may finish the stranger off (its track gone) before then
            AirDefenseSVA.Contact cs = kp.tracks().get(Integer.valueOf(s.getEntityId()));
            sForeign |= cs != null && cs.cls == RoeMath.FOREIGN;
            sEngaged |= s.state.hp < sHp0 || gun.engagements().containsKey(Integer.valueOf(s.getEntityId()));
        }
        if (fireOn && !codesDone && k - fireAt >= 360) {
            check(l1.state.hp == l1.state.maxHp() && !gun.engagements().containsKey(Integer.valueOf(l1.getEntityId()))
                    && !sam.engagements().containsKey(Integer.valueOf(l1.getEntityId())),
                    "ad wave A world «codes»: in «fire» the air defence leaves the liner with its airport's code alone (hull " + Math.round(l1.state.hp) + ")");
            check(sForeign && sEngaged,
                    "ad wave A world «codes»: a stranger squawking 7000 there is engaged by the gun «по всем чужим» (hull " + Math.round(sHp0) + " -> "
                            + Math.round(s.state.hp) + ")");
            codesDone = true;
        }
    }

    // ---------------------------------------------------------------- «IFF»

    private void iff(World w, AirportDataSVA.Tower tw, int k) {
        IffMath.Track tr = IffServerSVA.INSTANCE.track(tw.key, n.getEntityId());
        if (!iffWarned && tr != null && tr.state == IffMath.WARNED) {
            iffWarned = true;
            AirDefenseSVA.Contact c = kp.tracks().get(Integer.valueOf(n.getEntityId()));
            check(IffServerSVA.INSTANCE.alarm(tw.key) && tr.reason == IffMath.R_NO_REPLY && (c == null || c.cls == RoeMath.FOREIGN || c.cls == RoeMath.UNKNOWN)
                    && !sam.engagements().containsKey(Integer.valueOf(n.getEntityId())),
                    "ad wave A world «IFF»: a silent aircraft near the airport - the tower's alarm (no reply); not yet a violator for the post");
        }
        if (!iffHanded && tr != null && tr.state == IffMath.HANDED) {
            iffHanded = true;
            handedAt = k;
        }
        if (iffHanded && !iffChecked && k - handedAt >= 40) {
            AirDefenseSVA.Contact c = kp.tracks().get(Integer.valueOf(n.getEntityId()));
            boolean logged = false;
            for (String[] l : kp.logLines()) {
                logged |= l.length > 1 && l[1].equals("sva.ad.log.iffHandoff");
            }
            check(AirDefenseSVA.INSTANCE.handedOff(w, AirDefenseSVA.vehKey(n), w.getTotalWorldTime()) != null && c != null && c.cls == RoeMath.VIOLATOR && logged
                    && sam.engagements().containsKey(Integer.valueOf(n.getEntityId())),
                    "ad wave A world «IFF»: no answer - handed to the air defence: the post's violator (logged), the missile system «по нарушителям» takes it");
            iffChecked = true;
            n.state.xpdr.mode = XpdrMath.C;                         // the pilot answers: the transponder on
            resolveAt = k;
        }
        if (iffChecked && !iffResolved && k - resolveAt >= 60) {
            IffMath.Track t2 = IffServerSVA.INSTANCE.track(tw.key, n.getEntityId());
            check(t2 == null && AirDefenseSVA.INSTANCE.handedOff(w, AirDefenseSVA.vehKey(n), w.getTotalWorldTime()) == null,
                    "ad wave A world «IFF»: the transponder switched on - resolved, the handoff taken back");
            iffResolved = true;
        }
        // audit 2026-10-22 (bug 6): a code of the range dialled by hand is no friend; handed over, U and V are violators
        AirDefenseSVA.Contact cu = kp.tracks().get(Integer.valueOf(u.getEntityId())), cv = kp.tracks().get(Integer.valueOf(vm.getEntityId()));
        IffMath.Track tu = IffServerSVA.INSTANCE.track(tw.key, u.getEntityId()), tv = IffServerSVA.INSTANCE.track(tw.key, vm.getEntityId());
        if (!uChecked && cu != null && cu.askedAt >= 0 && cu.replied && cu.code == 04105 && (tu == null || tu.state != IffMath.HANDED)) {
            check(cu.cls == RoeMath.FOREIGN && cu.airport.length() == 0,
                    "ad trust world «IFF» (bug 6): a plane squawking a code of the airport's range the tower never gave it - no friend (class " + cu.cls + ")");
            uChecked = true;
        }
        if (!iffCodes && tu != null && tv != null && tu.state == IffMath.HANDED && tv.state == IffMath.HANDED && cu != null && cv != null
                && (cu.cls == RoeMath.VIOLATOR || k > 1500) && (cv.cls == RoeMath.VIOLATOR || k > 1500)) {
            check(tu.reason == IffMath.R_UNKNOWN && tv.reason == IffMath.R_MISMATCH && cu.cls == RoeMath.VIOLATOR && cv.cls == RoeMath.VIOLATOR
                    && cv.airport.length() == 0 && AirDefenseSVA.INSTANCE.handedOff(w, AirDefenseSVA.vehKey(vm), w.getTotalWorldTime()) != null,
                    "ad trust world «IFF» (bug 6): handed over with an unknown code (U) and with the code given to another (V) - both violators for the post, "
                            + "the aerodrome code does not cancel it (" + cu.cls + ", " + cv.cls + ")");
            iffCodes = true;
        }
        if (!iffCodes && k > 1700) {
            check(false, "ad trust world «IFF» (bug 6): U / V were never handed over (" + (tu == null ? "-" : IffMath.STATE_KEYS[tu.state]) + ", "
                    + (tv == null ? "-" : IffMath.STATE_KEYS[tv.state]) + ", V set at " + vSetAt + ")");
            iffCodes = true;
        }
        if (!iffResolved && k > 1600) {
            check(false, "ad wave A world «IFF»: the IFF chain did not finish (warned " + iffWarned + ", handed " + iffHanded + ", state "
                    + (tr == null ? "-" : IffMath.STATE_KEYS[tr.state]) + ")");
            iffResolved = true;
        }
    }

    // ---------------------------------------------------------------- «7600»

    private void noRadio(World w, int k) {
        if (!nrDecided) {
            NoRadioMath.Entry e1 = kp.noRadioEntry(r1.getEntityId()), e3 = kp.noRadioEntry(r3.getEntityId()), e2 = kp.noRadioEntry(r2.getEntityId());
            if (e1 != null && e3 != null && e2 != null && kp.noRadioEntry(r4.getEntityId()) != null) {
                AirDefenseSVA.Contact c2 = kp.tracks().get(Integer.valueOf(r2.getEntityId()));
                check(c2 != null && c2.cls == RoeMath.NO_RADIO && c2.nr == NoRadioMath.S_PENDING && c2.nrLeft > 0,
                        "ad wave A world «7600»: a 7600 track waits for the post's decision - NO RADIO, the timer (" + (c2 == null ? -1 : c2.nrLeft) + " s)");
                kp.debugNoRadio(r1.getEntityId(), false);
                kp.debugNoRadio(r3.getEntityId(), true);
                nrDecided = true;
                nrAt = k;
            } else if (k > 900) {
                check(false, "ad wave A world «7600»: the post never listed the 7600 tracks");
                nrDecided = nrChecked = nrDefaultDone = true;
                r4Off = r4Back = r4Decided = r4Done = true;
            }
        }
        if (nrDecided && !nrChecked && k - nrAt >= 30) {
            AirDefenseSVA.Contact c1 = kp.tracks().get(Integer.valueOf(r1.getEntityId())), c3 = kp.tracks().get(Integer.valueOf(r3.getEntityId()));
            check(c1 != null && c1.cls == RoeMath.NO_RADIO && c1.nr == NoRadioMath.S_CHECK && c3 != null && c3.cls == RoeMath.FRIEND
                    && !gun.engagements().containsKey(Integer.valueOf(r1.getEntityId())) && !gun.engagements().containsKey(Integer.valueOf(r2.getEntityId()))
                    && !gun.engagements().containsKey(Integer.valueOf(r3.getEntityId())),
                    "ad wave A world «7600»: «проверить» - NO RADIO, «свой» - a friend, the pending one - none of them engaged");
            nrChecked = true;
        }
        // audit 2026-10-22 (bug 8): R4 off 7600 for a surveillance cycle and back - the same entry, paused not restarted; then
        // «враждебный»: no grace, a stranger at once
        if (nrDecided && !r4Off) {
            r4Entry = kp.noRadioEntry(r4.getEntityId());
            r4.state.xpdr.setCode(XpdrMath.VFR, w.getTotalWorldTime());
            r4Off = true;
        }
        if (r4Off && !r4Back && k - nrAt >= 160) {
            NoRadioMath.Entry e = kp.noRadioEntry(r4.getEntityId());
            check(e != null && e == r4Entry && e.since < 0 && e.used > 0,
                    "ad trust world «7600» (bug 8): switched to 7000 - the episode paused, the entry kept (" + (e == null ? "none" : e.used + " ticks") + ")");
            r4.state.xpdr.setCode(XpdrMath.NO_RADIO, w.getTotalWorldTime());
            r4Back = true;
        }
        if (r4Back && !r4Decided && k - nrAt >= 300) {
            NoRadioMath.Entry e = kp.noRadioEntry(r4.getEntityId());
            AirDefenseSVA.Contact c4 = kp.tracks().get(Integer.valueOf(r4.getEntityId()));
            long now4 = w.getTotalWorldTime(), el = e == null ? -1 : NoRadioMath.elapsed(e, now4);
            check(e != null && e == r4Entry && e.since >= 0 && e.used > 0 && el > now4 - e.since && c4 != null && c4.nr >= 0,
                    "ad trust world «7600» (bug 8): back on 7600 - the same timer goes on (" + el + " ticks of 7600 in all, "
                            + (e == null ? 0 : now4 - e.since) + " this time), not a fresh one");
            kp.debugNoRadio(r4.getEntityId(), NoRadioMath.D_HOSTILE);
            r4Decided = true;
        }
        if (r4Decided && !r4Done && k - nrAt >= 340) {
            AirDefenseSVA.Contact c4 = kp.tracks().get(Integer.valueOf(r4.getEntityId()));
            check(c4 != null && c4.cls == RoeMath.FOREIGN && c4.nr == NoRadioMath.S_HOSTILE && RoeMath.roeWants(RoeMath.FOREIGN_ALL, c4.cls, false, false),
                    "ad trust world «7600» (bug 8): «враждебный» - a stranger at once, the rules of engagement want it");
            r4Done = true;
        }
        if (nrChecked && !nrDefaultDone && k - nrAt >= ConfigSVA.adNoRadioDecisionSeconds * 20 + 60) {
            AirDefenseSVA.Contact c2 = kp.tracks().get(Integer.valueOf(r2.getEntityId()));
            boolean logged = false;
            for (String[] l : kp.logLines()) {
                logged |= l.length > 1 && l[1].equals("sva.ad.log.noRadioDefault");
            }
            check(c2 != null && c2.cls == RoeMath.FOREIGN && c2.nr == NoRadioMath.S_DEFAULT && logged && RoeMath.roeWants(RoeMath.FOREIGN_ALL, c2.cls, false, false),
                    "ad wave A world «7600»: no decision in time - the usual rules: a stranger again (logged), the gun's «по всем чужим» wants it");
            nrDefaultDone = true;
        }
    }

    // ---------------------------------------------------------------- «modules» (and the model's reload)

    private void modules(World w) {
        net.minecraftforge.common.util.FakePlayer fp = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft((net.minecraft.world.WorldServer) w);
        int lx = x0 - 30, lz = z0 + 20;
        fp.setPosition(lx + 0.5, Y + 2, lz + 6.5);
        fp.rotationYaw = 180F;                                     // facing north
        BlockAirDefenseSVA.build(w, lx, Y + 1, lz, (BlockAirDefenseSVA) ModContentSVA.launcher, TileEntityLauncherSVA.GUN, 2);
        TileEntity te = w.getTileEntity(lx, Y + 1, lz);
        if (!(te instanceof TileEntityLauncherSVA)) {
            check(false, "ad wave A world «modules»: no launcher placed");
            modulesDone = true;
            return;
        }
        ItemStack oc = ModItems.upgrade.stackOf(UpgradeType.OVERCLOCKER);
        oc.stackSize = 2;
        ((TileEntityLauncherSVA) te).debugUpgrade(1, oc);
        drops(w, lx, lz, null);
        w.func_147480_a(lx, Y + 1, lz, true);
        Item li = Item.getItemFromBlock(ModContentSVA.launcher);
        ItemStack dropped = null;
        int loose = 0;
        for (EntityItem e : items(w, lx, lz)) {
            ItemStack is = e.getEntityItem();
            if (is.getItem() == li) {
                dropped = is.copy();
            } else if (is.getItem() == ModItems.upgrade) {
                loose += is.stackSize;
            }
        }
        drops(w, lx, lz, null);
        ItemStack[] in = dropped == null ? new ItemStack[4] : TileEntityAirDefenseSVA.readModules(dropped.getTagCompound());
        check(dropped != null && loose == 0 && in[1] != null && in[1].stackSize == 2,
                "ad wave A world «modules»: the broken launcher's item keeps its 2 overclockers inside, none loose (" + loose + ")");
        boolean placed = dropped != null
                && ((ItemBlockAirDefenseSVA) dropped.getItem()).placeBlockAt(dropped, fp, w, lx, Y + 1, lz, 1, 0.5F, 1F, 0.5F, dropped.getItemDamage());
        TileEntity te2 = w.getTileEntity(lx, Y + 1, lz);
        ItemStack back = te2 instanceof TileEntityLauncherSVA ? ((TileEntityLauncherSVA) te2).upgrades.getStackInSlot(1) : null;
        check(placed && back != null && back.stackSize == 2 && ((TileEntityLauncherSVA) te2).fireInterval() < ((TileEntityLauncherSVA) te2).baseFireInterval(),
                "ad wave A world «modules»: placed again - the overclockers are back in their slot and work (burst every "
                        + (te2 instanceof TileEntityLauncherSVA ? ((TileEntityLauncherSVA) te2).fireInterval() : -1) + " ticks)");
        // a creative break (no item): the modules drop loose as before
        if (te2 instanceof TileEntityLauncherSVA) {
            ((TileEntityLauncherSVA) te2).noDrop = true;
            w.func_147480_a(lx, Y + 1, lz, true);
            int mods = drops(w, lx, lz, ModItems.upgrade), items = drops(w, lx, lz, li);
            check(mods == 2 && items == 0, "ad wave A world «modules»: a creative break - no item, the modules loose (" + mods + ")");
        }
        drops(w, lx, lz, null);
        // the missile system's model: the tubes' reload follows its overclockers (synced in the description packet)
        ItemStack oc2 = ModItems.upgrade.stackOf(UpgradeType.OVERCLOCKER);
        oc2.stackSize = 2;
        sam.debugUpgrade(3, oc2);
        int rl = sam.syncedReload();
        sam.debugUpgrade(3, null);
        int rl0 = sam.syncedReload();
        check(rl == 29 && rl0 == com.sva.airdefense.BallisticsMath.MISSILE_RELOAD,
                "ad wave A world «modules»: the model's tube reload synced - " + rl + " ticks with 2 overclockers, " + rl0 + " without");
        modulesDone = true;
    }

    @SuppressWarnings("unchecked")
    private static List<EntityItem> items(World w, int x, int z) {
        return w.getEntitiesWithinAABB(EntityItem.class, AxisAlignedBB.getBoundingBox(x - 6, Y - 3, z - 6, x + 7, Y + 8, z + 7));
    }

    private static int drops(World w, int x, int z, Item it) {
        int n = 0;
        for (EntityItem e : items(w, x, z)) {
            ItemStack s = e.getEntityItem();
            if (s != null && !e.isDead && (it == null || s.getItem() == it)) {
                n += s.stackSize;
                e.setDead();
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- «RCS»

    private void rcsLiner() {
        LinerSystems L = liner2.state.liner();
        double gear = AirDefenseSVA.extras(liner2);
        L.cargoF = true;
        double doors = AirDefenseSVA.extras(liner2);
        L.cargoF = false;
        AirDefenseSVA.INSTANCE.debugBump(liner2, liner2.worldObj.getTotalWorldTime());
        double bump = AirDefenseSVA.extras(liner2);
        AirDefenseSVA.INSTANCE.debugBump(liner2, -1000);
        check(Math.abs(gear - 1.2) < 1e-9 && Math.abs(doors - 1.32) < 1e-9 && Math.abs(bump - 1.38) < 1e-9 && RadarMath.rcs(VehicleType.LINER) == 20,
                "ad wave A world «RCS»: the liner (RCS 20) - gear down x" + gear + ", the cargo door open x" + doors + ", the reverser's bump x" + bump);
    }

    private void rcsStep1() {
        boolean seen = survey.contacts().containsKey(Integer.valueOf(rcsPlane.getEntityId()));
        check(!seen && AirDefenseSVA.extras(rcsPlane) == 1, "ad wave A world «RCS»: a light plane 380 blocks off - beyond the surveillance radar's reach (337)");
        rcsPlane.state.tuning = new int[] {TuningMath.EXTRA_TANK, -1, -1, -1};
    }

    private void rcsStep2() {
        boolean seen = survey.contacts().containsKey(Integer.valueOf(rcsPlane.getEntityId()));
        check(seen && Math.abs(AirDefenseSVA.extras(rcsPlane) - 1.2) < 1e-9,
                "ad wave A world «RCS»: the same plane with a drop tank on its pylon (+20 %, 404 blocks) - seen");
        rcsDone = true;
    }

    // ---------------------------------------------------------------- «coverage»

    private void hill(World w, boolean on) {
        for (int x = x0 - 25; x <= x0 - 20; x++) {
            for (int z = z0 - 12; z <= z0 + 12; z++) {
                for (int y = Y + 1; y <= Y + 45; y++) {
                    w.setBlock(x, y, z, on ? Blocks.stone : Blocks.air, 0, 2);
                }
            }
        }
    }

    private void coverageBuild(World w) {
        hill(w, true);
    }

    private int[] cellOf(double wx, double wz) {
        double r = CoverageSVA.reach(survey), c = CoverageMath.cell(r), ax = survey.xCoord + 0.5, az = survey.zCoord + 0.5;
        return new int[] {(int) Math.floor((wx - (ax - r)) / c), (int) Math.floor((wz - (az - r)) / c)};
    }

    private boolean covAsked;

    private void coverageA(World w, int k) {
        net.minecraftforge.common.util.FakePlayer fp = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft((net.minecraft.world.WorldServer) w);
        if (!covAsked) {
            covAsked = true;
            CoverageSVA.INSTANCE.request(fp, survey, 20);
        }
        CoverageSVA.INSTANCE.run(800);                              // a few slices a tick (the ticks run 32 more)
        byte[] g = CoverageSVA.INSTANCE.grid(survey, 20);
        if (g == null) {
            if (k > 400) {
                check(false, "ad wave A world «coverage»: the map was never finished");
                covA = covB = true;
            }
            return;
        }
        int[] behind = cellOf(survey.xCoord - 43, survey.zCoord + 0.5), front = cellOf(survey.xCoord + 40, survey.zCoord + 20.5);
        int sb = CoverageMath.get(g, behind[0], behind[1]), sf = CoverageMath.get(g, front[0], front[1]);
        check(sb == CoverageMath.SHADOW && sf == CoverageMath.COVERED,
                "ad wave A world «coverage»: the surveillance radar's map at 20 blocks - a dead zone behind the stone hill (" + sb + "), open ground seen ("
                        + sf + "), " + CoverageMath.count(g, CoverageMath.SHADOW) + " dead cells");
        covA = true;
        hill(w, false);
        covRemovedAt = k;
    }

    private void coverageB(World w) {
        net.minecraftforge.common.util.FakePlayer fp = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft((net.minecraft.world.WorldServer) w);
        int st = CoverageSVA.INSTANCE.request(fp, survey, 20);
        for (int i = 0; i < 60 && CoverageSVA.INSTANCE.grid(survey, 20) == null; i++) {
            CoverageSVA.INSTANCE.run(1000);
        }
        byte[] g = CoverageSVA.INSTANCE.grid(survey, 20);
        int[] behind = cellOf(survey.xCoord - 43, survey.zCoord + 0.5);
        int sb = CoverageMath.get(g, behind[0], behind[1]);
        check(st == 1 && g != null && sb == CoverageMath.COVERED,
                "ad wave A world «coverage»: the hill taken away - the map seen stale (the ground changed), recomputed, the dead zone gone (" + sb + ")");
        covB = true;
    }

    // ---------------------------------------------------------------- «prediction»

    private void prediction(World w, int k) {
        if (predDone) {
            return;
        }
        ZoneMath.Zone f = null;
        for (ZoneMath.Zone z : kp.zones()) {
            if ("F-1".equals(z.name)) {
                f = z;
            }
        }
        if (f == null) {
            check(false, "ad wave A world «prediction»: no zone");
            predDone = true;
            return;
        }
        if (mStart < 0) {
            mStart = k;
        }
        int dt = k - mStart;
        double x = fx - 20 - 120 + 0.5 + dt * 2.0;               // 2 b/t east, 120 blocks off the border at the start
        m.setPosition(x, FLY, fz + 0.5);
        m.motionX = m.motionY = m.motionZ = 0;
        double[] vel = AirDefenseSVA.INSTANCE.velocity(m);
        double sec = ZoneMath.secondsToEntry(f, m.posX, m.posY, m.posZ, vel[0], vel[2], true, 2.0);
        int level = ZoneMath.predictLevel(sec, 2.0);
        if (predFiredAt < 0 && level >= 1 && level < 3) {
            predFiredAt = dt;
            predAtFire = sec;
        }
        if (entryAt < 0 && ZoneMath.contains2D(f, m.posX, m.posZ)) {
            entryAt = dt;
            double early = (entryAt - predFiredAt) / 20.0;
            check(predFiredAt >= 0 && early >= 1.5 && Math.abs(predAtFire - early) <= 0.35,
                    "ad wave A world «prediction»: «вход в зону через N с» fired " + early + " s before the border (predicted "
                            + String.format(java.util.Locale.ROOT, "%.2f", predAtFire) + " s) on the track at 2 b/t");
            predDone = true;
        }
        if (dt > 200) {
            check(false, "ad wave A world «prediction»: the plane never reached the zone");
            predDone = true;
        }
    }

    // ---------------------------------------------------------------- build

    private EntityVehicleSVA vehicle(World w, VehicleType type, double x, double y, double z, String owner, String name, boolean keep) {
        VehicleState st = new VehicleState(type);
        st.energy = st.maxEnergy();
        ItemStack is = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, type.ordinal()), st);
        EntityVehicleSVA v = ItemVehicleSVA.spawn(w, is, x, y, z, 0F, owner, name);
        if (v != null && keep) {
            held.add(v);
            hold.add(new double[] {x, y, z});
        }
        if (v != null) {
            v.state.xpdr.dayKey = false;
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private <T extends TileEntity> T block(World w, int x, int z, net.minecraft.block.Block bl, int meta, Class<T> c) {
        BlockAirDefenseSVA.build(w, x, Y + 1, z, (BlockAirDefenseSVA) bl, meta, 2);
        TileEntity te = w.getTileEntity(x, Y + 1, z);
        if (te instanceof TileEntityAirDefenseSVA) {
            ((TileEntityAirDefenseSVA) te).setOwnerName(OWNER_NAME);
        }
        return c.isInstance(te) ? (T) te : null;
    }

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 6000;
        z0 = sp.posZ - 6000;
        keepLoaded(w);
        mode0 = ConfigSVA.adMode;
        minWarn0 = ConfigSVA.adMinWarningSeconds;
        pvp0 = ConfigSVA.adRequirePvp;
        unmanned0 = ConfigSVA.adFireOnUnmanned;
        handoff0 = ConfigSVA.adIffHandoff;
        iffResp0 = ConfigSVA.adIffResponseSeconds;
        nrDec0 = ConfigSVA.adNoRadioDecisionSeconds;
        ConfigSVA.adMode = ConfigSVA.AD_WARNINGS;
        ConfigSVA.adMinWarningSeconds = 0;
        ConfigSVA.adRequirePvp = false;
        ConfigSVA.adFireOnUnmanned = true;
        ConfigSVA.adIffHandoff = true;
        ConfigSVA.adIffResponseSeconds = 5;
        ConfigSVA.adNoRadioDecisionSeconds = 12;
        w.getWorldInfo().setRaining(false);
        w.getWorldInfo().setThundering(false);
        area(w, true);
        kp = block(w, x0, z0, ModContentSVA.commandPost, 2, TileEntityCommandPostSVA.class);
        survey = block(w, x0 + 3, z0, ModContentSVA.radar, RadarMath.SURVEY, TileEntityRadarSVA.class);
        height = block(w, x0 + 6, z0, ModContentSVA.radar, RadarMath.HEIGHT, TileEntityRadarSVA.class);
        track = block(w, x0 + 9, z0, ModContentSVA.radar, RadarMath.TRACK, TileEntityRadarSVA.class);
        gun = block(w, x0, z0 + 4, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, TileEntityLauncherSVA.class);
        sam = block(w, x0 + 4, z0 + 4, ModContentSVA.launcher, TileEntityLauncherSVA.MISSILE, TileEntityLauncherSVA.class);
        tx = x0 + 60;
        tz = z0 + 30;
        tower = BlockTowerSVA.place(w, tx, Y + 1, tz, 0, OWNER_NAME);
        check(kp != null && survey != null && height != null && track != null && gun != null && sam != null && tower != null,
                "ad wave A world: the command post, three radars, two launchers and a tower placed");
        if (kp == null || survey == null || height == null || track == null || gun == null || sam == null || tower == null) {
            kp = null;
            return;
        }
        kp.debugSetup("waveA", null, 0, null);
        // a forbidden zone far east of the airport for the prediction run
        fx = x0 + 150;
        fz = z0 - 120;
        ZoneMath.Zone f = new ZoneMath.Zone();
        f.name = "F-1";
        f.type = ZoneMath.FORBIDDEN;
        f.shape = ZoneMath.CIRCLE;
        f.cx = fx;
        f.cz = fz;
        f.r = 20;
        List<ZoneMath.Zone> zs = new ArrayList<ZoneMath.Zone>();
        zs.add(f);
        kp.setZones(zs);
        gun.debugSettings(RoeMath.FOREIGN_ALL, true, false, 2);
        sam.debugSettings(RoeMath.VIOLATORS, true, false, 2);
        gun.debugAmmo(ModContentSVA.ammo.stackOf(ItemAmmoSVA.SHELLS, 64));
        for (int i = 0; i < 4; i++) {
            sam.debugAmmo(ModContentSVA.ammo.stackOf(ItemAmmoSVA.MISSILE, 1));
        }
        // the aircraft: the liner and a plane entering the airport (mode C), a stranger with 7000, a silent one out of the gun's reach
        l1 = vehicle(w, VehicleType.LINER, tx + 10.5, FLY, tz + 20.5, STRANGER, "Guest", true);
        p2 = vehicle(w, VehicleType.PLANE, tx + 30.5, FLY, tz + 50.5, STRANGER, "Visitor", true);      // in the zone, out of the gun's reach
        s = vehicle(w, VehicleType.PLANE, x0 + 40.5, FLY, z0 + 60.5, STRANGER, "Stranger", true);
        n = vehicle(w, VehicleType.PLANE, tx + 80.5, FLY, tz + 0.5, STRANGER, "Silent", true);
        p3 = vehicle(w, VehicleType.PLANE, tx + 20.5, Y + 1.1, tz - 20.5, STRANGER, "Departure", true);
        r1 = vehicle(w, VehicleType.PLANE, x0 + 20.5, FLY, z0 - 20.5, STRANGER, "NoRadio1", true);
        r2 = vehicle(w, VehicleType.PLANE, x0 + 30.5, FLY, z0 - 25.5, STRANGER, "NoRadio2", true);
        r3 = vehicle(w, VehicleType.PLANE, x0 + 40.5, FLY, z0 - 20.5, STRANGER, "NoRadio3", true);
        rcsPlane = vehicle(w, VehicleType.PLANE, x0 + 3 + 380.5, Y + 90, z0 + 0.5, STRANGER, "Far", true);
        m = vehicle(w, VehicleType.PLANE, fx - 140.5, FLY, fz + 0.5, STRANGER, "Mover", true);
        liner2 = vehicle(w, VehicleType.LINER, x0 - 20.5, Y + 1.1, z0 - 40.5, STRANGER, "Parked", true);
        p4 = vehicle(w, VehicleType.PLANE, tx - 30.5, FLY, tz + 15.5, OWNER, OWNER_NAME, true);
        e7 = vehicle(w, VehicleType.PLANE, tx - 10.5, FLY, tz - 30.5, OWNER, OWNER_NAME, true);
        dk = vehicle(w, VehicleType.PLANE, tx + 30.5, FLY, tz + 30.5, OWNER, OWNER_NAME, true);
        dr = vehicle(w, VehicleType.PLANE, tx + 40.5, FLY, tz - 30.5, OWNER, OWNER_NAME, true);
        u = vehicle(w, VehicleType.PLANE, tx + 90.5, FLY, tz + 50.5, STRANGER, "Dialler", true);
        vm = vehicle(w, VehicleType.PLANE, tx + 95.5, FLY, tz - 45.5, STRANGER, "Copycat", true);
        r4 = vehicle(w, VehicleType.PLANE, x0 + 20.5, FLY, z0 - 110.5, STRANGER, "NoRadio4", true);
        EntityVehicleSVA[] all = {l1, p2, s, n, p3, r1, r2, r3, rcsPlane, m, liner2, p4, e7, dk, dr, u, vm, r4};
        for (EntityVehicleSVA v : all) {
            if (v == null) {
                check(false, "ad wave A world: the test aircraft placed");
                kp = null;
                return;
            }
        }
        l1.state.xpdr.mode = XpdrMath.C;
        l1.state.xpdr.setCode(XpdrMath.VFR, w.getTotalWorldTime());
        LinerSystems Ls = l1.state.liner();
        Ls.set(com.sva.vehicle.LinerControls.BAT1, 1);
        Ls.set(com.sva.vehicle.LinerControls.ADIRS1, 1);
        p2.state.xpdr.mode = XpdrMath.C;
        s.state.xpdr.mode = XpdrMath.A;                             // mode A: no altitude - no arrival code for it, a stranger
        s.state.xpdr.setCode(XpdrMath.VFR, w.getTotalWorldTime());
        n.state.xpdr.mode = XpdrMath.OFF;
        p3.state.xpdr.mode = XpdrMath.A;
        for (EntityVehicleSVA r : new EntityVehicleSVA[] {r1, r2, r3}) {
            r.state.xpdr.mode = XpdrMath.A;
            r.state.xpdr.setCode(XpdrMath.NO_RADIO, w.getTotalWorldTime());
        }
        rcsPlane.state.xpdr.mode = XpdrMath.OFF;
        m.state.xpdr.mode = XpdrMath.C;
        long now = w.getTotalWorldTime();
        p4.state.xpdr.mode = XpdrMath.C;
        e7.state.xpdr.mode = XpdrMath.C;
        e7.state.xpdr.setCode(XpdrMath.EMERGENCY, now);
        dk.state.xpdr.mode = XpdrMath.C;
        dk.state.xpdr.setCode(02345, now);                          // the code from today's card, the day key running
        dk.state.xpdr.dayKey = true;
        dk.state.xpdr.cardDay = XpdrMath.day(w.getWorldTime());
        dr.state.xpdr.mode = XpdrMath.C;
        dr.state.xpdr.dayKey = true;                                // on, but no card today: the tower's code may replace it
        u.state.xpdr.mode = XpdrMath.C;
        u.state.xpdr.setCode(04105, now);                           // a code of the airport's range dialled by hand
        vm.state.xpdr.mode = XpdrMath.A;                            // 7000 until L1 has its code
        r4.state.xpdr.mode = XpdrMath.A;
        r4.state.xpdr.setCode(XpdrMath.NO_RADIO, now);
    }

    private void area(World w, boolean build) {
        for (int x = x0 - 64; x <= x0 + 90; x++) {
            for (int z = z0 - 50; z <= z0 + 60; z++) {
                for (int y = Y + 1; y <= Y + 8; y++) {
                    if (w.getBlock(x, y, z) != Blocks.air) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
                w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    private void keepLoaded(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 120) >> 4; cx <= (x0 + 400) >> 4; cx++) {
            for (int cz = (z0 - 140) >> 4; cz <= (z0 + 80) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void report() {
        check(codesDone && p3Done && iffResolved && nrDefaultDone && modulesDone && rcsDone && covB && predDone && iffCodes && drDone && r4Done,
                "ad wave A world: every stage finished in time (codes " + codesDone + ", departure " + p3Done + ", IFF " + iffResolved + ", 7600 "
                        + nrDefaultDone + ", modules " + modulesDone + ", RCS " + rcsDone + ", coverage " + covB + ", prediction " + predDone + ", IFF codes "
                        + iffCodes + ", day key back " + drDone + ", 7600 pause " + r4Done + ")");
    }

    private void finish(World w) {
        ConfigSVA.adMode = mode0 == null ? ConfigSVA.AD_WARNINGS : mode0;
        ConfigSVA.adMinWarningSeconds = minWarn0;
        ConfigSVA.adRequirePvp = pvp0;
        ConfigSVA.adFireOnUnmanned = unmanned0;
        ConfigSVA.adIffHandoff = handoff0;
        ConfigSVA.adIffResponseSeconds = iffResp0 <= 0 ? 30 : iffResp0;
        ConfigSVA.adNoRadioDecisionSeconds = nrDec0;
        for (EntityVehicleSVA v : held) {
            if (v != null && !v.isDead) {
                IffServerSVA.INSTANCE.forget(v);
                v.dismountAll();
                v.setDead();
            }
        }
        held.clear();
        hold.clear();
        for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {kp, survey, track, height, gun, sam}) {
            if (te != null) {
                te.noDrop = true;
            }
        }
        if (x0 != 0 || z0 != 0) {
            hill(w, false);
            if (tower != null) {
                for (int dy = TileEntityTowerSVA.PARTS; dy >= 0; dy--) {
                    w.setBlock(tx, Y + 1 + dy, tz, Blocks.air, 0, 3);
                }
            }
            area(w, false);
            List<EntityItem> l = items(w, x0 - 30, z0 + 20);
            for (EntityItem e : l) {
                e.setDead();
            }
        }
        AirDefenseSVA.INSTANCE.shots().clear();
        AirDefenseDataSVA dd = AirDefenseDataSVA.get(w);
        if (dd != null && kp != null) {
            dd.removeKp(kp.key());
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (reg != null) {
            reg.removeOwner(OWNER);
            reg.removeOwner(STRANGER);
        }
        phase = 10;
        done = true;
    }

    void abort(World w) {
        try {
            finish(w);
        } catch (Throwable t2) {
            done = true;
        }
    }
}
