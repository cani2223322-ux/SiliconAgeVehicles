package com.sva.entity;

import com.sva.ConfigSVA;
import com.sva.vehicle.BatteryMath;
import com.sva.vehicle.FlightMath;
import com.sva.vehicle.FuelMath;
import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerGuide;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.LinerMcdu;
import com.sva.vehicle.NavDb;
import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.PlaneParams;
import com.sva.vehicle.RouteMath;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.SystemsMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Liner wave 1 (plan-next §7): the SA-320's server side next to EntityVehicleSVA (like SpaceLogicSVA for stage E) - one
 * tick of its systems (LinerSystems) in place of the D2 engine machine and the hydrogen wave's electrics, the cockpit's
 * control packets (validated: only the two pilots), the quick start, the L1 door. The vehicle's old words follow the
 * liner's state, so everything that reads them keeps working: the D2 engine state (RUN while either engine runs - the
 * HUD, the sounds, hasLift), the switch word (the lights, the INS), the board battery word (powered()).
 * <p>
 * Wave 2: the pilot's keys become the stick (A / D roll and pedals, Space / «Снижение» pitch) and the brake pedals (S at
 * idle on the ground) for the hydraulics / flight controls / gear (LinerHyd, LinerFctl, LinerGear); the ground's grip and
 * the height; the PTU's bark, the RAT, the gear and a melted fuse plug as sounds; «Гидрожидкость» on the aircraft (refill).
 */
public final class LinerLogicSVA {

    /** The asymmetric thrust's yaw (deg a tick at one engine full, the other dead): in the air / rolling on the ground. */
    public static final double ASYM_AIR = 0.35, ASYM_GROUND = 0.15;
    /** The parking brake's deceleration on the ground (b/t a tick). */
    public static final double PARK_BRAKE = 0.06;
    /** The red chime repeats this often while MASTER WARN is lit (the A320's continuous repetitive chime). */
    private static final int WARN_REPEAT = 10;
    /** The fuel imbalance's yaw (deg a tick at a full LinerFuel.ROLL_FULL imbalance, in the air): the heavy wing drops. */
    public static final double IMBAL_YAW = 0.08;

    private LinerLogicSVA() {
    }

    /**
     * Server, once a tick from systemsTick (before the physics): the environment, the systems' tick, their outputs - АКБ 1's
     * charge, the H2 burnt, the old engine / switch / battery words, the sounds. @return EU for the tick's cost (0: the
     * liner's energy is its batteries, handled here)
     */
    static double tick(EntityVehicleSVA v, boolean pilot, int in) {
        LinerSystems L = v.state.liner();
        if (L == null) {
            return 0;
        }
        LinerSystems.Env e = new LinerSystems.Env();
        e.bat1In = v.state.batIn;
        e.bat1 = v.state.batIn ? v.state.energy : 0;
        e.bat1Cap = v.state.maxEnergy();
        e.extAvail = v.extPowerNow();
        e.ground = !v.wasAirborneNow();
        e.ground = e.ground && !v.debugLinerAir;
        // wave 4: the thrust levers are the AFS' (detents, the reverse zone): a throttle set from outside (an older test, a
        // reload) moves them; the pilot's W / S step them; the old throttle follows them
        LinerAfs F = L.afs;
        if (Double.isNaN(v.linerThrSet) || Math.abs(v.throttle - v.linerThrSet) > 1e-9) {
            F.lever = Math.max(0, Math.min(1, v.throttle));
        }
        boolean keyUp = (in & EntityVehicleSVA.IN_FWD) != 0, keyDown = (in & EntityVehicleSVA.IN_BACK) != 0;
        boolean keyRev = (in & (EntityVehicleSVA.IN_DOWN | EntityVehicleSVA.IN_BOOST)) != 0;
        if (pilot) {
            F.leverKeys(keyUp, keyDown, keyRev && e.ground, e.ground);
        } else if (!e.ground) {
            F.rev = 0;
        }
        v.throttle = F.lever;
        v.linerThrSet = F.lever;
        e.throttle = v.throttle;
        e.airspeed = v.airspeedNow();
        e.random = v.rand().nextDouble();
        e.failChance = Math.max(0, Math.min(0.5, ConfigSVA.linerStartFailChance));
        e.quickAllowed = ConfigSVA.linerQuickStart;
        e.burn = Math.max(0, ConfigSVA.consumption);                // wave 5: the FMS' fuel predictions
        // wave 2: the stick, the pedals, the ground speed, the grip, the height, the leak chance
        boolean left = (in & EntityVehicleSVA.IN_LEFT) != 0, right = (in & EntityVehicleSVA.IN_RIGHT) != 0;
        boolean up = (in & EntityVehicleSVA.IN_BRAKE) != 0, down = (in & (EntityVehicleSVA.IN_DOWN | EntityVehicleSVA.IN_BOOST)) != 0;
        e.roll = e.yaw = (right ? 1F : 0F) - (left ? 1F : 0F);
        e.pitch = (up ? 1F : 0F) - (down ? 1F : 0F);
        e.pedal = e.ground && (in & EntityVehicleSVA.IN_BACK) != 0 && v.throttle <= 0 && F.rev <= 0;
        e.speed = Math.max(0, v.speed);
        e.mu = v.linerMu();
        e.height = v.debugLinerAir ? 100 : v.linerRadio();          // wave 4: the radio altimeter every tick
        e.leakChance = Math.max(0, ConfigSVA.linerHydLeakChance);
        // wave 3: the altitude (the gravity fuel feed's ceiling), the LDG MEMO's height
        e.alt = v.posY;
        e.gravAlt = ConfigSVA.linerFuelGravityAlt;
        e.ldgMemoH = ConfigSVA.linerLdgMemoHeight;
        // wave 6: the outside air at the aircraft (the biome's temperature, the cloud layer, rain / snow) for the icing; the
        // older world tests fly without it (their own weather must not ice them), the wave-6 one asks for it
        air6(v, e);
        // wave 7: the ADIRUs - moving (the alignment restarts), the alignment's ticks and its IRS INIT gate, the world tests'
        // instant alignment, GPS, the random IR / ADR faults (the failures config, per hour -> per tick)
        e.moving = Math.abs(v.speed) > SystemsMath.ALIGN_SPEED || v.wasAirborneNow();
        e.insTotal = SystemsMath.alignTicks(ConfigSVA.insAlignSeconds);
        e.irsGate = com.sva.vehicle.LinerFms.irsGate(ConfigSVA.linerIrsAutoInit, L.fms.irsDone);
        e.instantIns = !ConfigSVA.ins || EntityVehicleSVA.testInstantEngine && !v.debugInsManual;
        e.gps = ConfigSVA.linerGpsAlwaysOn;
        e.adirsFail = ConfigSVA.failures && !EntityVehicleSVA.testInstantEngine ? Math.max(0, ConfigSVA.linerAdirsFailChance) / 72000.0 : 0;
        boolean instant = EntityVehicleSVA.testInstantEngine && !v.debugEngineManual;
        if (instant && pilot) {
            // the older world tests fly every aircraft at once: the liner's batteries and masters on, the engines at idle
            L.set(LinerControls.BAT1, 1);
            L.set(LinerControls.BAT2, 1);
            L.set(LinerControls.ENG_MASTER1, 1);
            L.set(LinerControls.ENG_MASTER2, 1);
            L.set(LinerControls.PARK_BRK, 0);
            e.instant = true;
            e.instantHyd = !v.debugLinerAir;                        // wave 2's gear test wants the real timing
        }
        air(v, L, e, in);                                           // wave 4: the aircraft for the AFS
        // the tanks against the vehicle's H2 (a station / a cell / the column filled it)
        L.reconcile(h2(v), FuelMath.tank(VehicleType.LINER));
        int quickBefore = L.quick;
        L.tick(e);
        // АКБ 1: the vehicle's own battery (its charge is state.energy on hydrogen)
        if (v.state.batIn) {
            double before = v.state.energy;
            v.state.energy = Math.max(0, Math.min(v.state.maxEnergy(), v.state.energy - L.bat1Eu));
            v.batteryWear(before, v.state.energy);
        }
        // the H2 the engines and the APU burnt (the tanks already took it; the vehicle's gas follows)
        double burn = (L.burnEng + L.burnApu) * Math.max(0, ConfigSVA.consumption);
        if (burn > 0) {
            v.state.drainGasFraction(FuelMath.H2, burn);
        }
        v.linerFlow(burn);
        // the old words
        v.linerEngine(L.anyRunning());
        v.state.switches = L.switchWord();
        v.state.battery = L.powered() ? SystemsMath.BAT_MAX : 0;
        // the doors shut by themselves once it rolls fast or flies (wave 7: all four - LinerGroundSVA.doorsTick in serverUpdate)
        // sounds of this tick (wave 3, vanilla): MASTER WARN - the continuous repetitive chime until pressed (or EMER CANC);
        // MASTER CAUT - a single chime
        if ((L.events & LinerSystems.E_CHIME_WARN) != 0 || L.masterWarn && !L.ecam.silenced && v.ticksExisted % WARN_REPEAT == 0) {
            v.vanillaSound("note.pling", 0.7F, 1.6F);
            v.vanillaSound("note.pling", 0.5F, 1.25F);
        }
        if ((L.events & LinerSystems.E_CHIME_CAUT) != 0) {
            v.vanillaSound("note.harp", 0.8F, 0.9F);
        }
        if ((L.events & LinerSystems.E_APU_AVAIL) != 0) {
            v.oneShot("ding", 0.3F, 1.6F);
        }
        if ((L.events & LinerSystems.E_STARTER) != 0) {
            v.vanillaClick(0.5F, 1.3F);
        }
        if ((L.events & LinerSystems.E_LIGHT_OFF) != 0) {
            v.vanillaGhast(0.35F, 0.8F);
        }
        if ((L.events & LinerSystems.E_CLICK) != 0) {
            v.vanillaClick(0.3F, 1.5F);
        }
        // wave 6: a cabin sign changed - the cabin's chime (two soft notes); ice shed by the anti-ice - chunks fly off the
        // wings / the nacelles; the passengers cold for long - «замерзание» (config, off by default)
        if ((L.events & LinerSystems.E_SIGN) != 0 && ConfigSVA.linerSignChime) {
            v.vanillaSound("note.pling", 0.45F, 1.05F);
            v.vanillaSound("note.pling", 0.4F, 0.79F);
        }
        if ((L.events & LinerSystems.E_ICE_SHED) != 0) {
            shedIce(v);
        }
        if (ConfigSVA.linerColdEffect && L.ice.freezing() && v.ticksExisted % 40 == 0) {
            freeze(v);
        }
        // wave 2: the PTU's bark (single-engine taxi), the RAT dropping out, the gear's thump, a fuse plug blowing
        if ((L.events & LinerSystems.E_PTU) != 0 && ConfigSVA.linerPtuSound) {
            v.vanillaSound("mob.wolf.bark", 0.18F, 0.55F);
        }
        if ((L.events & LinerSystems.E_RAT) != 0) {
            v.vanillaSound("random.door_open", 0.7F, 0.6F);
            v.tellRidersKey("sva.liner.msg.ratOut");
        }
        if ((L.events & LinerSystems.E_GEAR) != 0) {
            v.oneShot("door", 0.4F, 0.6F);
        }
        if ((L.events & LinerSystems.E_FUSE) != 0) {
            v.vanillaSound("random.fizz", 0.8F, 0.7F);
            v.tellRidersKey("sva.liner.msg.fusePlug");
        }
        // wave 4: the AP's cavalry charge (a rising arpeggio, repeating), the callouts (the heli's radar-altimeter tones),
        // RETARD (two low notes), V1
        if (F.cavNote >= 0) {
            int k = F.cavNote;
            if (k == 0 || k == 3 || k == 6 || k == 9) {
                float[] pitch = {1.0F, 1.26F, 1.5F, 2.0F};
                v.vanillaSound("note.harp", 0.9F, pitch[k / 3]);
            }
        }
        if (F.soundCall != 0) {
            int c = F.soundCall;
            if (c == LinerAfs.CALL_RETARD) {
                v.vanillaSound("note.bass", 1.0F, 0.6F);
                v.vanillaSound("note.bass", 1.0F, 0.5F);
            } else if (c == LinerAfs.CALL_V1) {
                v.vanillaSound("note.pling", 0.6F, 1.0F);
            } else {
                v.vanillaSound("note.pling", 0.45F, (float) (0.8F + (100 - Math.min(100, c)) / 100.0));
            }
        }
        // wave 7: the stall warning's aural (crickets - ALTN / DIRECT), the cabin call (two high-low notes and the line to the
        // passengers), a fire (the riders told; the red chime is the ECAM's)
        if ((L.events & LinerSystems.E_STALL) != 0) {
            v.vanillaSound("mob.silverfish.say", 0.9F, 1.8F);
            v.vanillaSound("note.bd", 0.8F, 0.6F);
        }
        if ((L.events & LinerSystems.E_PA) != 0) {
            v.vanillaSound("note.pling", 0.6F, 1.5F);
            v.vanillaSound("note.pling", 0.5F, 1.12F);
            v.tellRidersKey("sva.liner.msg.paCall");
        }
        if ((L.events & LinerSystems.E_FIRE) != 0) {
            v.tellRidersKey("sva.liner.msg.fire");
        }
        if (L.adirs.alignedNow) {
            v.vanillaClick(0.2F, 2.0F);
        }
        if (quickBefore != LinerSystems.QUICK_OFF && L.quick == LinerSystems.QUICK_OFF) {
            v.tellRidersKey("done".equals(L.quickWhy) ? "sva.liner.quick.done" : "sva.liner.quick.stopped." + L.quickWhy);
        }
        // wave 5: the MCDUs' own messages, their screens and the ND's plan to the pilots when they change
        LinerMcdu.Env me = mcduEnv(v, L);
        L.mcdu.tick(L, me);
        if (v.ticksExisted % MCDU_EVERY == 0) {
            mcduPush(v, L, me, false);
        }
        return 0;
    }

    /**
     * Wave 6: the air at the aircraft for LinerIce - the outside temperature (the biome's at sea level falling with the
     * height), visible moisture (the cloud layer, rain / snow at the aircraft, a snowy biome's air high up), the true
     * airspeed (km/h - a probe freezes at it), the icing on (config; the world tests only for a liner that asks).
     */
    static void air6(EntityVehicleSVA v, LinerSystems.Env e) {
        int x = net.minecraft.util.MathHelper.floor_double(v.posX), y = net.minecraft.util.MathHelper.floor_double(v.posY),
                z = net.minecraft.util.MathHelper.floor_double(v.posZ);
        net.minecraft.world.biome.BiomeGenBase b = v.worldObj.getBiomeGenForCoords(x, z);
        double temp = b == null ? 0.8 : b.temperature;
        boolean snowy = b != null && b.getEnableSnow();
        boolean precip = v.worldObj.isRaining() && b != null && b.getIntRainfall() > 0;
        double ay = Double.isNaN(v.debugLinerAirY) ? v.posY : v.debugLinerAirY;
        if (!Double.isNaN(v.debugLinerOat)) {
            temp = v.debugLinerOat;                                // world test: a biome temperature of its choosing
        }
        e.iceAir.oat = com.sva.vehicle.LinerIce.oat(ay, temp);
        e.iceAir.moisture = com.sva.vehicle.LinerIce.moisture(ay, precip || v.debugLinerWet, snowy);
        e.iceAir.icing = ConfigSVA.linerIcing && (!EntityVehicleSVA.testInstantEngine || v.debugLinerIcing);
        e.kmh = Math.max(e.airspeed, 0) * 72;
    }

    /** Wave 6: ice shed by the anti-ice - snowball puffs off both wings' leading edges and the nacelles. */
    static void shedIce(EntityVehicleSVA v) {
        if (!(v.worldObj instanceof net.minecraft.world.WorldServer)) {
            return;
        }
        net.minecraft.world.WorldServer ws = (net.minecraft.world.WorldServer) v.worldObj;
        for (int s = -1; s <= 1; s += 2) {
            for (double[] p : new double[][] {{0.6, 4.0}, {-1.0, 7.0}, {1.7, 2.6}}) {
                double[] w = v.toWorld(p[0], s * p[1]);
                ws.func_147487_a("snowballpoof", v.posX + w[0], v.posY + 1.6, v.posZ + w[1], 6, 0.3, 0.1, 0.3, 0.05);
            }
        }
        v.vanillaSound("dig.glass", 0.4F, 1.6F);
    }

    /**
     * Wave 6 (config vehicles.linerColdEffect, off by default - the core has no body temperature): passengers in a cabin
     * cold for COLD_TICKS get a mild slowness (and the pilots too - the cockpit cools with it); a helmet keeps it off.
     */
    static void freeze(EntityVehicleSVA v) {
        for (Entity r : v.riders()) {
            if (r instanceof EntityPlayer && ((EntityPlayer) r).getCurrentArmor(3) == null) {
                ((EntityPlayer) r).addPotionEffect(new net.minecraft.potion.PotionEffect(net.minecraft.potion.Potion.moveSlowdown.getId(), 60, 0, true));
            }
        }
    }

    /** Wave 6: a passenger's reading light toggled (the Action packet; the pilots have none - they are told so). */
    static void reading(EntityVehicleSVA v, EntityPlayer p) {
        LinerSystems L = v.state.liner();
        if (L == null) {
            return;
        }
        Entity m = p.ridingEntity;
        int seat = m instanceof EntitySeatSVA && ((EntitySeatSVA) m).getParent() == v ? ((EntitySeatSVA) m).getIndex() : m == v ? 0 : -1;
        if (seat < 0) {
            return;
        }
        if (!L.lights.toggleReading(seat)) {
            v.msgTo(p, "sva.liner.msg.readingPilot");
            return;
        }
        v.vanillaClick(0.2F, 1.9F);
        v.syncNow();
    }

    /** The MCDU screens are checked for a change this often (ticks); a key sends at once. */
    public static final int MCDU_EVERY = 4;

    /** Wave 5: what the MCDUs know of the world now (the database, the aircraft, the INS, the fuel, the ILS near). */
    public static LinerMcdu.Env mcduEnv(final EntityVehicleSVA v, LinerSystems L) {
        LinerMcdu.Env e = new LinerMcdu.Env();
        final int dim = v.worldObj.provider.dimensionId;
        final com.sva.block.NavDataSVA nd = com.sva.block.NavDataSVA.get(v.worldObj);
        if (nd != null) {
            e.db = nd.db(v.worldObj, dim, v.basePoint());
            e.store = new LinerMcdu.Store() {
                @Override
                public String putWpt(NavDb.Wpt w) {
                    return nd.putWpt(w);
                }

                @Override
                public String putApt(NavDb.Airport a) {
                    return nd.putApt(a);
                }

                @Override
                public String delete(String ident, boolean airport) {
                    return nd.delete(ident, airport, dim);
                }
            };
        }
        e.dim = dim;
        e.x = v.posX;
        e.y = v.posY;
        e.z = v.posZ;
        e.hdg = LinerGuide.compass(v.rotationYaw);
        e.gs = Math.max(0, v.speed);
        e.ground = !v.wasAirborneNow();
        e.time = v.worldObj.getWorldTime();
        e.insStatus = v.insStatus();
        e.insSec = v.insSecondsLeft();
        e.hdgTrue = LinerGuide.compass(v.rotationYaw);              // wave 7: the IRS INIT page's ПРОСТР heading entry
        e.autoIrs = ConfigSVA.linerIrsAutoInit;
        e.fob = L.tanks();
        e.gw = L.lastAir != null ? L.lastAir.mass : LinerAfs.MASS0 + L.tanks() / 48000.0 * 2600 + v.ridersNow() * 80;
        e.riders = v.ridersNow();
        e.burn = Math.max(0, ConfigSVA.consumption);
        e.accDefault = (int) Math.round(ConfigSVA.linerAccelHeight);
        if (v.linerIls != null) {
            e.ils = v.linerIls;
        }
        return e;
    }

    /** The pilots aboard (the captain, the F/O) as players. */
    static java.util.List<EntityPlayerMP> pilots(EntityVehicleSVA v) {
        java.util.List<EntityPlayerMP> out = new java.util.ArrayList<EntityPlayerMP>();
        for (Entity r : v.riders()) {
            if (r instanceof EntityPlayerMP && pilot(v, (EntityPlayer) r)) {
                out.add((EntityPlayerMP) r);
            }
        }
        return out;
    }

    /**
     * Wave 5: the two MCDU screens and the ND's plan to the pilots - each when it changed since last sent (or to everyone
     * at once when `force` / a pilot boarded).
     */
    static void mcduPush(EntityVehicleSVA v, LinerSystems L, LinerMcdu.Env me, boolean force) {
        java.util.List<EntityPlayerMP> ps = pilots(v);
        int sig = 0;
        for (EntityPlayerMP p : ps) {
            sig = sig * 31 + p.getEntityId();
        }
        if (sig != v.mcduPilots) {
            force = true;
            v.mcduPilots = sig;
        }
        if (ps.isEmpty()) {
            return;
        }
        for (int k = 0; k < 3; k++) {
            String s = k < 2 ? L.mcdu.render(L, k, me).pack() : L.fms.hasPlan() || L.fms.tmp != null ? L.fms.ndText() : "";
            if (!force && s.equals(v.mcduSent[k])) {
                continue;
            }
            v.mcduSent[k] = s;
            for (EntityPlayerMP p : ps) {
                if (p.playerNetServerHandler != null) {
                    com.sva.net.NetSVA.CHANNEL.sendTo(new com.sva.net.NetSVA.McduData(v.getEntityId(), k, s), p);
                }
            }
        }
    }

    /** The McduKey packet (server): a pilot's MCDU key; others are told it is locked. */
    public static void mcduKey(EntityVehicleSVA v, EntityPlayerMP p, int side, int code) {
        if (v.state.liner() == null) {
            return;
        }
        if (!pilot(v, p)) {
            v.lockedMsgTo(p);
            return;
        }
        mcduKeyServer(v, side, code);
        if (code == LinerMcdu.K_OPEN) {
            v.mcduSent[0] = v.mcduSent[1] = v.mcduSent[2] = null;   // the screen opened: everything again
        }
        mcduPush(v, v.state.liner(), mcduEnv(v, v.state.liner()), false);
    }

    /** The MCDU's key on the server (the packet after its checks; the world test calls it directly). */
    public static void mcduKeyServer(EntityVehicleSVA v, int side, int code) {
        LinerSystems L = v.state.liner();
        if (L == null) {
            return;
        }
        L.mcdu.key(L, Math.max(0, Math.min(1, side)), code, mcduEnv(v, L));
    }

    /**
     * Wave 4: the aircraft the AFS sees this tick - position, compass heading, the measured vertical speed, the flight
     * parameters of the configuration (stall, lift-off, drag, thrust, climb / dive), the gross mass (the SD's GW), the INS,
     * a stick key held (the AP override), the route's guidance (NAV), the ILS stations near (the world data) and the configs.
     */
    static void air(EntityVehicleSVA v, LinerSystems L, LinerSystems.Env e, int in) {
        LinerAfs.Air a = e.air;
        VehicleType t = VehicleType.LINER;
        a.x = v.posX;
        a.y = v.posY;
        a.z = v.posZ;
        a.hdg = LinerGuide.compass(v.rotationYaw);
        double dy = Double.isNaN(v.linerLastY) ? 0 : v.posY - v.linerLastY;
        if (Math.abs(dy) > 4) {
            dy = 0;                                                 // a teleport
        }
        v.linerVy = v.linerVy * 0.5 + dy * 0.5;
        v.linerLastY = v.posY;
        a.vy = v.linerVy;
        PlaneParams p = v.flightParams(t);
        a.stall = p.stall;
        a.liftOff = p.liftOff;
        a.drag = p.drag;
        a.thrustK = p.thrust;
        a.top = t.topSpeed;
        double sp = Math.max(e.airspeed, e.speed);
        a.cap = PlaneMath.climbCapacity(p, sp) * FlightMath.ceilingFactor(v.posY);
        a.dive = p.dive;
        a.mass = LinerAfs.MASS0 + L.tanks() / 48000.0 * 2600 + v.ridersNow() * 80 + L.ice.mass();     // wave 6: + the ice
        a.insReady = v.insReady();
        a.stick = !e.ground && (in & (EntityVehicleSVA.IN_LEFT | EntityVehicleSVA.IN_RIGHT | EntityVehicleSVA.IN_BRAKE | EntityVehicleSVA.IN_DOWN
                | EntityVehicleSVA.IN_BOOST)) != 0;
        RouteMath.Plan plan = v.plan();
        a.hasRoute = plan != null && !plan.isEmpty() && !plan.done();
        a.routeHdg = Double.NaN;
        a.routeAlt = Double.NaN;
        if (L.fms.hasPlan()) {
            // wave 5: the FMS' plan is the route - its bridge keeps the vehicle's (NAV, the ND, the driver's client) and sequences
            double rate = t.turnRate * Math.max(0, Math.min(1, sp / Math.max(1e-6, p.stall)));
            com.sva.vehicle.LinerFms.Nav n = L.fms.nav(v.state.plan, v.posX, v.posZ, a.hdg, sp, rate);
            if (n.replaced) {
                v.setFmsRoute(n.plan);
            }
            a.hasRoute = !n.plan.isEmpty() && !n.plan.done();
            a.routeHdg = a.hasRoute && n.g != null ? n.g.heading : Double.NaN;
            if (n.g != null && n.g.switched) {
                v.syncNow();
            }
        } else if (a.hasRoute && (L.afs.lat == LinerAfs.L_NAV || (L.afs.latArm & LinerAfs.AL_NAV) != 0 || L.afs.hdgMan)) {
            RouteMath.Guide g = v.routeGuide();
            if (g != null) {
                a.routeHdg = g.heading;
                a.routeAlt = g.alt;
                if (g.switched) {
                    v.syncNow();
                }
            }
        }
        a.ilsRange = Math.max(500, ConfigSVA.linerIlsRange);
        a.accelH = ConfigSVA.linerAccelHeight;
        a.callouts = ConfigSVA.linerCallouts;
        if (v.ticksExisted - v.linerIlsAt >= 20 || v.ticksExisted < v.linerIlsAt || v.linerIls == null) {
            v.linerIlsAt = v.ticksExisted;
            com.sva.block.IlsDataSVA d = com.sva.block.IlsDataSVA.get(v.worldObj);
            v.linerIls = d == null ? new java.util.ArrayList<LinerIls.Station>() : d.near(v.worldObj.provider.dimensionId, v.posX, v.posZ, a.ilsRange * 1.2);
        }
        a.ils = v.linerIls;
    }

    /** The vehicle's H2 now (mB, the spent part below one mB taken off). */
    static double h2(EntityVehicleSVA v) {
        int g = v.state.gas(FuelMath.H2);
        return Math.max(0, g - v.state.gasFrac[FuelMath.H2.ordinal()]);
    }

    /** May this player work the cockpit's controls: the captain (the driver) or the first officer (seat 1). */
    public static boolean pilot(EntityVehicleSVA v, EntityPlayer p) {
        if (p == null) {
            return false;
        }
        Entity m = p.ridingEntity;
        boolean seat = m instanceof EntitySeatSVA && ((EntitySeatSVA) m).getParent() == v;
        return com.sva.vehicle.LinerView.pilotSeat(v.riddenByEntity == p, seat, seat ? ((EntitySeatSVA) m).getIndex() : -1);
    }

    /** The control packet (server): a pilot's click on a row of LinerControls. */
    public static void control(EntityVehicleSVA v, EntityPlayerMP p, int index, int action) {
        LinerSystems L = v.state.liner();
        if (L == null || LinerControls.get(index) == null) {
            return;
        }
        if (!pilot(v, p)) {
            v.lockedMsgTo(p);
            return;
        }
        if (LinerControls.get(index) == LinerControls.QUICK && !ConfigSVA.linerQuickStart) {
            v.msgTo(p, "sva.liner.quick.stopped.disabled");
            return;
        }
        boolean was = L.quick != LinerSystems.QUICK_OFF;
        LinerControls.Ctl c = LinerControls.get(index);
        if (c == LinerControls.PRIO_L || c == LinerControls.PRIO_R) {
            // wave 7: the sidestick priority - the presser's own side only; the flying pilot swaps (LinerGroundSVA.priority)
            if (action == LinerControls.A_PRIMARY && LinerGroundSVA.priority(v, p, c == LinerControls.PRIO_R)) {
                v.vanillaClick(0.3F, 1.8F);
            }
            return;
        }
        if (L.control(index, action)) {
            v.vanillaClick(0.3F, LinerControls.get(index).kind == LinerControls.PBM ? 1.8F : 1.5F);
            applyLevers(v, L);
            if (!was && L.quick != LinerSystems.QUICK_OFF) {
                v.msgTo(p, "sva.liner.quick.started");
            } else if (LinerControls.get(index) == LinerControls.QUICK && L.quick == LinerSystems.QUICK_OFF && L.quickWhy.length() > 0) {
                v.msgTo(p, "sva.liner.quick.stopped." + L.quickWhy);
            }
            v.syncNow();
        }
    }

    /**
     * The old high-lift state from the liner's: the flaps where the hydraulics have moved them (wave 2), the speed brake's
     * lever at 1/2 or ПОЛН with its spoilers powered.
     */
    static void applyLevers(EntityVehicleSVA v, LinerSystems L) {
        v.linerHighLift(L.fctl.flapSetting(), L.at(LinerControls.SPD_BRK) >= 2 && L.fctl.speedBrakeWorks());
    }

    /**
     * Wave 2: «Гидрожидкость» used on the liner (server) - all three reservoirs full, the leaks stopped; one item used
     * (creative: free). Not a liner / nothing to fill: a line in the chat.
     */
    static void refill(EntityVehicleSVA v, EntityPlayer p, net.minecraft.item.ItemStack held) {
        LinerSystems L = v.state.liner();
        if (L == null) {
            v.msgTo(p, "sva.liner.msg.fluid.notLiner");
            return;
        }
        if (!L.hyd.needsFluid()) {
            v.msgTo(p, "sva.liner.msg.fluid.full");
            return;
        }
        L.hyd.refill();
        if (!p.capabilities.isCreativeMode) {
            held.stackSize--;
            if (held.stackSize <= 0) {
                p.inventory.setInventorySlotContents(p.inventory.currentItem, null);
            }
        }
        v.vanillaSound("liquid.water", 0.5F, 1.4F);
        v.msgTo(p, "sva.liner.msg.fluid.done");
        v.syncNow();
    }

    /** «Быстрый запуск» from the menu / the key (the pilot). */
    static void quick(EntityVehicleSVA v, EntityPlayer p) {
        LinerSystems L = v.state.liner();
        if (L == null) {
            return;
        }
        if (!ConfigSVA.linerQuickStart) {
            v.msgTo(p, "sva.liner.quick.stopped.disabled");
            return;
        }
        if (L.quick != LinerSystems.QUICK_OFF) {
            L.stopQuick("pilot");
            v.msgTo(p, "sva.liner.quick.stopped.pilot");
        } else if (L.startQuick()) {
            v.msgTo(p, "sva.liner.quick.started");
        } else {
            v.msgTo(p, "sva.liner.quick.stopped." + L.quickWhy);
        }
        v.syncNow();
    }

    /** The L1 door from the menu (open / shut; not in the air). */
    static void door(EntityVehicleSVA v, EntityPlayer p) {
        LinerSystems L = v.state.liner();
        if (L == null) {
            return;
        }
        if (!L.door && v.wasAirborneNow()) {
            v.msgTo(p, "sva.liner.door.air");
            return;
        }
        L.door = !L.door;
        v.oneShot("door", 0.6F, L.door ? 1.0F : 0.8F);
        v.syncNow();
    }

    /** АКБ 2 as an item (its charge, health...), null with none in. */
    static net.minecraft.item.ItemStack battery2Stack(EntityVehicleSVA v) {
        LinerSystems L = v.state.liner();
        if (L == null || !L.bat2In || com.sva.init.ModContentSVA.battery == null) {
            return null;
        }
        BatteryMath.Battery b = new BatteryMath.Battery();
        b.charge = L.bat2.charge;
        b.health = L.bat2.health;
        b.limit = L.bat2.limit;
        b.services = L.bat2.services;
        b.cycle = L.bat2.cycle;
        b.deep = L.bat2.deep;
        return com.sva.item.ItemBatterySVA.stackOf(com.sva.init.ModContentSVA.battery, VehicleType.LINER, b);
    }

    /** Takes АКБ 2 out (null: none in). */
    static net.minecraft.item.ItemStack takeBattery2(EntityVehicleSVA v) {
        net.minecraft.item.ItemStack s = battery2Stack(v);
        if (s != null) {
            LinerSystems L = v.state.liner();
            L.bat2In = false;
            L.bat2.charge = 0;
            v.syncNow();
        }
        return s;
    }

    /** Puts a liner battery into АКБ 2 (false: not a liner battery, one is in). */
    static boolean putBattery2(EntityVehicleSVA v, net.minecraft.item.ItemStack s) {
        LinerSystems L = v.state.liner();
        if (L == null || L.bat2In || !com.sva.item.ItemBatterySVA.fits(s, VehicleType.LINER)) {
            return false;
        }
        BatteryMath.Battery b = com.sva.item.ItemBatterySVA.batteryOf(s);
        L.bat2In = true;
        L.bat2.charge = b.charge;
        L.bat2.health = b.health;
        L.bat2.limit = b.limit;
        L.bat2.services = b.services;
        L.bat2.cycle = b.cycle;
        L.bat2.deep = b.deep;
        v.syncNow();
        return true;
    }

    /** Where a rider gets off: at the foot of the L1 airstairs (model x 5.30, 2.9 left of the centre line), else null. */
    static double[] doorSpot(EntityVehicleSVA v) {
        double[] w = v.toWorld(DOOR_X, -2.9);
        return new double[] {v.posX + w[0], v.posY, v.posZ + w[1]};
    }

    /** The L1 door's centre (model x, liner3d.py moved aft for the cabin: x 5.00..5.60) and how near a boarding player must stand. */
    public static final double DOOR_X = 5.30, BOARD_RANGE = 4.5;

    /** Is p standing by the L1 door (its side, within BOARD_RANGE of the stairs' foot or the door)? */
    static boolean byDoor(EntityVehicleSVA v, EntityPlayer p) {
        double[] f = doorSpot(v);
        double[] d = v.toWorld(DOOR_X, -1.2);
        double dx = p.posX - f[0], dz = p.posZ - f[2];
        double ex = p.posX - (v.posX + d[0]), ez = p.posZ - (v.posZ + d[1]);
        return dx * dx + dz * dz < BOARD_RANGE * BOARD_RANGE || ex * ex + ez * ez < BOARD_RANGE * BOARD_RANGE;
    }
}
