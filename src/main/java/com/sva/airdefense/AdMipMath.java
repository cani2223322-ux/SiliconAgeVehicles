package com.sva.airdefense;

/**
 * Multiblock wave: the mipmap levels of the air-defence model textures (client/AdModelSVA uploads them itself - its
 * 512 px tiles are not on the block atlas). Pure, so the self-test checks it: each level is the 2x2 box average of the
 * one above (an odd last row / column is averaged with itself), alpha included, rounded to nearest.
 */
public final class AdMipMath {

    private AdMipMath() {
    }

    /** ARGB pixels w x h -> the next level, max(1, w / 2) x max(1, h / 2). */
    public static int[] half(int[] src, int w, int h) {
        int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
        int[] out = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            int y0 = Math.min(h - 1, y * 2), y1 = Math.min(h - 1, y * 2 + 1);
            for (int x = 0; x < nw; x++) {
                int x0 = Math.min(w - 1, x * 2), x1 = Math.min(w - 1, x * 2 + 1);
                int a = src[y0 * w + x0], b = src[y0 * w + x1], c = src[y1 * w + x0], d = src[y1 * w + x1];
                int px = 0;
                for (int sh = 0; sh <= 24; sh += 8) {
                    int s = (a >>> sh & 255) + (b >>> sh & 255) + (c >>> sh & 255) + (d >>> sh & 255);
                    px |= ((s + 2) / 4) << sh;
                }
                out[y * nw + x] = px;
            }
        }
        return out;
    }

    /** Levels of a w x h texture down to 1 x 1 (512 -> 10). */
    public static int levels(int w, int h) {
        int n = 1;
        while (w > 1 || h > 1) {
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
            n++;
        }
        return n;
    }
}
