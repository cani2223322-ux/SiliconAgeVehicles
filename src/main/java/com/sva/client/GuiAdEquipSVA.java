package com.sva.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.sc.energy.Tier;
import com.sc.inventory.TextFitSC;
import com.sc.machine.UpgradeType;
import com.sc.manual.Lang;
import com.sva.FormatSVA;
import com.sva.airdefense.AdUpgradeMath;
import com.sva.airdefense.RadarMath;
import com.sva.block.TileEntityAirDefenseSVA;
import com.sva.block.TileEntityLauncherSVA;
import com.sva.block.TileEntityRadarSVA;
import com.sva.inventory.ContainerAdSVA;
import com.sva.net.NetADSVA;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Upgrades wave (2026-10-12): an air-defence block's equipment screen in the style of the launcher's (GuiLauncherSVA) -
 * the radar's (RMB) and the command post's («Оборудование» tab, «К экрану КП» goes back). Left: the radar's PPI scope (the
 * last scan's contacts, north up, green own, cyan friend, amber foreign; the sweep turns once a scan) or the post's
 * links; right: power, the energy bar, the input tier, EU/t, the radar's kind, range, scan interval (with the upgrades and
 * before them), its command post; under them the four upgrade slots (hover: which modules fit and what each does) and
 * the player's inventory. The state comes every 10 ticks (NetADSVA D_EQUIP), nothing every frame.
 */
public class GuiAdEquipSVA extends GuiContainer {

    static final int W = 260, H = 240, BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, LINE = 0xFF3A4150, PANEL = 0xFF14171D, GOLD = 0xF2A040,
            TEXT = 0xD8DCE4, MUTED = 0x8A93A3, AMBER = 0xF0B040, RED = 0xF06060, GREEN = 0x62E06A, CYAN = 0x5EC8F0;
    /** The scope: its panel's corner and size, the ring's radius. */
    private static final int PX = 6, PY = 20, PS = 112, PR = 52;
    private static final int B_BACK = 1;
    /** The contact's relation: foreign, own, friend (full keys: the lang self-test reads them from the class). */
    static final String[] REL = {"sva.ad.eq.rel0", "sva.ad.eq.rel1", "sva.ad.eq.rel2"};

    private static NBTTagCompound last;

    private final TileEntityAirDefenseSVA tile;

    public GuiAdEquipSVA(InventoryPlayer inv, TileEntityAirDefenseSVA tile) {
        super(new ContainerAdSVA(inv, tile));
        this.tile = tile;
        xSize = W;
        ySize = H;
        last = null;
    }

    /** The server's state for the open screen (only this block's). */
    public static void receive(NBTTagCompound t) {
        last = t;
    }

    private NBTTagCompound snap() {
        NBTTagCompound t = last;
        if (t == null) {
            return null;
        }
        int[] p = t.getIntArray("Pos");
        return p.length == 3 && p[0] == tile.xCoord && p[1] == tile.yCoord && p[2] == tile.zCoord ? t : null;
    }

    private boolean radar() {
        return tile instanceof TileEntityRadarSVA;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        if (!radar()) {
            buttonList.add(new GuiCargoSVA.Dark(B_BACK, guiLeft + PX + 4, guiTop + PY + PS - 18, PS - 8, 13, Lang.tr("sva.ad.eq.back"), null));
        }
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id == B_BACK) {
            int x = tile.xCoord, y = tile.yCoord, z = tile.zCoord;
            mc.thePlayer.closeScreen();                              // the container closes on the server too
            NetADSVA.cmd(NetADSVA.KP_OPEN, x, y, z, 0, null);         // and the post's screen comes back
        }
    }

    // ------------------------------------------------------------------ shared with the launcher's screen

    /** "HV" / "любое" - the input tier from a snapshot. */
    static String input(NBTTagCompound t) {
        if (t.getBoolean("Any")) {
            return Lang.tr("sva.ad.eq.any");
        }
        return Tier.byOrdinal(t.getByte("In")).name();
    }

    /** Seconds with one decimal ("6,0"). */
    static String sec(int ticks) {
        String p = Lang.tr("sva.num.point");
        return AdUpgradeMath.seconds(ticks, p.length() > 0 ? p.charAt(0) : '.');
    }

    /** Which modules fit this block and what each does (the slots' and the label's hover). */
    static List<String> fitsTip(TileEntityAirDefenseSVA te, boolean upgOn) {
        List<String> l = new ArrayList<String>();
        l.add(Lang.tr("sva.ad.eq.fits"));
        String kind = te instanceof TileEntityRadarSVA ? "radar"
                : te instanceof TileEntityLauncherSVA ? (((TileEntityLauncherSVA) te).kind() == TileEntityLauncherSVA.MISSILE ? "missile" : "gun") : "kp";
        for (UpgradeType u : AdUpgradeMath.accepted(te.upgradeBlock())) {
            String key = u.name().toLowerCase(java.util.Locale.ROOT);
            String eff = u == UpgradeType.OVERCLOCKER ? Lang.tr("sva.ad.upg." + key + "." + kind) : Lang.tr("sva.ad.upg." + key);
            l.add("§7" + com.sc.init.ModItems.upgrade.stackOf(u).getDisplayName() + ": " + eff);
        }
        l.add("§8" + Lang.tr("sva.ad.eq.stack"));
        if (!upgOn) {
            l.add("§c" + Lang.tr("sva.ad.eq.upgOff"));
        }
        return l;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void drawGuiContainerBackgroundLayer(float pt, int mx, int my) {
        TextFitSC.beginFrame();
        int x = guiLeft, y = guiTop;
        drawRect(x - 1, y - 1, x + W + 1, y + H + 1, EDGE);
        drawRect(x, y, x + W, y + H, BG);
        drawRect(x + 1, y + 1, x + W - 1, y + 2, LINE);
        drawRect(x + 3, y + 15, x + W - 3, y + 16, 0xFFE08A30);
        drawRect(x + PX - 1, y + PY - 1, x + PX + PS + 1, y + PY + PS + 1, LINE);
        drawRect(x + PX, y + PY, x + PX + PS, y + PY + PS, PANEL);
        for (Object o : inventorySlots.inventorySlots) {
            Slot s = (Slot) o;
            boolean upg = s instanceof ContainerAdSVA.UpgradeSlot;
            drawRect(x + s.xDisplayPosition - 1, y + s.yDisplayPosition - 1, x + s.xDisplayPosition + 17, y + s.yDisplayPosition + 17,
                    upg ? 0xFF2A5068 : 0xFF0E1014);
            drawRect(x + s.xDisplayPosition, y + s.yDisplayPosition, x + s.xDisplayPosition + 16, y + s.yDisplayPosition + 16, 0xFF2A2F3A);
        }
        NBTTagCompound t = snap();
        if (radar()) {
            drawScope(t, x + PX + PS / 2, y + PY + PS / 2);
        }
    }

    /** The PPI: rings at 1/3, 2/3 and the full range, the cross, the sweep (one turn a scan), the contacts. */
    private void drawScope(NBTTagCompound t, int cx, int cy) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator ts = Tessellator.instance;
        for (int r = 1; r <= 3; r++) {
            ts.startDrawing(GL11.GL_LINE_LOOP);
            ts.setColorRGBA(60, 200, 110, r == 3 ? 200 : 90);
            for (int i = 0; i < 48; i++) {
                double a = Math.PI * 2 * i / 48;
                ts.addVertex(cx + Math.sin(a) * PR * r / 3.0, cy - Math.cos(a) * PR * r / 3.0, 0);
            }
            ts.draw();
        }
        ts.startDrawing(GL11.GL_LINES);
        ts.setColorRGBA(60, 200, 110, 70);
        ts.addVertex(cx - PR, cy, 0);
        ts.addVertex(cx + PR, cy, 0);
        ts.addVertex(cx, cy - PR, 0);
        ts.addVertex(cx, cy + PR, 0);
        ts.draw();
        boolean on = t != null && t.getBoolean("Powered");
        if (on) {
            int iv = Math.max(1, t.getInteger("Interval"));
            long now = mc.theWorld == null ? 0 : mc.theWorld.getTotalWorldTime();
            double a = Math.PI * 2 * (now % iv) / iv;
            ts.startDrawing(GL11.GL_TRIANGLE_FAN);                   // the sweep with a fading wedge behind it
            ts.setColorRGBA(60, 230, 120, 110);
            ts.addVertex(cx, cy, 0);
            for (int i = 0; i <= 8; i++) {
                double b = a - i * 0.06;
                ts.setColorRGBA(60, 230, 120, 110 - i * 13);
                ts.addVertex(cx + Math.sin(b) * PR, cy - Math.cos(b) * PR, 0);
            }
            ts.draw();
        }
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        if (t == null) {
            return;
        }
        double range = Math.max(1, t.getInteger("Range"));
        NBTTagList l = t.getTagList("Contacts", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            NBTTagCompound c = l.getCompoundTagAt(i);
            int[] p = AdUpgradeMath.scope(c.getFloat("X"), c.getFloat("Z"), range, PR);
            int rel = c.getByte("Rel");
            int col = rel == 1 ? 0xFF62E06A : rel == 2 ? 0xFF5EC8F0 : 0xFFF0B040;
            drawRect(cx + p[0] - 2, cy + p[1] - 2, cx + p[0] + 2, cy + p[1] + 2, col);
            int alt = c.getInteger("Y");
            String d = Lang.tr("sva.ad.eq.contact", Integer.valueOf(Math.round(c.getFloat("X"))), Integer.valueOf(Math.round(c.getFloat("Z"))),
                    alt == Integer.MIN_VALUE ? "---" : String.valueOf(alt), Lang.tr(REL[Math.max(0, Math.min(2, rel))]));
            TextFitSC.hover(cx + p[0] - 3, cy + p[1] - 3, 7, 7, d);
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mx, int my) {
        NBTTagCompound t = snap();
        TextFitSC.draw(fontRendererObj, Lang.tr(tile.nameKey()), 6, 4, W - 50, GOLD, false, guiLeft, guiTop);
        int sx = PX + PS + 6, sy = 20, lw = W - sx - 6;
        if (t == null) {
            fontRendererObj.drawString(Lang.tr("sva.ad.l.wait"), sx, sy, MUTED);
        } else {
            String in = input(t);
            fontRendererObj.drawString(in, W - 6 - fontRendererObj.getStringWidth(in), 4, t.getBoolean("Any") ? GREEN : MUTED);
            boolean on = t.getBoolean("Powered");
            line(Lang.tr(!t.getBoolean("Ad") ? "sva.ad.eq.adOff" : on ? "sva.ad.eq.on" : "sva.ad.eq.off"), sx, sy, on ? GREEN : RED, lw);
            int e = t.getInteger("E"), max = Math.max(1, t.getInteger("EMax"));
            line(Lang.tr("sva.ad.eq.energy", FormatSVA.group(e), FormatSVA.group(max)), sx, sy + 11, TEXT, lw);
            drawRect(sx, sy + 21, sx + lw, sy + 25, 0xFF0E1014);
            float f = Math.max(0F, Math.min(1F, e / (float) max));
            drawRect(sx, sy + 21, sx + Math.round(lw * f), sy + 25, com.sc.inventory.GuiEnergyGaugeSC.colour(f));
            line(Lang.tr("sva.ad.eq.input", in, Tier.byOrdinal(t.getByte("Tier")).name()), sx, sy + 29, TEXT, lw);
            int use = t.getInteger("Use"), base = t.getInteger("BaseUse");
            line(use == base ? Lang.tr("sva.ad.eq.use", FormatSVA.group(use)) : Lang.tr("sva.ad.eq.useWas", FormatSVA.group(use), FormatSVA.group(base)),
                    sx, sy + 40, use > base ? AMBER : use < base ? GREEN : TEXT, lw);
            if (radar()) {
                int k = t.getByte("RKind");
                line(Lang.tr("sva.ad.eq.kind", Lang.tr("sva.ad.radar." + RadarMath.KEYS[RadarMath.validKind(k) ? k : 0])), sx, sy + 51, MUTED, lw);
                line(Lang.tr("sva.ad.eq.range", Integer.valueOf(t.getInteger("Range")), Integer.valueOf(t.getInteger("PlaneRange"))), sx, sy + 62, TEXT, lw);
                int iv = t.getInteger("Interval"), biv = t.getInteger("BaseInterval");
                line(iv == biv ? Lang.tr("sva.ad.eq.scan", sec(iv)) : Lang.tr("sva.ad.eq.scanWas", sec(iv), sec(biv)), sx, sy + 73, iv < biv ? GREEN : TEXT, lw);
                int n = t.getTagList("Contacts", 10).tagCount();
                line(on ? Lang.tr("sva.ad.eq.targets", Integer.valueOf(n)) : Lang.tr("sva.ad.eq.noScan"), sx, sy + 84, on && n > 0 ? AMBER : MUTED, lw);
                String kp = t.getString("Kp");
                line(kp.length() > 0 ? Lang.tr("sva.ad.l.kp", kp) : Lang.tr("sva.ad.l.noKp"), sx, sy + 95, kp.length() > 0 ? GREEN : AMBER, lw);
                TextFitSC.hover(guiLeft + PX, guiTop + PY, PS, 10, Lang.tr("sva.ad.eq.ppi.tip"));
                fontRendererObj.drawString("N", PX + PS / 2 - 2, PY + 1, 0x3CC86E);
            } else {
                line(Lang.tr("sva.ad.l.kp", t.getString("Kp")), sx, sy + 51, GOLD, lw);
                line(Lang.tr("sva.ad.eq.links", Integer.valueOf(t.getInteger("Radars")), Integer.valueOf(t.getInteger("Launchers"))), sx, sy + 62, TEXT, lw);
                line(Lang.tr("sva.ad.eq.tracks", Integer.valueOf(t.getInteger("Tracks"))), sx, sy + 73, TEXT, lw);
                TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.eq.kpNote"), PX + 4, PY + 6, PS - 8, MUTED, false, guiLeft, guiTop);
            }
        }
        // the upgrade slots: the label, every empty slot's hint
        boolean upgOn = t == null || t.getBoolean("Upg");
        int ly = ContainerAdSVA.UPG_Y - 11;
        fontRendererObj.drawString(Lang.tr("sva.ad.eq.modules"), ContainerAdSVA.UPG_X, ly, upgOn ? CYAN : RED);
        List<String> fits = fitsTip(tile, upgOn);
        TextFitSC.hover(guiLeft + ContainerAdSVA.UPG_X, guiTop + ly - 1, 100, 10, fits);
        for (int i = 0; i < AdUpgradeMath.SLOTS; i++) {
            if (tile.upgrades.getStackInSlot(i) == null) {
                TextFitSC.hover(guiLeft + ContainerAdSVA.UPG_X + 18 * i - 1, guiTop + ContainerAdSVA.UPG_Y - 1, 18, 18, fits);
            }
        }
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.eq.hint"), ContainerAdSVA.UPG_X + 76, ContainerAdSVA.UPG_Y + 4, W - ContainerAdSVA.UPG_X - 82, MUTED,
                false, guiLeft, guiTop);
        List<String> tip = TextFitSC.hoverAt(mx, my);
        if (tip != null && !tip.isEmpty()) {
            List<String> wrapped = new ArrayList<String>();
            for (String l : tip) {
                wrapped.addAll(fontRendererObj.listFormattedStringToWidth(l, 220));
            }
            drawHoveringText(wrapped, mx - guiLeft, my - guiTop, fontRendererObj);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private void line(String s, int x, int y, int c, int w) {
        TextFitSC.draw(fontRendererObj, s, x, y, w, c, false, guiLeft, guiTop);
    }
}
