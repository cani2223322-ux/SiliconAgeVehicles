package com.sva.item;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.ConfigSVA;
import com.sva.ReferenceSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.net.ServerConfigSVA;
import com.sva.vehicle.TuningMath;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * A vehicle as an item (meta = VehicleType ordinal); the NBT keeps its whole state. Right-click a block: place it.
 * ТБ-4: with IC2 it is an electric item (ISpecialElectricItem) - IC2's chargers and the core's storages / charge pads
 * top it up, at most its energy block's packet a tick; only the charge in the saved state changes.
 */
@cpw.mods.fml.common.Optional.Interface(iface = "ic2.api.item.ISpecialElectricItem", modid = com.sc.Reference.IC2_MODID)
public class ItemVehicleSVA extends Item implements ic2.api.item.ISpecialElectricItem {

    private IIcon[] icons;
    /** The IC2 manager (Object: IC2's type is only loaded with IC2). */
    private static Object ic2Manager;

    public ItemVehicleSVA() {
        setHasSubtypes(true);
        setMaxDamage(0);
        setMaxStackSize(1);
        setUnlocalizedName(ReferenceSVA.ASSETS + ".vehicle");
    }

    public static VehicleType typeOf(ItemStack s) {
        return VehicleType.byOrdinal(s.getItemDamage());
    }

    /** The state an item holds (a fresh one for a plain item). */
    public static VehicleState stateOf(ItemStack s) {
        VehicleState st = new VehicleState(typeOf(s));
        if (s.hasTagCompound() && s.getTagCompound().hasKey(EntityVehicleSVA.NBT_ROOT)) {
            st.readNBT(s.getTagCompound().getCompoundTag(EntityVehicleSVA.NBT_ROOT));
        }
        st.type = typeOf(s);
        return st;
    }

    public static ItemStack withState(ItemStack s, VehicleState st) {
        NBTTagCompound root = s.hasTagCompound() ? s.getTagCompound() : new NBTTagCompound();
        NBTTagCompound t = new NBTTagCompound();
        st.writeNBT(t);
        root.setTag(EntityVehicleSVA.NBT_ROOT, t);
        s.setTagCompound(root);
        return s;
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        return getUnlocalizedName() + "." + typeOf(s).key;
    }

    @Override
    public boolean onItemUse(ItemStack stack, EntityPlayer p, World w, int x, int y, int z, int side, float hx, float hy, float hz) {
        if (w.isRemote) {
            return true;
        }
        int[][] off = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        int bx = x, by = y, bz = z;
        if (!w.getBlock(x, y, z).isReplaceable(w, x, y, z)) {
            bx += off[side][0];
            by += off[side][1];
            bz += off[side][2];
        }
        if (com.sc.ShieldEventHandler.privateFor(w, p, bx, by, bz)) {
            return true;                                // someone else's private field (it says so)
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        String owner = p.getUniqueID().toString();
        if (reg != null && !VehicleMath.limitAllows(reg.count(owner), ConfigSVA.maxPerPlayer)) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.msg.limit", Integer.valueOf(ConfigSVA.maxPerPlayer)));
            return true;
        }
        float yaw = MathHelper.wrapAngleTo180_float(p.rotationYaw);
        EntityVehicleSVA v = spawn(w, stack, bx + 0.5, by, bz + 0.5, yaw, owner, p.getCommandSenderName());
        if (v == null) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.msg.noSpace"));
            return true;
        }
        if (!p.capabilities.isCreativeMode) {
            stack.stackSize--;
        }
        return true;
    }

    /**
     * Places the vehicle an item holds (server): owner = whoever places it. ТБ-5: the vehicle keeps its id (board
     * number, key cards) whenever no vehicle with that id is placed now (picking it up takes it off the registry); only
     * a plain item or a copy whose id is already out there (a creative copy, a dupe) gets a fresh one. Placed by a new
     * owner, the lock is changed (as «Сменить замок»): the key cards made for the old owner stop opening it.
     * @return null when there is no room (blocks or liquid in the way)
     */
    public static EntityVehicleSVA spawn(World w, ItemStack stack, double x, double y, double z, float yaw, String ownerId, String ownerName) {
        VehicleState st = stateOf(stack);
        String newOwner = ownerId == null ? "" : ownerId;
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        boolean saved = stack.hasTagCompound() && stack.getTagCompound().hasKey(EntityVehicleSVA.NBT_ROOT);
        boolean keepId = saved && reg != null && st.vehId != null && st.vehId.length() > 0 && !reg.has(st.vehId);
        if (keepId && st.ownerId != null && st.ownerId.length() > 0 && !st.ownerId.equals(newOwner)) {
            st.access.lockVersion++;                        // another owner: the old owner's cards are void
        }
        st.ownerId = newOwner;
        st.ownerName = ownerName == null ? "" : ownerName;
        if (!keepId) {
            st.vehId = java.util.UUID.randomUUID().toString();    // copied stacks never share a placed id
        }
        EntityVehicleSVA v = new EntityVehicleSVA(w, st.type);
        v.state = st;
        v.setLocationAndAngles(x, y, z, yaw, 0);
        if (!w.getCollidingBoundingBoxes(v, v.boundingBox.contract(0.02, 0.02, 0.02)).isEmpty() || w.isAnyLiquid(v.boundingBox)) {
            return null;
        }
        if (reg != null) {
            reg.add(st.vehId, st.ownerId);
        }
        w.spawnEntityInWorld(v);
        return v;
    }

    /**
     * ★15: places the vehicle an item holds as the SAME vehicle (the platform trailer's unloading): its id, owner and
     * whole state are kept - its board number and key cards stay valid. @return null when there is no room
     */
    public static EntityVehicleSVA spawnKept(World w, ItemStack stack, double x, double y, double z, float yaw) {
        VehicleState st = stateOf(stack);
        EntityVehicleSVA v = new EntityVehicleSVA(w, st.type);
        v.state = st;
        v.setLocationAndAngles(x, y, z, yaw, 0);
        if (!w.getCollidingBoundingBoxes(v, v.boundingBox.contract(0.02, 0.02, 0.02)).isEmpty() || w.isAnyLiquid(v.boundingBox)) {
            return null;
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (reg != null) {
            reg.add(st.vehId, st.ownerId);
        }
        w.spawnEntityInWorld(v);
        return v;
    }

    // ------------------------------------------------------------------ ТО-3: limits as the server counts them

    /** The charge limit with the server's vehicles.tuning (ServerConfigSVA; the local config until it is known). */
    static double shownMaxEnergy(VehicleState st) {
        return st.type.buffer * (ServerConfigSVA.tuning() ? TuningMath.bufferMul(st.tuning) : 1);
    }

    /** The charge shown: what the item holds (the part kept aside included) up to the server's limit. */
    static double shownEnergy(VehicleState st) {
        return Math.max(0, Math.min(st.energy + st.energyHeld, shownMaxEnergy(st)));
    }

    static int shownGasCapacity(VehicleState st, com.sc.util.ArmorGasSC.Gas g) {
        int c = st.type.gases.capacity(g);
        return ServerConfigSVA.tuning() ? (int) Math.round(c * TuningMath.gasMul(st.tuning)) : c;
    }

    static int shownGas(VehicleState st, com.sc.util.ArmorGasSC.Gas g) {
        return Math.max(0, Math.min(st.gas(g) + st.gasHeld[g.ordinal()], shownGasCapacity(st, g)));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer player, List list, boolean adv) {
        VehicleState st = stateOf(s);
        VehicleType t = st.type;
        double energy = shownEnergy(st), maxEnergy = shownMaxEnergy(st);
        int pct = (int) Math.floor(energy * 100 / Math.max(1, maxEnergy));
        list.add(Lang.tr("sva.tooltip.charge", fmt((long) energy), fmt((long) maxEnergy), Integer.valueOf(pct)));
        if (ServerConfigSVA.wear()) {
            list.add(st.hp <= 0 ? Lang.tr("sva.tooltip.wrecked")
                    : Lang.tr("sva.tooltip.hp", Integer.valueOf(Math.round(st.hp * 100 / st.maxHp()))));
        }
        for (com.sc.util.ArmorGasSC.Gas g : t.gases.gases()) {
            list.add(Lang.tr("sva.tooltip.gas", Lang.tr("sva.gas." + g.key()), fmt(shownGas(st, g)), fmt(shownGasCapacity(st, g))));
        }
        list.add(Lang.tr("sva.tooltip.cargo", Integer.valueOf(st.cargoUsed()), Integer.valueOf(st.cargoSize())));
        if (st.trailer) {
            list.add(st.trailerType == com.sva.vehicle.TrailerMath.CARGO ? Lang.tr("sva.tooltip.trailerOn")
                    : Lang.tr("sva.tooltip.trailerKindOn", Lang.tr("item.siliconagevehicles.trailer_" + com.sva.vehicle.TrailerMath.KEYS[st.trailerType] + ".name")));
        }
        if (t.hasDrill) {
            list.add(st.drillHead == null ? Lang.tr("sva.tooltip.drill.noHead")
                    : Lang.tr("sva.tooltip.drill.head", st.drillHead.getDisplayName()));
        }
        if (st.ownerName != null && st.ownerName.length() > 0) {
            list.add(Lang.tr("sva.tooltip.owner", st.ownerName));
        }
        if (com.sva.vehicle.TuningMath.count(st.tuning) > 0) {
            StringBuilder tb = new StringBuilder();
            for (int k : st.tuning) {
                if (com.sva.vehicle.TuningMath.valid(k)) {
                    tb.append(tb.length() > 0 ? ", " : "").append(Lang.tr("item.siliconagevehicles.tuning." + com.sva.vehicle.TuningMath.KEYS[k] + ".name"));
                }
            }
            list.add(Lang.tr("sva.tooltip.tuningList", tb.toString()));        // wave 2, ★8
        }
        boolean ground = t.movement == com.sva.vehicle.Movement.GROUND, flies = t.movement.flies,
                crawler = t.movement == com.sva.vehicle.Movement.CRAWLER;
        double cons = ServerConfigSVA.consumption();
        String details = (t == VehicleType.TILT ? Lang.tr("sva.tooltip.details." + t.key, keyNames()) : Lang.tr("sva.tooltip.details." + t.key)) + "\n"
                + Lang.tr(flies ? "sva.tooltip.vehicle.statsAir" : crawler ? "sva.tooltip.vehicle.statsCrawler" : "sva.tooltip.vehicle.stats", t.tier.name(),
                Integer.valueOf(VehicleMath.kmh(t.topSpeed)),
                Integer.valueOf(t.seats), Integer.valueOf(t.cargoSlots)) + "\n"
                + (flies ? Lang.tr("sva.tooltip.energy." + t.key, fmt((long) (t.peakCost * cons)), fmt((long) (t.cruiseCost * cons)),
                        fmt((long) (t.idleCost * cons)), fmt((long) VehicleMath.range(t, t.buffer, 0, cons)),
                        fmt((long) (t.vtolPeak * cons)), fmt((long) (t.vtolCruise * cons)))
                        : Lang.tr("sva.tooltip.vehicle.energy", fmt((long) (t.cruiseCost * cons)), fmt((long) (t.idleCost * cons)),
                        fmt((long) VehicleMath.range(t, t.buffer, 0, cons)))) + "\n"
                + (t.gases.liftGas != null || flies || crawler ? Lang.tr("sva.tooltip.vehicle.gas." + t.key, fmt(Math.round(t.gases.liftPerSecond)),
                        fmt(Math.round(t.gases.boostPerSecond)), fmt(Math.round(t.gases.boostGasPerSecond)),
                        String.valueOf(t.gases.boostMul).replace('.', ',')) + "\n" : "")
                + Lang.tr(ground ? "sva.tooltip.vehicle.limits" : "sva.tooltip.limits." + t.key, new Object[0]);
        TooltipSC.more(list, details, Lang.tr("sva.tooltip.howto.vehicle1") + "\n"
                + (ground ? Lang.tr("sva.tooltip.howto.vehicle2") : Lang.tr("sva.tooltip.howto." + t.key + "2", keyNames())) + "\n"
                + Lang.tr(ground ? "sva.tooltip.howto.vehicle3" : "sva.tooltip.howto." + t.key + "3", new Object[0]) + "\n"
                + Lang.tr("sva.tooltip.howto.menuKey", com.sva.client.VehicleKeyBindsSVA.menuKeyName()) + "\n"
                + Lang.tr("sva.tooltip.howto.cargoKey", com.sva.proxy.ClientProxySVA.cargoKeyName()) + "\n"
                + (t.hasDrill ? Lang.tr("sva.tooltip.howto.drillHead", com.sva.proxy.ClientProxySVA.cargoKeyName()) + "\n" : "")
                + Lang.tr("sva.tooltip.howto.pickUp", new Object[0]));
    }

    static String fmt(long n) {
        return com.sva.FormatSVA.group(n);
    }

    /** The rebindable keys the howto / tilt texts name, as their %1$s..%8$s: Снижение, Форсаж, Режим, Бур, Прыжок, Цель прыжка, Якорь, Невидимость. */
    @SideOnly(Side.CLIENT)
    static Object[] keyNames() {
        com.sva.vehicle.VehicleFn[] f = {com.sva.vehicle.VehicleFn.DOWN, com.sva.vehicle.VehicleFn.BOOST, com.sva.vehicle.VehicleFn.MODE,
                com.sva.vehicle.VehicleFn.DRILL, com.sva.vehicle.VehicleFn.JUMP, com.sva.vehicle.VehicleFn.TARGET, com.sva.vehicle.VehicleFn.ANCHOR,
                com.sva.vehicle.VehicleFn.CLOAK};
        Object[] n = new Object[f.length];
        for (int i = 0; i < f.length; i++) {
            n[i] = com.sva.client.VehicleKeyBindsSVA.name(f[i]);
        }
        return n;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getSubItems(Item item, CreativeTabs tab, List list) {
        for (VehicleType t : VehicleType.values()) {
            list.add(new ItemStack(item, 1, t.ordinal()));
            VehicleState full = new VehicleState(t);
            full.energy = full.maxEnergy();
            list.add(withState(new ItemStack(item, 1, t.ordinal()), full));
            if (t.gases.any()) {
                VehicleState gassed = new VehicleState(t);
                gassed.energy = gassed.maxEnergy();
                gassed.fillAllGases();
                list.add(withState(new ItemStack(item, 1, t.ordinal()), gassed));
            }
        }
    }

    @Override
    public boolean showDurabilityBar(ItemStack s) {
        return s.hasTagCompound();
    }

    @Override
    public double getDurabilityForDisplay(ItemStack s) {
        VehicleState st = stateOf(s);
        return 1 - shownEnergy(st) / Math.max(1, shownMaxEnergy(st));
    }

    // ------------------------------------------------------------------ ТБ-4: charged as an item

    public static boolean isVehicle(ItemStack s) {
        return s != null && s.stackSize == 1 && s.getItem() instanceof ItemVehicleSVA;
    }

    /** EU the item holds (in use: what tuning-off keeps aside isn't counted). */
    public static double chargeOf(ItemStack s) {
        return stateOf(s).energy;
    }

    public static double capacityOf(ItemStack s) {
        return stateOf(s).maxEnergy();
    }

    /** EU one charging call may move: the packet of the vehicle's energy block (its tier). */
    public static int transferLimit(ItemStack s) {
        return typeOf(s).tier.getVoltage();
    }

    public static int ic2Tier(ItemStack s) {
        return typeOf(s).tier.toIc2Tier();
    }

    /**
     * Adds up to `max` EU (server). Only the charge key of the saved state is rewritten - everything else in the NBT
     * stays as it is (a plain item gets its state written once first). @return EU taken
     */
    public static long charge(ItemStack s, double max, boolean simulate) {
        if (!isVehicle(s) || !(max > 0)) {
            return 0;
        }
        VehicleState st = stateOf(s);
        double room = st.maxEnergy() - st.energy;
        long moved = (long) Math.max(0, Math.min(Math.floor(room), Math.floor(max)));
        if (!simulate && moved > 0) {
            if (!s.hasTagCompound() || !s.getTagCompound().hasKey(EntityVehicleSVA.NBT_ROOT)) {
                withState(s, st);
            }
            NBTTagCompound root = s.getTagCompound().getCompoundTag(EntityVehicleSVA.NBT_ROOT);
            root.setDouble(VehicleState.K_ENERGY, st.energy + moved + st.energyHeld);   // the part kept aside stays
        }
        return moved;
    }

    // ---- IC2 (IElectricItem: plain methods, harmless without IC2; the manager only with it)

    @Override
    public boolean canProvideEnergy(ItemStack s) {
        return false;                                       // a vehicle is not a battery
    }

    @Override
    public Item getChargedItem(ItemStack s) {
        return this;
    }

    @Override
    public Item getEmptyItem(ItemStack s) {
        return this;
    }

    @Override
    public double getMaxCharge(ItemStack s) {
        return capacityOf(s);
    }

    @Override
    public int getTier(ItemStack s) {
        return ic2Tier(s);
    }

    @Override
    public double getTransferLimit(ItemStack s) {
        return transferLimit(s);
    }

    @Override
    @cpw.mods.fml.common.Optional.Method(modid = com.sc.Reference.IC2_MODID)
    public ic2.api.item.IElectricItemManager getManager(ItemStack s) {
        if (ic2Manager == null) {
            ic2Manager = new VehicleElectricManagerSVA();
        }
        return (ic2.api.item.IElectricItemManager) ic2Manager;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister r) {
        icons = new IIcon[VehicleType.values().length];
        for (VehicleType t : VehicleType.values()) {
            icons[t.ordinal()] = r.registerIcon(ReferenceSVA.ASSETS + ":vehicle_" + t.key);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconFromDamage(int meta) {
        return icons[VehicleType.byOrdinal(meta).ordinal()];
    }
}
