package com.sva.inventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Cargo screen v2 helpers, no world access (the self-test runs them): «Сорт.» (merge equal stacks, then order by item
 * id / meta / name), «Мусор» (the throwaway blocks of ConfigSVA.junk), the search matcher and the Shift+click
 * priority (a drill head into the head slot, a Bore Widener into the module slot, the rest into the cargo).
 */
public final class CargoToolsSVA {

    /** Shift+click targets from the player's inventory. */
    public static final int TO_HEAD = 0, TO_MODULE = 1, TO_CARGO = 2, TO_NONE = 3;

    private CargoToolsSVA() {
    }

    // ------------------------------------------------------------------ Shift+click

    /**
     * Where a stack Shift+clicked in the player's inventory goes: on the drill vehicle a head into a free head slot,
     * a Bore Widener into a free module slot; a vehicle item nowhere (no vehicles inside vehicles); else the cargo.
     */
    public static int transferTarget(boolean drill, boolean head, boolean widener, boolean headFree, boolean moduleFree, boolean vehicle) {
        if (vehicle) {
            return TO_NONE;
        }
        if (drill && head && headFree) {
            return TO_HEAD;
        }
        if (drill && widener && moduleFree) {
            return TO_MODULE;
        }
        return TO_CARGO;
    }

    // ------------------------------------------------------------------ sort

    /** Same item, damage and NBT: may share a stack. */
    public static boolean sameKind(ItemStack a, ItemStack b) {
        return a != null && b != null && a.getItem() == b.getItem() && a.getItemDamage() == b.getItemDamage()
                && ItemStack.areItemStackTagsEqual(a, b);
    }

    /** Sort order: item id, then meta, then the display name (NBT variants), fuller stacks first. */
    public static final Comparator<ItemStack> ORDER = new Comparator<ItemStack>() {
        @Override
        public int compare(ItemStack a, ItemStack b) {
            int ia = Item.getIdFromItem(a.getItem()), ib = Item.getIdFromItem(b.getItem());
            if (ia != ib) {
                return ia < ib ? -1 : 1;
            }
            if (a.getItemDamage() != b.getItemDamage()) {
                return a.getItemDamage() < b.getItemDamage() ? -1 : 1;
            }
            int n = name(a).compareTo(name(b));
            if (n != 0) {
                return n;
            }
            return b.stackSize - a.stackSize;
        }
    };

    private static String name(ItemStack s) {
        try {
            String n = s.getDisplayName();
            return n == null ? "" : n;
        } catch (RuntimeException e) {
            return "";                                              // a broken item's name: last among its kind
        }
    }

    /**
     * Merges equal stacks of c[from, to) (up to min(limit, max stack size)) and puts them in ORDER from `from` on,
     * the empty slots at the end. @return the number of stacks after
     */
    public static int sortRange(ItemStack[] c, int from, int to, int limit) {
        from = Math.max(0, from);
        to = Math.min(c.length, to);
        List<ItemStack> merged = new ArrayList<ItemStack>();
        for (int i = from; i < to; i++) {
            ItemStack s = c[i];
            c[i] = null;
            if (s == null || s.stackSize <= 0) {
                continue;
            }
            int max = Math.max(1, Math.min(limit, s.getMaxStackSize()));
            for (ItemStack m : merged) {
                if (s.stackSize > 0 && sameKind(m, s) && m.stackSize < max) {
                    int put = Math.min(s.stackSize, max - m.stackSize);
                    m.stackSize += put;
                    s.stackSize -= put;
                }
            }
            while (s.stackSize > 0) {
                ItemStack part = s.copy();
                part.stackSize = Math.min(s.stackSize, max);
                s.stackSize -= part.stackSize;
                merged.add(part);
            }
        }
        ItemStack[] out = merged.toArray(new ItemStack[merged.size()]);
        Arrays.sort(out, ORDER);
        for (int i = 0; i < out.length && from + i < to; i++) {
            c[from + i] = out[i];
        }
        return out.length;
    }

    // ------------------------------------------------------------------ junk

    /** Config entries "modid:name" or "modid:name@meta", trimmed and lower-cased (blank ones dropped). */
    public static Set<String> parseJunk(String[] entries) {
        Set<String> out = new HashSet<String>();
        if (entries == null) {
            return out;
        }
        for (String e : entries) {
            if (e != null && e.trim().length() > 0) {
                out.add(e.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** Is the item "name" (registry name) with `meta` on the junk list (any meta, or that meta)? */
    public static boolean isJunk(String name, int meta, Set<String> junk) {
        if (name == null || junk == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return junk.contains(n) || junk.contains(n + "@" + meta);
    }

    /** Registry name of an item ("minecraft:dirt"), or null. */
    public static String registryName(ItemStack s) {
        if (s == null || s.getItem() == null) {
            return null;
        }
        Object n = Item.itemRegistry.getNameForObject(s.getItem());
        return n == null ? null : n.toString();
    }

    /**
     * Takes the junk out of c[from, to) and returns it. `keepName` (with any meta): the first `keep` items of it stay
     * (the drill keeps a stack of cobblestone to wall up lava).
     */
    public static List<ItemStack> takeJunk(ItemStack[] c, int from, int to, Set<String> junk, String keepName, int keep) {
        List<ItemStack> out = new ArrayList<ItemStack>();
        int kept = 0;
        for (int i = Math.max(0, from); i < Math.min(c.length, to); i++) {
            ItemStack s = c[i];
            String n = registryName(s);
            if (s == null || !isJunk(n, s.getItemDamage(), junk)) {
                continue;
            }
            if (keepName != null && keepName.equalsIgnoreCase(n) && kept < keep) {
                int stay = Math.min(s.stackSize, keep - kept);
                kept += stay;
                if (stay == s.stackSize) {
                    continue;
                }
                ItemStack rest = s.splitStack(s.stackSize - stay);
                out.add(rest);
                continue;
            }
            out.add(s);
            c[i] = null;
        }
        return out;
    }

    // ------------------------------------------------------------------ search

    /**
     * Does a stack whose texts (display name, registry name, ore names...) are `texts` match the search `query`?
     * Case-insensitive substring of any text; a blank query matches everything; several words must all match.
     */
    public static boolean matches(String query, String... texts) {
        if (query == null || query.trim().length() == 0) {
            return true;
        }
        String[] words = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
        for (String w : words) {
            boolean found = false;
            for (String t : texts) {
                if (t != null && t.toLowerCase(Locale.ROOT).contains(w)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
