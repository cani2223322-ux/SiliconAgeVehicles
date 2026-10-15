package com.sva.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.sva.airport.AptScale;
import com.sva.airport.FlightCheck;
import com.sva.airport.IlsCat;
import com.sva.airport.IlsPairing;
import com.sva.airport.RunwayLayout;
import com.sva.block.AirportDataSVA;
import com.sva.block.BlockIlsAntennaSVA;
import com.sva.block.TileEntityIlsAntennaSVA;
import com.sva.block.TileEntityRunwayEndSVA;
import com.sva.net.NetAptSVA;
import com.sva.vehicle.LinerIls;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

/**
 * Airport wave B1: the screen of an ILS antenna or a «Торец полосы» marker - drawn with the liner's 2D kit (LinerGfxSVA,
 * the ILS beacon's screen's look). Rows with - / + buttons (Shift: a bigger step):
 * <ul>
 * <li>the localizer: its ident (typed, 3-4 letters) and frequency (108,10..111,95 - the glide slope paired to it);</li>
 * <li>the glide slope mast: its angle (2,5..3,5°);</li>
 * <li>the runway end: the course down the runway («ПО ВЗГЛЯДУ» - the player's look), the width, this end's lights (АВТО /
 * ВЫКЛ / 1..5 - until the wave B2 tower this is their control), its approach lights (ALS).</li>
 * </ul>
 * Under them the state: the station (runway, category, the monitor's flags, the flight inspection) with every issue and
 * its hint - the server's (the antenna tile's status); a runway end: its runway (AirportClientSVA) or why it has none.
 * «СОХРАНИТЬ» sends the values (NetAptSVA SET_ANTENNA / SET_END).
 */
@SideOnly(Side.CLIENT)
public class GuiAptSVA extends GuiScreen {

    static final int W = 300, H = 236;
    static final int BG = 0x1B1F27, EDGE = 0x0B0D11, GOLD = 0xF2A040, TEXT = 0xD8DCE4, MUTED = 0x8A93A3, VALUE = 0x46E87A, BTN = 0x2E3542,
            BTN_EDGE = 0x5A6476, CYAN = 0x5ADCFF, AMBER = 0xF0B040, RED = 0xF06060;
    static final int K_LOC = 0, K_GS = 1, K_END = 2;
    static final int B_SAVE = 100, B_CANCEL = 101, B_LOOK = 102, B_IDENT = 103;

    private final TileEntity te;
    private final int kind;
    private int left, top;
    // the values
    private String ident = "";
    private int freq = LinerIls.FREQ_DEFAULT, angle10 = 30, course, rwyWidth = 5, lights = RunwayLayout.LIGHTS_AUTO, als;
    private boolean identFocus;

    public GuiAptSVA(TileEntity te) {
        this.te = te;
        if (te instanceof TileEntityIlsAntennaSVA) {
            TileEntityIlsAntennaSVA a = (TileEntityIlsAntennaSVA) te;
            kind = a.kind() == TileEntityIlsAntennaSVA.GS ? K_GS : K_LOC;
            ident = a.ident;
            freq = a.freq;
            angle10 = (int) Math.round(a.angle * 10);
        } else {
            kind = K_END;
            TileEntityRunwayEndSVA e = (TileEntityRunwayEndSVA) te;
            course = (int) Math.round(e.facing) % 360;
            rwyWidth = e.width;
            lights = e.lights;
            als = e.als;
        }
    }

    static String tr(String k, Object... a) {
        return LinerDisplaysSVA.trAny("sva.apt.gui." + k, a);
    }

    int rows() {
        return kind == K_LOC ? 2 : kind == K_GS ? 1 : 4;
    }

    /** Buttons: id, x0, y0, x1, y1 (the row's - is 2r, its + 2r + 1). */
    List<int[]> buttons() {
        List<int[]> b = new ArrayList<int[]>();
        for (int r = 0; r < rows(); r++) {
            int y = 26 + r * 24;
            if (kind == K_LOC && r == 0) {
                b.add(new int[] {B_IDENT, 124, y, 226, y + 16});
                continue;
            }
            b.add(new int[] {r * 2, 124, y, 142, y + 16});
            b.add(new int[] {r * 2 + 1, 208, y, 226, y + 16});
            if (kind == K_END && r == 0) {
                b.add(new int[] {B_LOOK, 230, y, 292, y + 16});
            }
        }
        b.add(new int[] {B_SAVE, 70, H - 22, 146, H - 6});
        b.add(new int[] {B_CANCEL, 154, H - 22, 230, H - 6});
        return b;
    }

    String label(int r) {
        if (kind == K_LOC) {
            return r == 0 ? tr("ident") : tr("freq");
        }
        if (kind == K_GS) {
            return tr("angle");
        }
        return r == 0 ? tr("course") : r == 1 ? tr("width") : r == 2 ? tr("lights") : tr("als");
    }

    String value(int r) {
        if (kind == K_LOC) {
            return r == 0 ? ident : LinerIls.freqText(freq).replace('.', ',');
        }
        if (kind == K_GS) {
            return LinerGfxSVA.num(angle10 / 10.0, 1) + " " + tr("deg");
        }
        switch (r) {
            case 0:
                return String.format("%03d", Integer.valueOf(course)) + " " + tr("deg");
            case 1:
                return rwyWidth + " " + tr("blocks");
            case 2:
                return lights == RunwayLayout.LIGHTS_TOWER ? tr("tower") : lights == RunwayLayout.LIGHTS_AUTO ? tr("auto") : lights == 0 ? tr("off")
                        : String.valueOf(lights);
            default:
                return als != 0 ? tr("on") : tr("off");
        }
    }

    /** The state's lines {text, colour}. */
    List<Object[]> status() {
        List<Object[]> l = new ArrayList<Object[]>();
        if (kind != K_END) {
            TileEntityIlsAntennaSVA a = (TileEntityIlsAntennaSVA) te;
            if (a.cat < 0) {
                l.add(new Object[] {tr(kind == K_LOC ? "unpairedLoc" : "unpairedGs"), Integer.valueOf(AMBER)});
            } else {
                boolean off = a.cat == 0 || (a.flags & (LinerIls.F_OFF | LinerIls.F_NO_POWER)) != 0;
                l.add(new Object[] {tr("station", a.rwy, off ? tr("stateOff") : IlsCat.catText(a.cat)), Integer.valueOf(off ? RED : a.cat >= 2 ? VALUE : AMBER)});
                FlightCheck fc = new FlightCheck();
                fc.unpack(a.calib);
                int raw = a.calib & 3;
                l.add(new Object[] {raw == FlightCheck.DONE ? tr("calibDone", IlsCat.catText(fc.result)) : raw == FlightCheck.RUNNING ? tr("calibRunning")
                        : tr("calibWait"), Integer.valueOf(raw == FlightCheck.DONE ? VALUE : CYAN)});
            }
            for (Integer i : a.issueList()) {
                if (i.intValue() == IlsCat.I_UNCAL) {
                    continue;
                }
                l.add(new Object[] {"- " + LinerDisplaysSVA.trAny("sva.apt.issue." + IlsCat.ISSUE_KEYS[i.intValue()]), Integer.valueOf(AMBER)});
                l.add(new Object[] {"  " + LinerDisplaysSVA.trAny("sva.apt.hint." + IlsCat.ISSUE_KEYS[i.intValue()], "", ""), Integer.valueOf(MUTED)});
            }
            if (kind == K_LOC) {
                l.add(new Object[] {tr("locHint"), Integer.valueOf(MUTED)});
            } else {
                l.add(new Object[] {tr("gsHint", LinerGfxSVA.num(BlockIlsAntennaSVA.GS_ANGLE_MIN, 1), LinerGfxSVA.num(BlockIlsAntennaSVA.GS_ANGLE_MAX, 1)),
                        Integer.valueOf(MUTED)});
            }
        } else {
            String key = AirportDataSVA.key(te.getWorldObj(), te.xCoord, te.yCoord, te.zCoord);
            Object[] rw = AirportClientSVA.runwayOfEnd(key);
            if (rw == null) {
                l.add(new Object[] {tr("endUnpaired", Integer.valueOf((int) IlsPairing.PAIR_TOL)), Integer.valueOf(AMBER)});
            } else {
                IlsPairing.Rwy r = ((AirportClientSVA.Rwy) rw[0]).r;
                int dir = ((Integer) rw[1]).intValue();
                l.add(new Object[] {tr("endRunway", r.name(dir), r.name(-dir), Integer.valueOf((int) Math.round(r.len)), AptScale.kmText(r.len, ','),
                        Integer.valueOf(r.width)), Integer.valueOf(VALUE)});
                l.add(new Object[] {tr("endApt", r.apt), Integer.valueOf(CYAN)});
            }
            l.add(new Object[] {tr("endHint"), Integer.valueOf(MUTED)});
        }
        return l;
    }

    @Override
    public void initGui() {
        left = (width - W) / 2;
        top = (height - H) / 2;
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
        paint(mx - left, my - top);
        LinerGfxSVA.lift = lift;
        GL11.glPopMatrix();
        GL11.glPopAttrib();
        GL11.glColor4f(1F, 1F, 1F, 1F);
        super.drawScreen(mx, my, pt);
    }

    private void paint(float mx, float my) {
        LinerGfxSVA.resetLayer();
        LinerGfxSVA.rect(-1, -1, W + 1, H + 1, EDGE);
        LinerGfxSVA.rect(0, 0, W, H, BG);
        LinerGfxSVA.layer();
        LinerGfxSVA.rect(3, 17, W - 3, 18, 0xE08A30);
        LinerGfxSVA.text(tr(kind == K_LOC ? "titleLoc" : kind == K_GS ? "titleGs" : "titleEnd"), 8, 9, 9, GOLD, -1, W - 16);
        for (int r = 0; r < rows(); r++) {
            float y = 34 + r * 24;
            LinerGfxSVA.text(label(r), 10, y, 8, TEXT, -1, 110);
            if (!(kind == K_LOC && r == 0)) {
                LinerGfxSVA.rect(144, y - 8, 206, y + 8, 0x050607);
                LinerGfxSVA.layer();
                LinerGfxSVA.text(value(r), 175, y, 9, VALUE, 0, 58);
            }
        }
        for (int[] b : buttons()) {
            boolean hov = mx >= b[1] && mx < b[3] && my >= b[2] && my < b[4];
            String t;
            if (b[0] == B_SAVE) {
                t = tr("save");
            } else if (b[0] == B_CANCEL) {
                t = tr("cancel");
            } else if (b[0] == B_LOOK) {
                t = tr("look");
            } else if (b[0] == B_IDENT) {
                t = ident + (identFocus && (System.currentTimeMillis() / 400) % 2 == 0 ? "_" : "");
            } else {
                t = b[0] % 2 == 0 ? "-" : "+";
            }
            int face = b[0] == B_IDENT ? 0x050607 : hov ? 0x3A4252 : BTN;
            int edge = b[0] == B_IDENT && identFocus ? CYAN : hov ? 0x7A8496 : BTN_EDGE;
            LinerGfxSVA.rect(b[1], b[2], b[3], b[4], edge);
            LinerGfxSVA.layer();
            LinerGfxSVA.rect(b[1] + 1, b[2] + 1, b[3] - 1, b[4] - 1, face);
            LinerGfxSVA.layer();
            LinerGfxSVA.text(t, (b[1] + b[3]) / 2F, (b[2] + b[4]) / 2F, b[0] == B_IDENT ? 9 : 8, b[0] == B_IDENT ? CYAN : TEXT, 0, b[3] - b[1] - 4);
        }
        float y = 34 + rows() * 24;
        LinerGfxSVA.rect(6, y - 6, W - 6, y - 5, 0x3A4150);
        for (Object[] line : status()) {
            if (y > H - 32) {
                break;
            }
            LinerGfxSVA.text((String) line[0], 10, y + 2, 7, ((Integer) line[1]).intValue(), -1, W - 20);
            y += 10;
        }
    }

    private int hit(int mx, int my) {
        int x = mx - left, y = my - top;
        for (int[] b : buttons()) {
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
        if (b < 0) {
            return;
        }
        boolean big = isShiftKeyDown();
        identFocus = b == B_IDENT;
        if (b == B_SAVE) {
            save();
            mc.displayGuiScreen(null);
            return;
        }
        if (b == B_CANCEL) {
            mc.displayGuiScreen(null);
            return;
        }
        if (b == B_LOOK) {
            course = (int) LinerIls.suggestCourse(mc.thePlayer.rotationYaw);
        } else if (b < 100) {
            step(b / 2, b % 2 == 0 ? -1 : 1, big);
        }
        mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.func_147674_a(
                new net.minecraft.util.ResourceLocation("gui.button.press"), 1.0F));
    }

    private void step(int row, int d, boolean big) {
        if (kind == K_LOC) {
            freq = LinerIls.clampFreq(freq + d * (big ? 100 : 5));
        } else if (kind == K_GS) {
            angle10 = Math.max((int) Math.round(BlockIlsAntennaSVA.GS_ANGLE_MIN * 10), Math.min((int) Math.round(BlockIlsAntennaSVA.GS_ANGLE_MAX * 10),
                    angle10 + d));
        } else if (row == 0) {
            course = ((course + d * (big ? 10 : 1)) % 360 + 360) % 360;
        } else if (row == 1) {
            rwyWidth = Math.max(IlsPairing.WIDTH_MIN, Math.min(IlsPairing.WIDTH_MAX, rwyWidth + d));
        } else if (row == 2) {
            lights = Math.max(RunwayLayout.LIGHTS_TOWER, Math.min(RunwayLayout.STEPS, lights + d));   // wave B2: ВЫШКА below АВТО
        } else {
            als = als != 0 ? 0 : 1;
        }
    }

    private void save() {
        NBTTagCompound t = new NBTTagCompound();
        if (kind == K_END) {
            t.setDouble("Course", course);
            t.setInteger("Width", rwyWidth);
            t.setInteger("Lights", lights);
            t.setInteger("Als", als);
            NetAptSVA.cmd(NetAptSVA.SET_END, te.xCoord, te.yCoord, te.zCoord, t);
        } else {
            t.setString("Ident", ident);
            t.setInteger("Freq", freq);
            t.setDouble("Angle", angle10 / 10.0);
            NetAptSVA.cmd(NetAptSVA.SET_ANTENNA, te.xCoord, te.yCoord, te.zCoord, t);
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (identFocus) {
            if (key == Keyboard.KEY_BACK && ident.length() > 0) {
                ident = ident.substring(0, ident.length() - 1);
                return;
            }
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_TAB) {
                identFocus = false;
                return;
            }
            char u = Character.toUpperCase(c);
            boolean ok = u >= 'A' && u <= 'Z' || u >= '0' && u <= '9' || u >= 'А' && u <= 'Я' || u == 'Ё';
            if (ok && ident.length() < 4) {
                ident += u;
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
