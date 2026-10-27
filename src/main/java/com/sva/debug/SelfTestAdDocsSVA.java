package com.sva.debug;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;

import com.sva.airdefense.CodeOnlyMath;
import com.sva.airdefense.NoRadioMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;
import com.sva.block.TileEntityAdPartSVA;
import com.sva.item.ItemAmmoSVA;
import com.sva.net.NetADSVA;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.world.World;

/**
 * Audit 2026-10-22 self-test (docs, tests and consistency) - the dedicated server runs it:
 * <ul>
 * <li>7.4: Shift+RMB with ammunition reaches the launcher - ItemAmmoSVA overrides Forge's doesSneakBypassUse (without it the
 * block's code for it was dead);</li>
 * <li>7.3 / 7.4: a part's master link goes into its description packet and back (the client's pick-block and right-click
 * need it; an unlinked part stays unlinked);</li>
 * <li>7.5: NetADSVA's Cmd and Data through a byte buffer and back - every op / kind fits its byte, the NBT payload is the
 * same (lists, int arrays, strings with Cyrillic);</li>
 * <li>4.1 / 7.4: the «never fired at» rules as the docs now state them - 7700 / own codes / the white list are blocked even
 * with airdefense.fireOnFriends on (the access list's vehicles are not then), protected regions only while
 * fireInProtectedRegions is off; the launcher's hint, the transponder's hints and the book say so (and name
 * vehicles.wear, vehicles.tuning and the loaded chunks).</li>
 * <li>code-only wave (docs/proposal-ad-strict-iff.md items 8, 9, 13, 29, 43): the rules the book now states for «ПО КОДУ» -
 * nobody's machine is spared (fireOnFriends either way), the server's other bans stay (warnings only, PvP, unmanned, private
 * fields, the spawn), the 20 s floor, 7500 a target, 7600 in its grace, 7700 in its grace and never a target with
 * codeOnlyEmergencySeconds = 0, a reply in mode S as good as A / C; the book's first note no longer says «never» for every
 * mode, the new section «Режим ПО КОДУ» (its note, the post / launcher table, «Забыли карту», «Сломан КП») in ru and en,
 * the 7700 wording of the aviation chapter and the transponder's tip, every new server key in the settings chapter.</li>
 * </ul>
 */
final class SelfTestAdDocsSVA {

    private SelfTestAdDocsSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws Exception {
        sneakBypass();
        partLink();
        net();
        rules();
        docs();
        codeOnlyRules();
        codeOnlyDocs();
    }

    // ---------------------------------------------------------------- 7.4: Shift+RMB with ammunition

    private static void sneakBypass() {
        boolean declared;
        try {
            declared = ItemAmmoSVA.class.getDeclaredMethod("doesSneakBypassUse", World.class, int.class, int.class, int.class, EntityPlayer.class) != null;
        } catch (NoSuchMethodException e) {
            declared = false;
        }
        check(declared && ItemAmmoSVA.launcherAt(null, 0, 64, 0) == null,
                "ad docs (7.4): the ammunition overrides doesSneakBypassUse - Shift+RMB reaches the launcher's block (no world: no launcher)");
    }

    // ---------------------------------------------------------------- 7.3: the part's link on the client

    /**
     * The NBT a tile's description packet carries, read back from the packet's wire form (S35's own getter is client-only:
     * the dedicated server has no func_148857_g). {x, y, z} into `pos`.
     */
    static NBTTagCompound wireTag(S35PacketUpdateTileEntity p, int[] pos) throws java.io.IOException {
        PacketBuffer b = new PacketBuffer(Unpooled.buffer());
        p.writePacketData(b);
        pos[0] = b.readInt();
        pos[1] = b.readShort();
        pos[2] = b.readInt();
        b.readUnsignedByte();
        return b.readNBTTagCompoundFromBuffer();
    }

    private static void partLink() throws Exception {
        TileEntityAdPartSVA p = new TileEntityAdPartSVA();
        p.xCoord = 101;
        p.yCoord = 70;
        p.zCoord = -33;
        p.link(100, 69, -31, null);
        int[] pos = new int[3];
        NBTTagCompound wire = wireTag((S35PacketUpdateTileEntity) p.getDescriptionPacket(), pos);
        TileEntityAdPartSVA c = new TileEntityAdPartSVA();
        c.xCoord = 101;
        c.yCoord = 70;
        c.zCoord = -33;
        boolean before = c.isLinked();
        c.readLink(wire);
        TileEntityAdPartSVA u = new TileEntityAdPartSVA(), u2 = new TileEntityAdPartSVA();
        u2.readLink(wireTag((S35PacketUpdateTileEntity) u.getDescriptionPacket(), new int[3]));
        check(pos[0] == 101 && pos[1] == 70 && pos[2] == -33 && !before && c.isLinked() && c.masterX() == 100 && c.masterY() == 69 && c.masterZ() == -31
                && !u2.isLinked(),
                "ad docs (7.3): a part's master link goes to the client in its description packet (pick-block, right-click) - an unlinked one stays unlinked");
    }

    // ---------------------------------------------------------------- 7.5: NetADSVA through the wire

    private static NetADSVA.Cmd roundTrip(NetADSVA.Cmd m) {
        ByteBuf b = Unpooled.buffer();
        m.toBytes(b);
        NetADSVA.Cmd r = new NetADSVA.Cmd();
        r.fromBytes(b);
        return b.readableBytes() == 0 ? r : null;
    }

    private static NetADSVA.Data roundTrip(NetADSVA.Data m) {
        ByteBuf b = Unpooled.buffer();
        m.toBytes(b);
        NetADSVA.Data r = new NetADSVA.Data();
        r.fromBytes(b);
        return b.readableBytes() == 0 ? r : null;
    }

    private static void net() {
        NBTTagCompound d = new NBTTagCompound();
        d.setString("Op", "set");
        d.setString("Name", "КП-Север");
        d.setIntArray("Codes", new int[] {01234, -1, 07000});
        d.setIntArray("WB", new int[] {23, 42});
        NBTTagList wo = new NBTTagList();
        wo.appendTag(new NBTTagString("Алиса"));
        wo.appendTag(new NBTTagString(""));
        d.setTag("WO", wo);
        d.setString("Trust", "UBTW:Bob; UUEE");
        boolean ops = true;
        int[] all = {NetADSVA.XPDR_SET, NetADSVA.XPDR_IDENT, NetADSVA.KP_CMD, NetADSVA.KP_CLOSE, NetADSVA.LAUNCHER_SET, NetADSVA.AD_EQUIP,
            NetADSVA.KP_OPEN, NetADSVA.KP_LINK};
        for (int op : all) {
            NetADSVA.Cmd r = roundTrip(new NetADSVA.Cmd(op, -29999999, 255, 29999999, 123456, d));
            ops &= r != null && r.op == op && r.x == -29999999 && r.y == 255 && r.z == 29999999 && r.entity == 123456 && r.data.equals(d);
        }
        NetADSVA.Cmd empty = roundTrip(new NetADSVA.Cmd(NetADSVA.KP_CLOSE, 1, 2, 3, 0, null));
        check(ops && empty != null && empty.data != null && empty.data.hasNoTags(),
                "ad docs (7.5): NetADSVA.Cmd through a byte buffer - every op, the position, the entity and the NBT (Cyrillic, int arrays, lists) back as sent");
        boolean kinds = true;
        int[] dk = {NetADSVA.D_KP, NetADSVA.D_LAUNCHER, NetADSVA.D_XPDR, NetADSVA.D_RWR, NetADSVA.D_ZONES, NetADSVA.D_MSG, NetADSVA.D_EQUIP, NetADSVA.D_COVER};
        NBTTagCompound cov = new NBTTagCompound();
        byte[] g = new byte[128 * 128 / 4];
        for (int i = 0; i < g.length; i++) {
            g[i] = (byte) (i * 37 + (i >> 5));
        }
        cov.setByteArray("G", g);
        cov.setInteger("A", 60);
        for (int k : dk) {
            NetADSVA.Data r = roundTrip(new NetADSVA.Data(k, k == NetADSVA.D_COVER ? cov : d));
            kinds &= r != null && r.kind == k && r.data.equals(k == NetADSVA.D_COVER ? cov : d);
        }
        check(kinds && all.length == 8 && NetADSVA.KP_LINK < 128 && NetADSVA.D_COVER < 128,
                "ad docs (7.5): NetADSVA.Data through a byte buffer - every kind back as sent, a 4 KB coverage map too; the op / kind numbers fit their byte");
    }

    // ---------------------------------------------------------------- 4.1 / 7.4: the rules as documented

    private static void rules() {
        // a friend's vehicle squawking 7700 / an own code with fireOnFriends ON (ownBlocked false): still the «special» block
        int b7700 = RoeMath.fireBlock(true, false, false, false, false, false, true, false, true);
        // a friend's vehicle with fireOnFriends OFF: own-blocked; ON and nothing special: fire allowed
        int bOff = RoeMath.fireBlock(true, false, false, true, false, false, false, false, true);
        int bOn = RoeMath.fireBlock(true, false, false, false, false, false, false, false, true);
        int bProt = RoeMath.fireBlock(true, false, true, false, false, false, false, false, true);
        int bOff2 = RoeMath.fireBlock(false, false, false, false, false, false, false, false, true);
        check(b7700 == RoeMath.B_SPECIAL && bOff == RoeMath.B_OWN && bOn == RoeMath.OK && bProt == RoeMath.B_PROTECTED && bOff2 == RoeMath.B_DISABLED
                && RoeMath.roeWithoutKp(RoeMath.FOREIGN_ALL) == RoeMath.VIOLATORS && RoeMath.roeWithoutKp(RoeMath.KP_MARKS) == RoeMath.VIOLATORS
                && !RoeMath.roeWants(RoeMath.VIOLATORS, RoeMath.FOREIGN, true, true),
                "ad docs (4.1, 7.4): 7700 / own codes / the white list never fired at even with fireOnFriends on; friends only while it is off; "
                        + "protected regions only while fireInProtectedRegions is off; enabled=false blocks all; no KP - «по нарушителям»; an assignment "
                        + "binds only a launcher on «по отметкам КП»");
    }

    // ---------------------------------------------------------------- 4.1 / 7.4: what the docs say

    private static String resource(String path) throws Exception {
        InputStream in = SelfTestAdDocsSVA.class.getResourceAsStream(path);
        if (in == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                b.append(line).append('\n');
            }
        } finally {
            r.close();
        }
        return b.toString();
    }

    private static void docs() throws Exception {
        Map<String, String> ru = LangKeysSVA.map(LangKeysSVA.RU), en = LangKeysSVA.map(LangKeysSVA.EN);
        boolean hint = true;
        for (Map<String, String> m : new Map[] {ru, en}) {
            String always = m.get("sva.ad.l.always"), tip = m.get("sva.vmenu.xpdr.tip"), xh = m.get("sva.ad.xpdr.hint");
            hint &= always != null && always.contains("fireOnFriends") && always.contains("fireInProtectedRegions") && tip != null && tip.contains("7500")
                    && xh != null && xh.contains("7700");
        }
        check(hint, "ad docs (4.1, 7.4): the launcher's «never fired at» hint names the server's exceptions (fireOnFriends, fireInProtectedRegions), "
                + "the transponder's hints the board's power and 7500 (ru, en)");
        boolean book = true;
        StringBuilder miss = new StringBuilder();
        for (String lang : new String[] {"ru", "en"}) {
            String b = resource("/assets/siliconagevehicles/book/" + lang + "/airdefense.txt");
            String[] need = {"airdefense.fireOnFriends", "vehicles.wear", "vehicles.tuning", "damageMultiplier", "Shift+",
                lang.equals("ru") ? "прогруженных чанках" : "loaded chunks", lang.equals("ru") ? "задаются на каждой ПУ" : "set on each launcher"};
            for (String s : need) {
                if (!b.contains(s)) {
                    book = false;
                    miss.append(' ').append(lang).append(':').append(s);
                }
            }
            book &= !b.contains(lang.equals("ru") ? "только по цели РЛС сопровождения" : "only at a tracking radar's target");
        }
        check(book, "ad docs (4.1, 7.4): the book's air-defence chapter - the friends' exception, the damage by vehicles.wear, the RWR by vehicles.tuning, "
                + "loaded chunks, Shift+RMB, rules on each launcher, the missile's own eyes" + miss);
    }

    // ---------------------------------------------------------------- code-only wave: the rules as the book states them

    /**
     * Items 2, 8, 9, 13, 29 as the book's «Режим ПО КОДУ» pages put them: the classes come from RoeMath.classifyByCode (the
     * verdict table itself is SelfTestAdCodeOnlySVA's) - here what a reader is promised: whose machine it is never holds fire
     * in the mode, every other ban of the server still does, and the special codes behave as written.
     */
    private static void codeOnlyRules() {
        // item 2: the owner's / his list's machine - not spared in the mode, whatever fireOnFriends says; the usual mode as before
        boolean spared = RoeMath.ownBlocked(true, 1, false) || RoeMath.ownBlocked(true, 2, false) || RoeMath.ownBlocked(true, 1, true);
        boolean usual = RoeMath.ownBlocked(false, 1, true) && RoeMath.ownBlocked(false, 2, false) && !RoeMath.ownBlocked(false, 2, true);
        // item 29: the server's other bans on the owner's own machine without the code (ownBlocked false in the mode)
        boolean own = RoeMath.ownBlocked(true, 1, false);
        int unmanned = RoeMath.fireBlock(true, false, false, own, false, true, false, false, true);
        int warnOnly = RoeMath.fireBlock(true, false, false, own, false, false, false, true, true);
        int pvp = RoeMath.fireBlock(true, false, false, own, true, false, false, false, true);
        int prot = RoeMath.fireBlock(true, false, true, own, false, false, false, false, true);
        int spawn = RoeMath.fireBlock(true, true, false, own, false, false, false, false, true);
        int off = RoeMath.fireBlock(false, false, false, own, false, false, false, false, true);
        int fire = RoeMath.fireBlock(true, false, false, own, false, false, false, false, true);
        check(!spared && usual && unmanned == RoeMath.B_UNMANNED && warnOnly == RoeMath.B_WARN_ONLY && pvp == RoeMath.B_PVP && prot == RoeMath.B_PROTECTED
                && spawn == RoeMath.B_SPAWN && off == RoeMath.B_DISABLED && fire == RoeMath.OK,
                "ad docs (code-only, items 2, 29): in the mode no machine is spared for whose it is (fireOnFriends either way); the server's other bans "
                        + "stay - unmanned, warnings only, PvP, private fields, the spawn, enabled=false; the owner's manned machine without the code - fire allowed");
        // item 13: the floor - the same for everybody, never under the server's own minimum
        check(CodeOnlyMath.minWarning(5, true, 20) == 20 && CodeOnlyMath.minWarning(30, true, 20) == 30 && CodeOnlyMath.minWarning(5, false, 20) == 5,
                "ad docs (code-only, item 13): the wait to fire in the mode at least codeOnlyMinWarningSeconds (20), the usual mode keeps minWarningSeconds");
        // item 8: 7500 - a target once two misses confirm it (the post's alarm is the usual one); 7600 in its grace - NO_RADIO
        int key = 01234;
        int idHj = CodeOnlyMath.identify(true, XpdrMath.HIJACK, key, -1, false, CodeOnlyMath.CARD_NONE, false, true, false, false);
        int hj = RoeMath.classifyByCode(true, false, false, true, XpdrMath.HIJACK, -1, false, idHj, true, false);
        int hj1 = RoeMath.classifyByCode(true, false, false, true, XpdrMath.HIJACK, -1, false, idHj, false, false);
        int idNr = CodeOnlyMath.identify(true, XpdrMath.NO_RADIO, key, -1, false, CodeOnlyMath.CARD_NONE, false, true, false, false);
        int nr = RoeMath.classifyByCode(true, false, false, true, XpdrMath.NO_RADIO, -1, true, idNr, true, false);
        int nrOver = RoeMath.classifyByCode(true, false, false, true, XpdrMath.NO_RADIO, -1, false, idNr, true, true);
        check(RoeMath.idClass(hj) == RoeMath.FOREIGN && RoeMath.idReason(hj) == RoeMath.ID_HIJACK && RoeMath.idClass(hj1) == RoeMath.UNKNOWN
                && RoeMath.idClass(nr) == RoeMath.NO_RADIO && RoeMath.idReason(nr) == RoeMath.ID_NO_RADIO
                && RoeMath.idClass(nrOver) == RoeMath.VIOLATOR && RoeMath.idReason(nrOver) == RoeMath.ID_NO_RADIO_OVER,
                "ad docs (code-only, item 8): 7500 - «ПРОВ», then a target (hijack); 7600 in its grace - NO RADIO, past it in a zone - a violator");
        // item 7 / decision 2: 7700 in its grace - EMERGENCY; codeOnlyEmergencySeconds = 0 - never a target («по 7700 не стрелять никогда»)
        NoRadioMath.Entry e = new NoRadioMath.Entry(1000);
        int stIn = CodeOnlyMath.emergencyState(e, 1000 + 100, 180 * 20), stOver = CodeOnlyMath.emergencyState(e, 1000 + 181 * 20, 180 * 20);
        int stZero = CodeOnlyMath.emergencyState(e, 1000 + 99999, 0);
        int idEm = CodeOnlyMath.identify(true, XpdrMath.EMERGENCY, key, -1, false, CodeOnlyMath.CARD_NONE, false, true, false, false);
        int emIn = RoeMath.classifyByCode(true, false, false, true, XpdrMath.EMERGENCY, stIn, false, idEm, true, false);
        int emOver = RoeMath.classifyByCode(true, false, false, true, XpdrMath.EMERGENCY, stOver, false, idEm, true, false);
        int emZero = RoeMath.classifyByCode(true, false, false, true, XpdrMath.EMERGENCY, stZero, false, idEm, true, true);
        check(RoeMath.idClass(emIn) == RoeMath.EMERGENCY && RoeMath.idClass(emOver) == RoeMath.FOREIGN && RoeMath.idReason(emOver) == RoeMath.ID_EMERGENCY_OVER
                && RoeMath.idClass(emZero) == RoeMath.EMERGENCY && CodeOnlyMath.emergencyLeft(e, 1000 + 99999, 0) == -1,
                "ad docs (code-only, item 7; decision 2): 7700 within its 180 s - EMERGENCY, past it - a target; codeOnlyEmergencySeconds = 0 - 7700 never a target");
        // item 9: a reply in mode S carries the same code - the key's code a match; standby / off - no reply
        int s = CodeOnlyMath.identify(XpdrMath.replies(XpdrMath.S), key, key, -1, false, CodeOnlyMath.CARD_NONE, false, true, false, false);
        int c = CodeOnlyMath.identify(XpdrMath.replies(XpdrMath.C), key, key, -1, false, CodeOnlyMath.CARD_NONE, false, true, false, false);
        int stby = CodeOnlyMath.identify(XpdrMath.replies(XpdrMath.STBY), key, key, -1, false, CodeOnlyMath.CARD_NONE, false, true, false, false);
        int sNo = CodeOnlyMath.identify(XpdrMath.replies(XpdrMath.S), XpdrMath.VFR, key, -1, false, CodeOnlyMath.CARD_NONE, false, true, false, false);
        check(s == RoeMath.ID_KEY && c == RoeMath.ID_KEY && stby == RoeMath.ID_NO_REPLY && sNo == RoeMath.ID_NO_CARD && XpdrMath.givesBoard(XpdrMath.S),
                "ad docs (code-only, item 9): mode S answers with the code like A / C (the key's code - a match), its board number makes no friend; STBY - no reply");
    }

    /** Item 43: what the book, the transponder's tip and the settings chapter say about the mode (ru and en). */
    private static void codeOnlyDocs() throws Exception {
        StringBuilder miss = new StringBuilder();
        String[] keys = {"codeOnly", "codeOnlyManual", "codeOnlyArmSeconds", "codeOnlyConfirmSeconds", "codeOnlyMinWarningSeconds",
            "codeOnlyEmergencySeconds", "codeOnlyOverlapSeconds", "codeCardMaxPeriods", "codeOnlyProbeCodes", "codeOnlyProbeMinutes", "keyClock",
            "allTransponders"};
        for (String lang : new String[] {"ru", "en"}) {
            boolean ru = lang.equals("ru");
            String b = resource("/assets/siliconagevehicles/book/" + lang + "/airdefense.txt");
            String av = resource("/assets/siliconagevehicles/book/" + lang + "/aviation.txt");
            String cf = resource("/assets/siliconagevehicles/book/" + lang + "/config.txt");
            String faq = resource("/assets/siliconagevehicles/book/" + lang + "/faq.txt");
            String wn = resource("/assets/siliconagevehicles/book/" + lang + "/whatsnew.txt");
            // the chapter's first note: the «never» rules belong to the usual mode now
            int n = b.indexOf("@note ");
            String note = n < 0 ? "" : b.substring(n, b.indexOf('\n', n) < 0 ? b.length() : b.indexOf('\n', n));
            boolean first = note.startsWith(ru ? "@note В обычном режиме" : "@note In the usual mode") && note.contains(ru ? "ПО КОДУ" : "CODE-ONLY")
                    && note.contains("180") && note.contains("airdefense.fireOnFriends");
            if (!first) {
                miss.append(' ').append(lang).append(":firstNote");
            }
            String[] need = ru
                    ? new String[] {"# Режим ПО КОДУ", "@note Без карты кодов ПВО в режиме ПО КОДУ стреляет и по вашим машинам", "## Режим КП и правило ПУ",
                        "@kv ПО КОДУ, по всем без кода", "@kv ПО КОДУ, по нарушителям", "@kv ПО КОДУ, по отметкам КП", "@kv ПО КОДУ, огонь запрещён",
                        "@kv ПУ без привязки к КП", "**Забыли карту.**", "**Сломан КП.**", "\"Сменить ключ\"", "ПОДБОР", "ОПОЗН", "180 с", "20 с",
                        "\"Опознавание\"", "keyClock"}
                    : new String[] {"# CODE-ONLY mode", "@note Without a code card a CODE-ONLY air defence fires at your vehicles too",
                        "## The KP's mode and the launcher's rule", "@kv CODE-ONLY, all without code", "@kv CODE-ONLY, violators",
                        "@kv CODE-ONLY, the KP's marks", "@kv CODE-ONLY, weapons hold", "@kv A launcher with no KP link", "**Forgot the card.**",
                        "**A broken KP.**", "\"Change the key\"", "GUESSING", "NO ID", "180 s", "20 s", "\"Identification\"", "keyClock"};
            for (String s : need) {
                if (!b.contains(s)) {
                    miss.append(' ').append(lang).append(':').append(s);
                }
            }
            for (String k : keys) {
                if (!cf.contains(k) || !b.contains(k)) {
                    miss.append(' ').append(lang).append(":key ").append(k);
                }
            }
            // the stale «7700 - never fired at» (the aviation chapter, the old card of the day)
            if (av.contains(ru ? "не стреляет никогда" : "never fires at it)") || !av.contains("180")) {
                miss.append(' ').append(lang).append(":aviation 7700");
            }
            if (b.contains(ru ? "номер игровых суток" : "the game day =") || b.contains(ru ? "Вчерашняя карта" : "Yesterday's card")) {
                miss.append(' ').append(lang).append(":old day key");
            }
            if (!faq.contains(ru ? "ПО КОДУ" : "CODE-ONLY") || !wn.contains(ru ? "ПО КОДУ" : "CODE-ONLY")) {
                miss.append(' ').append(lang).append(":faq/whatsnew");
            }
        }
        Map<String, String> ru = LangKeysSVA.map(LangKeysSVA.RU), en = LangKeysSVA.map(LangKeysSVA.EN);
        for (Map<String, String> m : new Map[] {ru, en}) {
            // review 2026-10-27 (batch 2): the grace is the server's (%s from sva.ad.em.graceFull), not a fixed 180
            String tip = m.get("sva.vmenu.xpdr.tip"), full = m.get("sva.ad.em.graceFull");
            if (tip == null || !tip.contains("%s") || full == null || !String.format(tip, String.format(full, "180")).contains("180")
                    || !tip.contains(m == ru ? "ПО КОДУ" : "CODE-ONLY") || tip.contains("never fired at while")
                    || tip.contains("ПВО не стреляет, пока")) {
                miss.append(m == ru ? " ru" : " en").append(":xpdr.tip");
            }
        }
        check(miss.length() == 0, "ad docs (code-only, item 43): the book's first note (the usual mode; CODE-ONLY - 180 s), the section «Режим ПО КОДУ» "
                + "(note, post / launcher table, «Забыли карту», «Сломан КП»), every new server key in the settings and air-defence chapters, "
                + "the aviation chapter's and the transponder tip's 7700, faq and what's new (ru, en)" + miss);
    }
}
