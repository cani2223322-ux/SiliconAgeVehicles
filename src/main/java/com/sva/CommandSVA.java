package com.sva;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.sva.vehicle.MapMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleType;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.Teleporter;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * /sva vehicles list|tp|forget|forget-all - the operators' look into the vehicle registry (VehicleRegistrySVA). An entry
 * leaves the registry only when its vehicle is picked up or destroyed; a vehicle lost otherwise (a chunk rollback, a
 * world edit, a crash) keeps its entry: it stays on «Моя техника» and counts against vehicles.maxPerPlayer. The
 * operator lists a player's entries, flies to one to look and forgets the ones that are gone. A forgotten vehicle that
 * still exists writes its entry again the next time it is loaded (EntityVehicleSVA.updateRegistry) - nothing is lost.
 * Ids are the vehicles' UUIDs; any unique prefix works (the list shows the first ID_SHOWN characters).
 */
public class CommandSVA extends CommandBase {

    /** Characters of the id the list shows (and that are enough in practice). */
    static final int ID_SHOWN = 8;

    @Override
    public String getCommandName() {
        return "sva";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/sva vehicles list <player> | tp <id> | forget <id> | forget-all <player>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length != 3 || !"vehicles".equals(args[0])) {
            sender.addChatMessage(new ChatComponentTranslation("sva.cmd.usage"));
            return;
        }
        VehicleRegistrySVA reg = registry();
        if (reg == null) {
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if ("list".equals(sub)) {
            list(sender, reg, args[2]);
        } else if ("tp".equals(sub)) {
            VehicleRegistrySVA.Entry e = byId(sender, reg, args[2]);
            if (e != null) {
                tp(sender, e);
            }
        } else if ("forget".equals(sub)) {
            VehicleRegistrySVA.Entry e = byId(sender, reg, args[2]);
            if (e != null) {
                reg.remove(e.id);
                sender.addChatMessage(new ChatComponentTranslation("sva.cmd.forget.done", shortId(e.id)));
            }
        } else if ("forget-all".equals(sub)) {
            String owner = ownerOf(reg, args[2]);
            if (owner == null) {
                sender.addChatMessage(new ChatComponentTranslation("sva.cmd.noPlayer", args[2]));
                return;
            }
            sender.addChatMessage(new ChatComponentTranslation("sva.cmd.forgetAll.done", args[2], Integer.valueOf(reg.removeOwner(owner))));
        } else {
            sender.addChatMessage(new ChatComponentTranslation("sva.cmd.usage"));
        }
    }

    private static void list(ICommandSender sender, VehicleRegistrySVA reg, String player) {
        String owner = ownerOf(reg, player);
        if (owner == null) {
            sender.addChatMessage(new ChatComponentTranslation("sva.cmd.noPlayer", player));
            return;
        }
        List<VehicleRegistrySVA.Entry> rows = new ArrayList<VehicleRegistrySVA.Entry>();
        for (VehicleRegistrySVA.Entry e : reg.all()) {
            if (e.owner.equals(owner)) {
                rows.add(e);
            }
        }
        if (rows.isEmpty()) {
            sender.addChatMessage(new ChatComponentTranslation("sva.cmd.list.none", player));
            return;
        }
        sender.addChatMessage(new ChatComponentTranslation("sva.cmd.list.head", player, Integer.valueOf(rows.size())));
        long now = MinecraftServer.getServer().worldServerForDimension(0).getTotalWorldTime();
        for (VehicleRegistrySVA.Entry e : rows) {
            IChatComponent type = new ChatComponentTranslation("item.siliconagevehicles.vehicle." + VehicleType.byOrdinal(e.type).key + ".name");
            IChatComponent row = e.placed
                    ? new ChatComponentTranslation("sva.cmd.list.row", shortId(e.id), type, Integer.valueOf(e.board),
                            Integer.valueOf((int) Math.floor(e.x)), Integer.valueOf((int) Math.floor(e.y)), Integer.valueOf((int) Math.floor(e.z)),
                            Integer.valueOf(e.dim), age(MapMath.ageSeconds(now, e.seen)))
                    : new ChatComponentTranslation("sva.cmd.list.rowUnknown", shortId(e.id), type, Integer.valueOf(e.board));
            // a click puts "/sva vehicles tp <id>" into the chat line
            row.getChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/sva vehicles tp " + e.id));
            sender.addChatMessage(row);
        }
    }

    /** Moves the operator next to the entry's last known place (other dimensions too, without a portal). */
    private static void tp(ICommandSender sender, VehicleRegistrySVA.Entry e) {
        if (!(sender instanceof EntityPlayerMP)) {
            sender.addChatMessage(new ChatComponentTranslation("sva.cmd.tp.console"));
            return;
        }
        if (!e.placed) {
            sender.addChatMessage(new ChatComponentTranslation("sva.cmd.tp.unknown", shortId(e.id)));
            return;
        }
        EntityPlayerMP p = (EntityPlayerMP) sender;
        MinecraftServer s = MinecraftServer.getServer();
        if (p.ridingEntity != null) {
            p.mountEntity((Entity) null);
        }
        if (p.dimension != e.dim) {
            WorldServer to = net.minecraftforge.common.DimensionManager.isDimensionRegistered(e.dim) ? s.worldServerForDimension(e.dim) : null;
            if (to == null) {
                sender.addChatMessage(new ChatComponentTranslation("sva.cmd.tp.noDim", Integer.valueOf(e.dim)));
                return;
            }
            s.getConfigurationManager().transferPlayerToDimension(p, e.dim, new Stay(to));
        }
        // over the vehicle (its collision box is solid): the operator lands on its roof or next to it
        double up = VehicleType.byOrdinal(e.type).height + 0.5;
        p.setPositionAndUpdate(e.x, e.y + up, e.z);
        p.fallDistance = 0;
        sender.addChatMessage(new ChatComponentTranslation("sva.cmd.tp.done", shortId(e.id), Integer.valueOf((int) Math.floor(e.x)),
                Integer.valueOf((int) Math.floor(e.y)), Integer.valueOf((int) Math.floor(e.z))));
    }

    /** A dimension change that neither builds nor looks for a portal (the player keeps the position set afterwards). */
    static final class Stay extends Teleporter {
        Stay(WorldServer w) {
            super(w);
        }

        @Override
        public void placeInPortal(Entity e, double x, double y, double z, float yaw) {
        }

        @Override
        public boolean placeInExistingPortal(Entity e, double x, double y, double z, float yaw) {
            return true;
        }

        @Override
        public boolean makePortal(Entity e) {
            return true;
        }

        @Override
        public void removeStalePortalLocations(long time) {
        }
    }

    /** The entry whose id starts with `prefix` (case-insensitive); null and a chat line when none or several. */
    private static VehicleRegistrySVA.Entry byId(ICommandSender sender, VehicleRegistrySVA reg, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        VehicleRegistrySVA.Entry found = null;
        for (VehicleRegistrySVA.Entry e : reg.all()) {
            String id = e.id.toLowerCase(Locale.ROOT);
            if (id.equals(p)) {
                return e;
            }
            if (id.startsWith(p)) {
                if (found != null) {
                    sender.addChatMessage(new ChatComponentTranslation("sva.cmd.ambiguous", prefix));
                    return null;
                }
                found = e;
            }
        }
        if (found == null) {
            sender.addChatMessage(new ChatComponentTranslation("sva.cmd.noId", prefix));
        }
        return found;
    }

    /**
     * The owner UUID (as the registry keeps it) of `player`: an online player's, else the one of an entry with that
     * owner name (case-insensitive), else `player` itself when some entry has it as its owner UUID; null - unknown.
     */
    static String ownerOf(VehicleRegistrySVA reg, String player) {
        EntityPlayerMP online = MinecraftServer.getServer().getConfigurationManager().func_152612_a(player);
        if (online != null) {
            return online.getUniqueID().toString();
        }
        for (VehicleRegistrySVA.Entry e : reg.all()) {
            if (e.ownerName.equalsIgnoreCase(player)) {
                return e.owner;
            }
        }
        for (VehicleRegistrySVA.Entry e : reg.all()) {
            if (e.owner.equalsIgnoreCase(player)) {
                return e.owner;
            }
        }
        return null;
    }

    private static VehicleRegistrySVA registry() {
        World w = MinecraftServer.getServer() == null ? null : MinecraftServer.getServer().worldServerForDimension(0);
        return VehicleRegistrySVA.get(w);
    }

    static String shortId(String id) {
        return id.length() > ID_SHOWN ? id.substring(0, ID_SHOWN) : id;
    }

    /** "5 мин" / "2 ч" / "40 с" - the «Моя техника» words. */
    private static IChatComponent age(int sec) {
        return sec >= 3600 ? new ChatComponentTranslation("sva.map.ageH", Integer.valueOf(sec / 3600))
                : sec >= 60 ? new ChatComponentTranslation("sva.map.ageM", Integer.valueOf(sec / 60))
                : new ChatComponentTranslation("sva.map.ageS", Integer.valueOf(sec));
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "vehicles");
        }
        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, "list", "tp", "forget", "forget-all");
        }
        if (args.length == 3 && ("list".equals(args[1]) || "forget-all".equals(args[1]))) {
            return getListOfStringsMatchingLastWord(args, MinecraftServer.getServer().getAllUsernames());
        }
        return null;
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return false;
    }
}
