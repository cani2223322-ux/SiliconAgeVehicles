package com.sva.vehicle;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Wave 2, ★10: who may do what with a vehicle - the rights matrix (pure) and the vehicle's access list (kept in
 * VehicleState, NBT key "Access", survives the item conversion).
 *
 * Rights: DRIVE (the driver's seat), PASSENGER (a passenger seat), CARGO (the cargo screen: Shift+RMB / the L key /
 * sort / junk), REPAIR (repairs in the garage). The owner always has all; picking the vehicle up, the access screen and
 * tuning stay owner-only (EntityVehicleSVA.canManage). Lock modes: OPEN (everyone: all four rights), FRIENDS (the
 * players on the list get their ticks, everyone else the «Все остальные» row, a valid key card adds the rights written
 * on it), OWNER (nobody but the owner; cards ignored). A key card is valid while its lock version equals the vehicle's
 * («Сменить замок» bumps it). Server ops pass any lock with vehicles.lockOpBypass.
 *
 * ТН-1: every row carries an issue number (Issue). «+ Добавить» and «Выдать ключ-карту» give it a new one from the list's
 * never-reused counter (IssueSeq), and a friend's card (holder != the owner written on it) opens only while its holder
 * is still listed with the number written on the card: «Удалить» or a newer card for that player revokes just his old
 * card. The owner's card is checked by the lock version alone. Old rows / cards have no number (0) and still match.
 * ТН-4: a row added while the player was online also keeps his UUID (Id); such a row is found by the UUID (its name
 * follows the player's renames: refresh), rows without one by the name, as before.
 *
 * Old saves: a vehicle saved before wave 2 (its NBT has an id but no "Access") gets LEGACY_OTHERS - anyone may sit as a
 * passenger, only the owner drives / opens the cargo, which is exactly how it worked before. A new vehicle starts in
 * FRIENDS with nobody else allowed (NEW_OTHERS). With vehicles.locks off nothing here is consulted: the old rules hold.
 */
public final class AccessMath {

    public static final int DRIVE = 1, PASSENGER = 2, CARGO = 4, REPAIR = 8, ALL = 15;
    public static final int OPEN = 0, FRIENDS = 1, OWNER = 2, MODES = 3;
    public static final int LEGACY_OTHERS = PASSENGER, NEW_OTHERS = 0;
    public static final int MAX_FRIENDS = 12, MAX_NAME = 16;

    private AccessMath() {
    }

    /** The rights a card or row gives once normalised: driving implies a seat; unknown bits dropped. */
    public static int norm(int r) {
        r &= ALL;
        return (r & DRIVE) != 0 ? r | PASSENGER : r;
    }

    /**
     * The rights of one player.
     * @param locks  vehicles.locks
     * @param owner  the player owns it (or it has no owner)
     * @param op     a server op and vehicles.lockOpBypass
     * @param mode   OPEN / FRIENDS / OWNER
     * @param listed the player's row on the list, -1 if not listed
     * @param others the «Все остальные» row
     * @param card   rights of a valid key card the player carries, -1 if none
     */
    public static int rights(boolean locks, boolean owner, boolean op, int mode, int listed, int others, int card) {
        if (owner) {
            return ALL;
        }
        if (!locks) {
            return PASSENGER;                               // the rules before wave 2
        }
        if (op) {
            return ALL;
        }
        if (mode == OPEN) {
            return ALL;
        }
        if (mode == OWNER) {
            return 0;
        }
        int r = listed >= 0 ? listed : others;
        if (card >= 0) {
            r |= card;
        }
        return norm(r);
    }

    /** A card's rights if it opens this vehicle's lock now (same vehicle, same lock version), else -1. */
    public static int cardRights(String cardVeh, int cardLock, int cardRights, String vehId, int lockVersion) {
        if (cardVeh == null || vehId == null || !cardVeh.equals(vehId) || cardLock != lockVersion) {
            return -1;
        }
        return norm(cardRights);
    }

    /** A player name that may go on the list: 1..16 of letters, digits and '_'. */
    public static boolean validName(String n) {
        if (n == null || n.length() == 0 || n.length() > MAX_NAME) {
            return false;
        }
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /** The access list of one vehicle. */
    public static final class Access {
        public int mode = FRIENDS;
        public int others = NEW_OTHERS;
        public int lockVersion;
        public final List<String> names = new ArrayList<String>();
        public final List<Integer> rights = new ArrayList<Integer>();
        /** ТН-4: the row's player UUID ("" - unknown: found by the name). */
        public final List<String> ids = new ArrayList<String>();
        /** ТН-1: the row's issue number (0 - an old row: only old cards without a number match it). */
        public final List<Integer> issues = new ArrayList<Integer>();
        /** ТН-1: the last issue number given out (never reused, so a removed and re-added player's old card stays dead). */
        public int issueSeq;

        /** Index of a player's row (names compare ignoring case), -1 if not listed. */
        public int find(String name) {
            if (name == null) {
                return -1;
            }
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).equalsIgnoreCase(name)) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * ТН-4: a player's row - by his UUID first; a row without a UUID by the name (ignoring case). A row that keeps
         * another player's UUID never matches by the name alone (someone who took a renamed friend's old name).
         * An empty / null uuid: by the name only (find).
         */
        public int findPlayer(String uuid, String name) {
            if (uuid == null || uuid.length() == 0) {
                return find(name);
            }
            for (int i = 0; i < ids.size(); i++) {
                if (uuid.equals(ids.get(i))) {
                    return i;
                }
            }
            if (name == null) {
                return -1;
            }
            for (int i = 0; i < names.size(); i++) {
                if (ids.get(i).length() == 0 && names.get(i).equalsIgnoreCase(name)) {
                    return i;
                }
            }
            return -1;
        }

        /** The player's row rights, -1 if not listed. */
        public int listed(String name) {
            int i = find(name);
            return i < 0 ? -1 : rights.get(i).intValue();
        }

        /** ТН-4: the row rights of the player with this UUID / name (findPlayer), -1 if not listed. */
        public int listed(String uuid, String name) {
            int i = findPlayer(uuid, name);
            return i < 0 ? -1 : rights.get(i).intValue();
        }

        /** Adds a player (driver + passenger + cargo to start with, like the mock-up's friend row). @return added */
        public boolean add(String name) {
            return add(name, null);
        }

        /** ТН-4: adds a player, with his UUID when he is online (null / "": found by the name). @return added */
        public boolean add(String name, String uuid) {
            String id = uuid == null ? "" : uuid;
            if (!validName(name) || find(name) >= 0 || names.size() >= MAX_FRIENDS) {
                return false;
            }
            if (id.length() > 0 && ids.contains(id)) {
                return false;                                       // already listed under his old name
            }
            names.add(name);
            rights.add(Integer.valueOf(norm(DRIVE | PASSENGER | CARGO)));
            ids.add(id);
            issues.add(Integer.valueOf(++issueSeq));               // ТН-1: no older card of this player matches the new row
            return true;
        }

        public boolean remove(String name) {
            int i = find(name);
            if (i < 0) {
                return false;
            }
            names.remove(i);
            rights.remove(i);
            ids.remove(i);
            issues.remove(i);
            return true;
        }

        /** ТН-1, «Выдать ключ-карту»: a new issue number for the player's row (his older cards stop working). @return it, -1 not listed */
        public int reissue(String name) {
            int i = find(name);
            if (i < 0) {
                return -1;
            }
            issues.set(i, Integer.valueOf(++issueSeq));
            return issueSeq;
        }

        /** ТН-1: the row's issue number, -1 if not listed. */
        public int issueOf(String uuid, String name) {
            int i = findPlayer(uuid, name);
            return i < 0 ? -1 : issues.get(i).intValue();
        }

        /** ТН-4: the UUID kept for a listed name ("" - none / not listed). */
        public String idOf(String name) {
            int i = find(name);
            return i < 0 ? "" : ids.get(i);
        }

        /**
         * ТН-1: does a friend's card (its holder, his UUID and the issue number written on it) still belong to a listed
         * player? The owner's own card is not checked here (the lock version alone).
         */
        public boolean cardHolderOk(String holder, String holderId, int issue) {
            int i = findPlayer(holderId, holder);
            return i >= 0 && issues.get(i).intValue() == issue;
        }

        /**
         * ТН-4: an online player near the vehicle refreshes his row - a row found by the name gets his UUID, a row found
         * by the UUID follows his new name (unless another row already has that name). @return changed
         */
        public boolean refresh(String uuid, String name) {
            if (uuid == null || uuid.length() == 0 || !validName(name)) {
                return false;
            }
            int i = findPlayer(uuid, name);
            if (i < 0) {
                return false;
            }
            boolean changed = false;
            if (ids.get(i).length() == 0) {
                ids.set(i, uuid);
                changed = true;
            }
            if (!names.get(i).equals(name)) {
                int other = find(name);
                if (other < 0 || other == i) {
                    names.set(i, name);
                    changed = true;
                }
            }
            return changed;
        }

        public void set(String name, int r) {
            int i = find(name);
            if (i >= 0) {
                rights.set(i, Integer.valueOf(norm(r)));
            }
        }

        public void write(NBTTagCompound t) {
            t.setInteger("Mode", mode);
            t.setInteger("Others", others);
            t.setInteger("Lock", lockVersion);
            t.setInteger("IssueSeq", issueSeq);
            NBTTagList l = new NBTTagList();
            for (int i = 0; i < names.size(); i++) {
                NBTTagCompound e = new NBTTagCompound();
                e.setString("Name", names.get(i));
                e.setInteger("Rights", rights.get(i).intValue());
                if (ids.get(i).length() > 0) {
                    e.setString("Id", ids.get(i));
                }
                e.setInteger("Issue", issues.get(i).intValue());
                l.appendTag(e);
            }
            t.setTag("Players", l);
        }

        public void read(NBTTagCompound t) {
            mode = Math.max(0, Math.min(MODES - 1, t.getInteger("Mode")));
            others = norm(t.getInteger("Others"));
            lockVersion = t.getInteger("Lock");
            issueSeq = t.getInteger("IssueSeq");                   // 0 in saves before ТН-1
            names.clear();
            rights.clear();
            ids.clear();
            issues.clear();
            NBTTagList l = t.getTagList("Players", 10);
            for (int i = 0; i < l.tagCount() && names.size() < MAX_FRIENDS; i++) {
                NBTTagCompound e = l.getCompoundTagAt(i);
                String n = e.getString("Name"), id = e.getString("Id");
                if (validName(n) && find(n) < 0 && (id.length() == 0 || !ids.contains(id))) {
                    names.add(n);
                    rights.add(Integer.valueOf(norm(e.getInteger("Rights"))));
                    ids.add(id);
                    int issue = Math.max(0, e.getInteger("Issue"));
                    issues.add(Integer.valueOf(issue));
                    issueSeq = Math.max(issueSeq, issue);
                }
            }
        }

        /** The rules of a vehicle saved before wave 2. */
        public void legacy() {
            mode = FRIENDS;
            others = LEGACY_OTHERS;
            lockVersion = 0;
            issueSeq = 0;
            names.clear();
            rights.clear();
            ids.clear();
            issues.clear();
        }
    }
}
