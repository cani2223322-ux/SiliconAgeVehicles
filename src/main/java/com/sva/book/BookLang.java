package com.sva.book;

import java.util.HashMap;
import java.util.Map;

/**
 * Wave C: the lang files read directly (the addon's and the core's, ru_RU / en_US) - the exporter writes both languages
 * from one server run and the self-test checks the handbook's keys, neither can use the game's translator (it holds one
 * language, and on a dedicated server only English). Pure.
 */
public final class BookLang {

    private static final Map<String, Map<String, String>> MAPS = new HashMap<String, Map<String, String>>();
    /** Vanilla items' Russian names (the jar holds only en_US) - the few the addon's recipes use. */
    static final String[][] VANILLA_RU = {{"item.book.name", "Книга"}, {"item.paper.name", "Бумага"}, {"item.redstone.name", "Редстоун"},
        {"item.ingotIron.name", "Железный слиток"}, {"item.ingotGold.name", "Золотой слиток"}, {"item.compass.name", "Компас"},
        {"item.feather.name", "Перо"}, {"item.bucket.name", "Ведро"}, {"item.dyePowder.blue.name", "Лазурит"},
        {"item.dyePowder.yellow.name", "Жёлтый краситель"}, {"item.sulphur.name", "Порох"}, {"item.blazePowder.name", "Огненный порошок"},
        {"item.blazeRod.name", "Огненный стержень"}, {"item.fireball.name", "Огненный заряд"}, {"item.slimeball.name", "Слизь"},
        {"item.potion.name", "Бутылка воды"}, {"item.pickaxeIron.name", "Железная кирка"}, {"tile.chest.name", "Сундук"},
        {"tile.workbench.name", "Верстак"}, {"tile.glass.name", "Стекло"}, {"tile.stonebrick.name", "Булыжник"},
        {"tile.wood.name", "Доски"}, {"item.glowstoneDust.name", "Светопыль"}, {"item.diamond.name", "Алмаз"},
        {"tile.redstoneLight.name", "Лампа"}};

    private BookLang() {
    }

    /** "ru" / "en" -> the merged map (the core's, then the addon's over it). */
    public static synchronized Map<String, String> map(String lang) {
        Map<String, String> m = MAPS.get(lang);
        if (m == null) {
            m = new HashMap<String, String>();
            String file = "ru".equals(lang) ? "ru_RU.lang" : "en_US.lang";
            read(m, "/assets/minecraft/lang/en_US.lang");
            if ("ru".equals(lang)) {
                for (String[] v : VANILLA_RU) {
                    m.put(v[0], v[1]);
                }
            }
            read(m, "/assets/siliconage/lang/" + file);
            read(m, "/assets/siliconagevehicles/lang/" + file);
            MAPS.put(lang, m);
        }
        return m;
    }

    static void read(Map<String, String> m, String path) {
        String s = BookMarkup.read(path);
        if (s == null) {
            return;
        }
        for (String line : s.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("#")) {
                m.put(line.substring(0, eq).trim(), line.substring(eq + 1));
            }
        }
    }

    /** A key's text (the key itself when missing). */
    public static String tr(String lang, String key) {
        String v = map(lang).get(key);
        return v == null ? key : v;
    }

    public static String tr(String lang, String key, Object... args) {
        String v = tr(lang, key);
        try {
            return String.format(v, args);
        } catch (Exception e) {
            return v;
        }
    }

    public static boolean has(String lang, String key) {
        return map(lang).containsKey(key);
    }
}
