package com.sva.client;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sva.vehicle.LinerFms;
import com.sva.vehicle.LinerMcdu;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Liner wave 5: what the server sent of a liner's MCDUs (NetSVA.McduData) - the two screens (LinerMcdu.Screen packed) and
 * the ND's picture of the flight plan (LinerFms.ndText) - kept by entity and kind (a concurrent map: the network thread
 * writes it), parsed once per change on the client thread for the keyboard screen, the 3D faces and the ND.
 */
@SideOnly(Side.CLIENT)
public final class McduClientSVA {

    private static final Map<Long, String> RAW = new ConcurrentHashMap<Long, String>();
    private static final Map<Long, Object[]> PARSED = new HashMap<Long, Object[]>();

    private McduClientSVA() {
    }

    static Long key(int entity, int kind) {
        return Long.valueOf((long) entity << 4 | kind & 15);
    }

    /** Network thread: a screen / the ND's plan arrived. */
    public static void put(int entity, int kind, String data) {
        if (RAW.size() > 96) {
            RAW.clear();                                            // liners long gone
        }
        RAW.put(key(entity, kind), data == null ? "" : data);
    }

    /** MCDU `side`'s screen of a liner (null: nothing received yet). */
    public static LinerMcdu.Screen screen(int entity, int side) {
        Object o = parsed(entity, side);
        return o instanceof LinerMcdu.Screen ? (LinerMcdu.Screen) o : null;
    }

    /** The ND's plan (null: none / nothing received). */
    public static LinerFms.NdView nd(int entity) {
        Object o = parsed(entity, 2);
        return o instanceof LinerFms.NdView ? (LinerFms.NdView) o : null;
    }

    private static Object parsed(int entity, int kind) {
        Long k = key(entity, kind);
        String raw = RAW.get(k);
        if (raw == null) {
            return null;
        }
        Object[] p = PARSED.get(k);
        if (p != null && p[0] == raw) {
            return p[1];
        }
        Object v = kind == 2 ? LinerFms.parseNd(raw) : LinerMcdu.Screen.unpack(raw);
        if (PARSED.size() > 96) {
            PARSED.clear();
        }
        PARSED.put(k, new Object[] {raw, v});
        return v;
    }
}
