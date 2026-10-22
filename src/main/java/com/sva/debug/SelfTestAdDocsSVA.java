package com.sva.debug;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;

import com.sva.airdefense.RoeMath;
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
}
