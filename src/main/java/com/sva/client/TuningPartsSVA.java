package com.sva.client;

import org.lwjgl.opengl.GL11;

import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.Movement;
import com.sva.vehicle.TuningMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.client.renderer.OpenGlHelper;

/**
 * Wave 2, ★8: installed upgrades drawn on the model as simple extra boxes, in the model frame RenderVehicleSVA sets up
 * (model units: x forward, y up, z right). Where they go comes from the type and the OBJ's bounding box, not per-model
 * numbers, so every vehicle gets them without hand placement:
 * - the body's length: VehicleType.modelOffsetX +- halfLength / modelScale (the crawler's trailer is not the body);
 * - its height: the OBJ's lowest point to the top of its middle strip (ObjModelSVA.coreTop - an antenna at the side
 *   doesn't count); its half width: the OBJ's z range (ground vehicles have nothing sticking out sideways);
 * - the light bar over the driver's seat, on the roof line there (ObjModelSVA.roofAt); parts never past the OBJ's ends.
 * Ground vehicles / the hoverbike: «Броня» - two steel side plates with ribs over the lower middle of the body;
 * «Люстра фар» - a bar with four lamps across the roof's front third (lit with the headlights); «Аккумулятор+» - a box with
 * a glowing cyan stripe on the tail. Aircraft (their bounding box is wings and rotors): «Доп. бак» - a belly tank;
 * the other upgrades show only in their effect. «Большие колёса» scale the wheels (RenderVehicleSVA). Config
 * vehicles.tuningVisuals.
 */
public final class TuningPartsSVA {

    /** Big wheels: the wheel parts drawn this much bigger, grown from their contact point. */
    public static final float BIG_WHEEL_SCALE = 1.25F;

    private TuningPartsSVA() {
    }

    /** Draws the upgrades' parts (lighting / colour material on, texturing off, as RenderVehicleSVA leaves it). */
    public static void render(EntityVehicleSVA v, VehicleType t, ObjModelSVA m, float shade, boolean lampsOn) {
        if (!ConfigSVA.tuningVisuals || m == null || m.bbMax[1] < m.bbMin[1]) {
            return;
        }
        int[] tune = v.getTuning();
        if (TuningMath.count(tune) == 0) {
            return;
        }
        float s = t.modelScale;
        float cx = t.modelOffsetX, hl = (float) (t.halfLength / s);
        float y0 = m.bbMin[1], hgt = Math.max(0.2F, m.coreTop - m.bbMin[1]);
        float zh = Math.max(Math.abs(m.bbMin[2]), Math.abs(m.bbMax[2]));
        boolean flies = t.movement.flies;
        if (!flies) {
            if (TuningMath.has(tune, TuningMath.ARMOR)) {
                armour(Math.max(cx - hl * 0.62F, m.bbMin[0] + 0.1F), Math.min(cx + hl * 0.55F, m.bbMax[0] - 0.1F), y0, hgt, zh, shade);
            }
            if (TuningMath.has(tune, TuningMath.LIGHT_BAR)) {
                float bx = (float) (t.seatOffsets[0][0] / s) + cx + 0.05F;             // over the driver
                lightBar(bx, m.roofAt(bx), zh, shade, lampsOn);
            }
            if (TuningMath.has(tune, TuningMath.BATTERY)) {
                battery(Math.max(cx - hl * (t.movement == Movement.HOVER ? 0.85F : 0.92F), m.bbMin[0] + 0.16F), y0, hgt, zh, shade);
            }
        } else if (TuningMath.has(tune, TuningMath.EXTRA_TANK)) {
            bellyTank(cx, hl, y0, hgt, shade);
        }
    }

    private static void armour(float xa, float xb, float y0, float hgt, float zh, float shade) {
        float ya = y0 + hgt * 0.22F, yb = y0 + hgt * 0.52F;
        for (int side = -1; side <= 1; side += 2) {
            float z = side * (zh + 0.02F);
            colour(0.30F, 0.32F, 0.36F, shade);
            box(xa, ya, Math.min(z, z + side * 0.045F), xb, yb, Math.max(z, z + side * 0.045F));
            colour(0.45F, 0.47F, 0.52F, shade);
            for (int i = 0; i < 4; i++) {
                float rx = xa + (xb - xa) * (0.15F + 0.23F * i);
                float zo = z + side * 0.045F;
                box(rx - 0.02F, ya + 0.02F, Math.min(zo, zo + side * 0.02F), rx + 0.02F, yb - 0.02F, Math.max(zo, zo + side * 0.02F));
            }
            colour(0.85F, 0.70F, 0.15F, shade);          // a thin hazard edge at the top
            float zo = z + side * 0.046F;
            box(xa, yb - 0.025F, Math.min(zo, zo + side * 0.004F), xb, yb, Math.max(zo, zo + side * 0.004F));
        }
    }

    private static void lightBar(float x, float top, float zh, float shade, boolean lit) {
        float w = zh * 0.62F;
        colour(0.12F, 0.12F, 0.14F, shade);
        box(x - 0.05F, top, -w, x + 0.05F, top + 0.07F, w);
        box(x - 0.02F, top - 0.04F, -w * 0.8F, x + 0.02F, top, -w * 0.7F);     // the two mounts
        box(x - 0.02F, top - 0.04F, w * 0.7F, x + 0.02F, top, w * 0.8F);
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        if (lit) {
            GL11.glDisable(GL11.GL_LIGHTING);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
            GL11.glColor4f(1F, 0.97F, 0.85F, 1F);
        } else {
            colour(0.70F, 0.72F, 0.70F, shade);
        }
        for (int i = 0; i < 4; i++) {
            float zc = -w + w * 2 * (i + 0.5F) / 4;
            box(x + 0.05F, top + 0.012F, zc - w * 0.18F, x + 0.06F, top + 0.058F, zc + w * 0.18F);
        }
        if (lit) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
            GL11.glEnable(GL11.GL_LIGHTING);
        }
    }

    private static void battery(float xb, float y0, float hgt, float zh, float shade) {
        float xa = xb - 0.16F, ya = y0 + hgt * 0.28F, yb = ya + Math.min(0.22F, hgt * 0.25F);
        float w = Math.min(zh * 0.45F, 0.35F);
        colour(0.18F, 0.20F, 0.24F, shade);
        box(xa, ya, -w, xb + 0.02F, yb, w);
        colour(0.85F, 0.70F, 0.15F, shade);
        box(xa - 0.005F, yb - 0.035F, -w * 0.9F, xa, yb - 0.015F, -w * 0.2F);   // the yellow label
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glColor4f(0.35F * shade, 0.85F * shade, 1F * shade, 1F);
        box(xa - 0.005F, ya + 0.03F, -w * 0.9F, xa, ya + 0.05F, w * 0.9F);        // the charge stripe glows
        GL11.glEnable(GL11.GL_LIGHTING);
    }

    private static void bellyTank(float cx, float hl, float y0, float hgt, float shade) {
        float xa = cx - hl * 0.35F, xb = cx + hl * 0.25F, yc = y0 + hgt * 0.18F, r = Math.min(0.14F, hgt * 0.08F);
        colour(0.78F, 0.80F, 0.84F, shade);
        // an octagonal prism along x with tapered ends
        for (int k = 0; k < 8; k++) {
            double a0 = k * Math.PI / 4, a1 = (k + 1) * Math.PI / 4;
            float y0a = yc + (float) Math.sin(a0) * r, z0a = (float) Math.cos(a0) * r;
            float y1a = yc + (float) Math.sin(a1) * r, z1a = (float) Math.cos(a1) * r;
            float ny = (float) Math.sin((a0 + a1) / 2), nz = (float) Math.cos((a0 + a1) / 2);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glNormal3f(0F, ny, nz);
            GL11.glVertex3f(xa, y0a, z0a);
            GL11.glVertex3f(xb, y0a, z0a);
            GL11.glVertex3f(xb, y1a, z1a);
            GL11.glVertex3f(xa, y1a, z1a);
            GL11.glEnd();
            GL11.glBegin(GL11.GL_TRIANGLES);
            GL11.glNormal3f(1F, 0F, 0F);
            GL11.glVertex3f(xb + r * 1.5F, yc, 0F);
            GL11.glVertex3f(xb, y1a, z1a);
            GL11.glVertex3f(xb, y0a, z0a);
            GL11.glNormal3f(-1F, 0F, 0F);
            GL11.glVertex3f(xa - r, yc, 0F);
            GL11.glVertex3f(xa, y0a, z0a);
            GL11.glVertex3f(xa, y1a, z1a);
            GL11.glEnd();
        }
        colour(0.30F, 0.32F, 0.36F, shade);
        box(cx - hl * 0.15F, yc + r * 0.8F, -0.03F, cx + hl * 0.05F, yc + r * 1.6F, 0.03F);   // the pylon
    }

    private static void colour(float r, float g, float b, float shade) {
        GL11.glColor4f(r * shade, g * shade, b * shade, 1F);
    }

    /** An axis-aligned box with face normals. */
    static void box(float x0, float y0, float z0, float x1, float y1, float z1) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glNormal3f(0F, 1F, 0F);
        GL11.glVertex3f(x0, y1, z0);
        GL11.glVertex3f(x0, y1, z1);
        GL11.glVertex3f(x1, y1, z1);
        GL11.glVertex3f(x1, y1, z0);
        GL11.glNormal3f(0F, -1F, 0F);
        GL11.glVertex3f(x0, y0, z0);
        GL11.glVertex3f(x1, y0, z0);
        GL11.glVertex3f(x1, y0, z1);
        GL11.glVertex3f(x0, y0, z1);
        GL11.glNormal3f(1F, 0F, 0F);
        GL11.glVertex3f(x1, y0, z0);
        GL11.glVertex3f(x1, y1, z0);
        GL11.glVertex3f(x1, y1, z1);
        GL11.glVertex3f(x1, y0, z1);
        GL11.glNormal3f(-1F, 0F, 0F);
        GL11.glVertex3f(x0, y0, z0);
        GL11.glVertex3f(x0, y0, z1);
        GL11.glVertex3f(x0, y1, z1);
        GL11.glVertex3f(x0, y1, z0);
        GL11.glNormal3f(0F, 0F, 1F);
        GL11.glVertex3f(x0, y0, z1);
        GL11.glVertex3f(x1, y0, z1);
        GL11.glVertex3f(x1, y1, z1);
        GL11.glVertex3f(x0, y1, z1);
        GL11.glNormal3f(0F, 0F, -1F);
        GL11.glVertex3f(x0, y0, z0);
        GL11.glVertex3f(x0, y1, z0);
        GL11.glVertex3f(x1, y1, z0);
        GL11.glVertex3f(x1, y0, z0);
        GL11.glEnd();
    }
}
