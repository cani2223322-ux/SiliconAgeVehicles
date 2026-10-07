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

        /** The player's row rights, -1 if not listed. */
        public int listed(String name) {
            int i = find(name);
            return i < 0 ? -1 : rights.get(i).intValue();
        }

        /** Adds a player (driver + passenger + cargo to start with, like the mock-up's friend row). @return added */
        public boolean add(String name) {
            if (!validName(name) || find(name) >= 0 || names.size() >= MAX_FRIENDS) {
                return false;
            }
            names.add(name);
            rights.add(Integer.valueOf(norm(DRIVE | PASSENGER | CARGO)));
            return true;
        }

        public boolean remove(String name) {
            int i = find(name);
            if (i < 0) {
                return false;
            }
            names.remove(i);
            rights.remove(i);
            return true;
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
            NBTTagList l = new NBTTagList();
            for (int i = 0; i < names.size(); i++) {
                NBTTagCompound e = new NBTTagCompound();
                e.setString("Name", names.get(i));
                e.setInteger("Rights", rights.get(i).intValue());
                l.appendTag(e);
            }
            t.setTag("Players", l);
        }

        public void read(NBTTagCompound t) {
            mode = Math.max(0, Math.min(MODES - 1, t.getInteger("Mode")));
            others = norm(t.getInteger("Others"));
            lockVersion = t.getInteger("Lock");
            names.clear();
            rights.clear();
            NBTTagList l = t.getTagList("Players", 10);
            for (int i = 0; i < l.tagCount() && names.size() < MAX_FRIENDS; i++) {
                NBTTagCompound e = l.getCompoundTagAt(i);
                String n = e.getString("Name");
                if (validName(n) && find(n) < 0) {
                    names.add(n);
                    rights.add(Integer.valueOf(norm(e.getInteger("Rights"))));
                }
            }
        }

        /** The rules of a vehicle saved before wave 2. */
        public void legacy() {
            mode = FRIENDS;
            others = LEGACY_OTHERS;
            lockVersion = 0;
            names.clear();
            rights.clear();
        }
    }
}
