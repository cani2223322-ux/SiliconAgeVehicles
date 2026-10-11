package com.sva.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.GlassMath;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Cockpit wave (plan-next §5 / §6): the page each vehicle's MFD shows on THIS client («Страница MFD» - its key or the
 * «Техника» menu's row; no packet: every rider flips his own screen), remembered per vehicle across sessions in the
 * player's own config/SiliconAgeVehicles_mfd.cfg (GlassMath.memoryKey: type, board number, owner -> the page).
 */
@SideOnly(Side.CLIENT)
public final class MfdPagesSVA {

    private static final Properties PAGES = new Properties();
    private static File file;

    private MfdPagesSVA() {
    }

    /** Client init (ClientProxySVA, beside the keys' file). */
    public static void load(File configDir) {
        file = new File(configDir, "SiliconAgeVehicles_mfd.cfg");
        if (!file.isFile()) {
            return;
        }
        try {
            InputStream in = new FileInputStream(file);
            try {
                PAGES.load(in);
            } finally {
                in.close();
            }
        } catch (java.io.IOException e) {
            PAGES.clear();                                          // unreadable: every MFD starts on its first page
        }
    }

    private static String key(EntityVehicleSVA v) {
        return GlassMath.memoryKey(v.getVehicleType(), v.getBoardNumber(), v.getOwnerName());
    }

    /** The page this vehicle's MFD shows (its first one until flipped). */
    public static int page(EntityVehicleSVA v) {
        VehicleType t = v.getVehicleType();
        return GlassMath.sanitize(t, GlassMath.parsePage(PAGES.getProperty(key(v))));
    }

    /** «Страница MFD»: the next page, remembered. */
    public static void next(EntityVehicleSVA v) {
        int p = GlassMath.next(v.getVehicleType(), page(v));
        if (p < 0) {
            return;
        }
        PAGES.setProperty(key(v), String.valueOf(p));
        save();
    }

    private static void save() {
        if (file == null) {
            return;
        }
        try {
            OutputStream out = new FileOutputStream(file);
            try {
                PAGES.store(out, "Silicon Age: Vehicles - the MFD page per vehicle (type_board_owner = page: 0 ENG, 1 MAP, 2 ROUTE, 3 CARGO, 4 RWR)");
            } finally {
                out.close();
            }
        } catch (java.io.IOException e) {
            // not remembered this time - the page still flipped
        }
    }
}
