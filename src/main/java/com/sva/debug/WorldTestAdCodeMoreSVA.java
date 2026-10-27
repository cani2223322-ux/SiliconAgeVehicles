package com.sva.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.sva.ConfigSVA;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.CodeOnlyMath;
import com.sva.airdefense.NoRadioMath;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneMath;
import com.sva.block.BlockAirDefenseSVA;
import com.sva.block.TileEntityAirDefenseSVA;
import com.sva.block.TileEntityCommandPostSVA;
import com.sva.block.TileEntityLauncherSVA;
import com.sva.block.TileEntityRadarSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemCodeCardSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * Code-only wave world test, the gaps the earlier phases left (docs/proposal-ad-strict-iff.md; the docs group). Run by
 * WorldTestSVA alongside its other phases from tick START in its own area (x spawn + 17800, z spawn - 17800, a stone floor
 * at Y 150), owner «TestADG»: a powered post «KG» with a surveillance radar and an AA gun, a second gun linked to a post
 * that exists only as trust data (as an UNLOADED post does: its chunk away), four aircraft round the post and the owner's
 * own machine at the second gun:
 * <ul>
 * <li>item 16: no day key on the post, the server's airdefense.dayKey=false, a stranger - refused; item 34: a server
 * operator on another's post - armed, the log «переключил оператор сервера» (the op taken back at once);</li>
 * <li>while arming - the usual classification: the Mode S white list a friend, 7600 NO RADIO, 7500 a stranger and the
 * alarm;</li>
 * <li>item 24 through the post's commands: «Сменить ключ» - refused for the access list and without «Sure», then the
 * generation +1 and the log, a card printed before is an old key's; the cards' term capped by codeCardMaxPeriods; «Все ПУ:
 * по всем без кода» - the owner's alone, only the post's own launchers;</li>
 * <li>items 2, 8, 9 armed: the white-listed board in mode S without the key's code - a target (no card); mode S with the
 * key's code - a friend (its board number read); 7500 - a target «угон» and the alarm; 7600 - NO RADIO in its grace;</li>
 * <li>item 15: a launcher whose post is unloaded classes by the code from the post's data - the owner's own manned machine
 * in a forbidden zone a violator (no card), engaged; the link kept.</li>
 * <li>review 2026-10-27: the post's verdict by hand - the key's code dialled 0.5 s before the interrogation is a miss (no
 * guess), stood 3 s a friend; a code caught mid-dial is no guess at the post's later round, a stood one counts once
 * (findings 1, 2); the unloaded post's gun - 7700 for a moment pauses the engagement, 7000 again resumes it at once from
 * its stage (finding 4); two owners' cards - the owner's own sets the code near his post, the boarding notes (finding 5);
 * the post's day key off and on - the arming anew (finding 6).</li>
 * <li>review 2026-10-27, batch 2: two tracks of one owner disagree on 7700 (one holds 7000 from an older survey) - the
 * operator's «Проверить» stays and the grace runs in one piece, a fresher 7000 pauses it, an older 7700 does not resume it
 * (classifyByCode with the answers' ticks); a guest with his own key's card and the owner's card near the owner's post,
 * a third owner's post nearer - the owner's card sets the code (its post in reach), and between two cards in reach the
 * first in the inventory.</li>
 * </ul>
 * The server-wide switches it flips (dayKey, the op list) are flipped back within the same tick: the other phases share them.
 */
final class WorldTestAdCodeMoreSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000adf6", OWNER_NAME = "TestADG";
    private static final String STRANGER = "00000000-5bad-4e57-0000-00000000adf7";
    private static final String SECRET = "moreKey";
    static final int START = 3300;
    private static final int Y = 150, FLY = Y + 20, MAX_TICKS = 1200;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0, armK = -1;
    boolean done;
    private TileEntityCommandPostSVA kp;
    private TileEntityLauncherSVA gun, gunU;
    private TileEntityRadarSVA radar;
    private String phantom = "";
    private FakePlayer owner, friend, op, wbPilot, sPilot, hjPilot, nrPilot;
    private EntityVehicleSVA pWb, pSKey, p7500, p7600, pOwnU;
    private final List<EntityVehicleSVA> planes = new ArrayList<EntityVehicleSVA>();
    private final List<double[]> hold = new ArrayList<double[]>();
    private ItemStack oldCard;
    private boolean dayKey0 = true, opAdded, usualDone, strictDone, unloadedDone;
    /**
     * Review 2026-10-27: the unloaded post's gun - the 7700 pause's step and its tick, the engagement before it {began, stage};
     * the second owner's post (trust data only) for the cards of two owners; the review's last stage done.
     */
    private int uStep, uMark;
    private long began0, stage0;
    private String phantomB = "";
    private boolean reviewDone;
    /** Review 2026-10-27, batch 2: the guest pilot and the posts (trust data only) of his key and of a third owner. */
    private FakePlayer guest;
    private String phantomG = "", phantomC = "";

    WorldTestAdCodeMoreSVA(WorldTestSVA t) {
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
            for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {kp, radar, gun, gunU}) {
                te.fillForTest();
            }
            for (int i = 0; i < planes.size(); i++) {
                EntityVehicleSVA v = planes.get(i);
                double[] p = hold.get(i);
                if (v != null && !v.isDead) {
                    v.setPosition(p[0], p[1], p[2]);
                    v.motionX = v.motionY = v.motionZ = 0;
                }
            }
            long now = w.getTotalWorldTime();
            pSKey.state.xpdr.setCode(AirDefenseDataSVA.keyCodeNow(w, OWNER_NAME), now);   // dialled by hand (the key may change mid-test)
            run(w, k, now);
            if (k > MAX_TICKS) {
                check(false, "ad code-only more world: every stage finished in time (usual " + usualDone + ", strict " + strictDone + ", unloaded "
                        + unloadedDone + ")");
                phase = 9;
            }
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private static boolean logged(TileEntityCommandPostSVA p, String key) {
        for (String[] l : p.logLines()) {
            if (l.length > 1 && l[1].equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static NBTTagCompound cmd(String op) {
        NBTTagCompound d = new NBTTagCompound();
        d.setString("Op", op);
        return d;
    }

    private AirDefenseSVA.Contact track(EntityVehicleSVA v) {
        return kp.tracks().get(Integer.valueOf(v.getEntityId()));
    }

    private void run(World w, int k, long now) {
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        if (k == 5) {
            switching(w, d);
        }
        if (k == 6) {
            reviewDirect(w, d, now);
        }
        if (k >= 20 && !usualDone && armK < 0) {
            usual(k);
        }
        if (usualDone && armK < 0) {
            kp.debugArmNow();
            kp.debugCodeOnlyRound();
            armK = k;
            check(kp.codeOnly() && logged(kp, "sva.ad.log.coOn"), "ad code-only more world (item 16): armed - the post classes by the code, the log says so");
            commands(w, d, now);
        }
        if (armK >= 0 && !strictDone) {
            strict(k);
        }
        if (k >= 20 && !unloadedDone) {
            unloaded(w, k, now);
        }
        if (strictDone && unloadedDone && !reviewDone) {
            review(w, d, now);
        }
        if (strictDone && unloadedDone && reviewDone) {
            phase = 9;
        }
    }

    // ---------------------------------------------------------------- items 16, 34: who may switch it on

    private void settings(boolean dayKey) {
        NBTTagCompound set = cmd("set");                            // as the screen sends it: the name, the day key, the white list, the list
        set.setString("Name", "KG");
        set.setBoolean("DayKey", dayKey);
        set.setIntArray("Codes", new int[0]);
        set.setIntArray("WB", new int[] {pWb.getBoardNumber()});
        NBTTagList wo = new NBTTagList();
        wo.appendTag(new NBTTagString("TestADGwb"));
        set.setTag("WO", wo);
        NBTTagList al = new NBTTagList();
        al.appendTag(new NBTTagString("TestADGfr"));
        set.setTag("Access", al);
        kp.command(owner, set);
    }

    private void switching(World w, AirDefenseDataSVA d) {
        NBTTagCompound on = cmd("codeOnly");
        on.setBoolean("On", true);
        on.setBoolean("Sure", true);
        settings(false);                                            // the post's own day key off
        kp.command(owner, on);
        boolean noKey = !d.info(kp.key(), false).codeOnly;
        settings(true);
        dayKey0 = ConfigSVA.adDayKey;
        ConfigSVA.adDayKey = false;                                 // the server's day key off - and back within this tick
        try {
            kp.command(owner, on);
        } finally {
            ConfigSVA.adDayKey = dayKey0;
        }
        boolean noServerKey = !d.info(kp.key(), false).codeOnly;
        kp.command(op, on);                                         // not an operator yet: a stranger
        boolean stranger = !d.info(kp.key(), false).codeOnly && !kp.isOwner(op);
        MinecraftServer s = MinecraftServer.getServer();
        boolean byOp = false, isOp = false, back = false;
        if (s != null && s.getConfigurationManager() != null) {
            s.getConfigurationManager().func_152605_a(op.getGameProfile());   // item 34: a server operator counts as the owner
            opAdded = true;
            try {
                isOp = kp.isOwner(op);
                kp.command(op, on);
                byOp = d.info(kp.key(), false).codeOnly;
            } finally {
                s.getConfigurationManager().func_152610_b(op.getGameProfile());
                opAdded = false;
            }
            back = !kp.isOwner(op);
        }
        check(noKey && noServerKey && stranger, "ad code-only more world (item 16): refused without the post's day key, with airdefense.dayKey=false and "
                + "for a stranger (" + noKey + noServerKey + stranger + ")");
        check(isOp && byOp && back && kp.codeOnlyState() == CodeOnlyMath.CO_ARMING && logged(kp, "sva.ad.log.coByOp") && logged(kp, "sva.ad.log.coArming"),
                "ad code-only more world (item 34): a server operator on another's post - arming, the log «переключил оператор сервера» (op " + isOp
                        + ", taken back " + back + ")");
    }

    // ---------------------------------------------------------------- the usual classification while arming

    private void usual(int k) {
        AirDefenseSVA.Contact wb = track(pWb), hj = track(p7500), nr = track(p7600);
        boolean asked = wb != null && hj != null && nr != null && wb.askedAt >= 0 && hj.askedAt >= 0 && nr.askedAt >= 0;
        boolean ok = asked && wb.cls == RoeMath.FRIEND && wb.board == pWb.getBoardNumber() && !wb.codeOnly && hj.cls == RoeMath.FOREIGN && kp.isAlarm()
                && nr.cls == RoeMath.NO_RADIO && kp.codeOnlyState() == CodeOnlyMath.CO_ARMING;
        if (ok || k > 400) {
            check(ok, "ad code-only more world (items 8, 9, 16): while arming the usual classification - the Mode S white list a friend ("
                    + (wb == null ? "-" : String.valueOf(wb.cls)) + "), 7500 a stranger with the alarm, 7600 NO RADIO");
            usualDone = true;
        }
    }

    // ---------------------------------------------------------------- item 24: the post's commands

    private void commands(World w, AirDefenseDataSVA d, long now) {
        int g0 = d.gen(OWNER_NAME);
        NBTTagCompound rk = cmd("rekey");
        rk.setBoolean("Sure", true);
        kp.command(friend, rk);                                     // the access list may not
        boolean listNo = d.gen(OWNER_NAME) == g0;
        NBTTagCompound rk0 = cmd("rekey");
        kp.command(owner, rk0);                                     // the owner's first press: only the confirmation
        boolean sureNo = d.gen(OWNER_NAME) == g0;
        kp.command(owner, rk);
        int g1 = d.gen(OWNER_NAME);
        long per = AirDefenseDataSVA.keyPeriod(w);
        int old = CodeOnlyMath.cardState(ItemCodeCardSVA.owner(oldCard), ItemCodeCardSVA.gen(oldCard), ItemCodeCardSVA.first(oldCard),
                ItemCodeCardSVA.until(oldCard), OWNER_NAME, g1, per, false);
        check(listNo && sureNo && g1 == g0 + 1 && logged(kp, "sva.ad.log.rekey") && old == CodeOnlyMath.CARD_OLD_KEY,
                "ad code-only more world (item 24): «Сменить ключ» - not for the access list, not without «Sure»; the owner's - generation " + g0 + " -> " + g1
                        + ", the log, a card printed before is an old key's (" + old + ")");
        NBTTagCompound o = cmd("codeOpts");
        o.setInteger("Opts", CodeOnlyMath.DEFAULT_OPTS);
        o.setInteger("Periods", 99);
        kp.command(owner, o);
        ItemStack c = new ItemStack(ModContentSVA.codeCard);
        kp.printCardForTest(c);
        int max = Math.max(1, ConfigSVA.adCodeCardMaxPeriods);
        check(d.info(kp.key(), false).cardPeriods == max && ItemCodeCardSVA.periods(c) == max && ItemCodeCardSVA.gen(c) == g1
                && logged(kp, "sva.ad.log.coOpts"),
                "ad code-only more world (items 18, 24): the cards' term capped by codeCardMaxPeriods (" + ItemCodeCardSVA.periods(c)
                        + "), a new card of the new generation, the log");
        NBTTagCompound all = cmd("coAllRoe");
        kp.command(friend, all);
        boolean allNo = gun.roe() == RoeMath.HOLD;
        kp.command(owner, all);
        check(allNo && gun.roe() == RoeMath.FOREIGN_ALL && gunU.roe() == RoeMath.VIOLATORS && logged(kp, "sva.ad.log.coAllRoe"),
                "ad code-only more world (item 27): «Все ПУ: по всем без кода» - the owner's alone, the post's own gun only (the unloaded post's gun kept)");
    }

    // ---------------------------------------------------------------- items 2, 8, 9 armed

    private void strict(int k) {
        AirDefenseSVA.Contact wb = track(pWb), sk = track(pSKey), hj = track(p7500), nr = track(p7600);
        boolean all = wb != null && sk != null && hj != null && nr != null;
        boolean wbOk = all && wb.codeOnly && RoeMath.hostile(wb.cls) && wb.idMiss == RoeMath.ID_NO_CARD && wb.board == pWb.getBoardNumber();
        boolean skOk = all && sk.codeOnly && sk.cls == RoeMath.FRIEND && sk.idr == RoeMath.ID_KEY && sk.board > 0;
        boolean hjOk = all && hj.codeOnly && RoeMath.hostile(hj.cls) && hj.idr == RoeMath.ID_HIJACK && kp.isAlarm();
        boolean nrOk = all && nr.codeOnly && nr.cls == RoeMath.NO_RADIO && nr.idr == RoeMath.ID_NO_RADIO;
        if (wbOk && skOk && hjOk && nrOk || k > armK + 500) {
            check(wbOk, "ad code-only more world (items 2, 9): armed - the white-listed board in mode S without the key's code is a target (no card), "
                    + "its board number no help (" + (wb == null ? "-" : wb.cls + "/" + wb.idMiss) + ")");
            check(skOk, "ad code-only more world (item 9): mode S with the key's code (by hand) - a friend, its board read (" + (sk == null ? "-" : sk.cls + "/"
                    + sk.idr + "/" + sk.board) + ")");
            check(hjOk, "ad code-only more world (item 8): 7500 - a target «угон», the post's alarm on (" + (hj == null ? "-" : hj.cls + "/" + hj.idr) + ")");
            check(nrOk, "ad code-only more world (item 8): 7600 - NO RADIO in its grace, as in the usual mode (" + (nr == null ? "-" : nr.cls + "/" + nr.idr) + ")");
            strictDone = true;
        }
    }

    // ---------------------------------------------------------------- item 15: an unloaded post

    private void unloaded(World w, int k, long now) {
        Integer id = Integer.valueOf(pOwnU.getEntityId());
        int[] c = gunU.lastPicture().get(id);
        boolean engaged = gunU.engagements().containsKey(id);
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        NoRadioMath.Entry e = d == null ? null : d.emergencyEntry(OWNER_NAME, AirDefenseSVA.vehKey(pOwnU));
        if (uStep == 0) {
            boolean ok = c != null && c[0] == RoeMath.VIOLATOR && c[3] == RoeMath.ID_NO_CARD && c[4] == RoeMath.ID_NO_CARD && engaged;
            if (ok || k > 600) {
                NBTTagCompound snap = gunU.snapshot();
                check(ok && phantom.equals(gunU.kpLink()) && snap.getByte("Co") == CodeOnlyMath.CO_ON && "KU".equals(snap.getString("CoKp")),
                        "ad code-only more world (item 15): the gun of an unloaded post - ПО КОДУ from the post's data (link kept), the owner's own manned machine "
                                + "in the zone without a card a violator, engaged (" + (c == null ? "-" : c[0] + "/" + c[3]) + ", " + engaged + ")");
                if (!ok) {
                    unloadedDone = true;
                    return;
                }
                // review 2026-10-27 (finding 4): 7700 for a moment - the engagement pauses, it does not start over
                long[] st = gunU.engagements().get(id);
                began0 = st[0];
                stage0 = st[1];
                pOwnU.state.xpdr.setCode(XpdrMath.EMERGENCY, now);
                uStep = 1;
                uMark = k;
            }
        } else if (uStep == 1 && k >= uMark + 12) {
            long[] p = gunU.debugPaused(id.intValue());
            check(!engaged && p != null && p[0] == began0 && p[1] == stage0 && c != null && c[0] == RoeMath.EMERGENCY && e != null,
                    "ad code-only more world (review, finding 4): 7700 in its grace - the gun's engagement paused at its stage (not dropped), the time charged "
                            + "to the owner's 7700 memory (" + (c == null ? "-" : String.valueOf(c[0])) + ", paused " + (p != null) + ")");
            pOwnU.state.xpdr.setCode(XpdrMath.VFR, now);
            uStep = 2;
            uMark = k;
        } else if (uStep == 2 && k >= uMark + 12) {
            long[] st = gunU.engagements().get(id);
            check(st != null && st[0] >= began0 + 5 && st[1] >= stage0 && c != null && c[0] == RoeMath.VIOLATOR && gunU.debugPaused(id.intValue()) == null
                    && e != null && NoRadioMath.elapsed(e, now) >= 5,
                    "ad code-only more world (review, finding 4): 7000 again - a violator at once (the two misses kept, no «ПРОВ 1/2»), the engagement goes on "
                            + "from its stage with its clock moved by the pause (" + (st == null ? "-" : (st[0] - began0) + "/" + st[1]) + ")");
            unloadedDone = true;
        }
    }

    // ---------------------------------------------------------------- the review of 2026-10-27, findings 1 and 2

    /**
     * The post's verdict by hand (AirDefenseSVA.classifyByCode with made-up answers, the owner's key): the key's code dialled
     * 10 ticks before the interrogation - no friend, no guess, a miss like any other; stood 3 s - a friend. A wrong code caught
     * 10 ticks after it was set and weighed by the post's round 5 s later - no guess; one that stood - one guess, however many
     * rounds go over the same answer.
     */
    private void reviewDirect(World w, AirDefenseDataSVA d, long now) {
        AirDefenseSVA ad = AirDefenseSVA.INSTANCE;
        AirDefenseDataSVA.KpInfo i = d.info(kp.key(), false);
        AirDefenseSVA.CodeKey ck = AirDefenseSVA.codeKey(w, i);
        AirDefenseSVA.Contact c = new AirDefenseSVA.Contact(-73101);
        c.vid = "sva-review-dial";
        c.replied = true;
        c.mode = XpdrMath.C;
        c.code = ck.keyNow;
        c.codeSince = now - 10;
        int r1 = ad.classifyByCode(w, ck, c, true, now, false, false, false, false, false, now);
        CodeOnlyMath.Probe p1 = d.probe(OWNER_NAME, AirDefenseDataSVA.probeKey(c.vid, null), false);
        boolean fresh = RoeMath.idClass(r1) == RoeMath.UNKNOWN && RoeMath.idReason(r1) == RoeMath.ID_CHECK && RoeMath.idMissOf(r1) == RoeMath.ID_NO_CARD
                && (p1 == null || p1.codes.isEmpty());
        c.codeSince = now - CodeOnlyMath.PROBE_STABLE_TICKS;
        int r2 = ad.classifyByCode(w, ck, c, true, now + 1, false, false, false, false, false, now + 1);
        check(i != null && ck.manual && ck.guard && ck.keyNow >= 0 && fresh && RoeMath.idClass(r2) == RoeMath.FRIEND && RoeMath.idReason(r2) == RoeMath.ID_KEY,
                "ad code-only more world (review, finding 1): the key's code dialled by hand 0.5 s before the interrogation - a miss like any other, no guess; "
                        + "stood 3 s - a friend (" + RoeMath.idReason(r1) + " / " + RoeMath.idReason(r2) + ")");
        int wc = ck.keyNow == 01357 || ck.keyPrev == 01357 ? 01356 : 01357;
        AirDefenseSVA.Contact m = new AirDefenseSVA.Contact(-73102);
        m.vid = "sva-review-mid";
        m.replied = true;
        m.mode = XpdrMath.C;
        m.code = wc;
        m.codeSince = now - 10;
        ad.classifyByCode(w, ck, m, true, now, false, false, false, false, false, now + 100);    // the post's round 5 s after the answer
        CodeOnlyMath.Probe p2 = d.probe(OWNER_NAME, AirDefenseDataSVA.probeKey(m.vid, null), false);
        boolean mid = p2 == null || p2.codes.isEmpty();
        m.codeSince = now - 100;                                    // the next answer: the code stood
        ad.classifyByCode(w, ck, m, true, now + 2, false, false, false, false, false, now + 2);
        ad.classifyByCode(w, ck, m, true, now + 2, false, false, false, false, false, now + 50);   // the same answer at the post's next round
        p2 = d.probe(OWNER_NAME, AirDefenseDataSVA.probeKey(m.vid, null), false);
        check(mid && p2 != null && p2.codes.size() == 1 && p2.at.get(0).longValue() == now + 2,
                "ad code-only more world (review, finding 2): a code caught mid-dial is no guess at the post's later round; one that stood - one guess, "
                        + "the same answer weighed again changes nothing (" + (p2 == null ? "-" : String.valueOf(p2.codes.size())) + ")");
        d.probeUnblock(OWNER_NAME, m.vid, "");
        d.probeUnblock(OWNER_NAME, c.vid, "");
    }

    // ---------------------------------------------------------------- the review of 2026-10-27, findings 5 and 6

    private void review(World w, AirDefenseDataSVA d, long now) {
        reviewDone = true;
        // finding 6: the post's day key off and on again - never straight back to ON: the arming anew, its log
        AirDefenseDataSVA.KpInfo i = d.info(kp.key(), false);
        boolean on0 = kp.codeOnly();
        settings(false);
        boolean off = kp.codeOnlyState() == CodeOnlyMath.CO_NO_KEY && i.armAt == -1;
        settings(true);
        boolean arming = kp.codeOnlyState() == CodeOnlyMath.CO_ARMING && !kp.codeOnly() && !i.codeOnlyActive(now, ConfigSVA.adCodeOnly, ConfigSVA.adDayKey);
        kp.debugCodeOnlyRound();
        boolean rearmed = logged(kp, "sva.ad.log.coRearm") && CodeOnlyMath.armLeft(i.codeOnly, i.armAt, now) == ConfigSVA.adCodeOnlyArmSeconds;
        check(on0 && off && arming && rearmed, "ad code-only more world (review, finding 6): the post's day key off - the arming dropped; on again - "
                + "arming anew (never ON at once), the countdown and the log (" + on0 + off + arming + rearmed + ")");
        // finding 5: the owner carries his own card (3 periods) and an ally's (7, whose post lists him) - near his post his own sets
        // the code; on boarding he hears of the ally's post; «Ключ дня» off - he hears of his own
        String b = "TestADGb";
        d.setSecret(b, "moreKeyB");
        phantomB = AirDefenseDataSVA.key(w.provider.dimensionId, x0 + 3000, Y + 1, z0 + 3000);
        AirDefenseDataSVA.KpInfo bi = d.info(phantomB, true);
        bi.owner = b;
        bi.name = "KB";
        bi.dim = w.provider.dimensionId;
        bi.dayKey = true;
        bi.codeOnly = true;
        bi.armAt = now - 1;
        bi.access.add(OWNER_NAME);
        long per = AirDefenseDataSVA.keyPeriod(w);
        ItemStack own = new ItemStack(ModContentSVA.codeCard), ally = new ItemStack(ModContentSVA.codeCard);
        ItemCodeCardSVA.print(own, "KG", OWNER_NAME, d.gen(OWNER_NAME), per, 3);
        ItemCodeCardSVA.print(ally, "KB", b, d.gen(b), per, 7);
        clearInv(owner);
        owner.inventory.mainInventory[3] = ally;
        owner.inventory.mainInventory[4] = own;
        XpdrMath.Xpdr x = pOwnU.state.xpdr;
        x.dayKey = true;
        AirDefenseSVA.INSTANCE.debugCardCode(pOwnU);
        boolean mine = x.fromCard() && OWNER_NAME.equalsIgnoreCase(x.cardOwner) && x.code == AirDefenseDataSVA.keyCodeNow(w, OWNER_NAME);
        String[] n = AirDefenseSVA.INSTANCE.boardingNote(w, owner, x);
        boolean other = n != null && "sva.ad.msg.coOtherCard".equals(n[0]) && "KB".equals(n[1]) && n.length > 2 && OWNER_NAME.equalsIgnoreCase(n[2]);
        x.dayKey = false;
        String[] n2 = AirDefenseSVA.INSTANCE.boardingNote(w, owner, x);
        boolean keyOff = n2 != null && "sva.ad.msg.coKeyOff".equals(n2[0]) && "KG".equals(n2[1]);
        check(mine && other && keyOff, "ad code-only more world (review, finding 5): two owners' cards - near his own post the owner's card sets the code "
                + "(not the ally's longer one) (" + mine + "); boarding - the ally's post named (" + (n == null ? "-" : n[0]) + "); «Ключ дня» off - his own post named ("
                + (n2 == null ? "-" : n2[0]) + ")");
        clearInv(owner);
        x.dayKey = true;
        batch2(w, d, now);
    }

    // ---------------------------------------------------------------- the review of 2026-10-27, batch 2

    private void batch2(World w, AirDefenseDataSVA d, long now) {
        // finding 1: post A's track hears 7700 (asked at now - 10), the operator says «Проверить»; post B's track still holds
        // 7000 from its survey at now - 40 - it must not wipe the decision nor chop the grace
        AirDefenseSVA.CodeKey k = AirDefenseSVA.codeKey(w, d.info(kp.key(), false));
        String vid = "sva-test-b2-7700";
        AirDefenseSVA.Contact a = new AirDefenseSVA.Contact(9101), b = new AirDefenseSVA.Contact(9102);
        for (AirDefenseSVA.Contact c : new AirDefenseSVA.Contact[] {a, b}) {
            c.vid = vid;
            c.replied = true;
            c.mode = XpdrMath.C;
            c.pilot = "TestADGem";
        }
        a.code = XpdrMath.EMERGENCY;
        b.code = XpdrMath.VFR;
        AirDefenseSVA.INSTANCE.classifyByCode(w, k, a, true, now - 10, false, false, false, false, false, now);
        NoRadioMath.Entry e = d.emergencyEntry(OWNER_NAME, vid);
        if (e != null) {
            NoRadioMath.decide(e, NoRadioMath.D_CHECK, now, 600);
        }
        for (long t2 = now; t2 <= now + 100; t2 += 10) {
            AirDefenseSVA.INSTANCE.classifyByCode(w, k, a, true, now - 10, false, false, false, false, false, t2);
            AirDefenseSVA.INSTANCE.classifyByCode(w, k, b, true, now - 40, false, false, false, false, false, t2);
        }
        boolean kept = e != null && e.decision == NoRadioMath.D_CHECK && e.since == now && NoRadioMath.elapsed(e, now + 100) == 100
                && a.em == NoRadioMath.S_CHECK && a.cls == RoeMath.EMERGENCY;
        AirDefenseSVA.INSTANCE.classifyByCode(w, k, b, true, now + 110, false, false, false, false, false, now + 110);   // B's next survey: 7000
        boolean paused = e != null && e.since < 0 && e.decision == NoRadioMath.D_NONE && NoRadioMath.elapsed(e, now + 200) == 110;
        AirDefenseSVA.INSTANCE.classifyByCode(w, k, a, true, now - 10, false, false, false, false, false, now + 120);   // A's old 7700
        boolean stale = e != null && e.since < 0;
        AirDefenseSVA.INSTANCE.classifyByCode(w, k, a, true, now + 130, false, false, false, false, false, now + 130);  // A's next: 7700
        boolean resumed = e != null && e.since == now + 130;
        check(kept && paused && stale && resumed, "ad code-only more world (review batch 2, finding 1): one owner's two tracks disagree on 7700 - "
                + "the older 7000 keeps «Проверить» and the grace whole (" + kept + "), a fresher 7000 pauses it and drops «Проверить» (" + paused
                + "), an older 7700 does not resume it (" + stale + "), a fresher one does (" + resumed + ")");
        AirDefenseDataSVA.OwnerKey ok = d.ownerKey(OWNER_NAME, false);
        if (ok != null) {
            ok.emergency.remove(vid);
        }
        // finding 2: the guest has his own key's card (7 periods, slot 0) and the owner's (3, slot 5); his own post is far, a
        // third owner's post is the nearest one - the owner's card (his post in reach) sets the code
        String g = "TestADGg", c3 = "TestADGc";
        guest = player(w, g);
        d.setSecret(g, "moreKeyG");
        d.setSecret(c3, "moreKeyC");
        phantomG = AirDefenseDataSVA.key(w.provider.dimensionId, x0 - 3000, Y + 1, z0 - 3000);
        phantomC = AirDefenseDataSVA.key(w.provider.dimensionId, x0 + 75, Y + 1, z0 + 25);
        String[] owners = {g, c3}, keys = {phantomG, phantomC};
        for (int i = 0; i < 2; i++) {
            AirDefenseDataSVA.KpInfo pi = d.info(keys[i], true);
            pi.owner = owners[i];
            pi.name = i == 0 ? "KGG" : "KGC";
            pi.dim = w.provider.dimensionId;
            pi.dayKey = true;
            pi.codeOnly = true;
            pi.armAt = now - 1;
        }
        long per = AirDefenseDataSVA.keyPeriod(w);
        ItemStack mineG = new ItemStack(ModContentSVA.codeCard), host = new ItemStack(ModContentSVA.codeCard), third = new ItemStack(ModContentSVA.codeCard);
        ItemCodeCardSVA.print(mineG, "KGG", g, d.gen(g), per, 7);
        ItemCodeCardSVA.print(host, "KG", OWNER_NAME, d.gen(OWNER_NAME), per, 3);
        ItemCodeCardSVA.print(third, "KGC", c3, d.gen(c3), per, 7);
        ride(owner, null);
        clearInv(guest);
        ride(guest, pOwnU);
        guest.inventory.mainInventory[0] = mineG;
        guest.inventory.mainInventory[5] = host;
        XpdrMath.Xpdr x = pOwnU.state.xpdr;
        x.dayKey = true;
        AirDefenseSVA.INSTANCE.debugCardCode(pOwnU);
        java.util.Set<String> reach = AirDefenseSVA.codeOnlyOwnersInReach(d, w.provider.dimensionId, pOwnU.posX, pOwnU.posZ);
        boolean hostWins = OWNER_NAME.equalsIgnoreCase(x.cardOwner) && x.code == AirDefenseDataSVA.keyCodeNow(w, OWNER_NAME) && x.fromCard()
                && c3.equalsIgnoreCase(AirDefenseSVA.nearestCodeOnlyOwner(d, w.provider.dimensionId, pOwnU.posX, pOwnU.posZ))
                && reach.contains(OWNER_NAME.toLowerCase()) && reach.contains(c3.toLowerCase()) && !reach.contains(g.toLowerCase());
        // two cards whose posts both see him (the owner's and the third owner's): the first in the inventory - the pilot's choice
        guest.inventory.mainInventory[2] = third;
        AirDefenseSVA.INSTANCE.debugCardCode(pOwnU);
        boolean thirdFirst = c3.equalsIgnoreCase(x.cardOwner) && x.code == AirDefenseDataSVA.keyCodeNow(w, c3);
        guest.inventory.mainInventory[1] = host;
        guest.inventory.mainInventory[5] = null;
        AirDefenseSVA.INSTANCE.debugCardCode(pOwnU);
        boolean hostFirst = OWNER_NAME.equalsIgnoreCase(x.cardOwner) && x.code == AirDefenseDataSVA.keyCodeNow(w, OWNER_NAME);
        check(hostWins && thirdFirst && hostFirst, "ad code-only more world (review batch 2, finding 2): a guest with his own key's card and the "
                + "owner's - the owner's sets the code near his post though a third owner's post is nearer (" + hostWins + " " + x.cardOwner
                + "); two cards whose posts are in reach - the first in the inventory (" + thirdFirst + " " + hostFirst + ")");
        clearInv(guest);
        ride(guest, null);
        ride(owner, pOwnU);
        AirDefenseSVA.INSTANCE.debugCardCode(pOwnU);                // no card with the owner: the card's code back to 7000
    }

    // ---------------------------------------------------------------- the area

    private static FakePlayer player(World w, String name) {
        return FakePlayerFactory.get((WorldServer) w, new GameProfile(UUID.nameUUIDFromBytes(("sva-test:" + name).getBytes()), name));
    }

    /** Mounts / unmounts a fake player by the fields (EntityPlayerMP.mountEntity sends a packet it has no connection for). */
    private static void ride(FakePlayer p, Entity m) {
        if (p.ridingEntity != null) {
            p.ridingEntity.riddenByEntity = null;
        }
        p.ridingEntity = m;
        if (m != null) {
            m.riddenByEntity = p;
        }
    }

    private static void clearInv(FakePlayer p) {
        for (int i = 0; i < p.inventory.mainInventory.length; i++) {
            p.inventory.mainInventory[i] = null;
        }
    }

    private EntityVehicleSVA plane(World w, double x, double z, String ownerId, String ownerName, FakePlayer pilot, int mode, boolean dayKey, int code) {
        VehicleState st = new VehicleState(VehicleType.PLANE);
        st.energy = st.maxEnergy();
        EntityVehicleSVA v = ItemVehicleSVA.spawn(w, ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.PLANE.ordinal()), st), x, FLY, z,
                0F, ownerId, ownerName);
        if (v != null) {
            planes.add(v);
            hold.add(new double[] {x, FLY, z});
            v.state.xpdr.mode = mode;
            v.state.xpdr.dayKey = dayKey;
            v.state.xpdr.setCode(code, w.getTotalWorldTime());
            clearInv(pilot);
            ride(pilot, v);
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
        x0 = (sp.posX + 17800) >> 4 << 4;
        z0 = (sp.posZ - 17800) >> 4 << 4;
        keepLoaded(w);
        w.getWorldInfo().setRaining(false);
        w.getWorldInfo().setThundering(false);
        area(w, true);
        kp = block(w, x0, z0, ModContentSVA.commandPost, 2, TileEntityCommandPostSVA.class);
        radar = block(w, x0 + 6, z0 + 6, ModContentSVA.radar, RadarMath.SURVEY, TileEntityRadarSVA.class);
        gun = block(w, x0 + 12, z0 - 6, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, TileEntityLauncherSVA.class);
        gunU = block(w, x0 + 70, z0 + 10, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, TileEntityLauncherSVA.class);
        check(kp != null && radar != null && gun != null && gunU != null, "ad code-only more world: a post, a surveillance radar and two guns placed");
        if (kp == null || radar == null || gun == null || gunU == null) {
            kp = null;
            return;
        }
        owner = player(w, OWNER_NAME);
        friend = player(w, "TestADGfr");
        op = player(w, "TestADGop");
        wbPilot = player(w, "TestADGwb");
        sPilot = player(w, "TestADGs");
        hjPilot = player(w, "TestADGhj");
        nrPilot = player(w, "TestADGnr");
        long now = w.getTotalWorldTime();
        int key = AirDefenseDataSVA.keyCodeNow(w, OWNER_NAME);
        // the post's aircraft (north of it, out of the second gun's eyes and zone): the white-listed board in mode S, mode S with
        // the key's code, 7500, 7600; the owner's own machine at the second gun, in its zone
        pWb = plane(w, x0 - 12, z0 + 45, STRANGER, "TestADGwb", wbPilot, XpdrMath.S, false, XpdrMath.VFR);
        pSKey = plane(w, x0, z0 + 45, STRANGER, "TestADGs", sPilot, XpdrMath.S, false, key);
        p7500 = plane(w, x0 + 12, z0 + 45, STRANGER, "TestADGhj", hjPilot, XpdrMath.C, false, XpdrMath.HIJACK);
        p7600 = plane(w, x0 + 24, z0 + 45, STRANGER, "TestADGnr", nrPilot, XpdrMath.C, false, XpdrMath.NO_RADIO);
        pOwnU = plane(w, x0 + 70, z0 + 22, OWNER, OWNER_NAME, owner, XpdrMath.C, true, XpdrMath.VFR);
        if (planes.size() != 5) {
            check(false, "ad code-only more world: the test aircraft placed");
            kp = null;
            return;
        }
        kp.debugSetup(SECRET, null, 0, null);                       // the owner's secret
        settings(true);
        radar.setKpLink(kp.key());
        gun.setKpLink(kp.key());
        gun.debugSettings(RoeMath.HOLD, true, false, 2);
        gun.debugFireMode = true;                                   // no ammunition: nothing is ever shot
        // the second gun's post: its trust data only, as a post in an unloaded chunk leaves it (code-only, armed long ago)
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        phantom = AirDefenseDataSVA.key(w.provider.dimensionId, x0 + 2000, Y + 1, z0 + 2000);
        AirDefenseDataSVA.KpInfo pi = d.info(phantom, true);
        pi.owner = OWNER_NAME;
        pi.name = "KU";
        pi.dim = w.provider.dimensionId;
        pi.dayKey = true;
        pi.codeOnly = true;
        pi.armAt = now - 1;
        gunU.setKpLink(phantom);
        gunU.debugSettings(RoeMath.VIOLATORS, true, false, 2);
        gunU.debugFireMode = true;
        ZoneMath.Zone z = new ZoneMath.Zone();                      // the owner's forbidden zone round the second gun (his zones are one set)
        z.name = "KGZ";
        z.type = ZoneMath.FORBIDDEN;
        z.shape = ZoneMath.CIRCLE;
        z.cx = x0 + 70;
        z.cz = z0 + 10;
        z.r = 20;
        z.alarm = false;
        List<ZoneMath.Zone> zs = new ArrayList<ZoneMath.Zone>();
        zs.add(z);
        kp.setZones(zs);
        oldCard = new ItemStack(ModContentSVA.codeCard);
        kp.printCardForTest(oldCard);                               // printed before «Сменить ключ»
    }

    private void area(World w, boolean build) {
        for (int x = x0 - 20; x <= x0 + 90; x++) {
            for (int z = z0 - 20; z <= z0 + 60; z++) {
                for (int y = Y + 1; y <= Y + 10; y++) {
                    if (w.getBlock(x, y, z) != Blocks.air) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
                w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    private void keepLoaded(World w) {
        if (!(w instanceof WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        ChunkProviderServer cp = ((WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 40) >> 4; cx <= (x0 + 100) >> 4; cx++) {
            for (int cz = (z0 - 40) >> 4; cz <= (z0 + 70) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void finish(World w) {
        if (opAdded) {
            MinecraftServer s = MinecraftServer.getServer();
            if (s != null && s.getConfigurationManager() != null && op != null) {
                s.getConfigurationManager().func_152610_b(op.getGameProfile());
            }
            opAdded = false;
        }
        for (FakePlayer p : new FakePlayer[] {owner, friend, op, wbPilot, sPilot, hjPilot, nrPilot, guest}) {
            if (p != null) {
                clearInv(p);
                ride(p, null);
            }
        }
        for (EntityVehicleSVA v : planes) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        planes.clear();
        hold.clear();
        for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {kp, radar, gun, gunU}) {
            if (te != null) {
                te.noDrop = true;
            }
        }
        if (x0 != 0 || z0 != 0) {
            keepLoaded(w);
            area(w, false);
        }
        AirDefenseDataSVA dd = AirDefenseDataSVA.get(w);
        if (dd != null) {
            if (kp != null) {
                dd.removeKp(kp.key());
            }
            if (phantom.length() > 0) {
                dd.removeKp(phantom);
            }
            if (phantomB.length() > 0) {
                dd.removeKp(phantomB);
            }
            for (String ph : new String[] {phantomG, phantomC}) {
                if (ph.length() > 0) {
                    dd.removeKp(ph);                                // review batch 2: the guest's and the third owner's posts
                }
            }
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
