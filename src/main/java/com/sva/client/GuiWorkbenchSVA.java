package com.sva.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.sc.energy.Tier;
import com.sc.inventory.GuiEnergyGaugeSC;
import com.sc.inventory.TextFitSC;
import com.sc.machine.UpgradeType;
import com.sc.manual.Lang;
import com.sva.FormatSVA;
import com.sva.block.TileEntityWorkbenchSVA;
import com.sva.init.ModContentSVA;
import com.sva.inventory.ContainerWorkbenchSVA;
import com.sva.item.ComponentType;
import com.sva.net.NetSVA;
import com.sva.workbench.FillPlannerSVA;
import com.sva.workbench.WorkbenchMath;
import com.sva.workbench.WorkbenchRecipeSVA;
import com.sva.workbench.WorkbenchRecipeSVA.Category;
import com.sva.workbench.WorkbenchRecipesSVA;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * The Engineering Workbench screen (dark, after docs/mockups/veh_6_*): category tabs, search and the recipe list
 * on the left, the 5x5 grid with ghost items, progress and output in the middle, info / EU gauge / «Нужно для
 * сборки» on the right, upgrades bottom-left. 400 x 232 - fits a 427 x 240 screen (GUI scale 3 at 720p... 4 at 1080p).
 */
public class GuiWorkbenchSVA extends GuiContainer {

    private static final int W = 400, H = 232;
    private static final int LIST_X = 6, LIST_Y = 64, ROW_H = 20, ROWS = 5, LIST_W = 118;
    private static final int BTN_START = 10, BTN_FILL = 11, BTN_CLEAR = 12;
    /** The progress arrow (screen-relative), also NEI's «recipes» click area (com.sva.nei). */
    public static final int ARROW_X = 226, ARROW_Y = 57, ARROW_W = 32, ARROW_H = 12;
    // colours
    private static final int BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, PANEL = 0xFF232833, PANEL_HI = 0xFF2E3542, SLOT = 0xFF2B313C, SLOT_DARK = 0xFF0D1015,
            GOLD = 0xF2C04A, CYAN = 0x5EC8F0, TEXT = 0xD8DCE4, MUTED = 0x8A93A3, GREEN = 0x62E06A, RED = 0xF06060, YELLOW = 0xF0D060;

    private final TileEntityWorkbenchSVA tile;
    private final ContainerWorkbenchSVA container;
    private final InventoryPlayer inv;
    private GuiTextField search;
    private int category;
    private int scroll;
    private WorkbenchRecipeSVA selected;
    private GuiButton start, fill, clear;

    public GuiWorkbenchSVA(InventoryPlayer inv, TileEntityWorkbenchSVA te) {
        super(new ContainerWorkbenchSVA(inv, te));
        this.tile = te;
        this.container = (ContainerWorkbenchSVA) inventorySlots;
        this.inv = inv;
        xSize = W;
        ySize = H;
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        ItemStack[] icons = {ModContentSVA.component.stackOf(ComponentType.WHEEL, 1), new ItemStack(ModContentSVA.vehicle, 1, 0),
                new ItemStack(ModContentSVA.charger, 1, Tier.MV.ordinal()), com.sc.init.ModItems.upgrade.stackOf(UpgradeType.OVERCLOCKER)};
        for (int i = 0; i < 4; i++) {
            buttonList.add(new TextFitSC.Tab(i, guiLeft + LIST_X + i * 30, guiTop + 18, 28, 18, icons[i], "", Lang.tr("sva.wb.tab." + i)));
        }
        start = new TextFitSC.Button(BTN_START, guiLeft + 130, guiTop + 112, 54, 14, Lang.tr("sva.wb.btn.start"));
        fill = new TextFitSC.Button(BTN_FILL, guiLeft + 186, guiTop + 112, 56, 14, Lang.tr("sva.wb.btn.fill"));
        clear = new TextFitSC.Button(BTN_CLEAR, guiLeft + 244, guiTop + 112, 50, 14, Lang.tr("sva.wb.btn.clear"));
        buttonList.add(start);
        buttonList.add(fill);
        buttonList.add(clear);
        String old = search == null ? "" : search.getText();
        search = new GuiTextField(fontRendererObj, guiLeft + LIST_X + 3, guiTop + 51, LIST_W - 6, 10);
        search.setEnableBackgroundDrawing(false);
        search.setMaxStringLength(32);
        search.setText(old);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
    }

    // ------------------------------------------------------------------ data

    private List<WorkbenchRecipeSVA> filtered() {
        List<WorkbenchRecipeSVA> l = new ArrayList<WorkbenchRecipeSVA>();
        String q = search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        Category cat = Category.values()[category];
        for (WorkbenchRecipeSVA r : WorkbenchRecipesSVA.all()) {
            if (r.category != cat) {
                continue;
            }
            if (q.length() > 0 && !name(r).toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            l.add(r);
        }
        return l;
    }

    private static String name(WorkbenchRecipeSVA r) {
        return r.display().getDisplayName();
    }

    /** What the player has for a plan: the grid, then the main inventory. */
    private ItemStack[] available() {
        ItemStack[] a = new ItemStack[TileEntityWorkbenchSVA.GRID_SLOTS + inv.mainInventory.length];
        for (int i = 0; i < TileEntityWorkbenchSVA.GRID_SLOTS; i++) {
            a[i] = tile.getStackInSlot(i);
        }
        System.arraycopy(inv.mainInventory, 0, a, TileEntityWorkbenchSVA.GRID_SLOTS, inv.mainInventory.length);
        return a;
    }

    private boolean gridEmpty() {
        for (int i = 0; i < TileEntityWorkbenchSVA.GRID_SLOTS; i++) {
            if (tile.getStackInSlot(i) != null) {
                return false;
            }
        }
        return true;
    }

    /** The recipe the right side talks about: the one being built, else the chosen one, else the grid's. */
    private WorkbenchRecipeSVA focus() {
        WorkbenchRecipeSVA a = tile.getAssembling();
        if (a != null) {
            return a;
        }
        return selected != null ? selected : tile.current();
    }

    private int count(UpgradeType t) {
        return tile.upgradeCount(t);
    }

    private Tier input() {
        return tile.inputTier();
    }

    /** The output holds a finished product (not the synced preview of an instant recipe). */
    private boolean outputDone() {
        ItemStack o = tile.getStackInSlot(TileEntityWorkbenchSVA.OUTPUT);
        if (o == null) {
            return false;
        }
        WorkbenchRecipeSVA cur = tile.current();
        return cur == null || !cur.instant() || !o.isItemEqual(cur.display());
    }

    private static int tierColour(Tier t) {
        switch (t) {
            case LV: return 0xFFB0B8C0;
            case MV: return 0xFFF0B838;
            case HV: return 0xFFF08A30;
            case EV: return 0xFFB070F0;
            case IV: return 0xFF8090A0;
            case QV: return 0xFF4AB8F0;
            case XV: return 0xFF50E0B0;
            default: return 0xFFF060D0;
        }
    }

    private static String needName(Object ing) {
        ItemStack d = WorkbenchRecipeSVA.displayOf(ing);
        return d == null ? String.valueOf(ing) : d.getDisplayName();
    }

    /** «нет: X ×N» for the first missing ingredient, or null when everything is there. */
    private String missingText(WorkbenchRecipeSVA r, ItemStack[] avail) {
        Object[] cells = r.layout();
        List<FillPlannerSVA.Need> miss = FillPlannerSVA.missing(FillPlannerSVA.needs(cells, FillPlannerSVA.plan(cells, avail)));
        if (miss.isEmpty()) {
            return null;
        }
        FillPlannerSVA.Need n = miss.get(0);
        return Lang.tr("sva.wb.row.missing", needName(n.ingredient), Integer.valueOf(n.need - n.have));
    }

    // ------------------------------------------------------------------ input

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id >= 0 && b.id < 4) {
            if (category != b.id) {
                category = b.id;
                scroll = 0;
            }
            return;
        }
        int action = b.id == BTN_START ? NetSVA.Workbench.START : b.id == BTN_FILL ? NetSVA.Workbench.FILL : b.id == BTN_CLEAR ? NetSVA.Workbench.CLEAR : -1;
        if (action < 0) {
            return;
        }
        WorkbenchRecipeSVA f = focus();
        NetSVA.CHANNEL.sendToServer(new NetSVA.Workbench(tile.xCoord, tile.yCoord, tile.zCoord, action, f == null ? "" : f.id));
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (search != null && search.isFocused() && key != Keyboard.KEY_ESCAPE) {
            if (search.textboxKeyTyped(c, key)) {
                scroll = 0;
            }
            return;
        }
        super.keyTyped(c, key);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        super.mouseClicked(mx, my, button);
        if (search != null) {
            search.mouseClicked(mx, my, button);
            if (button == 1 && inside(mx, my, guiLeft + LIST_X, guiTop + 48, LIST_W, 14)) {
                search.setText("");
                scroll = 0;
            }
        }
        if (inside(mx, my, guiLeft + LIST_X, guiTop + LIST_Y, LIST_W - 4, ROWS * ROW_H)) {
            int row = (my - guiTop - LIST_Y) / ROW_H + scroll;
            List<WorkbenchRecipeSVA> l = filtered();
            if (row >= 0 && row < l.size()) {
                WorkbenchRecipeSVA r = l.get(row);
                selected = selected == r ? null : r;
            }
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d != 0) {
            int max = Math.max(0, filtered().size() - ROWS);
            scroll = Math.max(0, Math.min(max, scroll + (d > 0 ? -1 : 1)));
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (search != null) {
            search.updateCursorCounter();
        }
        WorkbenchRecipeSVA cur = tile.current();
        boolean busy = tile.isAssembling();
        start.enabled = !busy && cur != null && !cur.instant();
        fill.enabled = focus() != null && !busy;
        clear.enabled = !gridEmpty();
    }

    /**
     * From NEI's «?» (com.sva.nei.WorkbenchOverlayHandlerSVA, no NEI classes here): pick recipe `r` in the list (its
     * tab, scrolled into view - the screen then shows its ghost items and checklist) and, with `fill`, lay it out
     * like the «Разложить» button (the same server packet; not while an assembly runs).
     */
    public void selectFromNei(WorkbenchRecipeSVA r, boolean fill) {
        if (r == null) {
            return;
        }
        category = r.category.ordinal();
        if (search != null) {
            search.setText("");
        }
        selected = r;
        int row = filtered().indexOf(r);
        scroll = Math.max(0, row - ROWS + 1);
        if (fill && !tile.isAssembling()) {
            NetSVA.CHANNEL.sendToServer(new NetSVA.Workbench(tile.xCoord, tile.yCoord, tile.zCoord, NetSVA.Workbench.FILL, r.id));
        }
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
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
        } else {
            listTooltip(mx, my);
        }
    }

    private void listTooltip(int mx, int my) {
        if (!inside(mx, my, guiLeft + LIST_X, guiTop + LIST_Y, LIST_W - 4, ROWS * ROW_H)) {
            return;
        }
        int row = (my - guiTop - LIST_Y) / ROW_H + scroll;
        List<WorkbenchRecipeSVA> l = filtered();
        if (row >= 0 && row < l.size()) {
            renderToolTip(l.get(row).display(), mx, my);
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

    @Override
    protected void drawGuiContainerBackgroundLayer(float pt, int mx, int my) {
        int x = guiLeft, y = guiTop;
        GL11.glColor4f(1F, 1F, 1F, 1F);
        // body
        frame(x, y, W, H, EDGE, BG);
        rect(x + 1, y + 1, W - 2, 1, 0xFF3A4150);
        rect(x + 4, y + 15, W - 8, 1, 0xFFB08A28);
        // left panel
        frame(x + 4, y + 16 + 22, LIST_W + 4, 128, EDGE, PANEL);
        frame(x + LIST_X, y + 49, LIST_W, 13, 0xFF3A4150, 0xFF0E1116);
        // grid frame
        frame(x + ContainerWorkbenchSVA.GRID_X - 4, y + ContainerWorkbenchSVA.GRID_Y - 4, 98, 98, EDGE, PANEL);
        // slot backgrounds
        for (Object o : inventorySlots.inventorySlots) {
            Slot s = (Slot) o;
            frame(x + s.xDisplayPosition - 1, y + s.yDisplayPosition - 1, 18, 18, SLOT_DARK, SLOT);
        }
        // output frame
        WorkbenchRecipeSVA cur = tile.current();
        boolean ready = outputDone() || cur != null && cur.instant();
        int ox = x + ContainerWorkbenchSVA.OUT_X, oy = y + ContainerWorkbenchSVA.OUT_Y;
        rect(ox - 5, oy - 5, 26, 26, ready ? 0xFF5EC8F0 : 0xFF3A4150);
        rect(ox - 4, oy - 4, 24, 24, 0xFF151920);
        frame(ox - 1, oy - 1, 18, 18, SLOT_DARK, SLOT);
        // progress arrow
        int ax = x + ARROW_X, ay = y + ARROW_Y + 2;
        float p = tile.isAssembling() && tile.getTotalTicks() > 0 ? Math.min(1F, tile.getProgress() / (float) tile.getTotalTicks())
                : (cur != null && cur.instant() || outputDone()) ? 1F : 0F;
        int arrowColour = cur != null && cur.instant() && !tile.isAssembling() ? 0xFF62E06A : 0xFF5EC8F0;
        frame(ax, ay, 26, 8, EDGE, 0xFF0E1116);
        rect(ax + 1, ay + 1, Math.round(24 * p), 6, arrowColour);
        for (int i = 0; i < 6; i++) {
            int c = p >= 1F ? arrowColour : 0xFF3A4150;
            rect(ax + 26 + i, ay - 2 + i, 1, 12 - 2 * i, c);
        }
        // status line box
        frame(x + 130, y + 129, 164, 13, tile.isAssembling() ? 0xFF5EC8F0 : 0xFF3A4150, 0xFF0E1116);
        // right: info panel, gauge, checklist
        frame(x + 297, y + 18, 73, 56, EDGE, PANEL);
        float level = container.clientMaxEnergy() <= 0 ? 0F : tile.getEnergyStored() / (float) container.clientMaxEnergy();
        GuiEnergyGaugeSC.draw(x + 374, y + 18, 22, 54, level);
        frame(x + 297, y + 82, 99, 146, EDGE, PANEL);
        // upgrades frame
        frame(x + 4, y + 168, LIST_W + 4, 60, EDGE, PANEL);
        for (Object o : inventorySlots.inventorySlots) {
            Slot s = (Slot) o;
            if (s.slotNumber >= TileEntityWorkbenchSVA.FIRST_UPGRADE && s.slotNumber < TileEntityWorkbenchSVA.SIZE) {
                frame(x + s.xDisplayPosition - 1, y + s.yDisplayPosition - 1, 18, 18, SLOT_DARK, SLOT);
            }
        }
        // list rows
        drawList(x, y, mx, my);
        // search box text
        search.drawTextBox();
        if (search.getText().length() == 0 && !search.isFocused()) {
            fontRendererObj.drawString(Lang.tr("sva.wb.search"), x + LIST_X + 3, y + 51, 0x5A6272);
        }
        // ghost items
        WorkbenchRecipeSVA f = focus();
        if (f != null && gridEmpty() && !tile.isAssembling()) {
            drawGhost(f, x, y);
        }
    }

    private void drawList(int x, int y, int mx, int my) {
        List<WorkbenchRecipeSVA> l = filtered();
        int max = Math.max(0, l.size() - ROWS);
        scroll = Math.max(0, Math.min(max, scroll));
        for (int i = 0; i < ROWS && i + scroll < l.size(); i++) {
            WorkbenchRecipeSVA r = l.get(i + scroll);
            int rx = x + LIST_X, ry = y + LIST_Y + i * ROW_H;
            boolean sel = r == selected, hov = inside(mx, my, rx, ry, LIST_W - 4, ROW_H - 1);
            frame(rx, ry, LIST_W - 4, ROW_H - 1, sel ? 0xFF5EC8F0 : 0xFF3A4150, hov ? PANEL_HI : 0xFF262C37);
        }
        if (l.size() > ROWS) {
            int bx = x + LIST_X + LIST_W - 3, by = y + LIST_Y, bh = ROWS * ROW_H - 1;
            rect(bx, by, 3, bh, 0xFF0E1116);
            int th = Math.max(8, bh * ROWS / l.size());
            int ty = by + (bh - th) * scroll / Math.max(1, max);
            rect(bx, ty, 3, th, 0xFF5EC8F0);
        }
    }

    private void drawGhost(WorkbenchRecipeSVA r, int x, int y) {
        Object[] cells = r.layout();
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        for (int i = 0; i < cells.length; i++) {
            ItemStack d = cells[i] == null ? null : WorkbenchRecipeSVA.displayOf(cells[i]);
            if (d == null) {
                continue;
            }
            int sx = x + ContainerWorkbenchSVA.GRID_X + (i % 5) * 18, sy = y + ContainerWorkbenchSVA.GRID_Y + (i / 5) * 18;
            itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), d, sx, sy);
        }
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != null) {
                int sx = x + ContainerWorkbenchSVA.GRID_X + (i % 5) * 18, sy = y + ContainerWorkbenchSVA.GRID_Y + (i / 5) * 18;
                rect(sx, sy, 16, 16, 0x9A2B313C);
                rect(sx - 1, sy - 1, 18, 1, 0x705EC8F0);
            }
        }
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mx, int my) {
        int ox = guiLeft, oy = guiTop;
        // title and the line
        fontRendererObj.drawString(Lang.tr("tile.siliconagevehicles.workbench.name"), 7, 5, GOLD);
        Tier in = input();
        String head = Lang.tr("sva.wb.head", in.name(), Integer.valueOf(in.getVoltage()), FormatSVA.eu(tile.getEnergyStored()),
                FormatSVA.eu(container.clientMaxEnergy()));
        int hw = Math.min(220, fontRendererObj.getStringWidth(head));
        TextFitSC.draw(fontRendererObj, head, W - 6 - hw, 5, 220, MUTED, ox, oy);
        // left: category, list, upgrades
        fontRendererObj.drawString(Lang.tr("sva.wb.tab." + category), LIST_X + 1, 40, CYAN);
        drawListText(mx, my);
        fontRendererObj.drawString(Lang.tr("sva.wb.upgrades"), LIST_X + 1, 171, CYAN);
        upgradeInfo(mx, my);
        // middle: percentage, status
        drawProgressText();
        drawStatus();
        // right: info, gauge text, checklist
        drawInfo();
        String eu = FormatSVA.eu(tile.getEnergyStored());
        TextFitSC.drawCentered(fontRendererObj, eu, 372, 75, 26, MUTED, false, ox, oy);
        List<String> g = new ArrayList<String>();
        g.add(Lang.tr("sva.wb.gauge.eu", FormatSVA.eu(tile.getEnergyStored()), FormatSVA.eu(container.clientMaxEnergy())));
        g.add(Lang.tr("sva.wb.gauge.in", in.name(), Integer.valueOf(in.getVoltage())));
        TextFitSC.hover(ox + 374, oy + 18, 22, 54, g);
        drawChecklist();
    }

    private void drawListText(int mx, int my) {
        List<WorkbenchRecipeSVA> l = filtered();
        if (l.isEmpty()) {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.wb.list.none"), LIST_X + 3, LIST_Y + 4, LIST_W - 8,
                    MUTED, guiLeft, guiTop);
            return;
        }
        ItemStack[] avail = available();
        for (int i = 0; i < ROWS && i + scroll < l.size(); i++) {
            WorkbenchRecipeSVA r = l.get(i + scroll);
            int rx = LIST_X, ry = LIST_Y + i * ROW_H;
            RenderHelper.enableGUIStandardItemLighting();
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), r.display(), rx + 2, ry + 1);
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL11.GL_LIGHTING);
            String nm = name(r) + (r.display().stackSize > 1 ? " x" + r.display().stackSize : "");
            TextFitSC.draw(fontRendererObj, nm, rx + 20, ry + 2, 66, TEXT, guiLeft, guiTop);
            // tier chip
            String tn = r.tier.name();
            int cw = fontRendererObj.getStringWidth(tn) + 4;
            rect(rx + LIST_W - 8 - cw, ry + 2, cw, 9, tierColour(r.tier));
            fontRendererObj.drawString(tn, rx + LIST_W - 6 - cw, ry + 3, 0x1A1A1A);
            // status
            String miss = missingText(r, avail);
            String st;
            int col;
            if (r.category == Category.VEHICLES && !WorkbenchMath.canBuild(r.tier, input())) {
                st = Lang.tr("sva.wb.row.tier", WorkbenchMath.requiredInput(r.tier).name());
                col = YELLOW;
            } else if (miss == null) {
                st = Lang.tr("sva.wb.row.ok");
                col = GREEN;
            } else {
                st = miss;
                col = RED;
            }
            TextFitSC.draw(fontRendererObj, st, rx + 20, ry + 11, LIST_W - 26, col, guiLeft, guiTop);
        }
    }

    private void upgradeInfo(int mx, int my) {
        int oc = count(UpgradeType.OVERCLOCKER), st = count(UpgradeType.ENERGY_STORAGE), tr = count(UpgradeType.TRANSFORMER),
                eco = count(UpgradeType.ECONOMIZER);
        String[] names = {"sva.wb.upg.oc", "sva.wb.upg.storage", "sva.wb.upg.transformer", "sva.wb.upg.eco"};
        for (int i = 0; i < TileEntityWorkbenchSVA.UPGRADE_SLOTS; i++) {
            if (tile.getStackInSlot(TileEntityWorkbenchSVA.FIRST_UPGRADE + i) == null) {
                List<String> t = new ArrayList<String>();
                t.add(Lang.tr(names[i]));
                t.add("§7" + Lang.tr("sva.wb.upg.any"));
                TextFitSC.hover(guiLeft + ContainerWorkbenchSVA.UPG_X + i * ContainerWorkbenchSVA.UPG_STEP - 1,
                        guiTop + ContainerWorkbenchSVA.UPG_Y - 1, 18, 18, t);
                fontRendererObj.drawString(Lang.tr(names[i] + ".s"), ContainerWorkbenchSVA.UPG_X + i * ContainerWorkbenchSVA.UPG_STEP + 4,
                        ContainerWorkbenchSVA.UPG_Y + 4, 0x4A5262);
            }
        }
        double time = WorkbenchMath.ticks(1000, oc) / 1000.0, eu = WorkbenchMath.eu(1000000, oc, eco) / 1000000.0;
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.wb.upg.line1", pct(time), pct(eu)), LIST_X + 1, 201, LIST_W - 2, oc + eco > 0 ? GREEN : MUTED,
                guiLeft, guiTop);
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.wb.upg.line2", input().name(), FormatSVA.eu(WorkbenchMath.buffer(st))), LIST_X + 1, 211,
                LIST_W - 2, tr + st > 0 || tile.acceptsAnyVoltage() ? GREEN : MUTED, guiLeft, guiTop);
        WorkbenchRecipeSVA f = focus();
        if (f != null && !f.instant()) {
            String line = Lang.tr("sva.wb.upg.line3", FormatSVA.eu(tile.effectiveEu(f)), Integer.valueOf(FormatSVA.seconds(tile.effectiveTicks(f))));
            TextFitSC.draw(fontRendererObj, line, LIST_X + 1, 221, LIST_W - 2, TEXT, guiLeft, guiTop);
        }
    }

    private static String pct(double k) {
        return "x" + String.format(Locale.ROOT, "%.2f", Double.valueOf(k)).replace('.', Lang.tr("sva.num.point").charAt(0));
    }

    private void drawProgressText() {
        WorkbenchRecipeSVA cur = tile.current();
        String t;
        int c = CYAN;
        if (tile.isAssembling() && tile.getTotalTicks() > 0) {
            t = (tile.getProgress() * 100 / tile.getTotalTicks()) + "%";
        } else if (cur != null && cur.instant()) {
            t = Lang.tr("sva.wb.instant");
            c = GREEN;
        } else {
            return;
        }
        TextFitSC.drawCentered(fontRendererObj, t, 220, 70, 40, c, false, guiLeft, guiTop);
    }

    private void drawStatus() {
        String s;
        int c;
        WorkbenchRecipeSVA cur = tile.current(), a = tile.getAssembling();
        if (a != null) {
            int left = FormatSVA.seconds(Math.max(0, tile.getTotalTicks() - tile.getProgress()));
            if (!tile.isPowered()) {
                s = Lang.tr(WorkbenchMath.canBuild(a.tier, input()) ? "sva.wb.st.noPower" : "sva.wb.st.lowTier", WorkbenchMath.requiredInput(a.tier).name());
                c = YELLOW;
            } else if (tile.getProgress() >= tile.getTotalTicks()) {
                s = Lang.tr("sva.wb.st.outputFull");
                c = YELLOW;
            } else {
                s = Lang.tr("sva.wb.st.working", name(a), Integer.valueOf(left));
                c = CYAN;
            }
        } else if (outputDone()) {
            s = Lang.tr("sva.wb.st.done", tile.getStackInSlot(TileEntityWorkbenchSVA.OUTPUT).getDisplayName());
            c = GREEN;
        } else if (cur != null) {
            if (cur.instant()) {
                s = Lang.tr("sva.wb.st.instant");
                c = GREEN;
            } else if (!WorkbenchMath.canBuild(cur.tier, input())) {
                s = Lang.tr("sva.wb.st.lowTier", WorkbenchMath.requiredInput(cur.tier).name());
                c = YELLOW;
            } else {
                s = Lang.tr("sva.wb.st.ready");
                c = GREEN;
            }
        } else if (selected != null) {
            String miss = missingText(selected, available());
            if (miss == null) {
                s = Lang.tr("sva.wb.st.canFill");
                c = GREEN;
            } else {
                s = Lang.tr("sva.wb.st.missing", missList(selected));
                c = RED;
            }
        } else if (!gridEmpty()) {
            s = Lang.tr("sva.wb.st.unknown");
            c = MUTED;
        } else {
            s = Lang.tr("sva.wb.st.idle");
            c = MUTED;
        }
        TextFitSC.draw(fontRendererObj, s, 133, 132, 158, c, guiLeft, guiTop);
    }

    private String missList(WorkbenchRecipeSVA r) {
        Object[] cells = r.layout();
        List<FillPlannerSVA.Need> miss = FillPlannerSVA.missing(FillPlannerSVA.needs(cells, FillPlannerSVA.plan(cells, available())));
        StringBuilder b = new StringBuilder();
        for (FillPlannerSVA.Need n : miss) {
            if (b.length() > 0) {
                b.append(", ");
            }
            b.append(needName(n.ingredient)).append(" x").append(n.need - n.have);
        }
        return b.toString();
    }

    private void drawInfo() {
        WorkbenchRecipeSVA f = focus();
        int x = 300, y = 21, w = 67;
        if (f == null) {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.wb.info.none1"), x, y, w, MUTED, guiLeft, guiTop);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.wb.info.none2"), x, y + 10, w, MUTED, guiLeft, guiTop);
            return;
        }
        TextFitSC.draw(fontRendererObj, name(f), x, y, w, 0xFFFFFF, guiLeft, guiTop);
        String tn = f.tier.name();
        int cw = fontRendererObj.getStringWidth(tn) + 4;
        rect(x, y + 10, cw, 9, tierColour(f.tier));
        fontRendererObj.drawString(tn, x + 2, y + 11, 0x1A1A1A);
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.wb.cat." + f.category.ordinal()), x + cw + 3, y + 11, w - cw - 3, YELLOW, guiLeft, guiTop);
        if (f.instant()) {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.wb.info.instant"), x, y + 22, w, GREEN, guiLeft, guiTop);
            if (f.display().stackSize > 1) {
                TextFitSC.draw(fontRendererObj, Lang.tr("sva.wb.info.count", Integer.valueOf(f.display().stackSize)), x, y + 32, w, TEXT, guiLeft, guiTop);
            }
        } else {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.wb.info.eu", FormatSVA.eu(tile.effectiveEu(f))), x, y + 22, w, TEXT, guiLeft, guiTop);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.wb.info.time", Integer.valueOf(FormatSVA.seconds(tile.effectiveTicks(f)))), x, y + 32, w, TEXT,
                    guiLeft, guiTop);
            Tier need = WorkbenchMath.requiredInput(f.tier);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.wb.info.input", need.name()), x, y + 42, w,
                    WorkbenchMath.canBuild(f.tier, input()) ? GREEN : RED, guiLeft, guiTop);
        }
    }

    private void drawChecklist() {
        fontRendererObj.drawString(Lang.tr("sva.wb.need"), 300, 85, CYAN);
        WorkbenchRecipeSVA f = focus();
        if (f == null) {
            return;
        }
        Object[] cells = f.layout();
        ItemStack[] avail = available();
        List<FillPlannerSVA.Need> needs = FillPlannerSVA.needs(cells, FillPlannerSVA.plan(cells, avail));
        boolean building = tile.isAssembling();
        int y = 96;
        for (int i = 0; i < needs.size() && y < 222; i++, y += 10) {
            FillPlannerSVA.Need n = needs.get(i);
            boolean ok = building || n.ok();
            if (ok) {
                // a tick
                rect(300, y + 4, 1, 2, 0xFF62E06A);
                rect(301, y + 5, 1, 2, 0xFF62E06A);
                rect(302, y + 6, 1, 1, 0xFF62E06A);
                for (int k = 0; k < 4; k++) {
                    rect(303 + k, y + 5 - k, 1, 2, 0xFF62E06A);
                }
            } else {
                for (int k = 0; k < 5; k++) {
                    rect(300 + k, y + 1 + k, 1, 1, 0xFFF06060);
                    rect(304 - k, y + 1 + k, 1, 1, 0xFFF06060);
                }
            }
            String cnt = (building ? n.need : Math.min(n.have, n.need)) + "/" + n.need;
            int cntW = fontRendererObj.getStringWidth(cnt);
            TextFitSC.draw(fontRendererObj, needName(n.ingredient), 310, y, 82 - cntW - 4, TEXT, guiLeft, guiTop);
            fontRendererObj.drawString(cnt, 393 - cntW, y, ok ? GREEN : RED);
        }
    }
}
