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
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * Code-only wave world test (docs/proposal-ad-strict-iff.md) - the command post, the launchers, the interrogation. Run by
 * WorldTestSVA alongside its other phases from tick START in its own area (x spawn + 13800, z spawn - 13800, a stone floor at
 * Y 150), owner «TestADK». Post A is left UNPOWERED with a forbidden zone round its AA gun (no ammunition: the fire stage is
 * reached, nothing is shot, «debugFireMode»): the gun classes with its own eyes from A's trust data (item 15). Post C is
 * powered, with a surveillance radar, out of the gun's link reach (its log, its track commands). Eight aircraft round the
 * gun, each flown by a fake player - the targets inside the zone (the owner's machine without a card nearest: the gun has one
 * fire channel), the friends outside:
 * <ul>
 * <li>item 16: switched on by the access list - refused; by the owner without «Sure» - refused; with the server's codeOnly
 * off - refused; by the owner - arming (the usual classification meanwhile: the owner's machine OWN, a silent stranger
 * engaged), armed - the log, the engagements start over, a miss first is only «ПРОВ»;</li>
 * <li>items 1-6, 13, 22, 33: the owner's machine without a card - the ОПОЗН lamp, a violator after two misses, engaged, the
 * «Огонь» stage not before 20 s, ready to fire; «Нет карты кодов» for the owner on boarding; the post's log «борт
 * ВЛАДЕЛЬЦА»;</li>
 * <li>a friend's card - a friend, never engaged; a code dialled by hand - a friend (decision 1); a guest with a card handed
 * over by the owner's tower - a friend (the key beats the handoff), post C logs «верный код у чужого борта» (item 30);</li>
 * <li>item 30: the owner's machine stolen (a card's code on it, the thief without one) - the code back to 7000, a target;</li>
 * <li>item 7: 7700 near the end of its grace - EMERGENCY, then a target; post C's «Свой» makes it a friend at the gun too
 * (per owner), «Враждебный» takes it back;</li>
 * <li>item 11: four wrong codes - the «ПОДБОР» block, the right code then is no help; post C's «Снять» lifts it;</li>
 * <li>item 14: the card's code at the last moment - the look before the shot lets the target go;</li>
 * <li>item 25: a code-only post's launcher - its rule the owner's alone (the post's option gives it back to the list);</li>
 * <li>item 32: the readiness line; items 15, 28: the server's codeOnly=false and a broken post A - the usual mode (the owner's
 * machine spared, the link gone).</li>
 * </ul>
 */
final class WorldTestAdCodeKpSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000adf4", OWNER_NAME = "TestADK";
    private static final String STRANGER = "00000000-5bad-4e57-0000-00000000adf5";
    private static final String SECRET = "kpKey";
    static final int START = 3150;
    private static final int Y = 150, FLY = Y + 20, ARM = 25, MAX_TICKS = 1500;
    private static final int[] GUESSES = {01111, 02222, 03333, 04444};

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    boolean done;
    private TileEntityCommandPostSVA kpA, kpC;
    private TileEntityLauncherSVA gun;
    private TileEntityRadarSVA radar;
    private FakePlayer owner, friend, man, thief, emp, guesser, guest, sil;
    private EntityVehicleSVA pNoCard, pCard, pManual, pStolen, pEm, pGuess, pHand, pSilent;
    private final List<EntityVehicleSVA> planes = new ArrayList<EntityVehicleSVA>();
    private final List<double[]> hold = new ArrayList<double[]>();
    private boolean codeOnly0 = true, banned, cardEngaged;
    private long armTick, began = -1;
    private int stage, at, guessAt;
    private boolean sawOwnLog, sawStrangerLog, guessBlocked, ready20;

    WorldTestAdCodeKpSVA(WorldTestSVA t) {
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
            phase = kpA == null ? 9 : 1;
        }
        int k = ticks - t0;
        if (phase == 1) {
            for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {kpC, radar, gun}) {
                te.fillForTest();                                   // post A stays without power
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
            int key = AirDefenseDataSVA.keyCodeNow(w, OWNER_NAME);
            pManual.state.xpdr.setCode(key, now);                   // dialled by hand (a new key period mid-test too)
            if (stage >= 2 && gun.engagements().containsKey(Integer.valueOf(pCard.getEntityId()))) {
                cardEngaged = true;
            }
            run(w, k, now, key);
            if (k > MAX_TICKS) {
                check(false, "ad code-only kp world: every stage finished in time (stopped in stage " + stage + ")");
                phase = 9;
            }
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private int[] pic(EntityVehicleSVA v) {
        int[] c = gun.lastPicture().get(Integer.valueOf(v.getEntityId()));
        return c == null ? new int[] {-1, 0, 0, 0, 0} : c;
    }

    private boolean engaged(EntityVehicleSVA v) {
        return gun.engagements().containsKey(Integer.valueOf(v.getEntityId()));
    }

    private static boolean logged(TileEntityCommandPostSVA kp, String key) {
        for (String[] l : kp.logLines()) {
            if (l.length > 1 && l[1].equals(key)) {
                return true;
            }
        }
        return false;
    }

    private NBTTagCompound cmd(String op, EntityVehicleSVA v) {
        NBTTagCompound d = new NBTTagCompound();
        d.setString("Op", op);
        if (v != null) {
            d.setInteger("Id", v.getEntityId());
        }
        return d;
    }

    private void run(World w, int k, long now, int key) {
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        if (stage == 0 && k >= 5) {
            arming(w, d);
            stage = 1;
        } else if (stage == 1 && k >= 20) {
            // arming: the usual classification - the owner's machine OWN, a silent stranger in the zone a violator, engaged
            check(pic(pNoCard)[0] == RoeMath.OWN && pic(pNoCard)[3] == RoeMath.ID_NONE && pic(pSilent)[0] == RoeMath.VIOLATOR && engaged(pSilent),
                    "ad code-only kp world (item 16): while arming the usual classification - the owner's machine OWN (" + pic(pNoCard)[0]
                            + "), the silent stranger a violator, engaged");
            check(pStolen.state.xpdr.code == XpdrMath.VFR && !pStolen.state.xpdr.fromCard(),
                    "ad code-only kp world (item 30): the owner's machine stolen - the thief has no card: the card's code back to 7000 within a second");
            stage = 2;
        } else if (stage == 2 && k >= ARM) {
            kpA.debugArmNow();
            kpC.debugArmNow();
            armTick = now;
            kpA.debugCodeOnlyRound();
            check(kpA.codeOnly() && kpA.codeOnlyState() == CodeOnlyMath.CO_ON && logged(kpA, "sva.ad.log.coOn") && logged(kpA, "sva.ad.log.coArming"),
                    "ad code-only kp world (item 16): armed - ON, the log says the arming and the switch-on");
            // 7700 with 10 s of its grace left (the owner's memory, shared by his posts)
            NoRadioMath.Entry e = d.emergency(OWNER_NAME, AirDefenseSVA.vehKey(pEm), true, now);
            e.used = Math.max(0, ConfigSVA.adCodeOnlyEmergencySeconds * 20 - 200);
            stage = 3;
        } else if (stage == 3 && k >= ARM + 12) {
            firstRound(w, now);
            stage = 4;
        } else if (stage >= 4) {
            guessing(w, d, k, now, key);
            later(w, d, k, now, key);
        }
        // post C's log, looked at as it goes (40 lines kept)
        if (stage >= 3) {
            sawOwnLog |= logged(kpC, "sva.ad.log.coMissOwn.noCard");
            sawStrangerLog |= logged(kpC, "sva.ad.log.coStranger");
        }
    }

    // ---------------------------------------------------------------- item 16: switching on

    private void arming(World w, AirDefenseDataSVA d) {
        NBTTagCompound on = cmd("codeOnly", null);
        on.setBoolean("On", true);
        kpA.command(friend, on);                                    // the access list may not
        boolean listNo = !d.info(kpA.key(), false).codeOnly;
        kpA.command(owner, on);                                     // the owner without the confirmation window's «Sure»
        boolean sureNo = !d.info(kpA.key(), false).codeOnly;
        on.setBoolean("Sure", true);
        ConfigSVA.adCodeOnly = false;
        banned = true;
        kpA.command(owner, on);
        boolean banNo = !d.info(kpA.key(), false).codeOnly;
        ConfigSVA.adCodeOnly = codeOnly0;
        banned = false;
        kpA.command(owner, on);
        kpC.command(owner, on);
        AirDefenseDataSVA.KpInfo i = d.info(kpA.key(), false);
        long now = w.getTotalWorldTime();
        check(listNo && sureNo && banNo && i.codeOnly && kpA.codeOnlyState() == CodeOnlyMath.CO_ARMING
                && CodeOnlyMath.armLeft(i.codeOnly, i.armAt, now) == ConfigSVA.adCodeOnlyArmSeconds && !kpA.codeOnly(),
                "ad code-only kp world (item 16): the access list, the owner without «Sure», the server's codeOnly=false - refused; the owner - arming "
                        + CodeOnlyMath.armLeft(i.codeOnly, i.armAt, now) + " s, the usual classification till then");
        NBTTagCompound zones = AirDefenseSVA.debugZonesTag(d);
        boolean marked = false;
        for (int z = 0; z < zones.getTagList("Z", 10).tagCount(); z++) {
            NBTTagCompound c = zones.getTagList("Z", 10).getCompoundTagAt(z);
            marked |= "KTA".equals(c.getString("Name")) && c.getBoolean("CO") && !c.hasKey("CodeLo");
        }
        check(marked, "ad code-only kp world (item 22): the code-only post's zone goes to the clients marked «ПО КОДУ»");
    }

    // ---------------------------------------------------------------- the first strict round

    private void firstRound(World w, long now) {
        long[] st = gun.engagements().get(Integer.valueOf(pSilent.getEntityId()));
        boolean restarted = st == null || st[0] >= armTick;
        check(restarted && pic(pSilent)[0] == RoeMath.UNKNOWN && pic(pSilent)[3] == RoeMath.ID_CHECK && pic(pNoCard)[0] == RoeMath.UNKNOWN,
                "ad code-only kp world (items 5, 16): armed - the engagements start over, a first miss is only «ПРОВ 1/2» (no fire) - the owner's machine too");
        check(pic(pCard)[0] == RoeMath.FRIEND && pic(pCard)[3] == RoeMath.ID_KEY && pCard.state.xpdr.fromCard() && pic(pManual)[0] == RoeMath.FRIEND
                && pic(pManual)[3] == RoeMath.ID_KEY && pic(pHand)[0] == RoeMath.FRIEND && pic(pEm)[0] == RoeMath.EMERGENCY,
                "ad code-only kp world (items 1, 7, 21; decision 1): a card - a friend; the code dialled by hand - a friend; a guest's card over the "
                        + "owner's tower handoff - a friend; 7700 in its grace (" + pic(pCard)[0] + pic(pManual)[0] + pic(pHand)[0] + pic(pEm)[0] + ")");
        boolean l0 = pNoCard.state.xpdr.idCaution(now), l1 = pCard.state.xpdr.idCaution(now), l2 = pManual.state.xpdr.idCaution(now);
        check(l0 && !l1 && !l2, "ad code-only kp world (item 22): the ОПОЗН lamp lit on the machine without a match (" + l0 + "), dark with the card ("
                + !l1 + ") / the right code (" + !l2 + ") - post C's older survey answer does not relight it");
        AirDefenseSVA ad = AirDefenseSVA.INSTANCE;
        String p0 = ad.noCardPost(w, owner);
        check(p0 != null && ad.noCardPost(w, friend) == null && ad.noCardPost(w, guest) == null,
                "ad code-only kp world (item 22): «Нет карты кодов» on boarding - the owner without a card (" + p0 + "); not his friend with one, not a guest");
        // item 25: the rule of a code-only post's launcher - the owner's alone, unless the post trusts its list
        NBTTagCompound s = new NBTTagCompound();
        s.setInteger("Roe", RoeMath.HOLD);
        s.setBoolean("Msg", true);
        s.setBoolean("Warn", false);
        s.setInteger("Wait", 2);
        gun.applySettings(friend, s);
        boolean kept = gun.roe() == RoeMath.FOREIGN_ALL && gun.ownerOnly(friend) && !gun.ownerOnly(owner);
        NBTTagCompound o = cmd("codeOpts", null);
        o.setInteger("Opts", CodeOnlyMath.DEFAULT_OPTS | CodeOnlyMath.OPT_LAUNCHERS_ACCESS);
        kpA.command(owner, o);
        boolean trusted = !gun.ownerOnly(friend);
        o.setInteger("Opts", CodeOnlyMath.DEFAULT_OPTS);
        kpA.command(friend, o);                                     // the list may not change the options back
        boolean listOpts = !gun.ownerOnly(friend);
        kpA.command(owner, o);
        check(kept && trusted && listOpts && gun.ownerOnly(friend),
                "ad code-only kp world (item 25): the list's rule refused (the rule stays), the owner's option «Доверить ПУ списку доступа» gives it back; options the owner's");
        NBTTagCompound snap = gun.snapshot();
        // the user's request 2026-10-27: «только предупреждения» only with mode=warnings and codeOnlyFire off
        int want = ConfigSVA.adFiresFor(true) ? CodeOnlyMath.R_KP_OFF : CodeOnlyMath.R_WARN_ONLY;
        check(snap.getByte("Co") == CodeOnlyMath.CO_ON && "KA".equals(snap.getString("CoKp")) && snap.getBoolean("CoLock") && snap.getByte("CoReady") == want
                && snap.getInteger("CoMinWait") == Math.max(ConfigSVA.adMinWarningSeconds, ConfigSVA.adCodeOnlyMinWarningSeconds),
                "ad code-only kp world (items 15, 32, 41): the launcher of an unpowered post - ПО КОДУ from its data, the readiness line (" + snap.getByte("CoReady")
                        + "), the rule locked, the 20 s floor");
        // review 2026-10-27 (batch 3, item 25): the link button - a list member may not move a launcher INTO the owner's
        // code-only post either (unlinked here, post A the only one in reach); the owner may
        gun.setKpLink("");
        boolean refused = !ad.relink(friend, gun) && gun.kpLink().length() == 0;
        boolean ownerLinks = ad.relink(owner, gun) && kpA.key().equals(gun.kpLink());
        check(refused && ownerLinks, "ad code-only kp world (review batch 3, item 25): the list's link button into a code-only post refused (" + refused
                + "), the owner's links it (" + ownerLinks + ")");
        // review batch 3 (item 22): without the day key (the server's or the post's) the post classes the usual way - its zones
        // lose «ПО КОДУ», nothing is said on boarding
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        AirDefenseDataSVA.KpInfo ia = d.info(kpA.key(), false), ic = d.info(kpC.key(), false);
        boolean on = zoneMarked(d) && ad.noCardPost(w, owner) != null;
        boolean dk0 = ConfigSVA.adDayKey;
        ConfigSVA.adDayKey = false;
        boolean srvOff = !zoneMarked(d) && ad.noCardPost(w, owner) == null;
        ConfigSVA.adDayKey = dk0;
        ia.dayKey = false;                                          // post A's key off: its zone unmarked, post C still speaks
        boolean postOff = !zoneMarked(d) && ad.noCardPost(w, owner) != null;
        ic.dayKey = false;                                          // both posts' keys off: nothing said on boarding
        postOff &= ad.noCardPost(w, owner) == null;
        ia.dayKey = true;
        ic.dayKey = true;
        check(on && srvOff && postOff && zoneMarked(d), "ad code-only kp world (review batch 3, item 22): the «ПО КОДУ» zone mark and «Нет карты кодов» - on ("
                + on + "); not with the server's dayKey=false (" + srvOff + ") nor the post's key off (" + postOff + ")");
    }

    /** Post A's zone «KTA» goes to the clients marked «ПО КОДУ». */
    private static boolean zoneMarked(AirDefenseDataSVA d) {
        NBTTagCompound zones = AirDefenseSVA.debugZonesTag(d);
        boolean marked = false;
        for (int z = 0; z < zones.getTagList("Z", 10).tagCount(); z++) {
            NBTTagCompound c = zones.getTagList("Z", 10).getCompoundTagAt(z);
            marked |= "KTA".equals(c.getString("Name")) && c.getBoolean("CO");
        }
        return marked;
    }

    // ---------------------------------------------------------------- item 11

    private void guessing(World w, AirDefenseDataSVA d, int k, long now, int key) {
        XpdrMath.Xpdr x = pGuess.state.xpdr;
        int n = (k - ARM - 15) / 15;
        if (!guessBlocked && n >= 0 && n < GUESSES.length && x.code != GUESSES[n]) {
            x.setCode(GUESSES[n], now);
            x.codeSince = now - 80;                                 // stood its 3 s
        }
        if (!guessBlocked && n == GUESSES.length) {
            x.setCode(key, now);                                    // the right one now - too late
            guessBlocked = true;
            guessAt = k;
        }
        if (guessBlocked && guessAt > 0 && k == guessAt + 20) {
            check(d.probeBlocked(OWNER_NAME, AirDefenseSVA.vehKey(pGuess), guesser.getCommandSenderName(), now) && RoeMath.hostile(pic(pGuess)[0])
                    && pic(pGuess)[3] == RoeMath.ID_PROBE,
                    "ad code-only kp world (item 11): four wrong codes - the «ПОДБОР» block, the right code then still a target (" + pic(pGuess)[0] + ", "
                            + pic(pGuess)[3] + ")");
            guessAt = -1;
        }
    }

    // ---------------------------------------------------------------- the rest, by the clock

    private void later(World w, AirDefenseDataSVA d, int k, long now, int key) {
        Integer nid = Integer.valueOf(pNoCard.getEntityId());
        if (stage == 4 && began < 0 && engaged(pNoCard)) {
            began = gun.engagements().get(nid)[0];
            check(RoeMath.hostile(pic(pNoCard)[0]) && pic(pNoCard)[4] == RoeMath.ID_NO_CARD && began - armTick >= ConfigSVA.adCodeOnlyConfirmSeconds * 20 - 5
                    && RoeMath.hostile(pic(pStolen)[0]),
                    "ad code-only kp world (items 2, 5, 30): the owner's machine without a card and the stolen one - targets after two misses ("
                            + (began - armTick) + " ticks after arming), engaged");
        }
        if (stage == 4 && began >= 0 && now >= began + 450) {
            long[] st = gun.engagements().get(nid);
            check(st != null && st[1] < RoeMath.ST_FIRE, "ad code-only kp world (item 13): 22 s into the engagement - still no fire stage (20 s + the message)");
            stage = 5;
        } else if (stage == 5 && now >= began + 530) {
            long[] st = gun.engagements().get(nid);
            ready20 = st != null && st[1] == RoeMath.ST_FIRE && gun.debugReady(pNoCard.getEntityId());
            check(ready20, "ad code-only kp world (items 2, 13): the owner's machine without a card - at «Огонь», nothing forbidding it (fire mode)");
            // item 14: the card's code at the last moment - the silent look before the burst lets it go
            gun.debugShoot(pNoCard.getEntityId());
            boolean still = engaged(pNoCard);
            AirDefenseDataSVA kd = AirDefenseDataSVA.get(w);
            long per = AirDefenseDataSVA.keyPeriod(w);
            pNoCard.state.xpdr.takeCard(key, OWNER_NAME, kd.gen(OWNER_NAME), per, per + 3, per, now);
            gun.debugShoot(pNoCard.getEntityId());
            check(still && !engaged(pNoCard) && !gun.debugReady(pNoCard.getEntityId()),
                    "ad code-only kp world (item 14): no code - the look before the burst keeps it; the card's code at the last moment - let go, no shot");
            stage = 6;
        }
        if (k == 400) {
            check(RoeMath.hostile(pic(pEm)[0]) && pic(pEm)[4] == RoeMath.ID_EMERGENCY_OVER && gun.debugWanted(pEm.getEntityId()),
                    "ad code-only kp world (item 7; decision 2): 7700 past its grace - a target (" + pic(pEm)[0] + ", " + pic(pEm)[4] + "), the gun's rule wants it");
            kpC.command(owner, cmd("emFriend", pEm));
        } else if (k == 420) {
            NoRadioMath.Entry e = d.emergencyEntry(OWNER_NAME, AirDefenseSVA.vehKey(pEm));
            check(e != null && e.decision == NoRadioMath.D_FRIEND && pic(pEm)[0] == RoeMath.FRIEND && logged(kpC, "sva.ad.log.emFriend"),
                    "ad code-only kp world (items 7, 31): post C's «Свой» on the 7700 - a friend at the gun of post A too (one memory per owner)");
            kpC.command(owner, cmd("emHostile", pEm));
        } else if (k == 440) {
            NoRadioMath.Entry e = d.emergencyEntry(OWNER_NAME, AirDefenseSVA.vehKey(pEm));
            check(e != null && e.decision == NoRadioMath.D_HOSTILE && pic(pEm)[0] != RoeMath.FRIEND && logged(kpC, "sva.ad.log.emHostile"),
                    "ad code-only kp world (item 7): «Враждебный» - no friend, no grace");
            kpC.command(owner, cmd("unlock", pGuess));
        } else if (k == 460) {
            check(!d.probeBlocked(OWNER_NAME, AirDefenseSVA.vehKey(pGuess), guesser.getCommandSenderName(), now) && pic(pGuess)[0] == RoeMath.FRIEND
                    && logged(kpC, "sva.ad.log.coUnlock"),
                    "ad code-only kp world (item 11): post C's «Снять» lifts the «ПОДБОР» block - the right code a friend again (" + pic(pGuess)[0] + ")");
            AirDefenseSVA.Contact c = kpC.tracks().get(Integer.valueOf(pHand.getEntityId()));
            NBTTagCompound snap = kpC.snapshot();
            boolean trackCo = false;
            for (int i = 0; i < snap.getTagList("Tracks", 10).tagCount(); i++) {
                NBTTagCompound tc = snap.getTagList("Tracks", 10).getCompoundTagAt(i);
                trackCo |= tc.getBoolean("Co") && tc.getInteger("Id") == pNoCard.getEntityId() && tc.getByte("Rel") == 1;
            }
            check(c != null && c.cls == RoeMath.FRIEND && c.idr == RoeMath.ID_KEY && snap.getByte("Co") == CodeOnlyMath.CO_ON && trackCo,
                    "ad code-only kp world (item 39): post C's tracks by the code - the guest's card a friend; the picture has the mode and each track's reason");
            // review 2026-10-27 (batch 3, item 11): the operator's «Свой» on a 7700 lifts «ПОДБОР» too - the guesser blocked again,
            // a 7700 memory for him, post C's «Свой»
            String gv = AirDefenseSVA.vehKey(pGuess), gp = guesser.getCommandSenderName();
            for (int g = 0; g < Math.max(1, ConfigSVA.adCodeOnlyProbeCodes) + 1; g++) {
                d.probeWrong(OWNER_NAME, gv, gp, 01111 + g * 0101, now, ConfigSVA.adCodeOnlyProbeCodes, Math.max(1, ConfigSVA.adCodeOnlyProbeMinutes) * 1200L);
            }
            boolean reblocked = d.probeBlocked(OWNER_NAME, gv, gp, now);
            d.emergency(OWNER_NAME, gv, true, now);
            kpC.command(owner, cmd("emFriend", pGuess));
            NoRadioMath.Entry ge = d.emergencyEntry(OWNER_NAME, gv);
            check(reblocked && !d.probeBlocked(OWNER_NAME, gv, gp, now) && ge != null && ge.decision == NoRadioMath.D_FRIEND
                    && kpC.tracks().get(Integer.valueOf(pGuess.getEntityId())) != null,
                    "ad code-only kp world (review batch 3, item 11): post C's «Свой» on a blocked aircraft's 7700 lifts «ПОДБОР» (" + reblocked + ")");
            check(sawOwnLog && sawStrangerLog, "ad code-only kp world (items 30, 33): post C's log - «не опознан ... борт ВЛАДЕЛЬЦА» (" + sawOwnLog
                    + "), «верный код у чужого борта» (" + sawStrangerLog + ")");
        }
        if (stage == 6) {
            ConfigSVA.adCodeOnly = false;                           // item 28: the server forbids the mode
            banned = true;
            at = k;
            stage = 7;
        } else if (stage == 7 && k >= at + 15) {
            boolean usual = pic(pNoCard)[0] == RoeMath.OWN && pic(pNoCard)[3] == RoeMath.ID_NONE && !engaged(pNoCard) && !kpA.codeOnly();
            ConfigSVA.adCodeOnly = codeOnly0;
            banned = false;
            check(usual, "ad code-only kp world (item 28): the server's codeOnly=false - the saved code-only post works the usual way (the owner's machine OWN)");
            at = k;
            stage = 8;
        } else if (stage == 8 && k >= at + 15 && k > 470) {
            // item 15: post A broken - its data and zones go, the launcher loses the link: the usual mode (in doubt, less fire)
            String key0 = kpA.key();
            kpA.noDrop = true;
            w.setBlock(kpA.xCoord, kpA.yCoord, kpA.zCoord, Blocks.air, 0, 3);
            check(d.info(key0, false) == null, "ad code-only kp world (item 15): the broken post's trust data is gone");
            at = k;
            stage = 9;
        } else if (stage == 9 && k >= at + 15) {
            check(gun.kpLink().length() == 0 && pic(pNoCard)[0] == RoeMath.OWN && pic(pNoCard)[3] == RoeMath.ID_NONE && !engaged(pNoCard),
                    "ad code-only kp world (item 15): post A broken - the gun unlinked, the usual mode: the owner's machine spared");
            check(!cardEngaged, "ad code-only kp world: the friend with a card was never engaged");
            phase = 9;
        }
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

    private static void give(FakePlayer p, ItemStack s) {
        for (int i = 0; i < p.inventory.mainInventory.length; i++) {
            p.inventory.mainInventory[i] = null;
        }
        p.inventory.mainInventory[5] = s;
    }

    private EntityVehicleSVA plane(World w, int i, String ownerId, String ownerName, FakePlayer pilot, boolean dayKey, int code) {
        double a = Math.PI * 2 * i / 8, gx = x0 + 10.5, gz = z0 + 10.5, r = i == 0 ? 16 : i % 2 == 0 ? 21 : 36;
        double x = gx + Math.cos(a) * r, z = gz + Math.sin(a) * r;
        VehicleState st = new VehicleState(VehicleType.PLANE);
        st.energy = st.maxEnergy();
        EntityVehicleSVA v = ItemVehicleSVA.spawn(w, ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.PLANE.ordinal()), st), x, FLY, z,
                0F, ownerId, ownerName);
        if (v != null) {
            planes.add(v);
            hold.add(new double[] {x, FLY, z});
            v.state.xpdr.mode = XpdrMath.C;
            v.state.xpdr.dayKey = dayKey;
            v.state.xpdr.setCode(code, w.getTotalWorldTime());
            give(pilot, null);
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
        x0 = (sp.posX + 13800) >> 4 << 4;
        z0 = (sp.posZ - 13800) >> 4 << 4;
        keepLoaded(w);
        codeOnly0 = ConfigSVA.adCodeOnly;
        w.getWorldInfo().setRaining(false);
        w.getWorldInfo().setThundering(false);
        area(w, true);
        kpA = block(w, x0, z0, ModContentSVA.commandPost, 2, TileEntityCommandPostSVA.class);
        gun = block(w, x0 + 10, z0 + 10, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, TileEntityLauncherSVA.class);
        kpC = block(w, x0 + 90, z0 + 10, ModContentSVA.commandPost, 2, TileEntityCommandPostSVA.class);
        radar = block(w, x0 + 96, z0 + 16, ModContentSVA.radar, RadarMath.SURVEY, TileEntityRadarSVA.class);
        check(kpA != null && gun != null && kpC != null && radar != null, "ad code-only kp world: two command posts, a gun and a surveillance radar placed");
        if (kpA == null || gun == null || kpC == null || radar == null) {
            kpA = null;
            return;
        }
        owner = player(w, OWNER_NAME);
        friend = player(w, "TestADKfr");
        man = player(w, "TestADKman");
        thief = player(w, "TestADKthief");
        emp = player(w, "TestADKem");
        guesser = player(w, "TestADKg");
        guest = player(w, "TestADKguest");
        sil = player(w, "TestADKsil");
        kpA.debugSetup(SECRET, new int[] {01234}, 0, null);         // the owner's secret (one for both posts)
        kpC.debugSetup(SECRET, null, 0, null);
        NBTTagCompound set = new NBTTagCompound();                  // the names and post A's access list, as the screen sends them
        set.setString("Op", "set");
        set.setString("Name", "KA");
        set.setBoolean("DayKey", true);
        set.setIntArray("Codes", new int[] {01234});
        net.minecraft.nbt.NBTTagList al = new net.minecraft.nbt.NBTTagList();
        al.appendTag(new net.minecraft.nbt.NBTTagString("TestADKfr"));
        set.setTag("Access", al);
        kpA.command(owner, set);
        set.setString("Name", "KC");
        set.setIntArray("Codes", new int[0]);
        set.setTag("Access", new net.minecraft.nbt.NBTTagList());
        kpC.command(owner, set);
        ZoneMath.Zone z = new ZoneMath.Zone();
        z.name = "KTA";
        z.type = ZoneMath.FORBIDDEN;
        z.shape = ZoneMath.CIRCLE;
        z.cx = x0 + 10;
        z.cz = z0 + 10;
        z.r = 26;
        z.alarm = false;
        List<ZoneMath.Zone> zs = new ArrayList<ZoneMath.Zone>();
        zs.add(z);
        kpA.setZones(zs);
        gun.setKpLink(kpA.key());                                   // its post - unpowered (its trust data)
        gun.debugSettings(RoeMath.FOREIGN_ALL, true, false, 2);
        gun.debugFireMode = true;                                   // no ammunition: the fire stage is reached, nothing is shot
        long now = w.getTotalWorldTime();
        int key = AirDefenseDataSVA.keyCodeNow(w, OWNER_NAME);
        // in the zone (even slots): the owner's machine nearest the gun (16), the stolen one, the 7700, the silent stranger (21);
        // outside it (odd slots, 36 - the gun's eyes still have them): the friend's card, the code by hand, the guesser, the guest
        pNoCard = plane(w, 0, OWNER, OWNER_NAME, owner, true, XpdrMath.VFR);
        pCard = plane(w, 1, OWNER, OWNER_NAME, friend, true, XpdrMath.VFR);
        pStolen = plane(w, 2, OWNER, OWNER_NAME, thief, true, XpdrMath.VFR);
        pManual = plane(w, 3, STRANGER, "TestADKman", man, false, key);
        pEm = plane(w, 4, STRANGER, "TestADKem", emp, false, XpdrMath.EMERGENCY);
        pGuess = plane(w, 5, STRANGER, "TestADKg", guesser, false, XpdrMath.VFR);
        pSilent = plane(w, 6, STRANGER, "TestADKsil", sil, false, XpdrMath.VFR);
        pHand = plane(w, 7, STRANGER, "TestADKguest", guest, true, XpdrMath.VFR);
        if (pManual != null) {
            pManual.state.xpdr.codeSince = now - CodeOnlyMath.PROBE_STABLE_TICKS;   // review 2026-10-27: dialled before the test, stood 3 s
        }
        if (planes.size() != 8) {
            check(false, "ad code-only kp world: the test aircraft placed");
            kpA = null;
            return;
        }
        ItemStack c1 = new ItemStack(ModContentSVA.codeCard), c2 = new ItemStack(ModContentSVA.codeCard);
        kpA.printCardForTest(c1);
        kpA.printCardForTest(c2);
        give(friend, c1);
        give(guest, c2);
        // the stolen machine: its owner's card set the code before; the thief boards without one
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        long per = AirDefenseDataSVA.keyPeriod(w);
        pStolen.state.xpdr.takeCard(key, OWNER_NAME, d.gen(OWNER_NAME), per, per + 3, per, now);
        // the owner's tower hands the guest and the silent stranger over to the air defence
        AirDefenseSVA.INSTANCE.handoff(w, AirDefenseSVA.vehKey(pHand), "twr:testADK", OWNER_NAME, "TKPA", now + 6000);
        AirDefenseSVA.INSTANCE.handoff(w, AirDefenseSVA.vehKey(pSilent), "twr:testADK", OWNER_NAME, "TKPA", now + 6000);
    }

    private void area(World w, boolean build) {
        for (int x = x0 - 30; x <= x0 + 110; x++) {
            for (int z = z0 - 30; z <= z0 + 50; z++) {
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
        for (int cx = (x0 - 40) >> 4; cx <= (x0 + 120) >> 4; cx++) {
            for (int cz = (z0 - 40) >> 4; cz <= (z0 + 60) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void finish(World w) {
        if (banned) {
            ConfigSVA.adCodeOnly = codeOnly0;
            banned = false;
        }
        for (FakePlayer p : new FakePlayer[] {owner, friend, man, thief, emp, guesser, guest, sil}) {
            if (p != null) {
                give(p, null);
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
        for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {kpA, kpC, gun, radar}) {
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
            for (TileEntityCommandPostSVA k : new TileEntityCommandPostSVA[] {kpA, kpC}) {
                if (k != null) {
                    dd.removeKp(k.key());
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
            if (banned) {
                ConfigSVA.adCodeOnly = codeOnly0;
            }
            done = true;
        }
    }
}
