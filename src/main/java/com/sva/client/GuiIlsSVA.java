package com.sva.client;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.block.TileEntityIlsSVA;
import com.sva.net.NetSVA;
import com.sva.vehicle.LinerIls;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiScreen;

/**
 * Liner wave 4: «Курсо-глиссадный маяк»'s screen - the landing course (with «ПО ВЗГЛЯДУ»: the player's facing), the glide
 * slope's angle, the ident (3-4 letters, typed), the frequency (108.10..111.95, 0.05 a click, Shift 1 MHz), the centre
 * line's offset (a beacon beside the strip); «СОХРАНИТЬ» sends them (NetSVA.IlsSet). Drawn with the liner's 2D kit
 * (LinerGfxSVA - the panel, the values, the buttons) so the standalone preview renders the very same screen
 * (paint()); clicks are hit-tested on the same rectangles.
 */
@SideOnly(Side.CLIENT)
public class GuiIlsSVA extends GuiScreen {

    private final TileEntityIlsSVA te;
    private final IlsScreenSVA.View v = new IlsScreenSVA.View();
    private int left, top;

    public GuiIlsSVA(TileEntityIlsSVA te) {
        this.te = te;
        LinerIls.Station s = te.st;
        v.course = (int) Math.round(s.course) % 360;
        v.angle10 = (int) Math.round(s.angle * 10);
        v.freq = s.freq;
        v.offset = s.offset;
        v.ident = s.ident;
        v.elev = (int) Math.round(s.elev);
        v.range = com.sva.ConfigSVA.linerIlsRange;
    }

    @Override
    public void initGui() {
        left = (width - IlsScreenSVA.W) / 2;
        top = (height - IlsScreenSVA.H) / 2;
        Keyboard.enableRepeatEvents(true);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void updateScreen() {
        if (te.isInvalid() || mc.thePlayer == null || mc.thePlayer.getDistanceSq(te.xCoord + 0.5, te.yCoord + 0.5, te.zCoord + 0.5) > 64) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glPushMatrix();
        GL11.glTranslatef(left, top, 0F);
        float lift = LinerGfxSVA.lift;
        LinerGfxSVA.lift = 0F;
        LinerGfxSVA.bright = 1F;
        HiResSVA.beginFrame();
        IlsScreenSVA.paint(v, mx - left, my - top);
        LinerGfxSVA.lift = lift;
        GL11.glPopMatrix();
        GL11.glPopAttrib();
        GL11.glColor4f(1F, 1F, 1F, 1F);
        super.drawScreen(mx, my, pt);
    }

    private int hit(int mx, int my) {
        int x = mx - left, y = my - top;
        for (int[] b : IlsScreenSVA.BUTTONS) {
            if (x >= b[1] && x < b[3] && y >= b[2] && y < b[4]) {
                return b[0];
            }
        }
        return -1;
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) {
        super.mouseClicked(mx, my, btn);
        if (btn != 0) {
            return;
        }
        int b = hit(mx, my);
        boolean big = isShiftKeyDown();
        v.identFocus = b == IlsScreenSVA.B_IDENT;
        switch (b) {
            case IlsScreenSVA.B_CRS_DN:
                v.course = ((v.course - (big ? 10 : 1)) % 360 + 360) % 360;
                break;
            case IlsScreenSVA.B_CRS_UP:
                v.course = (v.course + (big ? 10 : 1)) % 360;
                break;
            case IlsScreenSVA.B_LOOK:
                v.course = (int) LinerIls.suggestCourse(mc.thePlayer.rotationYaw);
                break;
            case IlsScreenSVA.B_ANG_DN:
                v.angle10 = Math.max((int) Math.round(LinerIls.ANGLE_MIN * 10), v.angle10 - 1);
                break;
            case IlsScreenSVA.B_ANG_UP:
                v.angle10 = Math.min((int) Math.round(LinerIls.ANGLE_MAX * 10), v.angle10 + 1);
                break;
            case IlsScreenSVA.B_FRQ_DN:
                v.freq = LinerIls.clampFreq(v.freq - (big ? 100 : 5));
                break;
            case IlsScreenSVA.B_FRQ_UP:
                v.freq = LinerIls.clampFreq(v.freq + (big ? 100 : 5));
                break;
            case IlsScreenSVA.B_OFF_DN:
                v.offset = Math.max(-LinerIls.OFFSET_MAX, v.offset - 1);
                break;
            case IlsScreenSVA.B_OFF_UP:
                v.offset = Math.min(LinerIls.OFFSET_MAX, v.offset + 1);
                break;
            case IlsScreenSVA.B_SAVE:
                NetSVA.CHANNEL.sendToServer(new NetSVA.IlsSet(te.xCoord, te.yCoord, te.zCoord, v.course, v.angle10, v.ident, v.freq, v.offset));
                mc.displayGuiScreen(null);
                return;
            case IlsScreenSVA.B_CANCEL:
                mc.displayGuiScreen(null);
                return;
            default:
                return;
        }
        mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.func_147674_a(
                new net.minecraft.util.ResourceLocation("gui.button.press"), 1.0F));
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (v.identFocus) {
            if (key == Keyboard.KEY_BACK && v.ident.length() > 0) {
                v.ident = v.ident.substring(0, v.ident.length() - 1);
                return;
            }
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_TAB) {
                v.identFocus = false;
                return;
            }
            char u = Character.toUpperCase(c);
            boolean ok = u >= 'A' && u <= 'Z' || u >= '0' && u <= '9' || u >= 'А' && u <= 'Я' || u == 'Ё';
            if (ok && v.ident.length() < 4) {
                v.ident += u;
                return;
            }
            if (key != Keyboard.KEY_ESCAPE) {
                return;
            }
        }
        if (key == Keyboard.KEY_ESCAPE || key == mc.gameSettings.keyBindInventory.getKeyCode()) {
            mc.displayGuiScreen(null);
        }
    }
}
