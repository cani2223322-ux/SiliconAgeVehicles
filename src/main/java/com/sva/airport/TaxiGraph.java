package com.sva.airport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Airport wave B2 (plan-next §9, item 27): «Следуй за мной» - the taxiway lights as a graph and a cleared aircraft's route
 * through it. Pure (the self-test builds graphs on numbers):
 * <ul>
 * <li><b>Nodes</b>: the green centre-line lights («Огонь рулёжки»), the stands (a yellow stand light) and the holding points
 * (a red stop bar across the taxiway). The blue edge lights are no nodes (they only burn with the tower's taxi step).</li>
 * <li><b>Edges</b> (build()): AUTO from adjacency - two nodes link when each is the other's NEAREST in its compass sector (8
 * sectors) within LINK blocks (a straight line of lights links neighbour to neighbour, never skipping one; a crossing links
 * its arms, a corner may be cut by its diagonal); the tower's map adds links (`extra`, «a-b» key pairs) or cuts them
 * (`cut`).</li>
 * <li><b>A route</b> (route()): the shortest path (Dijkstra by length) from the node nearest the aircraft to the target -
 * a departure: the holding point (stop bar) nearest the runway in use's threshold; an arrival: the nearest stand.</li>
 * <li><b>Progressive lights</b>: the aircraft's place along its route (progress(): the nearest route node within REACH,
 * never moving back) - the next AHEAD nodes light up, the ones behind go out (lit()).</li>
 * <li><b>Stop bars</b>: red across the holding point, lit while the tower is on; the one an aircraft is cleared through
 * (line-up / take-off) goes out until it has passed (or STOP_RELIGHT ticks).</li>
 * </ul>
 */
public final class TaxiGraph {

    /** Node kinds: centre line (green), stand (yellow), holding point (red stop bar). */
    public static final int CENTRE = 0, STAND = 1, STOP = 2;
    /** Auto links reach (blocks); the aircraft is «at» a node this close; lights lit ahead (nodes). */
    public static final double LINK = 9, REACH = 6;
    public static final int AHEAD = 10;
    /** A stop bar dropped for a clearance comes back after this long anyway (ticks). */
    public static final int STOP_RELIGHT = 1200;

    private TaxiGraph() {
    }

    public static final class Node {
        public final String key;
        public final int x, y, z, kind;

        public Node(String key, int x, int y, int z, int kind) {
            this.key = key;
            this.x = x;
            this.y = y;
            this.z = z;
            this.kind = kind;
        }

        double dist(Node o) {
            return Math.sqrt((double) (x - o.x) * (x - o.x) + (double) (z - o.z) * (z - o.z) + 0.25 * (y - o.y) * (y - o.y));
        }
    }

    /** The link's key of two node keys (order-free). */
    public static String link(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    /** The compass sector (0..7) of the direction a -> b. */
    static int sector(Node a, Node b) {
        double ang = Math.toDegrees(Math.atan2(b.x - a.x, -(b.z - a.z)));
        return (int) Math.floor(((ang + 22.5) % 360 + 360) % 360 / 45) & 7;
    }

    /** The adjacency lists (indices) of the nodes (see the class comment). */
    public static List<List<Integer>> build(List<Node> nodes, Set<String> extra, Set<String> cut) {
        int n = nodes.size();
        List<List<Integer>> adj = new ArrayList<List<Integer>>();
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<Integer>());
        }
        int[][] best = new int[n][8];
        for (int i = 0; i < n; i++) {
            Node a = nodes.get(i);
            double[] bd = new double[8];
            Arrays.fill(best[i], -1);
            Arrays.fill(bd, Double.MAX_VALUE);
            for (int j = 0; j < n; j++) {
                if (j == i || Math.abs(nodes.get(j).y - a.y) > 3) {
                    continue;
                }
                double d = a.dist(nodes.get(j));
                if (d > LINK) {
                    continue;
                }
                int s = sector(a, nodes.get(j));
                if (d < bd[s]) {
                    bd[s] = d;
                    best[i][s] = j;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            for (int s = 0; s < 8; s++) {
                int j = best[i][s];
                if (j >= 0 && best[j][sector(nodes.get(j), nodes.get(i))] == i) {
                    connect(adj, i, j);                             // mutual: each the other's nearest that way
                }
            }
        }
        if (extra != null) {
            for (String e : extra) {
                int[] ij = ends(nodes, e);
                if (ij != null) {
                    connect(adj, ij[0], ij[1]);
                }
            }
        }
        if (cut != null) {
            for (String e : cut) {
                int[] ij = ends(nodes, e);
                if (ij != null) {
                    adj.get(ij[0]).remove(Integer.valueOf(ij[1]));
                    adj.get(ij[1]).remove(Integer.valueOf(ij[0]));
                }
            }
        }
        return adj;
    }

    static void connect(List<List<Integer>> adj, int i, int j) {
        if (!adj.get(i).contains(Integer.valueOf(j))) {
            adj.get(i).add(Integer.valueOf(j));
        }
        if (!adj.get(j).contains(Integer.valueOf(i))) {
            adj.get(j).add(Integer.valueOf(i));
        }
    }

    /** A link key's two node indices (null: a node gone). */
    static int[] ends(List<Node> nodes, String link) {
        int bar = link.indexOf('|');
        if (bar < 0) {
            return null;
        }
        int a = index(nodes, link.substring(0, bar)), b = index(nodes, link.substring(bar + 1));
        return a < 0 || b < 0 || a == b ? null : new int[] {a, b};
    }

    public static int index(List<Node> nodes, String key) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).key.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** The node nearest (x, z) within `max` blocks, of a kind (-1: any), -1 none. */
    public static int nearest(List<Node> nodes, double x, double z, double max, int kind) {
        int best = -1;
        double bd = max * max;
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (kind >= 0 && n.kind != kind) {
                continue;
            }
            double d = (n.x + 0.5 - x) * (n.x + 0.5 - x) + (n.z + 0.5 - z) * (n.z + 0.5 - z);
            if (d <= bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    /** The shortest path from -> to (node indices, both included); empty: no way. */
    public static List<Integer> route(List<Node> nodes, List<List<Integer>> adj, int from, int to) {
        List<Integer> out = new ArrayList<Integer>();
        int n = nodes.size();
        if (from < 0 || to < 0 || from >= n || to >= n) {
            return out;
        }
        double[] dist = new double[n];
        int[] prev = new int[n];
        boolean[] done = new boolean[n];
        Arrays.fill(dist, Double.MAX_VALUE);
        Arrays.fill(prev, -1);
        dist[from] = 0;
        for (int k = 0; k < n; k++) {
            int u = -1;
            for (int i = 0; i < n; i++) {
                if (!done[i] && dist[i] < Double.MAX_VALUE && (u < 0 || dist[i] < dist[u])) {
                    u = i;
                }
            }
            if (u < 0 || u == to) {
                break;
            }
            done[u] = true;
            for (Integer v : adj.get(u)) {
                double d = dist[u] + nodes.get(u).dist(nodes.get(v.intValue()));
                if (d < dist[v.intValue()]) {
                    dist[v.intValue()] = d;
                    prev[v.intValue()] = u;
                }
            }
        }
        if (dist[to] == Double.MAX_VALUE) {
            return out;
        }
        for (int v = to; v >= 0; v = prev[v]) {
            out.add(Integer.valueOf(v));
        }
        Collections.reverse(out);
        return out;
    }

    /** The holding point (STOP node) nearest a runway threshold (x, z), -1 none. */
    public static int holdingPoint(List<Node> nodes, double tx, double tz) {
        return nearest(nodes, tx, tz, Double.MAX_VALUE / 4, STOP);
    }

    /**
     * The aircraft's progress along its route: the index of the route node nearest it within REACH, searched from `was`
     * (never back) up to a few nodes ahead; nothing within reach - `was`.
     */
    public static int progress(List<Node> nodes, List<Integer> route, int was, double x, double z) {
        int best = was;
        double bd = REACH * REACH;
        for (int i = Math.max(0, was); i < route.size() && i <= Math.max(0, was) + AHEAD; i++) {
            Node n = nodes.get(route.get(i).intValue());
            double d = (n.x + 0.5 - x) * (n.x + 0.5 - x) + (n.z + 0.5 - z) * (n.z + 0.5 - z);
            if (d <= bd) {
                bd = d;
                best = i;
            }
        }
        return Math.max(was, best);
    }

    /** The route's nodes lit for an aircraft at `progress`: progress .. progress + AHEAD (indices into the node list). */
    public static Set<Integer> lit(List<Integer> route, int progress, int ahead) {
        Set<Integer> out = new HashSet<Integer>();
        for (int i = Math.max(0, progress); i < route.size() && i <= Math.max(0, progress) + ahead; i++) {
            out.add(route.get(i));
        }
        return out;
    }

    /** The route has been taxied to its end (at the last node). */
    public static boolean arrived(List<Integer> route, int progress) {
        return !route.isEmpty() && progress >= route.size() - 1;
    }

    /** A stop bar burns: the tower on and not dropped for a clearance (dropped until `until`). */
    public static boolean stopLit(boolean towerOn, long droppedUntil, long now) {
        return towerOn && now >= droppedUntil;
    }

    /** The route as text for the clearance: the node count and its length (blocks) - «12 огней, 84 м-экв». */
    public static double length(List<Node> nodes, List<Integer> route) {
        double s = 0;
        for (int i = 1; i < route.size(); i++) {
            s += nodes.get(route.get(i - 1).intValue()).dist(nodes.get(route.get(i).intValue()));
        }
        return s;
    }
}
