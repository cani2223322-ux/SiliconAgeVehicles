package com.sva.client;


import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.entity.EntityVehicleSVA;
import com.sva.net.NetSVA;
import com.sva.vehicle.LinerMcdu;
import com.sva.vehicle.LinerSystems;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiScreen;

/**
 * Liner wave 5 (mock-up veh_liner_14, «Как открыть в игре»): the MCDU's keyboard screen - the big screen (what the server
 * renders, McduScreenSVA) with the A320's keys around it: 6 + 6 line keys, the function keys in Russian, the arrows, the
 * Cyrillic letters, the digits, «/», «+/-», СБР, ПРОЛ, ПРОБ. The mouse presses the keys; the PC keyboard types into the
 * scratchpad (Latin and Cyrillic letters, digits), Enter = the line key 6 right, Backspace / Delete = СБР, the arrows
 * scroll / turn the pages, F1..F6 / F7..F12 = the line keys, Esc closes. Every key is a NetSVA.McduKey to the server
 * (the pilots only); the game goes on (no pause) - the aircraft keeps flying on the AP. Drawn in its own units (W x H,
 * fitted to the window) with LinerGfxSVA like the cockpit's faces.
 */
@SideOnly(Side.CLIENT)
public class GuiMcduSVA extends GuiScreen {

    private final EntityVehicleSVA v;
    private final int side;
    private float ox, oy, k;
    private McduPanelSVA.Key pressed;
    private long pressedAt;
    private boolean opened;

    public GuiMcduSVA(EntityVehicleSVA v, int side) {
        this.v = v;
        this.side = Math.max(0, Math.min(1, side));
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        k = Math.min((width - 8) / McduPanelSVA.W, (height - 22) / McduPanelSVA.H);
        ox = (width - McduPanelSVA.W * k) / 2;
        oy = Math.max(2, (height - 14 - McduPanelSVA.H * k) / 2);
        if (!opened) {
            opened = true;
            send(LinerMcdu.K_OPEN);
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    private void send(int code) {
        if (code == LinerMcdu.K_BRT || code == LinerMcdu.K_DIM) {
            McduPanelSVA.BRIGHT[side] = Math.max(0.35F, Math.min(1F, McduPanelSVA.BRIGHT[side] + (code == LinerMcdu.K_BRT ? 0.15F : -0.15F)));
            return;
        }
        if (!LinerClickSVA.pilotSeat(mc.thePlayer, v)) {
            return;
        }
        NetSVA.CHANNEL.sendToServer(new NetSVA.McduKey(v.getEntityId(), side, code));
    }

    private void press(McduPanelSVA.Key key) {
        pressed = key;
        pressedAt = System.currentTimeMillis();
        send(key.code);
        mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.func_147674_a(
                new net.minecraft.util.ResourceLocation("gui.button.press"), 2.0F));
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        LinerSystems L = v.liner();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glPushMatrix();
        GL11.glTranslatef(ox, oy, 0F);
        GL11.glScalef(k, k, 1F);
        float lift = LinerGfxSVA.lift;
        LinerGfxSVA.lift = 0F;
        LinerGfxSVA.bright = 1F;
        LinerGfxSVA.resetLayer();
        HiResSVA.beginFrame();
        paint(L, (mx - ox) / k, (my - oy) / k);
        LinerGfxSVA.lift = lift;
        LinerGfxSVA.bright = 1F;
        GL11.glPopMatrix();
        GL11.glPopAttrib();
        GL11.glColor4f(1F, 1F, 1F, 1F);
        String hint = LinerClickSVA.pilotSeat(mc.thePlayer, v) ? Lang.tr("sva.mcdu.gui.hint") : Lang.tr("sva.liner.gui.readOnly");
        drawCenteredString(fontRendererObj, hint, width / 2, height - 11, 0xB8BCC4);
        super.drawScreen(mx, my, pt);
    }

    /** The panel, the screen, the keys (panel units; the mouse at (mx, my)). */
    void paint(LinerSystems L, float mx, float my) {
        McduPanelSVA.paintPanel(side, L == null || L.powered(), McduClientSVA.screen(v.getEntityId(), side), mx, my, pressed,
                System.currentTimeMillis() - pressedAt < 160);
    }

    private McduPanelSVA.Key keyAt(int mx, int my) {
        float x = (mx - ox) / k, y = (my - oy) / k;
        for (McduPanelSVA.Key key : McduPanelSVA.KEYS) {
            if (x >= key.x0 && x <= key.x1 && y >= key.y0 && y <= key.y1) {
                return key;
            }
        }
        return null;
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) {
        super.mouseClicked(mx, my, btn);
        McduPanelSVA.Key key = keyAt(mx, my);
        if (key != null && btn == 0) {
            press(key);
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        int code = -1;
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            code = LinerMcdu.K_R1 + 5;
        } else if (key == Keyboard.KEY_BACK || key == Keyboard.KEY_DELETE) {
            code = LinerMcdu.K_CLR;
        } else if (key == Keyboard.KEY_UP || key == Keyboard.KEY_PRIOR) {
            code = LinerMcdu.K_UP;
        } else if (key == Keyboard.KEY_DOWN || key == Keyboard.KEY_NEXT) {
            code = LinerMcdu.K_DOWN;
        } else if (key == Keyboard.KEY_LEFT) {
            code = LinerMcdu.K_PREV;
        } else if (key == Keyboard.KEY_RIGHT) {
            code = LinerMcdu.K_NEXT;
        } else if (key >= Keyboard.KEY_F1 && key <= Keyboard.KEY_F6) {
            code = LinerMcdu.K_L1 + key - Keyboard.KEY_F1;
        } else if (key >= Keyboard.KEY_F7 && key <= Keyboard.KEY_F10) {
            code = LinerMcdu.K_R1 + key - Keyboard.KEY_F7;
        } else if (key == Keyboard.KEY_F11 || key == Keyboard.KEY_F12) {
            code = LinerMcdu.K_R1 + 4 + (key == Keyboard.KEY_F12 ? 1 : 0);
        } else if (c == ' ') {
            code = LinerMcdu.K_SP;
        } else if (LinerMcdu.norm(c) != 0) {
            code = LinerMcdu.K_CHAR + LinerMcdu.norm(c);
        }
        if (code >= 0) {
            for (McduPanelSVA.Key k2 : McduPanelSVA.KEYS) {
                if (k2.code == code) {
                    pressed = k2;
                    pressedAt = System.currentTimeMillis();
                }
            }
            send(code);
        }
    }

    @Override
    public void updateScreen() {
        if (v.isDead || HudVehicleSVA.vehicleOf(mc.thePlayer) != v) {
            mc.displayGuiScreen(null);
        }
    }
}
