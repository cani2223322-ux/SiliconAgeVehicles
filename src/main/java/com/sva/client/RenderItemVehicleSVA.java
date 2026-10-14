package com.sva.client;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.sva.ConfigSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.IItemRenderer;

/**
 * ★16 a vehicle / trailer item drawn as its 3D model (mock-up veh_design_7_icons.png): in a slot a small model seen from
 * a little above, turning once in 4 s (it stops while the mouse is over the slot), in the hand like a held block, on the
 * ground spinning and bobbing like any dropped item (Forge's ENTITY_ROTATION / ENTITY_BOBBING helpers). The vehicle's
 * moving parts are drawn at rest (rotor, propeller, bore, nacelles up; the truck without its trailer). The trailer item
 * is truck.obj's "trailer" part alone. Scaled to fit from the model's bounding box (read once per model). Config
 * vehicles.icons3d = false: the flat 16x16 icons (this renderer then declines every type).
 * GL state: everything it touches is pushed and popped (enable bits, lighting incl. the light set-up, colour, depth,
 * the bound texture), so the slots and text drawn after stay as they were.
 */
@SideOnly(Side.CLIENT)
public class RenderItemVehicleSVA implements IItemRenderer {

    /** The trailer item instead of a vehicle. */
    private final boolean trailer;

    /** model (or "truck#trailer") -> {minX, minY, minZ, maxX, maxY, maxZ}. */
    private static final Map<String, float[]> BOUNDS = new HashMap<String, float[]>();
    /** Per stack in a slot: {angle (deg), last time (ms)} - the turn pauses under the mouse. */
    private static final WeakHashMap<ItemStack, float[]> SPIN = new WeakHashMap<ItemStack, float[]>();
    /**
     * The stack under the mouse this frame (worked out once a frame from GuiContainer's slots, no glGet a slot) and
     * whether it is worked out yet; RenderTickEvent START clears it.
     */
    private static ItemStack hoverStack;
    private static boolean hoverKnown, ticking;
    private static Field guiLeft, guiTop;
    private static boolean noFields;

    /** truck.obj: the trailer is everything behind x -1.25 (ObjModelSVA's "trailer" part). */
    private static final float TRAILER_X = -1.25F;

    public RenderItemVehicleSVA(boolean trailer) {
        this.trailer = trailer;
        if (!ticking) {
            ticking = true;
            FMLCommonHandler.instance().bus().register(new FrameReset());
        }
    }

    /** FML bus: a new frame - the hovered slot is looked up again. */
    public static final class FrameReset {
        @SubscribeEvent
        public void onRender(TickEvent.RenderTickEvent e) {
            if (e.phase == TickEvent.Phase.START) {
                hoverKnown = false;
                hoverStack = null;
            }
        }
    }

    /**
     * Reads every vehicle / trailer model (ObjModelSVA) and its item bounding box now (client post-init), so the first
     * look at the creative tab / NEI or the first vehicle in sight does not stall a frame.
     */
    public static void preloadModels() {
        for (VehicleType t : VehicleType.values()) {
            if (t.model == null || t == VehicleType.LINER) {
                continue;                                           // liner wave 1: its own model (LinerModelSVA)
            }
            ObjModelSVA.get(t.model);
            preBounds(t.model, t.model, t.trailerSlots > 0 ? -1 : 0);
        }
        preBounds(VehicleType.TRUCK.model + "#trailer", VehicleType.TRUCK.model, 1);
        for (String m : com.sva.vehicle.TrailerMath.MODELS) {
            if (m != null) {
                ObjModelSVA.get(m);
                preBounds(m, m, 0);
            }
        }
    }

    private static void preBounds(String key, String model, int cut) {
        if (BOUNDS.containsKey(key)) {
            return;
        }
        float[] b;
        try {
            b = readBounds(model, cut);
        } catch (Exception e) {
            b = null;
        }
        BOUNDS.put(key, b);
    }

    private VehicleType type(ItemStack s) {
        return trailer ? VehicleType.TRUCK : ItemVehicleSVA.typeOf(s);
    }

    /** ★15: the trailer kind of a trailer item (CARGO for a vehicle item). */
    private int kind(ItemStack s) {
        return trailer ? com.sva.item.ItemTrailerSVA.kindOf(s) : com.sva.vehicle.TrailerMath.CARGO;
    }

    /** The model drawn: the vehicle's, truck.obj for the cargo trailer, ★15 a trailer kind's own model. */
    private String modelOf(ItemStack s) {
        int k = kind(s);
        return k != com.sva.vehicle.TrailerMath.CARGO ? com.sva.vehicle.TrailerMath.MODELS[k] : type(s).model;
    }

    @Override
    public boolean handleRenderType(ItemStack item, ItemRenderType type) {
        if (!ConfigSVA.icons3d || item == null) {
            return false;
        }
        boolean ok = type == ItemRenderType.INVENTORY || type == ItemRenderType.ENTITY || type == ItemRenderType.EQUIPPED
                || type == ItemRenderType.EQUIPPED_FIRST_PERSON;
        if (!trailer && type(item) == VehicleType.LINER) {
            return ok;                                              // liner wave 1: LinerModelSVA's LOD2
        }
        return ok && ObjModelSVA.get(modelOf(item)) != null && bounds(item) != null;
    }

    @Override
    public boolean shouldUseRenderHelper(ItemRenderType type, ItemStack item, ItemRendererHelper helper) {
        switch (helper) {
            case ENTITY_ROTATION:
            case ENTITY_BOBBING:
            case BLOCK_3D:
            case EQUIPPED_BLOCK:
                return true;
            default:
                return false;                                       // INVENTORY_BLOCK: our own slot transform
        }
    }

    @Override
    public void renderItem(ItemRenderType type, ItemStack item, Object... data) {
        boolean liner = !trailer && type(item) == VehicleType.LINER;
        ObjModelSVA m = liner ? null : ObjModelSVA.get(modelOf(item));
        float[] b = liner ? LinerModelSVA.ITEM_BOUNDS : bounds(item);
        boolean kindModel = kind(item) != com.sva.vehicle.TrailerMath.CARGO;
        if (!liner && (m == null || b == null)) {
            return;
        }
        if (!liner) {
            // the model is shared: set the item's own skin, not the last drawn vehicle's (tier livery, board 1, at rest)
            m.style(com.sva.vehicle.SkinMath.livery(type(item).tier), 1);
            m.anim(0F, 1F);
            m.cabin(-1F, 0);
            m.band(-1);
        }
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_TRANSFORM_BIT);
        GL11.glPushMatrix();
        try {
            float cx = (b[0] + b[3]) / 2, cz = (b[2] + b[5]) / 2;
            float hx = (b[3] - b[0]) / 2, hz = (b[5] - b[2]) / 2, height = b[4] - b[1];
            float radius = (float) Math.sqrt(hx * hx + hz * hz);       // it turns: the horizontal half-diagonal
            if (type == ItemRenderType.INVENTORY) {
                // the slot: 16 x 16 GUI pixels from (0, 0), y down; a 25 deg look from above, turning about the vertical
                float tilt = 25F;
                double tr = Math.toRadians(tilt);
                float s = Math.min(15F / (2 * radius), 15F / (float) (height * Math.cos(tr) + 2 * radius * Math.sin(tr)));
                GL11.glTranslatef(8F, 8.5F, 50F);
                RenderHelper.enableGUIStandardItemLighting();          // before the turn: the light stays put
                GL11.glRotatef(180F, 1F, 0F, 0F);                      // y up, z away (no mirror)
                GL11.glRotatef(-tilt, 1F, 0F, 0F);
                GL11.glRotatef(spin(item), 0F, 1F, 0F);
                GL11.glScalef(s, s, s);
                GL11.glTranslatef(-cx, -(b[1] + height / 2), -cz);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDepthMask(true);
            } else if (type == ItemRenderType.ENTITY) {
                // Forge has scaled by 0.5 (BLOCK_3D) and turns / bobs it: ~0.75 blocks long, standing on the item's origin
                float s = 1.5F / (2 * Math.max(radius, height / 2));
                GL11.glScalef(s, s, s);
                GL11.glTranslatef(-cx, -b[1], -cz);
            } else {
                // EQUIPPED / first person (EQUIPPED_BLOCK: the held-block cube 0..1): the model fills it, nose ahead
                float s = 1.1F / (2 * Math.max(radius, height / 2));
                GL11.glTranslatef(0.5F, 0.5F, 0.5F);
                if (type == ItemRenderType.EQUIPPED_FIRST_PERSON) {
                    GL11.glRotatef(-45F, 0F, 1F, 0F);
                }
                GL11.glScalef(s, s, s);
                GL11.glTranslatef(-cx, -(b[1] + height / 2), -cz);
            }
            GL11.glEnable(GL11.GL_NORMALIZE);
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_COLOR_MATERIAL);
            GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            if (liner) {
                LinerModelSVA.renderItem();
            } else if (trailer && !kindModel) {
                for (ObjModelSVA.Part p : m.parts()) {
                    if (p.kind == ObjModelSVA.Part.TRAILER || p.def != null && p.def.trailer) {
                        m.renderPart(p, 0F, 1F);                   // the cargo trailer and its two wheels
                    }
                }
            } else {
                m.render(1F, ObjModelSVA.GLASS_ALPHA, 1F, null);
                for (ObjModelSVA.Part p : m.parts()) {
                    if (p.kind == ObjModelSVA.Part.TRAILER || p.def != null && p.def.trailer) {
                        continue;                                  // a crawler item comes without its trailer
                    }
                    if (p.kind == ObjModelSVA.Part.NACELLE) {
                        m.renderNacelle(p, 0F, 0F, 1F, 0F);
                    } else {
                        m.renderPart(p, 0F, 1F);
                    }
                }
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
            GL11.glColor4f(1F, 1F, 1F, 1F);
        }
    }

    /**
     * The slot's turn: 90 deg/s (one turn in 4 s), paused while the mouse is over this slot (an open GuiContainer; the
     * slot under the mouse is found once a frame from guiLeft / guiTop + the slot's place).
     */
    private static float spin(ItemStack item) {
        long now = Minecraft.getSystemTime();
        float[] st = SPIN.get(item);
        if (st == null) {
            st = new float[] {(now % 4000L) * 0.09F + 30F, now};
            SPIN.put(item, st);
        }
        float dt = Math.min(250F, Math.max(0F, now - st[1]));
        st[1] = now;
        if (!hovered(item)) {
            st[0] = (st[0] + dt * 0.09F) % 360F;
        }
        return st[0];
    }

    private static boolean hovered(ItemStack item) {
        if (!hoverKnown) {
            hoverKnown = true;
            hoverStack = findHovered();
        }
        return hoverStack != null && hoverStack == item;
    }

    /** The stack in the GuiContainer slot under the mouse, or null (no container open / the fields unreadable). */
    private static ItemStack findHovered() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!(mc.currentScreen instanceof GuiContainer) || noFields) {
            return null;
        }
        GuiContainer g = (GuiContainer) mc.currentScreen;
        int left, top;
        try {
            if (guiLeft == null) {
                guiLeft = ReflectionHelper.findField(GuiContainer.class, "guiLeft", "field_147003_i");
                guiTop = ReflectionHelper.findField(GuiContainer.class, "guiTop", "field_147009_r");
            }
            left = guiLeft.getInt(g);
            top = guiTop.getInt(g);
        } catch (Exception e) {
            noFields = true;                                        // never again: the slots then just keep turning
            return null;
        }
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        float mx = Mouse.getX() * sr.getScaledWidth() / (float) mc.displayWidth;
        float my = sr.getScaledHeight() - Mouse.getY() * sr.getScaledHeight() / (float) mc.displayHeight - 1;
        if (g.inventorySlots == null) {
            return null;
        }
        for (Object o : g.inventorySlots.inventorySlots) {
            Slot sl = (Slot) o;
            int x = left + sl.xDisplayPosition, y = top + sl.yDisplayPosition;
            if (mx >= x && mx < x + 16 && my >= y && my < y + 16) {
                return sl.getStack();
            }
        }
        return null;
    }

    /**
     * The bounding box to fit: the vehicle (the truck without its trailer), the cargo trailer alone or (★15) a trailer
     * kind's whole model; null if unreadable.
     */
    private float[] bounds(ItemStack item) {
        VehicleType t = type(item);
        boolean kindModel = kind(item) != com.sva.vehicle.TrailerMath.CARGO;
        String model = modelOf(item);
        String key = trailer && !kindModel ? t.model + "#trailer" : model;
        if (!BOUNDS.containsKey(key)) {
            float[] b = null;
            try {
                b = readBounds(model, kindModel ? 0 : trailer ? 1 : t.trailerSlots > 0 ? -1 : 0);
            } catch (Exception e) {
                b = null;
            }
            BOUNDS.put(key, b);
        }
        return BOUNDS.get(key);
    }

    /** All vertices of models/NAME.obj; cut 1: only x < TRAILER_X (the trailer), -1: only x >= TRAILER_X, 0: all. */
    private static float[] readBounds(String model, int cut) throws java.io.IOException {
        InputStream in = RenderItemVehicleSVA.class.getResourceAsStream("/assets/siliconagevehicles/models/" + model + ".obj");
        if (in == null) {
            return null;
        }
        float[] b = null;
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 1 << 16);
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("v ")) {
                    continue;
                }
                String[] p = line.trim().split("\\s+");
                float x = Float.parseFloat(p[1]), y = Float.parseFloat(p[2]), z = Float.parseFloat(p[3]);
                if (cut > 0 && x >= TRAILER_X || cut < 0 && x < TRAILER_X) {
                    continue;
                }
                if (b == null) {
                    b = new float[] {x, y, z, x, y, z};
                }
                b[0] = Math.min(b[0], x);
                b[1] = Math.min(b[1], y);
                b[2] = Math.min(b[2], z);
                b[3] = Math.max(b[3], x);
                b[4] = Math.max(b[4], y);
                b[5] = Math.max(b[5], z);
            }
        } finally {
            r.close();
        }
        return b;
    }
}
