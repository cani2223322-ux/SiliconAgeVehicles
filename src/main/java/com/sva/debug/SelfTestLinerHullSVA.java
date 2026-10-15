package com.sva.debug;

import com.sva.entity.EntityVehicleSVA;
import com.sva.entity.LinerGroundSVA;
import com.sva.vehicle.LinerHull;

/**
 * The liner from any side (the user's «с любой стороны открыть и убрать в инвентарь»): the hull's parts take a click from
 * every side (LinerHull - the client's pick, the server's reach), a far click is the addon's own only where vanilla's was
 * dropped, the click hint at most once in 3 s, the pick-up only stopped on the ground with the engines off.
 */
final class SelfTestLinerHullSVA {

    private SelfTestLinerHullSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() {
        hull();
        rules();
    }

    /** A level look from an eye at model {x, z} (1.62 up) toward model {x, z, y}: the distance to the hull (-1 a miss). */
    private static double look(double ex, double ez, double tx, double tz, double ty) {
        double[] o = {ex, 1.62, ez};
        double dx = tx - ex, dy = ty - 1.62, dz = tz - ez, l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return LinerHull.ray(o, new double[] {dx / l, dy / l, dz / l}, 3.0, null);
    }

    private static void hull() {
        // left, right (forward of the wings), front, rear, a wing tip (both), the tailplane, the fin from behind, under the wing (looking up at it)
        double[] d = {look(7.5, -4, 7.5, -1.27, 2), look(7.5, 4, 7.5, 1.27, 2), look(11.5, 0, 9.2, 0, 2), look(-11.5, 0, -9.2, 0, 2.5),
                look(-1.5, 11, -1.5, 8.6, 2.2), look(-1.5, -11, -1.5, -8.6, 2.2), look(-7.5, -5.4, -7.5, -2.9, 2.75), look(-0.5, 6, -0.5, 6, 3)};
        boolean all = true;
        StringBuilder s = new StringBuilder();
        for (double v : d) {
            all &= v >= 0 && v <= 3;
            s.append(' ').append(Math.round(v * 10) / 10.0);
        }
        boolean miss = look(7.5, -4, 7.5, -10, 2) < 0 && look(13, 0, 16, 0, 2) < 0;           // looking away - nothing
        // the reach: 2 blocks off the wing tip is 10.9 blocks from the centre, but 2 from the hull
        double tip = LinerHull.distSq(-1.5, 1.6, 10.8), centre = 1.5 * 1.5 + 10.8 * 10.8;
        // the frame: yaw 0 faces +z (forward), the right is -x (EntityVehicleSVA.toWorld)
        double[] f = LinerHull.local(0, 0, 5, 0F), r = LinerHull.local(-5, 0, 0, 0F);
        boolean frame = Math.abs(f[0] - 5) < 1e-9 && Math.abs(f[2]) < 1e-9 && Math.abs(r[2] - 5) < 1e-9 && Math.abs(r[0]) < 1e-9;
        check(all && miss && tip < 4.5 && centre > 64 && frame, "liner hull: a click from the left, right, front, rear, both wing tips, the"
                + " tail, under the wing hits within 3 blocks (" + s.toString().trim() + "); looking away - none; 2 blocks off the wing tip is"
                + " in reach (the hull's, not the centre's " + Math.round(Math.sqrt(centre)) + ")");
    }

    private static void rules() {
        boolean far = EntityVehicleSVA.hullFarClick(100, true, 4) && EntityVehicleSVA.hullFarClick(20, false, 4)
                && !EntityVehicleSVA.hullFarClick(20, true, 4) && !EntityVehicleSVA.hullFarClick(4, false, 1)
                && !EntityVehicleSVA.hullFarClick(100, true, 50);
        check(far, "liner hull: a far click is the addon's only where vanilla dropped its own (6 blocks seen, 3 unseen) and the hull is"
                + " within " + EntityVehicleSVA.HULL_REACH + " - never both, never out of reach");
        boolean hint = LinerGroundSVA.hintDue(-1, 0) && !LinerGroundSVA.hintDue(100, 101) && !LinerGroundSVA.hintDue(100, 159)
                && LinerGroundSVA.hintDue(100, 160) && LinerGroundSVA.hintDue(100, 50);
        check(hint && LinerGroundSVA.HINT_TICKS == 60, "liner hull: the click hint at most once in 3 s (a world reload - again)");
        boolean pick = "sva.liner.msg.pickMoving".equals(EntityVehicleSVA.linerPickBlock(true, 0, false))
                && "sva.liner.msg.pickMoving".equals(EntityVehicleSVA.linerPickBlock(false, 0.2, false))
                && "sva.liner.msg.pickEngines".equals(EntityVehicleSVA.linerPickBlock(false, 0, true))
                && EntityVehicleSVA.linerPickBlock(false, 0.01, false) == null;
        check(pick, "liner pick-up: refused in the air / rolling, with an engine running; stopped on the ground, engines off - yes");
    }
}
