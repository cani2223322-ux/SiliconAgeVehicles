package com.sva.debug;

import java.util.Set;

import com.sva.ConfigSVA;
import com.sva.airdefense.CmdRateMath;
import com.sva.airdefense.ZoneEditMath;
import com.sva.airdefense.ZoneMath;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Audit 2026-10-22 self-test (exploits and hardening) - pure logic, the dedicated server runs it:
 * <ul>
 * <li>bug 1: a zone box of +-2e9 read (the size check overflowed an int - a zone over the whole world): clamped to the
 * world's border and cut to 1024; a circle's centre clamped; the editor's check() in long; the zone's reach from its
 * command post (farthest point, withinReach, check() with the reach); the ND's edge clipped to the ring before the dashes;</li>
 * <li>7.1: the command post's commands per player - the HEAVY / LIGHT allowances (a burst, then one an interval);</li>
 * <li>the config defaults and the new lang keys.</li>
 * </ul>
 */
final class SelfTestAdHardenSVA {

    private SelfTestAdHardenSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    private static boolean near(double a, double b, double tol) {
        return Math.abs(a - b) <= tol;
    }

    static void run() throws Exception {
        zoneOverflow();
        reach();
        clip();
        rate();
        configAndLang();
    }

    private static ZoneMath.Zone rect(int ax, int az, int bx, int bz) {
        ZoneMath.Zone z = new ZoneMath.Zone();
        z.name = "R";
        z.shape = ZoneMath.RECT;
        z.x0 = ax;
        z.z0 = az;
        z.x1 = bx;
        z.z1 = bz;
        return z;
    }

    private static ZoneMath.Zone circle(int cx, int cz, int r) {
        ZoneMath.Zone z = new ZoneMath.Zone();
        z.name = "C";
        z.cx = cx;
        z.cz = cz;
        z.r = r;
        return z;
    }

    // ---------------------------------------------------------------- bug 1: the overflow

    private static void zoneOverflow() {
        int g = 2000000000, W = ZoneMath.WORLD_LIMIT;
        NBTTagCompound t = new NBTTagCompound();
        rect(-g, -g, g, g).write(t);
        ZoneMath.Zone a = new ZoneMath.Zone();
        a.read(t);
        check(a.x0 == -W && a.z0 == -W && a.x1 == -W + 2 * ZoneMath.MAX_HALF && a.z1 == -W + 2 * ZoneMath.MAX_HALF,
                "ad harden (bug 1): a box of +-2e9 read - clamped to the world's border and cut to 1024 (" + a.x0 + ".." + a.x1 + ")");
        check(!ZoneMath.contains2D(a, 0.5, 0.5) && !ZoneMath.contains2D(a, 1e6, -1e6) && ZoneMath.contains2D(a, -W + 10, -W + 10),
                "ad harden (bug 1): that zone no longer covers the world - only a corner at the border");
        NBTTagCompound t2 = new NBTTagCompound();
        rect(g, g, -g, -g).write(t2);
        ZoneMath.Zone b = new ZoneMath.Zone();
        b.read(t2);
        ZoneMath.Zone c = rect(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 10).copy();
        check(b.x0 == -W && b.x1 == -W + 1024 && c.x0 == -W && c.x1 == -W + 1024 && c.z0 == 0 && c.z1 == 10,
                "ad harden (bug 1): the corners swapped and the int limits (through copy(), as the saves and the pilots' copies) - the same cut");
        NBTTagCompound t3 = new NBTTagCompound();
        circle(0, 0, 10).write(t3);
        t3.setIntArray("Circle", new int[] {g, -g, 100});
        ZoneMath.Zone ci = new ZoneMath.Zone();
        ci.read(t3);
        ZoneMath.Zone ok = rect(100, -50, 300, 20).copy();
        check(ci.cx == W && ci.cz == -W && ci.r == 100 && ok.x0 == 100 && ok.z0 == -50 && ok.x1 == 300 && ok.z1 == 20,
                "ad harden (bug 1): a circle's centre clamped to the border; an ordinary box untouched");
        // the editor's check() counted in int too: a raw box of +-2e9 passed as OK
        check(ZoneEditMath.check(rect(-g, 0, g, 10)) == ZoneEditMath.E_TOO_BIG && ZoneEditMath.check(rect(0, -g, 10, g)) == ZoneEditMath.E_TOO_BIG
                && ZoneEditMath.check(rect(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 10)) == ZoneEditMath.E_TOO_BIG
                && ZoneEditMath.check(rect(0, 0, 1024, 1024)) == ZoneEditMath.OK,
                "ad harden (bug 1): the zone editor's check counts the sizes in long - +-2e9 is too big, 1024 fits");
    }

    // ---------------------------------------------------------------- bug 1: the reach from the command post

    private static void reach() {
        ZoneMath.Zone mid = rect(-512, -512, 511, 511);
        ZoneMath.Zone far = circle(900, 0, 200);
        ZoneMath.Zone edge = circle(800, 0, 200);
        ZoneMath.Zone turned = rect(0, 0, 99, 99);
        turned.rot = 45;
        double diag = Math.hypot(50, 50);
        check(near(ZoneMath.farthest(mid, 0.5, 0.5), Math.hypot(512.5, 512.5), 1e-6) && near(ZoneMath.farthest(far, 0.5, 0.5), 1100, 1e-6)
                && near(ZoneMath.farthest(turned, 50, 50), diag, 1e-6),
                "ad harden (bug 1): the zone's farthest point - a box's corner (turned too), a circle's centre + radius");
        check(ZoneMath.withinReach(mid, 0, 0, 1024) && !ZoneMath.withinReach(far, 0, 0, 1024) && ZoneMath.withinReach(edge, 0, 0, 1024)
                && ZoneMath.withinReach(far, 0, 0, 0) && ZoneMath.withinReach(far, 0, 0, 1200),
                "ad harden (bug 1): within reach of the command post - the largest box round it yes, a circle reaching 1100 no (1024), 0 = no limit");
        NBTTagCompound t = new NBTTagCompound();
        rect(-2000000000, -2000000000, 2000000000, 2000000000).write(t);
        ZoneMath.Zone world = new ZoneMath.Zone();
        world.read(t);
        check(!ZoneMath.withinReach(world, 100, -200, 1024) && ZoneEditMath.check(far, 0, 0, 1024) == ZoneEditMath.E_TOO_FAR
                && ZoneEditMath.check(mid, 0, 0, 1024) == ZoneEditMath.OK && ZoneEditMath.check(far, 0, 0, 0) == ZoneEditMath.OK
                && ZoneEditMath.CHECK_KEYS.length == ZoneEditMath.E_TOO_FAR + 1 && "tooFar".equals(ZoneEditMath.CHECK_KEYS[ZoneEditMath.E_TOO_FAR]),
                "ad harden (bug 1): the clamped world box is out of reach; the editor says 'too far' before sending (0 - the limit not known)");
    }

    // ---------------------------------------------------------------- bug 1: the ND's clip

    private static void clip() {
        double[] mid = ZoneMath.clipToCircle(-1000, 0, 1000, 0, 0, 0, 100);
        double[] inside = ZoneMath.clipToCircle(-10, 5, 10, -5, 0, 0, 100);
        double[] half = ZoneMath.clipToCircle(0, 0, 1000, 0, 0, 0, 100);
        check(mid != null && near(mid[0], 0.45, 1e-9) && near(mid[1], 0.55, 1e-9) && inside != null && inside[0] == 0 && inside[1] == 1
                && half != null && half[0] == 0 && near(half[1], 0.1, 1e-9),
                "ad harden (bug 1): an edge clipped to the ND's ring - through it 0.45..0.55, inside whole, from the centre out 0..0.1");
        check(ZoneMath.clipToCircle(-1000, 500, 1000, 500, 0, 0, 100) == null && ZoneMath.clipToCircle(200, 0, 1000, 0, 0, 0, 100) == null
                && ZoneMath.clipToCircle(5, 5, 5, 5, 0, 0, 100) != null && ZoneMath.clipToCircle(500, 5, 500, 5, 0, 0, 100) == null,
                "ad harden (bug 1): a missing edge, one ending before the ring and a point - nothing / the point alone");
        // what the ND drew of a world-wide edge (2 blocks a piece at 40 blocks' range): 3e7 pieces a frame before, a handful now
        double range = 40, piece = Math.max(2, range / 24), ax = -30000000, bx = 30000000;
        long n = (long) Math.ceil((bx - ax) / piece);
        double[] in = ZoneMath.clipToCircle(ax, 3, bx, 3, 0.5, 0.5, range * 1.02);
        long pieces = in == null ? 0 : Math.min(n, (long) Math.ceil(in[1] * n)) - (long) Math.floor(in[0] * n);
        check(n == 30000000L && in != null && pieces > 0 && pieces <= 2 * range * 1.02 / piece + 3,
                "ad harden (bug 1): a world-wide edge on the ND - " + pieces + " pieces of " + n + " (only those on the screen)");
    }

    // ---------------------------------------------------------------- 7.1: the commands' rate

    private static void rate() {
        check(CmdRateMath.kind("open") == CmdRateMath.FREE && CmdRateMath.kind("msg") == CmdRateMath.HEAVY && CmdRateMath.kind("alarm") == CmdRateMath.HEAVY
                && CmdRateMath.kind("set") == CmdRateMath.HEAVY && CmdRateMath.kind("zone") == CmdRateMath.HEAVY && CmdRateMath.kind("zoneDel") == CmdRateMath.HEAVY
                && CmdRateMath.kind("assign") == CmdRateMath.LIGHT && CmdRateMath.kind("cov") == CmdRateMath.LIGHT && CmdRateMath.kind(CmdRateMath.OPEN) == CmdRateMath.LIGHT && CmdRateMath.kind("whatever") == CmdRateMath.HEAVY,
                "ad harden (7.1): the commands' allowances - a message, the alarm, the settings, zones heavy; track orders light; opening free");
        long tat = 0, now = 1000;
        int ok = 0;
        for (int i = 0; i < 10; i++) {
            long n = CmdRateMath.take(tat, CmdRateMath.HEAVY, now);
            if (n >= 0) {
                tat = n;
                ok++;
            }
        }
        long a19 = CmdRateMath.take(tat, CmdRateMath.HEAVY, now + 19), a20 = CmdRateMath.take(tat, CmdRateMath.HEAVY, now + 20);
        long later = CmdRateMath.take(tat, CmdRateMath.HEAVY, now + 200);
        check(ok == 3 && a19 < 0 && a20 >= 0 && later == now + 220,
                "ad harden (7.1): heavy - 3 at once, the 4th refused; the next after 20 ticks (1 s); a pause fills it up again (" + ok + ")");
        long lt = 0;
        int lok = 0;
        for (int i = 0; i < 24; i++) {
            long n = CmdRateMath.take(lt, CmdRateMath.LIGHT, now);
            if (n >= 0) {
                lt = n;
                lok++;
            }
        }
        // 24 a tick for a whole second (the server's flood limit) from one player: 8 + 19 / 4
        int sec = 0;
        long st = 0;
        for (int tick = 0; tick < 20; tick++) {
            for (int i = 0; i < 24; i++) {
                long n = CmdRateMath.take(st, CmdRateMath.HEAVY, now + tick);
                if (n >= 0) {
                    st = n;
                    sec++;
                }
            }
        }
        check(lok == 8 && CmdRateMath.take(lt, CmdRateMath.LIGHT, now + 4) >= 0 && sec == 3 && CmdRateMath.take(5, CmdRateMath.FREE, now) == 5
                && CmdRateMath.take(now + 100000, CmdRateMath.HEAVY, now) == now + 20,
                "ad harden (7.1): light - 8 at once, one in 4 ticks; a flood of 24 a tick for a second gets 3 heavy (" + sec + ", was 480); free; a clock gone back starts afresh");
    }

    // ---------------------------------------------------------------- config, lang

    private static void configAndLang() throws Exception {
        check(ConfigSVA.adZoneMaxDistance == 1024 && ConfigSVA.adMaxZonesPerPlayer == 64,
                "ad harden config: the defaults - a zone within 1024 blocks of its command post, 64 zones per player");
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        String[] keys = {"sva.ad.msg.protected", "sva.ad.msg.tooFast", "sva.ad.msg.zoneFar", "sva.ad.msg.zoneLimit", "sva.ad.zone.check.tooFar"};
        StringBuilder miss = new StringBuilder();
        for (String k : keys) {
            if (!ru.contains(k) || !en.contains(k)) {
                miss.append(' ').append(k);
            }
        }
        check(miss.length() == 0, "ad harden lang: the refusals' lines in ru and en" + miss);
    }
}
