package com.sva.book;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sva.vehicle.BatteryMath;
import com.sva.vehicle.FuelMath;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleType;

/**
 * Wave C: BookLayout's runtime facts for one language - a vehicle's numbers straight from VehicleType / FuelMath /
 * BatteryMath (item 42: «stats from VehicleType»), the recipes' boxes from the registered recipes (BookRecipesSVA), the
 * pictures' sizes from their PNG headers, the schematics' and checklists' lengths. The text comes through a Tr (the game's
 * translator on the client, BookLang's files for the self-test / exporter). Common code.
 */
public class BookResolveSVA implements BookLayout.Resolver {

    /** key -> text. */
    public interface Tr {
        String tr(String key);
    }

    /** BookLang's files for a language. */
    public static Tr files(final String lang) {
        return new Tr() {
            public String tr(String key) {
                return BookLang.tr(lang, key);
            }
        };
    }

    private final Tr tr;
    private final Map<String, int[]> imgSizes = new HashMap<String, int[]>();

    public BookResolveSVA(Tr tr) {
        this.tr = tr;
    }

    /** "4 000 000" (plain spaces: the font has no thin one). */
    public static String group(long v) {
        String s = Long.toString(Math.abs(v));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) {
                b.append(' ');
            }
            b.append(s.charAt(i));
        }
        return (v < 0 ? "-" : "") + b;
    }

    public static VehicleType type(String name) {
        for (VehicleType t : VehicleType.values()) {
            if (t.name().equalsIgnoreCase(name)) {
                return t;
            }
        }
        return null;
    }

    public List<String[]> vehicleRows(String type) {
        List<String[]> l = new ArrayList<String[]>();
        VehicleType t = type(type);
        if (t == null) {
            return l;
        }
        l.add(new String[] {tr.tr("sva.book.veh.tier"), t.tier.name()});
        l.add(new String[] {tr.tr("sva.book.veh.speed"), VehicleMath.kmh(t.topSpeed) + " " + tr.tr("sva.book.unit.kmh")});
        l.add(new String[] {tr.tr("sva.book.veh.seats"), String.valueOf(t.seats)});
        l.add(new String[] {tr.tr("sva.book.veh.cargo"), t.trailerSlots > 0 ? t.cargoSlots + " + " + t.trailerSlots + " " + tr.tr("sva.book.veh.trailer")
                : String.valueOf(t.cargoSlots)});
        l.add(new String[] {tr.tr("sva.book.veh.buffer"), group(t.buffer) + " EU"});
        int tank = FuelMath.tank(t);
        l.add(new String[] {tr.tr("sva.book.veh.h2"), tank > 0 ? group(tank) + " " + tr.tr("sva.book.unit.mb") + ", " + FuelMath.minutes(t) + " "
                + tr.tr("sva.book.unit.min") : tr.tr("sva.book.veh.noH2")});
        double bat = BatteryMath.capacity(t);
        if (bat > 0 && tank > 0) {
            l.add(new String[] {tr.tr("sva.book.veh.battery"), group((long) bat) + " EU"});
        }
        l.add(new String[] {tr.tr("sva.book.veh.hull"), String.valueOf((int) t.maxHp)});
        return l;
    }

    public int[] recipeSize(String ref) {
        BookRecipesSVA.Grid g = BookRecipesSVA.resolve(ref);
        return g == null ? null : BookRecipesSVA.size(g);
    }

    public int imageHeight(String name, int width) {
        int[] s = imageSize(name);
        if (s == null) {
            return -1;
        }
        double k = Math.min(1.0, width / (double) s[0]);
        return (int) Math.round(Math.min(s[1] * k, 150));
    }

    /** A picture's {width, height} from its PNG header, or null when missing. */
    public int[] imageSize(String name) {
        if (imgSizes.containsKey(name)) {
            return imgSizes.get(name);
        }
        int[] s = pngSize("/assets/siliconagevehicles/textures/book/" + name + ".png");
        imgSizes.put(name, s);
        return s;
    }

    /** {width, height} of a PNG resource (its IHDR), or null. */
    public static int[] pngSize(String path) {
        InputStream in = BookResolveSVA.class.getResourceAsStream(path);
        if (in == null) {
            return null;
        }
        try {
            byte[] h = new byte[24];
            int n = 0;
            while (n < 24) {
                int k = in.read(h, n, 24 - n);
                if (k < 0) {
                    break;
                }
                n += k;
            }
            in.close();
            if (n < 24 || h[1] != 'P' || h[2] != 'N' || h[3] != 'G') {
                return null;
            }
            return new int[] {be(h, 16), be(h, 20)};
        } catch (java.io.IOException e) {
            return null;
        }
    }

    static int be(byte[] b, int o) {
        return (b[o] & 255) << 24 | (b[o + 1] & 255) << 16 | (b[o + 2] & 255) << 8 | b[o + 3] & 255;
    }

    public int schemList(String id) {
        BookSchematics.Schem s = BookSchematics.get(id, 0);
        return s == null ? -1 : s.list.size();
    }

    public int checkItems(String id) {
        int l = BookChecklists.byId(id);
        return l < 0 ? -1 : BookChecklists.size(l);
    }
}
