package com.sva;

import com.sc.manual.Lang;

/** Number formatting for tooltips and screens ("200 тыс." / "200k", "1,28 млн" / "1.28M"). Lang lookups: client only. */
public final class FormatSVA {

    private FormatSVA() {
    }

    /** Short: below 10 000 as is, then thousands, then millions with two decimals. */
    public static String eu(long n) {
        if (n < 10000) {
            return String.valueOf(n);
        }
        if (n < 1000000) {
            return Lang.tr("sva.num.k", String.valueOf(Math.round(n / 1000.0)));
        }
        String m = String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(n / 1000000.0));
        if (m.endsWith("0")) {
            m = m.substring(0, m.length() - 1);
        }
        if (m.endsWith(".0")) {
            m = m.substring(0, m.length() - 2);
        }
        return Lang.tr("sva.num.m", m.replace('.', Lang.tr("sva.num.point").charAt(0)));
    }

    /** Seconds from ticks, rounded up. */
    public static int seconds(int ticks) {
        return (ticks + 19) / 20;
    }
}
