package com.sva.debug;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import com.sva.vehicle.VehicleType;

/**
 * Does a seated player stay inside the vehicle? Loads the type's OBJ (the hull: the painted shell and the glass) and
 * checks that the rider's chest and head - the 1.7.10 player model at x0.9375 sitting on the seat: hip (leg pivot) at
 * seatY + 0.203, torso 0.469 x 0.234 x 0.703 over it, the head a 0.469 cube on top - are enclosed by the hull: from
 * points on each box's surface, rays in 26 directions; a point is outside when 8 or more of them leave the model
 * without hitting it. No Minecraft classes (runs in the dedicated server's self-test).
 */
public final class RiderFitSVA {

    /** Hull materials: the shell (ObjModelSVA.isShell) and the glass. */
    private static final String[] HULL = {"paint", "paint2", "paint3", "white", "tiles", "nb", "glass", "canopy"};
    private static final float[][] DIRS = dirs();

    private RiderFitSVA() {
    }

    private static float[][] dirs() {
        List<float[]> l = new ArrayList<float[]>();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) {
                        float n = (float) Math.sqrt(x * x + y * y + z * z);
                        l.add(new float[] {x / n, y / n, z / n});
                    }
                }
            }
        }
        return l.toArray(new float[l.size()][]);
    }

    /** The hull triangles of models/NAME.obj (9 floats each, model units), or null when it can't be read. */
    public static float[][] hull(String model) throws java.io.IOException {
        return hull("/assets/siliconagevehicles/models/" + model + ".obj", HULL);
    }

    /** Liner wave 1: the hull triangles of any OBJ resource, of the given materials (the liner's skin: fus, belly, door, fin). */
    public static float[][] hull(String path, String[] materials) throws java.io.IOException {
        InputStream in = RiderFitSVA.class.getResourceAsStream(path);
        if (in == null) {
            return null;
        }
        java.util.Set<String> hull = new java.util.HashSet<String>(java.util.Arrays.asList(materials));
        List<float[]> v = new ArrayList<float[]>();
        List<float[]> tris = new ArrayList<float[]>();
        boolean take = false;
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 1 << 16);
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("v ")) {
                    String[] p = line.trim().split("\\s+");
                    v.add(new float[] {Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                } else if (line.startsWith("g ") || line.startsWith("o ") || line.startsWith("usemtl ")) {
                    take = hull.contains(line.substring(line.indexOf(' ') + 1).trim());
                } else if (take && line.startsWith("f ")) {
                    String[] p = line.substring(2).trim().split("\\s+");
                    float[][] poly = new float[p.length][];
                    for (int i = 0; i < p.length; i++) {
                        poly[i] = v.get(Integer.parseInt(p[i].split("/")[0]) - 1);
                    }
                    for (int i = 1; i + 1 < poly.length; i++) {
                        tris.add(new float[] {poly[0][0], poly[0][1], poly[0][2], poly[i][0], poly[i][1], poly[i][2],
                                poly[i + 1][0], poly[i + 1][1], poly[i + 1][2]});
                    }
                }
            }
        } finally {
            r.close();
        }
        return tris.toArray(new float[tris.size()][]);
    }

    /**
     * The bounding box {minX, minY, minZ, maxX, maxY, maxZ} of the given groups of models/NAME.obj (model units), null
     * when the file can't be read or has none of them.
     */
    public static float[] bounds(String model, String... groups) throws java.io.IOException {
        InputStream in = RiderFitSVA.class.getResourceAsStream("/assets/siliconagevehicles/models/" + model + ".obj");
        if (in == null) {
            return null;
        }
        java.util.Set<String> want = new java.util.HashSet<String>(java.util.Arrays.asList(groups));
        List<float[]> v = new ArrayList<float[]>();
        float[] b = null;
        boolean take = false;
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 1 << 16);
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("v ")) {
                    String[] p = line.trim().split("\\s+");
                    v.add(new float[] {Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                } else if (line.startsWith("g ") || line.startsWith("o ") || line.startsWith("usemtl ")) {
                    take = want.contains(line.substring(line.indexOf(' ') + 1).trim());
                } else if (take && line.startsWith("f ")) {
                    for (String f : line.substring(2).trim().split("\\s+")) {
                        float[] q = v.get(Integer.parseInt(f.split("/")[0]) - 1);
                        if (b == null) {
                            b = new float[] {q[0], q[1], q[2], q[0], q[1], q[2]};
                        }
                        for (int a = 0; a < 3; a++) {
                            b[a] = Math.min(b[a], q[a]);
                            b[a + 3] = Math.max(b[a + 3], q[a]);
                        }
                    }
                }
            }
        } finally {
            r.close();
        }
        return b;
    }

    /** Does the ray from o along d hit any triangle? */
    static boolean hits(float[][] tris, double ox, double oy, double oz, float[] d) {
        for (float[] t : tris) {
            double e1x = t[3] - t[0], e1y = t[4] - t[1], e1z = t[5] - t[2];
            double e2x = t[6] - t[0], e2y = t[7] - t[1], e2z = t[8] - t[2];
            double px = d[1] * e2z - d[2] * e2y, py = d[2] * e2x - d[0] * e2z, pz = d[0] * e2y - d[1] * e2x;
            double det = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(det) < 1e-12) {
                continue;
            }
            double inv = 1 / det;
            double sx = ox - t[0], sy = oy - t[1], sz = oz - t[2];
            double u = (sx * px + sy * py + sz * pz) * inv;
            if (u < 0 || u > 1) {
                continue;
            }
            double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
            double w = (d[0] * qx + d[1] * qy + d[2] * qz) * inv;
            if (w < 0 || u + w > 1) {
                continue;
            }
            if ((e2x * qx + e2y * qy + e2z * qz) * inv > 1e-6) {
                return true;
            }
        }
        return false;
    }

    /** Points of a box (blocks, vehicle frame) outside the hull, of all its surface points. */
    private static int outside(float[][] tris, float s, float ox, double[] lo, double[] hi, int[] total) {
        int n = 4, out = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                for (int k = 0; k < n; k++) {
                    if (i != 0 && i != n - 1 && j != 0 && j != n - 1 && k != 0 && k != n - 1) {
                        continue;                                   // surface points only
                    }
                    double x = lo[0] + 0.02 + (hi[0] - lo[0] - 0.04) * i / (n - 1);
                    double y = lo[1] + 0.02 + (hi[1] - lo[1] - 0.04) * j / (n - 1);
                    double z = lo[2] + 0.02 + (hi[2] - lo[2] - 0.04) * k / (n - 1);
                    int esc = 0;
                    for (float[] d : DIRS) {
                        if (!hits(tris, x / s + ox, y / s, z / s, d)) {
                            esc++;
                        }
                    }
                    if (esc >= 8) {
                        out++;
                    }
                    total[0]++;
                }
            }
        }
        return out;
    }

    /**
     * Rider of `seat` (seat offsets, seatY + seatDy) against the hull: {chest points outside, head points outside,
     * points per box}.
     */
    public static int[] check(VehicleType t, float[][] tris, int seat) {
        double[] o = t.seatOffsets[seat];
        double fx = o[0], fz = o[1], hip = t.seatY + t.seatDy(seat) + 0.203;
        int[] total = new int[1];
        int chest = outside(tris, t.modelScale, t.modelOffsetX, new double[] {fx - 0.117, hip + 0.35, fz - 0.234},
                new double[] {fx + 0.117, hip + 0.703, fz + 0.234}, total);
        total[0] = 0;
        int head = outside(tris, t.modelScale, t.modelOffsetX, new double[] {fx - 0.234, hip + 0.703, fz - 0.234},
                new double[] {fx + 0.234, hip + 1.172, fz + 0.234}, total);
        return new int[] {chest, head, total[0]};
    }
}
