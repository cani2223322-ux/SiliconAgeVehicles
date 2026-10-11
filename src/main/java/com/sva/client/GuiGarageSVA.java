package com.sva.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.sc.inventory.GuiEnergyGaugeSC;
import com.sc.inventory.TextFitSC;
import com.sc.manual.Lang;
import com.sva.FormatSVA;
import com.sva.block.TileEntityGarageSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.inventory.ContainerGarageSVA;
import com.sva.net.NetSVA;
import com.sva.vehicle.GarageMath;
import com.sva.vehicle.TuningMath;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * Wave 2, ★7 / ★8: the repair garage screen (mock-up docs/mockups/veh_more_3_garage_tuning.png, in the charging
 * station's dark style): the vehicle card - name, board number, tier, owner; condition bars from what the vehicle really
 * keeps (hull and its wear, engine heat, charge, the drill head's life); the repair price and «Чинить всё» / «Только
 * корпус»; the four tuning slots with the installed upgrades; the metal slots; the EU gauge; the status line with the
 * job's progress; the garage floor and the player's inventory. 300 x 232 - fits a 427 x 240 screen.
 */
public class GuiGarageSVA extends GuiContainer {

    private static final int W = 300, H = 232;
    private static final int BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, PANEL = 0xFF232833, SLOT = 0xFF2B313C, SLOT_DARK = 0xFF0D1015,
            GOLD = 0xF2A040, CYAN = 0x5EC8F0, TEXT = 0xD8DCE4, MUTED = 0x8A93A3, GREEN = 0x62E06A, RED = 0xF06060, YELLOW = 0xF0D060;
    private static final int BAR_X = 62, BAR_W = 88, BAR_Y = 42, BAR_STEP = 10;

    private final TileEntityGarageSVA tile;
    private final ContainerGarageSVA container;
    private GuiButton all, hull, service;

    public GuiGarageSVA(InventoryPlayer inv, TileEntityGarageSVA te) {
        super(new ContainerGarageSVA(inv, te));
        this.tile = te;
        this.container = (ContainerGarageSVA) inventorySlots;
        xSize = W;
        ySize = H;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        // hydrogen wave: a third button, «Обслужить АКБ» (the repairs' two get narrower)
        all = new TextFitSC.Button(0, guiLeft + 8, guiTop + 104, 58, 16, Lang.tr("sva.garage.btn.all"));
        hull = new TextFitSC.Button(1, guiLeft + 68, guiTop + 104, 58, 16, Lang.tr("sva.garage.btn.hull"));
        service = new TextFitSC.Button(2, guiLeft + 128, guiTop + 104, 56, 16, Lang.tr("sva.garage.btn.service"));
        buttonList.add(all);
        buttonList.add(hull);
        buttonList.add(service);
    }

    // ------------------------------------------------------------------ data

    private int v(int id) {
        return container.get(id);
    }

    private EntityVehicleSVA vehicle() {
        int id = v(ContainerGarageSVA.V_VEH) - 1;
        if (id < 0 || mc.theWorld == null) {
            return null;
        }
        Entity e = mc.theWorld.getEntityByID(id);
        return e instanceof EntityVehicleSVA && !e.isDead ? (EntityVehicleSVA) e : null;
    }

    private boolean st(int bit) {
        return (v(ContainerGarageSVA.V_STATUS) & bit) != 0;
    }

    private boolean job() {
        return v(ContainerGarageSVA.V_JOB) != 0;
    }

    private static String pct(double f) {
        return Math.round(Math.max(0, Math.min(1, f)) * 100) + "%";
    }

    /** Is the metal for a repair there (steel, or iron x2 for the missing steel)? */
    private boolean metalOk() {
        return GarageMath.pay(v(ContainerGarageSVA.V_STEEL), v(ContainerGarageSVA.V_HAVE_STEEL), v(ContainerGarageSVA.V_HAVE_IRON)) != null;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        EntityVehicleSVA veh = vehicle();
        boolean free = veh != null && !job() && metalOk();
        all.enabled = free && v(ContainerGarageSVA.V_EU_ALL) > 0;
        hull.enabled = free && v(ContainerGarageSVA.V_EU_HULL) > 0;
        service.enabled = veh != null && v(ContainerGarageSVA.V_SERVICE) == TileEntityGarageSVA.R_OK;
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        int mode = b.id == 2 ? TileEntityGarageSVA.SERVICE : b.id == 0 ? GarageMath.ALL : GarageMath.HULL;
        NetSVA.CHANNEL.sendToServer(new NetSVA.Garage(tile.xCoord, tile.yCoord, tile.zCoord, mode));
    }

    // ------------------------------------------------------------------ drawing

    /** ТИ-2: the window shrinks as a whole on a screen too small for it (GuiFitSVA); 1 = as designed. */
    private float fit = 1F;

    @Override
    public void setWorldAndResolution(net.minecraft.client.Minecraft mc, int w, int h) {
        fit = GuiFitSVA.fit(w, h, W, H);
        super.setWorldAndResolution(mc, GuiFitSVA.virtual(w, fit), GuiFitSVA.virtual(h, fit));
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        GuiFitSVA.begin(fit);
        drawFitted(GuiFitSVA.mouse(mx, fit), GuiFitSVA.mouse(my, fit), pt);
        GuiFitSVA.end(fit);
    }

    /** drawScreen in the window's own (virtual) coordinates. */
    private void drawFitted(int mx, int my, float pt) {
        TextFitSC.beginFrame();
        super.drawScreen(mx, my, pt);
        List<String> tip = TextFitSC.hoverAt(mx, my);
        if (tip != null && !tip.isEmpty()) {
            drawHoveringText(tip, mx, my, fontRendererObj);
        }
    }

    private void rect(int x, int y, int w, int h, int c) {
        if (w > 0 && h > 0) {
            drawRect(x, y, x + w, y + h, c);
        }
    }

    private void frame(int x, int y, int w, int h, int border, int fillC) {
        rect(x, y, w, h, border);
        rect(x + 1, y + 1, w - 2, h - 2, fillC);
    }

    private void bar(int x, int y, double frac, int colour) {
        rect(x, y, BAR_W, 5, 0xFF0E1014);
        rect(x, y, (int) Math.round(BAR_W * Math.max(0, Math.min(1, frac))), 5, colour);
    }

    /** The condition rows: {lang key, fraction, colour, text}. Only what the vehicle really keeps. */
    private List<Object[]> rows(EntityVehicleSVA veh) {
        List<Object[]> r = new ArrayList<Object[]>();
        float max = veh.getMaxHp();
        double hp = max <= 0 ? 0 : veh.getHp() / max;
        if (veh.wearOn()) {
            r.add(new Object[] {"sva.garage.row.hull", Double.valueOf(hp), Integer.valueOf(hp < 0.25 ? 0xFFFF4040 : hp < 0.6 ? 0xFFF0B030 : 0xFF3CCB6E),
                    veh.isWrecked() ? Lang.tr("sva.garage.wrecked") : pct(hp)});
            r.add(new Object[] {"sva.garage.row.wear", Double.valueOf(1 - hp), Integer.valueOf(0xFFF08A30), pct(1 - hp)});
        }
        double heat = veh.getHeat() / VehicleMath.HEAT_STOP;
        r.add(new Object[] {"sva.garage.row.engine", Double.valueOf(heat), Integer.valueOf(veh.isHeatStopped() ? 0xFFFF4040 : 0xFFF0B030),
                veh.isHeatStopped() ? Lang.tr("sva.garage.overheat") : pct(heat)});
        double e = veh.getEnergy() / Math.max(1.0, veh.getMaxEnergy());
        if (veh.onHydrogen()) {
            // hydrogen wave: the battery - its health against the limit a service brings it to (charge in the text)
            if (!veh.batteryPresent()) {
                r.add(new Object[] {"sva.garage.row.bat", Double.valueOf(0), Integer.valueOf(0xFFF06060), Lang.tr("sva.garage.noBat")});
            } else {
                double h = veh.batteryHealth();
                r.add(new Object[] {"sva.garage.row.bat", Double.valueOf(h), Integer.valueOf(h < 0.5 ? 0xFFFF4040 : h < 0.8 ? 0xFFF0B030 : 0xFF3CCB6E),
                        Lang.tr("sva.garage.batHealth", Integer.valueOf((int) Math.round(h * 100)), Integer.valueOf((int) Math.round(veh.batteryLimit() * 100)))});
            }
        } else {
            r.add(new Object[] {"sva.garage.row.energy", Double.valueOf(e), Integer.valueOf(0xFFE05454), pct(e)});
        }
        if (veh.getVehicleType().hasDrill && veh.hasHead()) {
            int left = veh.getHeadLeft(), maxH = veh.getHeadMax();
            double h = left < 0 ? 1 : maxH <= 0 ? 0 : left / (double) maxH;
            r.add(new Object[] {"sva.garage.row.drill", Double.valueOf(h), Integer.valueOf(0xFF5EC8F0),
                    left < 0 ? Lang.tr("sva.garage.forever") : pct(h)});
        }
        return r;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float pt, int mx, int my) {
        int x = guiLeft, y = guiTop;
        GL11.glColor4f(1F, 1F, 1F, 1F);
        frame(x, y, W, H, EDGE, BG);
        rect(x + 1, y + 1, W - 2, 1, 0xFF3A4150);
        rect(x + 4, y + 15, W - 8, 1, 0xFFE08A30);
        frame(x + 4, y + 18, 184, 106, EDGE, PANEL);           // the vehicle
        frame(x + 191, y + 18, 105, 106, EDGE, PANEL);         // tuning / metal / EU
        boolean job = job();
        int stc = st(TileEntityGarageSVA.S_NO_FIT) ? 0xFFF06060 : job ? 0xFF5EC8F0 : st(TileEntityGarageSVA.S_VEHICLE) ? 0xFF62E06A : 0xFF3A4150;
        frame(x + 4, y + 127, 292, 12, stc, 0xFF0E1116);       // status line
        if (job) {
            rect(x + 5, y + 128, (int) Math.round(290 * v(ContainerGarageSVA.V_PROG) / 1000.0), 10, 0xFF1D4A66);
        }
        frame(x + 4, y + 142, 59, 87, EDGE, PANEL);            // the floor
        frame(x + 65, y + 142, 170, 87, EDGE, PANEL);          // inventory
        frame(x + 237, y + 142, 59, 87, EDGE, PANEL);          // the job
        for (Object o : inventorySlots.inventorySlots) {
            Slot s = (Slot) o;
            boolean tune = s.slotNumber >= ContainerGarageSVA.FIRST_TUNE && s.slotNumber < ContainerGarageSVA.PLAYER_START;
            frame(x + s.xDisplayPosition - 1, y + s.yDisplayPosition - 1, 18, 18, tune ? 0xFF3A6A88 : SLOT_DARK, SLOT);
        }
        GuiEnergyGaugeSC.draw(x + 281, y + 22, 11, 98, Math.min(1F, v(ContainerGarageSVA.V_E) / (float) GarageMath.BUFFER));
        EntityVehicleSVA veh = vehicle();
        if (veh != null) {
            int ry = y + BAR_Y;
            for (Object[] row : rows(veh)) {
                bar(x + BAR_X, ry + 1, ((Double) row[1]).doubleValue(), ((Integer) row[2]).intValue());
                ry += BAR_STEP;
            }
            item(new ItemStack(ModContentSVA.vehicle, 1, veh.getVehicleType().ordinal()), x + 7, y + 20);
        }
        item(new ItemStack(ModContentSVA.garagePlate, 1, 0), x + 8, y + 155);
        // ghost upgrades in the empty tuning slots: what could go there
        if (veh != null) {
            int[] t = veh.getTuning();
            for (int i = 0; i < TuningMath.MAX_SLOTS; i++) {
                if (!TuningMath.valid(t[i])) {
                    rect(x + ContainerGarageSVA.TUNE_X + i * ContainerGarageSVA.TUNE_STEP + 6, y + ContainerGarageSVA.TUNE_Y + 6, 4, 4, 0xFF3A4150);
                }
            }
        }
    }

    private void item(ItemStack s, int x, int y) {
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), s, x, y);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mx, int my) {
        int ox = guiLeft, oy = guiTop;
        fontRendererObj.drawString(Lang.tr("tile.siliconagevehicles.garage.name"), 7, 5, GOLD);
        String head = Lang.tr("sva.garage.head", FormatSVA.eu(v(ContainerGarageSVA.V_E)), FormatSVA.eu(GarageMath.BUFFER));
        int hw = Math.min(170, fontRendererObj.getStringWidth(head));
        TextFitSC.draw(fontRendererObj, head, W - 6 - hw, 5, 170, MUTED, ox, oy);
        drawCard();
        drawRight();
        drawStatus();
        drawBottom();
        fontRendererObj.drawString(Lang.tr("sva.st.inventory"), 69, 144, CYAN);
    }

    private void drawCard() {
        EntityVehicleSVA veh = vehicle();
        if (veh == null) {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.garage.none1"), 8, 24, 176, MUTED, guiLeft, guiTop);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.garage.none2"), 8, 36, 176, MUTED, guiLeft, guiTop);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.garage.none3"), 8, 48, 176, MUTED, guiLeft, guiTop);
            return;
        }
        VehicleType t = veh.getVehicleType();
        String name = Lang.tr("sva.garage.vehicle", Lang.tr("item.siliconagevehicles.vehicle." + t.key + ".name"),
                Integer.valueOf(veh.getBoardNumber()), t.tier.name());
        TextFitSC.draw(fontRendererObj, name, 26, 21, 158, TEXT, ox(), oy());
        String owner = veh.getOwnerName();
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.gui.veh.owner", owner.length() == 0 ? Lang.tr("sva.gui.veh.noOwner") : owner,
                Integer.valueOf(veh.ridersNow()), Integer.valueOf(t.seats)), 26, 30, 158, MUTED, ox(), oy());
        int ry = BAR_Y - 1;
        for (Object[] row : rows(veh)) {
            TextFitSC.draw(fontRendererObj, Lang.tr((String) row[0]), 8, ry, 52, TEXT, ox(), oy());
            TextFitSC.draw(fontRendererObj, (String) row[3], BAR_X + BAR_W + 4, ry, 182 - BAR_X - BAR_W - 4, TEXT, ox(), oy());
            ry += BAR_STEP;
        }
        long euAll = v(ContainerGarageSVA.V_EU_ALL), euHull = v(ContainerGarageSVA.V_EU_HULL);
        int steel = v(ContainerGarageSVA.V_STEEL);
        String cost;
        int c = TEXT;
        if (euAll <= 0) {
            cost = Lang.tr("sva.garage.cost.none");
            c = GREEN;
        } else if (steel > 0) {
            cost = Lang.tr("sva.garage.cost", FormatSVA.group(euAll), Integer.valueOf(steel), Integer.valueOf(steel * GarageMath.IRON_PER_STEEL));
            c = metalOk() ? TEXT : RED;
        } else {
            cost = Lang.tr("sva.garage.cost.eu", FormatSVA.group(euAll));
        }
        TextFitSC.draw(fontRendererObj, cost, 8, 93, 176, c, ox(), oy());
        List<String> tip = new ArrayList<String>();
        tip.add(Lang.tr("sva.garage.tip.all", FormatSVA.group(euAll)));
        tip.add(Lang.tr("sva.garage.tip.hull", FormatSVA.group(euHull)));
        tip.add("§7" + Lang.tr("sva.garage.tip.metal", Integer.valueOf(GarageMath.IRON_PER_STEEL)));
        TextFitSC.hover(guiLeft + 8, guiTop + 92, 176, 10, tip);
        List<String> st = new ArrayList<String>();
        st.add(Lang.tr("sva.garage.tip.service", FormatSVA.group(com.sva.vehicle.BatteryMath.GARAGE_EU),
                Integer.valueOf((int) Math.round(com.sva.vehicle.BatteryMath.CAP_DROP * 100))));
        int sc = v(ContainerGarageSVA.V_SERVICE);
        if (sc != TileEntityGarageSVA.R_OK) {
            st.add("\u00a7c" + Lang.tr("sva.garage.refused." + sc, veh.getOwnerName(), FormatSVA.group(com.sva.vehicle.BatteryMath.GARAGE_EU)));
        }
        TextFitSC.hover(guiLeft + 128, guiTop + 104, 56, 16, st);
    }

    private int ox() {
        return guiLeft;
    }

    private int oy() {
        return guiTop;
    }

    private void drawRight() {
        EntityVehicleSVA veh = vehicle();
        int[] t = veh == null ? TuningMath.empty() : veh.getTuning();
        int n = TuningMath.count(t);
        fontRendererObj.drawString(Lang.tr("sva.garage.tuning", Integer.valueOf(n), Integer.valueOf(TuningMath.MAX_SLOTS)), 195, 22, CYAN);
        StringBuilder b = new StringBuilder();
        for (int k : t) {
            if (TuningMath.valid(k)) {
                if (b.length() > 0) {
                    b.append(", ");
                }
                b.append(Lang.tr("sva.tuning.short." + TuningMath.KEYS[k]));
            }
        }
        String list = veh == null ? Lang.tr("sva.garage.tuning.noVehicle") : b.length() == 0 ? Lang.tr("sva.garage.tuning.empty") : b.toString();
        List<String> lines = fontRendererObj.listFormattedStringToWidth(list, 82);
        for (int i = 0; i < lines.size() && i < 2; i++) {
            TextFitSC.draw(fontRendererObj, lines.get(i), 195, 54 + i * 9, 82, veh == null ? MUTED : TEXT, ox(), oy());
        }
        List<String> tip = new ArrayList<String>();
        tip.add(Lang.tr("sva.garage.tuning.tip1"));
        tip.add("§7" + Lang.tr("sva.garage.tuning.tip2", Integer.valueOf(TuningMath.MAX_SLOTS)));
        tip.add("§7" + Lang.tr("sva.garage.tuning.tip3"));
        TextFitSC.hover(guiLeft + 195, guiTop + 21, 82, 10, tip);
        fontRendererObj.drawString(Lang.tr("sva.garage.metal"), 195, 78, CYAN);
        int ely = v(ContainerGarageSVA.V_ELYTE);
        TextFitSC.draw(fontRendererObj, ely > 0 ? Lang.tr("sva.garage.haveEl", Integer.valueOf(v(ContainerGarageSVA.V_HAVE_STEEL)),
                Integer.valueOf(v(ContainerGarageSVA.V_HAVE_IRON)), Integer.valueOf(ely))
                : Lang.tr("sva.garage.have", Integer.valueOf(v(ContainerGarageSVA.V_HAVE_STEEL)),
                Integer.valueOf(v(ContainerGarageSVA.V_HAVE_IRON))), 195, 109, 82, MUTED, ox(), oy());
        TextFitSC.hover(guiLeft + 281, guiTop + 22, 11, 98, Lang.tr("sva.garage.eu.tip", FormatSVA.eu(v(ContainerGarageSVA.V_E)),
                FormatSVA.eu(GarageMath.BUFFER)));
    }

    private void drawStatus() {
        String s;
        int c;
        EntityVehicleSVA veh = vehicle();
        if (job()) {
            int p = v(ContainerGarageSVA.V_PROG) / 10;
            if (st(TileEntityGarageSVA.S_OFF)) {
                s = Lang.tr("sva.garage.st.off");
                c = YELLOW;
            } else if (st(TileEntityGarageSVA.S_NO_ENERGY)) {
                s = Lang.tr("sva.garage.st.noEnergy", Integer.valueOf(p));
                c = RED;
            } else {
                s = Lang.tr(v(ContainerGarageSVA.V_MODE) == GarageMath.ALL ? "sva.garage.st.all" : "sva.garage.st.hull", Integer.valueOf(p));
                c = CYAN;
            }
        } else if (st(TileEntityGarageSVA.S_DONE)) {
            s = Lang.tr("sva.garage.st.done");
            c = GREEN;
        } else if (v(ContainerGarageSVA.V_PLATES) == 0) {
            s = Lang.tr("sva.garage.st.noFloor");
            c = YELLOW;
        } else if (veh == null) {
            s = Lang.tr(st(TileEntityGarageSVA.S_NO_FIT) ? "sva.garage.st.noFit" : "sva.garage.st.empty");
            c = st(TileEntityGarageSVA.S_NO_FIT) ? RED : MUTED;
        } else if (v(ContainerGarageSVA.V_EU_ALL) <= 0) {
            s = Lang.tr("sva.garage.st.fine");
            c = GREEN;
        } else if (!metalOk()) {
            s = Lang.tr("sva.garage.st.noMetal");
            c = RED;
        } else {
            s = Lang.tr("sva.garage.st.ready");
            c = TEXT;
        }
        TextFitSC.draw(fontRendererObj, s, 8, 129, 284, c, ox(), oy());
    }

    private void drawBottom() {
        fontRendererObj.drawString(Lang.tr("sva.garage.floor"), 8, 144, CYAN);
        int plates = v(ContainerGarageSVA.V_PLATES);
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.garage.plates", Integer.valueOf(plates)), 26, 159, 35, TEXT, ox(), oy());
        EntityVehicleSVA veh = vehicle();
        String fit = veh != null ? Lang.tr("sva.st.fits.yes", veh.getVehicleType().platformSize.label())
                : st(TileEntityGarageSVA.S_NO_FIT) ? Lang.tr("sva.st.fits.no", "?") : Lang.tr("sva.st.fits.none");
        List<String> lines = fontRendererObj.listFormattedStringToWidth(fit, 53);
        for (int i = 0; i < lines.size() && i < 3; i++) {
            TextFitSC.draw(fontRendererObj, lines.get(i), 7, 178 + i * 9, 53, veh != null ? GREEN : MUTED, ox(), oy());
        }
        TextFitSC.hover(guiLeft + 4, guiTop + 142, 59, 87, Lang.tr("sva.garage.floor.tip"));
        fontRendererObj.drawString(Lang.tr("sva.garage.job"), 241, 144, CYAN);
        String big = job() ? (v(ContainerGarageSVA.V_PROG) / 10) + "%" : st(TileEntityGarageSVA.S_DONE) ? "100%" : "--";
        GL11.glPushMatrix();
        GL11.glTranslatef(266.5F - fontRendererObj.getStringWidth(big), 168F, 0F);
        GL11.glScalef(2F, 2F, 1F);
        fontRendererObj.drawString(big, 0, 0, job() ? CYAN : st(TileEntityGarageSVA.S_DONE) ? GREEN : MUTED);
        GL11.glPopMatrix();
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.garage.comparator"), 241, 196, 53, MUTED, ox(), oy());
        TextFitSC.hover(guiLeft + 237, guiTop + 142, 59, 87, Lang.tr("sva.garage.job.tip"));
    }
}
