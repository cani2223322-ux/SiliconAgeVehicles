package com.sva;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.ArmorGasSC.Gas;
import com.sva.block.TileEntityChargerSVA;
import com.sva.block.TileEntityGarageSVA;
import com.sva.block.TileEntityGasColumnSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.VehicleType;

import mcp.mobius.waila.api.IWailaConfigHandler;
import mcp.mobius.waila.api.IWailaDataAccessor;
import mcp.mobius.waila.api.IWailaDataProvider;
import mcp.mobius.waila.api.IWailaEntityAccessor;
import mcp.mobius.waila.api.IWailaEntityProvider;
import mcp.mobius.waila.api.IWailaRegistrar;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * WAILA lines (the core's WailaSC pattern): a vehicle - its kind in the head, then owner, board number, charge, hull and
 * the lock mode; the charging station and the repair garage - owner and the vehicle they serve now (the core adds their
 * energy); the gas column - owner and its tanks. The numbers come from the server (getNBTData); without WAILA on the
 * server the vehicle falls back to what its entity syncs anyway (no lock mode) and the blocks show nothing extra.
 * Only loaded when WAILA is present - it finds us through the IMC message ModContentSVA.preInit sends.
 */
public class WailaSVA implements IWailaDataProvider, IWailaEntityProvider {

    private static final String K = "svaW";

    /** Called by WAILA (IMC "register"). */
    public static void callbackRegister(IWailaRegistrar registrar) {
        WailaSVA provider = new WailaSVA();
        registrar.registerHeadProvider((IWailaEntityProvider) provider, EntityVehicleSVA.class);
        registrar.registerBodyProvider((IWailaEntityProvider) provider, EntityVehicleSVA.class);
        registrar.registerNBTProvider((IWailaEntityProvider) provider, EntityVehicleSVA.class);
        for (Class<?> c : new Class<?>[]{TileEntityChargerSVA.class, TileEntityGarageSVA.class, TileEntityGasColumnSVA.class}) {
            registrar.registerBodyProvider((IWailaDataProvider) provider, c);
            registrar.registerNBTProvider((IWailaDataProvider) provider, c);
        }
    }

    // ------------------------------------------------------------------ vehicles (entities)

    @Override
    public Entity getWailaOverride(IWailaEntityAccessor accessor, IWailaConfigHandler config) {
        return null;
    }

    @Override
    public List<String> getWailaHead(Entity e, List<String> tip, IWailaEntityAccessor accessor, IWailaConfigHandler config) {
        if (e instanceof EntityVehicleSVA) {
            String name = "§f" + typeName(((EntityVehicleSVA) e).getVehicleType());
            if (tip.isEmpty()) {
                tip.add(name);
            } else {
                tip.set(0, name);                                   // "Техника" -> "Электробагги"
            }
        }
        return tip;
    }

    @Override
    public List<String> getWailaBody(Entity e, List<String> tip, IWailaEntityAccessor accessor, IWailaConfigHandler config) {
        if (!(e instanceof EntityVehicleSVA)) {
            return tip;
        }
        EntityVehicleSVA v = (EntityVehicleSVA) e;
        NBTTagCompound t = accessor.getNBTData();
        NBTTagCompound w = t != null && t.hasKey(K) ? t.getCompoundTag(K) : null;
        String owner = w != null ? w.getString("Owner") : v.getOwnerName();
        int board = w != null ? w.getInteger("Board") : v.getBoardNumber();
        int charge = w != null ? w.getInteger("Charge") : pct(v.getEnergy(), v.getMaxEnergy());
        int hull = w != null ? w.getInteger("Hull") : pct(v.getHp(), v.getMaxHp());
        tip.add(Lang.tr("sva.tooltip.owner", owner.length() > 0 ? owner : Lang.tr("sva.waila.nobody")));
        tip.add(Lang.tr("sva.waila.board", Integer.valueOf(board)));
        tip.add(Lang.tr("sva.waila.charge", Integer.valueOf(charge)));
        tip.add(Lang.tr(v.isWrecked() ? "sva.waila.hull.wrecked" : "sva.waila.hull", Integer.valueOf(hull)));
        if (w != null && w.hasKey("Lock")) {
            tip.add(Lang.tr("sva.waila.lock", Lang.tr("sva.access.mode." + w.getInteger("Lock"))));
        }
        return tip;
    }

    @Override
    public List<String> getWailaTail(Entity e, List<String> tip, IWailaEntityAccessor accessor, IWailaConfigHandler config) {
        return tip;
    }

    @Override
    public NBTTagCompound getNBTData(EntityPlayerMP player, Entity e, NBTTagCompound tag, World world) {
        if (e instanceof EntityVehicleSVA) {
            EntityVehicleSVA v = (EntityVehicleSVA) e;
            NBTTagCompound w = new NBTTagCompound();
            w.setString("Owner", v.getOwnerName());
            w.setInteger("Board", v.getBoardNumber());
            w.setInteger("Charge", pct(v.state.energy, v.getMaxEnergy()));
            w.setInteger("Hull", pct(v.state.hp, v.getMaxHp()));
            if (ConfigSVA.locks) {
                w.setInteger("Lock", v.state.access.mode);
            }
            tag.setTag(K, w);
        }
        return tag;
    }

    // ------------------------------------------------------------------ station, garage, gas column (tiles)

    @Override
    public ItemStack getWailaStack(IWailaDataAccessor accessor, IWailaConfigHandler config) {
        return null;
    }

    @Override
    public List<String> getWailaHead(ItemStack stack, List<String> tip, IWailaDataAccessor accessor, IWailaConfigHandler config) {
        return tip;
    }

    @Override
    public List<String> getWailaBody(ItemStack stack, List<String> tip, IWailaDataAccessor accessor, IWailaConfigHandler config) {
        NBTTagCompound t = accessor.getNBTData();
        if (t == null || !t.hasKey(K)) {
            return tip;
        }
        NBTTagCompound w = t.getCompoundTag(K);
        String owner = w.getString("Owner");
        boolean column = w.getBoolean("Column");
        tip.add(Lang.tr("sva.tooltip.owner", owner.length() > 0 ? owner : Lang.tr(column ? "sva.waila.nobody.column" : "sva.waila.nobody")));
        if (column) {
            NBTTagCompound g = w.getCompoundTag("Gas");
            boolean any = false;
            for (Gas gas : Gas.values()) {
                int mb = g.getInteger(gas.key());
                if (mb > 0) {
                    any = true;
                    tip.add(Lang.tr("sva.waila.gas", Lang.tr("sc.armorStation.gas." + gas.key()), Integer.valueOf(mb),
                            Integer.valueOf(TileEntityGasColumnSVA.TANK)));
                }
            }
            if (!any) {
                tip.add(Lang.tr("sva.waila.gas.empty"));
            }
        } else if (w.hasKey("VType")) {
            tip.add(Lang.tr("sva.waila.served", typeName(VehicleType.byOrdinal(w.getInteger("VType"))), Integer.valueOf(w.getInteger("VBoard")),
                    w.getString("VOwner")));
            if (w.hasKey("Repair")) {
                tip.add(Lang.tr("sva.waila.repair", Integer.valueOf(w.getInteger("Repair"))));
            }
        } else {
            tip.add(Lang.tr("sva.waila.served.none"));
        }
        return tip;
    }

    @Override
    public List<String> getWailaTail(ItemStack stack, List<String> tip, IWailaDataAccessor accessor, IWailaConfigHandler config) {
        return tip;
    }

    @Override
    public NBTTagCompound getNBTData(EntityPlayerMP player, TileEntity te, NBTTagCompound tag, World world, int x, int y, int z) {
        NBTTagCompound w = new NBTTagCompound();
        EntityVehicleSVA served = null;
        if (te instanceof TileEntityChargerSVA) {
            TileEntityChargerSVA c = (TileEntityChargerSVA) te;
            w.setString("Owner", c.getOwnerName());
            served = c.getCurrent();
        } else if (te instanceof TileEntityGarageSVA) {
            TileEntityGarageSVA g = (TileEntityGarageSVA) te;
            w.setString("Owner", g.getOwnerName());
            served = g.getCurrent();
            if (served != null && g.isRepairing()) {
                w.setInteger("Repair", (int) Math.round(Math.max(0, Math.min(1, g.progress())) * 100));
            }
        } else if (te instanceof TileEntityGasColumnSVA) {
            TileEntityGasColumnSVA col = (TileEntityGasColumnSVA) te;
            w.setString("Owner", col.getOwnerName());
            w.setBoolean("Column", true);
            NBTTagCompound g = new NBTTagCompound();
            for (Gas gas : Gas.values()) {
                g.setInteger(gas.key(), col.amount(gas));
            }
            w.setTag("Gas", g);
        } else {
            return tag;
        }
        if (served != null && !served.isDead) {
            w.setInteger("VType", served.state.type.ordinal());
            w.setInteger("VBoard", served.getBoardNumber());
            w.setString("VOwner", served.getOwnerName());
        }
        tag.setTag(K, w);
        return tag;
    }

    // ------------------------------------------------------------------ helpers

    static String typeName(VehicleType t) {
        return Lang.tr("item.siliconagevehicles.vehicle." + t.key + ".name");
    }

    /** value / max as a whole percent 0..100 (max <= 0: 0). */
    static int pct(double value, double max) {
        if (!(max > 0) || Double.isNaN(value)) {
            return 0;
        }
        return (int) Math.round(Math.max(0, Math.min(1, value / max)) * 100);
    }
}
