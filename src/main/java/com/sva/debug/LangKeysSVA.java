package com.sva.debug;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Fix 2026-10 (the user's screenshot: the Singular Ship's HUD showed the raw key «sva.hud.jump.why.1» - the HUD builds
 * "sva.hud.jump.why." + the jump check's code, and five codes had no line): are the lang keys the code asks for in both
 * lang files? Pure Java (the dedicated server's self-test runs it):
 * <ul>
 * <li>every LITERAL key: the string constants of the mod's own classes (read from the class files' constant pools - the
 * sources are not in the jar) that look like a full key ("sva." + letters / digits / dots, not ending with a dot) must be
 * in ru_RU and en_US - except NOT_KEYS (system properties, the NEI handler's id);</li>
 * <li>the dynamic families ("sva.hud.jump.why." + code...) are checked by the self-test with their value ranges.</li>
 * </ul>
 */
public final class LangKeysSVA {

    public static final String RU = "/assets/siliconagevehicles/lang/ru_RU.lang", EN = "/assets/siliconagevehicles/lang/en_US.lang";
    /** "sva.*" string constants that are not lang keys. */
    static final String[] NOT_KEYS = {"sva.selftest", "sva.worldtest", "sva.workbench"};

    private LangKeysSVA() {
    }

    /** The keys of a lang file (null when it is missing). */
    public static Set<String> keys(String path) throws IOException {
        InputStream in = LangKeysSVA.class.getResourceAsStream(path);
        if (in == null) {
            return null;
        }
        Set<String> s = new HashSet<String>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0 && !line.startsWith("#")) {
                    s.add(line.substring(0, eq).trim());
                }
            }
        } finally {
            r.close();
        }
        return s;
    }

    /** Is s shaped like a full key: "sva." then letters, digits, '_' and dots, not ending with a dot? */
    public static boolean keyLike(String s) {
        if (!s.startsWith("sva.") || s.length() < 5 || s.endsWith(".")) {
            return false;
        }
        for (int i = 4; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    /** The CONSTANT_Utf8 strings of a class file (closes the stream). */
    public static List<String> classStrings(InputStream in) throws IOException {
        List<String> out = new ArrayList<String>();
        DataInputStream d = new DataInputStream(new java.io.BufferedInputStream(in));
        try {
            if (d.readInt() != 0xCAFEBABE) {
                return out;
            }
            d.readUnsignedShort();
            d.readUnsignedShort();
            int n = d.readUnsignedShort();
            for (int k = 1; k < n; k++) {
                int tag = d.readUnsignedByte();
                switch (tag) {
                    case 1:
                        out.add(d.readUTF());
                        break;
                    case 3:
                    case 4:
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 17:
                    case 18:
                        d.readInt();
                        break;
                    case 5:
                    case 6:
                        d.readLong();
                        k++;                                       // longs and doubles take two slots
                        break;
                    case 7:
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                        d.readUnsignedShort();
                        break;
                    case 15:
                        d.readUnsignedByte();
                        d.readUnsignedShort();
                        break;
                    default:
                        return out;                                // unknown tag: stop (the strings so far stand)
                }
            }
        } finally {
            d.close();
        }
        return out;
    }

    /**
     * Every key-like string constant of the mod's classes (com/sva/**) - from the mod jar, or from the class folder in a
     * dev run; null when neither can be found.
     */
    public static Set<String> literalKeys() throws IOException {
        java.net.URL u = LangKeysSVA.class.getResource("LangKeysSVA.class");
        if (u == null) {
            return null;
        }
        String s = u.toString();
        Set<String> keys = new TreeSet<String>();
        if (s.startsWith("jar:")) {
            String path = s.substring(4, s.indexOf("!/"));
            File f;
            try {
                f = new File(new java.net.URI(path));
            } catch (Exception e) {
                return null;
            }
            JarFile jar = new JarFile(f);
            try {
                for (Enumeration<JarEntry> en = jar.entries(); en.hasMoreElements();) {
                    JarEntry e = en.nextElement();
                    if (e.getName().startsWith("com/sva/") && e.getName().endsWith(".class")) {
                        addKeys(keys, jar.getInputStream(e));
                    }
                }
            } finally {
                jar.close();
            }
            return keys;
        }
        if (s.startsWith("file:")) {
            File dir;
            try {
                dir = new File(u.toURI()).getParentFile().getParentFile();      // .../com/sva/debug -> .../com/sva
            } catch (Exception e) {
                return null;
            }
            walk(dir, keys);
            return keys;
        }
        return null;
    }

    private static void walk(File dir, Set<String> keys) throws IOException {
        File[] fs = dir.listFiles();
        if (fs == null) {
            return;
        }
        for (File f : fs) {
            if (f.isDirectory()) {
                walk(f, keys);
            } else if (f.getName().endsWith(".class")) {
                addKeys(keys, new FileInputStream(f));
            }
        }
    }

    private static void addKeys(Set<String> keys, InputStream in) throws IOException {
        for (String c : classStrings(in)) {
            if (keyLike(c)) {
                keys.add(c);
            }
        }
    }

    /** The literal keys missing from `lang` (NOT_KEYS left out). */
    public static List<String> missing(Set<String> literal, Set<String> lang) {
        List<String> out = new ArrayList<String>();
        Set<String> skip = new HashSet<String>(java.util.Arrays.asList(NOT_KEYS));
        for (String k : literal) {
            if (!skip.contains(k) && !lang.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }
}
