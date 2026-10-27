package com.sva;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/** config/SiliconAgeVehicles.cfg. Every number is NaN / infinity safe (Forge's clamp lets NaN through). */
public final class ConfigSVA {

    /** Hull wear: attacks and wall hits damage vehicles; at 0 HP a vehicle is wrecked. */
    public static boolean wear = true;
    /** Vehicles one player may have placed at once; 0 = unlimited. */
    public static int maxPerPlayer = 0;
    /** Charging station rate = input voltage x this. */
    public static float chargerRate = 1F;
    /** Vehicles' EU use x this. */
    public static float consumption = 1F;
    /**
     * Client: the first-person view from a closed vehicle shows the world through the whole hull (drawn see-through)
     * instead of only through the cockpit windows. Off by default.
     */
    public static boolean seeThroughHull = false;
    /** Client: material textures on the vehicle models (pixel detail, panel seams, rivets); off = flat colours. */
    public static boolean textures = true;
    /** Client: liveries by tier (stripe, hazard band, glowing lines) and the side decal (SA emblem, board number, charge hatch). */
    public static boolean liveries = true;
    /**
     * Client (aircraft stage B, ★13): weathering that grows with hull wear - exhaust soot, belly oil, chipped paint at the
     * doors / leading edges, mud on the wheels and the lower body; repairing in the garage cleans it. Off = always clean.
     */
    public static boolean weathering = true;
    /**
     * Client (★14): service markings - the aircraft's anti-glare panel, walkways and «НЕ НАСТУПАТЬ», rescue / propeller /
     * jacking stencils, registration, the fin's number and emblem; the drill's «ОПАСНО: БУР». Off = none.
     */
    public static boolean markings = true;
    /** Client (★14): the cockpit dials / screens / warning lamps show the vehicle's real readings. On by default. */
    public static boolean liveInstruments = true;
    /** Client (★16): vehicle and trailer items drawn as small 3D models (false: the flat 16x16 icons). On by default. */
    public static boolean icons3d = true;
    /** Client (★6): headlight / landing-light beams at night and brighter brake lights. On by default. */
    public static boolean headlights = true;
    /** Client (★6): headlights light up the blocks ahead at night (client-side block light, restored when they move on). */
    public static boolean dynamicLight = true;
    /** Client (★6): dynamic light for this many other vehicles besides your own (0 = only your own). */
    public static int dynamicLightVehicles = 4;
    /** Client (★6): other vehicles light the blocks only within this many blocks. */
    public static int dynamicLightRange = 32;
    /** Client (★6): the dynamic light is updated every this many ticks. */
    public static int dynamicLightPeriod = 4;
    /** Client (★7): navigation lights - red / green / strobe / beacon on aircraft, amber beacons and markers on work vehicles. */
    public static boolean navLights = true;
    /** Client (★10): animated parts - wheels, tracks, steering, control surfaces, landing gear, rotor tilt, hover lean. */
    public static boolean animations = true;
    /** Client (★7): the cabin trim follows the vehicle's tier - seats, floor, inserts, roof lining (SkinMath.trimTile). */
    public static boolean interiorTrim = true;
    /** Client (cockpit stage 2, ★8): at night instruments and lamps glow in the tier's colour, plafonds and the floor strip light up. */
    public static boolean nightLighting = true;
    /** Client (★8): the night glow's brightness 0..1. */
    public static float cabinLightLevel = 0.8F;
    /** Client (★11): the boarding sequence - lamp test, SA logo on the screens, needle sweep. */
    public static boolean cockpitStartup = true;
    /** Client (★11): cockpit warning sounds for the vehicle's own riders - overheating beep, stall siren. */
    public static boolean cockpitSounds = true;
    /** Client (wave 1, ★1): engine / rotor / turbine loops and the vehicles' own one-shot sounds. On by default. */
    public static boolean sounds = true;
    /** Client (★1): the vehicle loops' volume x this (0..2; the game's «Игроки» slider applies too). */
    public static float soundVolume = 1F;
    /** Client (★3): the third-person camera while riding - distance by the vehicle's size, higher, smooth follow. */
    public static boolean vehicleCamera = true;
    /** Client (★3): that camera's distance x this (0.25..4; the mouse wheel zooms 0.5x..2x on top). */
    public static float cameraDistance = 1F;
    /** ★4: cruise control for ground vehicles and the hoverbike (key «Круиз»). */
    public static boolean cruise = true;
    /** ★4: the aircraft autopilot (plane, jet, tiltrotor in airplane mode) - holds heading and height. */
    public static boolean autopilot = true;
    /** Wave 2 (★8): installed tuning upgrades change the vehicle (speed, buffer, armour...); off = upgrades do nothing. */
    public static boolean tuning = true;
    /** Client (★8): installed upgrades are drawn on the model (armour plates, light bar, battery box, extra tank, big wheels). */
    public static boolean tuningVisuals = true;
    /** Client (★7): the garage's hologram ring over a vehicle being repaired. */
    public static boolean garageHologram = true;
    /** Wave 2 (★10): access rights and lock modes (key cards); off = the old rules (owner drives, anyone rides). */
    public static boolean locks = true;
    /** ★10: server operators pass any vehicle lock. */
    public static boolean lockOpBypass = true;
    /** Who may dent a vehicle (hand, arrow, his explosion): "all", "pvp" (the owner; others only with the server's PvP on), "owner", "none" (EntityVehicleSVA.playerDamageAllowed). */
    public static String playerDamage = "pvp";
    /** Operators in creative manage other players' vehicles: pick up, cargo screen, access screen; and other players' charging station settings. */
    public static boolean opManage = true;
    /** Client: write key presses / the vehicle menu opening to the log (diagnostics for «the menu does not open»). Off by default. */
    public static boolean debugKeys = false;
    /**
     * Wave 3 (★15): the new trailer kinds (tanker, car platform, mobile charger) may be hitched; false = only the cargo
     * trailer (the others refuse - one already hitched keeps working).
     */
    public static boolean trailerKinds = true;
    /** Wave 3 (★19): «Моя техника» - the vehicle map (key, the station's button) and the server's vehicle cards. */
    public static boolean vehicleMap = true;
    /** The Singular Ship's own portal «Прыжок» (SpaceLogicSVA.startJump); off = the key answers "отключено на сервере". */
    public static boolean singularJump = true;
    /** The shuttle's and the ship's docking with an open core bridge vortex (SpaceLogicSVA.tryDock). */
    public static boolean bridgeDock = true;
    /** The drill vehicle's bore digs (DrillLogicSVA); off = it still drives and carries, the bore stays off. */
    public static boolean drill = true;
    /** The Singular Ship's invisibility for mobs (SpaceLogicSVA.cloaked, EventsSVA). */
    public static boolean mobCloak = true;
    /** Wave 4 (★15): regenerative braking / energy recovery (RegenMath) and its efficiency 0..0.5. */
    public static boolean regen = true;
    public static float regenEfficiency = 0.2F;
    /**
     * Aircraft stage A (HighLiftMath): flaps, automatic slats, spoilers / airbrake on the light plane, the jet and the
     * tiltrotor (flaperons); off = everything stays retracted (the old flight) and the keys answer "отключено".
     */
    public static boolean highLift = true;
    /**
     * Client (aircraft stage C): the avionics drawn in the cockpit - the light plane's glare-shield autopilot panel, HSI,
     * flap position / trim indicators and the flap gate's marks, the jet's HSI screen and the AP / annunciator lines on the
     * jet's and tiltrotor's text screens; off = those faces stay dark (the autopilot modes still work: «Панель автопилота»).
     */
    public static boolean avionics = true;
    /**
     * Client (aircraft round 2, stage D1): the runway's painted threshold marks («зебра», the designator) and - on strips
     * with lit edges - the centre-line and threshold lights (client.RunwayLightsSVA); off = plain runway blocks as before.
     */
    public static boolean airfield = true;
    /**
     * Client (stage D1, ★14): the light plane's retractable landing lights under the wing - they swing out with the flaps
     * or low on the approach and shine at night; off = they stay retracted.
     */
    public static boolean landingLamps = true;
    /**
     * Server (aircraft round 2, stage D2, ★6): the light plane's, the jet's and the tiltrotor's engine has to be started
     * (АКБ on, «ЗАПУСК» or pushing forward - the starter, the catch, the warm-up); off = the motor is ready whenever
     * powered, as before (the overhead switches still work).
     */
    public static boolean engineStart = true;
    /**
     * Server (stage D2, ★7): worn aircraft (over 60 % wear) may suffer failures in flight - engine power loss, a jammed
     * flap, a dead instrument; the garage's repair clears them. Off = no failures (and none at a start).
     */
    public static boolean failures = true;
    /**
     * Server (stage D5): the aircraft's (and the helicopter's) inertial navigation - the «ИНС» switch and its alignment
     * (SystemsMath.Ins): until aligned the PFD has no attitude / heading, the MFD no map, the autopilot can't engage.
     * Off = always aligned, the switch does nothing.
     */
    public static boolean ins = true;
    /** Server (stage D5): the INS alignment in seconds standing still with the board powered (0 = instant). */
    public static int insAlignSeconds = 10;
    /**
     * Liner wave 7: GPS always on (the user's decision: the IRs' drift is corrected, GPS PRIMARY on the ND / PROG) - kept as
     * a hidden option, no GPS-off control in the cockpit; the IR / ADR random faults' chance per flight hour (behind
     * vehicles.failures, rare); towing a liner by a tug; the passengers fastened by the seat-belt sign.
     */
    public static boolean linerGpsAlwaysOn = true, linerTow = true, linerSeatBelts = true;
    public static double linerAdirsFailChance = 0.02;
    /**
     * Client (aircraft round 2, stage D3, ★12): the jet's and the tiltrotor's gear doors open before the legs move and shut
     * after they are in (GearMath.sequence); off = the doors follow the legs at once.
     */
    public static boolean gearDoors = true;
    /**
     * Client (stage D3, ★12): the oleo struts compress on touchdown and on bumps (jet, tiltrotor; the light plane's spring
     * legs flex) and the airframe settles on them; off = the gear stays at its parking stroke.
     */
    public static boolean gearStruts = true;
    /** Client (stage D3, ★13): the jet nozzle's petals follow the throttle; the afterburner flame with shock diamonds. */
    public static boolean jetNozzle = true;
    /** Client (stage D3, ★18): the jet's head-up display on the canopy glass (first-person pilot view). */
    public static boolean jetHud = true;
    /**
     * Client (cockpit wave, plan-next §5 / §6): the light plane's and the helicopter's C+ glass - the PFD, the MFD and its
     * pages, the lamp strip, the transponder, the radar-warning scope, the heli's tach / power / hover / radar altimeter
     * faces (off: the faces stay dark; the dials, the AP bar and the switch panels as before).
     */
    public static boolean glassCockpit = true;
    /**
     * 2K / 4K wave (client): sharp cockpit text and dial faces - the anti-aliased glyph atlas and the baked dial faces
     * (client/HiResSVA); the quality AUTO / 1K / 2K / 4K (HiResMath.QUALITY_KEYS; AUTO from the window's height).
     */
    public static boolean cockpitHiRes = true;
    public static String cockpitQuality = "AUTO";
    /**
     * Liner wave 1 (plan-next §7): «Быстрый запуск» - the menu row and the guarded overhead switch walk the start-up by
     * themselves in ~40 s (off: servers «только вручную»).
     */
    public static boolean linerQuickStart = true;
    /** Liner wave 1: the chance of a random hot / of a hung engine start (each; 0 = only low air pressure causes them). */
    public static double linerStartFailChance = 0.02;
    /**
     * Liner wave 1 (client): the cockpit / cabin texture atlas - AUTO (by cockpitQuality: 1K / 2K / 4K -> 1024 / 2048 / 4096
     * px), LOW (512), 1K, 2K, 4K (LinerTexMath).
     */
    public static String linerTextures = "AUTO";
    /** Liner wave 1 (client): the exterior's levels of detail by distance (off: always the full model). */
    public static boolean linerLod = true;
    /** Liner wave 1 (client): the passenger cabin drawn (inside it or near the open door); off - never. */
    public static boolean linerCabin = true;
    /** Liner wave 2: the chance of a hydraulic leak per hour of pressurised running (0 = never). */
    public static double linerHydLeakChance = 0.03;
    /** Liner wave 2: the PTU's «bark» (single-engine taxi) as a sound. */
    public static boolean linerPtuSound = true;
    /** Liner wave 2 (client): hot brakes glow red on the model. */
    public static boolean linerBrakeGlow = true;
    /**
     * Liner wave 3: the gravity fuel feed's ceiling (absolute Y): a side with its pumps off feeds its engine by gravity under
     * it; above it the engine starves (flames out) over 60 % thrust.
     */
    public static double linerFuelGravityAlt = 160;
    /** Liner wave 3: the LDG MEMO / the ECAM's approach phase - the gear lever down under this height over the ground (blocks). */
    public static double linerLdgMemoHeight = 60;
    /**
     * Liner wave 4: the acceleration / thrust reduction height (blocks over the runway): SRS -> CLB, «LVR CLB» (the A320's
     * 1500 ft, scaled).
     */
    public static double linerAccelHeight = 30;
    /** Liner wave 4: how far (blocks) an airliner hears a «Курсо-глиссадный маяк» (the auto-tuning's range). */
    public static double linerIlsRange = 4000;
    /** Liner wave 4: the height callouts (100, 50, 40, 30, 20, 10), RETARD and V1 as sounds. */
    public static boolean linerCallouts = true;
    /**
     * Liner wave 5: the IRS INIT is confirmed by itself (the INS finishes its alignment without the MCDU's «ВЫСТАВИТЬ»);
     * off - the alignment waits for it (the amber memo, the MCDU's message).
     */
    public static boolean linerIrsAutoInit = true;
    /** Liner wave 5 (client): the MCDU's screen drawn live on the 3D cockpit's two MCDUs. */
    public static boolean linerMcdu3d = true;
    /** Liner wave 6: ice builds up in cold cloud / precipitation (the wing, the engines, the probes, the windscreen). */
    public static boolean linerIcing = true;
    /** Liner wave 6: a long stay in a cold cabin slows the riders («замерзание»; the core has no body temperature). Off. */
    public static boolean linerColdEffect = false;
    /** Liner wave 6: the cabin's chime when a sign (seat belts / no smoking) changes. */
    public static boolean linerSignChime = true;
    /** Liner wave 6 (client): the exterior lights' halos and beams (landing, taxi / take-off, turn-off, wing, logo). */
    public static boolean linerExtLights = true;
    /** Liner wave 6 (client): the light pools on the ground under the beams. */
    public static boolean linerGroundPools = true;
    /** Liner wave 6 (client): the landing / taxi lights really light the blocks (vehicles.dynamicLight's mechanism). */
    public static boolean linerDynamicLight = true;
    /** Liner wave 6 (client): the ice on the model, the frost on the windscreen. */
    public static boolean linerIceVisuals = true;
    /** Liner wave 6 (client): the cockpit's / cabin's light levels (dome, flood, cabin steps, night blue, reading lights). */
    public static boolean linerCabinLighting = true;
    /** Cockpit view (client, plan-next 8 item 1): no first-person hand / held item in the liner's pilot seats. */
    public static boolean linerHideHand = true;
    /** Cockpit view (client, item 4): the pilots' quick views («Вид кабины»: PFD / ND, ECAM, overhead, pedestal, windows). */
    public static boolean linerQuickViews = true;
    /** Client (cockpit wave): the helicopter's radar altimeter calls «50.. 40.. 30..» on the descent. */
    public static boolean radarAltCallouts = true;
    /** How the callouts come: "sound" (a tone each), "flash" (the number flashed on the HUD, no chat), "both". */
    public static String radarAltCalloutStyle = "both";
    /** Fast charging (2026-10-10): the station's «Форсаж» mode. */
    public static boolean stationBoost = true;
    /** A core (or IC2) energy storage touching the station feeds the vehicle directly. */
    public static boolean stationStorageLink = true;
    /** Several stations on one platform charge the same vehicle together (false: only one of them). */
    public static boolean stationsAddUp = true;
    /**
     * Server (hydrogen wave, plan-next §2-§3): HYDROGEN - the buggy, hoverbike, crawler, drill vehicle, helicopter, light
     * plane, jet and tiltrotor burn hydrogen (FuelMath), their electrics run on a removable battery (BatteryMath); EU - the
     * old behaviour (driving spends the EU buffer, the battery item does nothing).
     */
    public static String fuelMode = com.sva.vehicle.FuelMath.MODE_HYDROGEN;
    /** fuelMode is HYDROGEN (kept beside it: asked every tick). */
    public static boolean hydrogen = true;
    /** Server: the battery's health wears with charge cycles and deep discharges (false: it stays as it is). */
    public static boolean batteryWear = true;
    /** Cargo screen «Мусор»: items thrown out next to the vehicle ("modid:name" or "modid:name@meta"). */
    public static final String[] JUNK_DEFAULT = {"minecraft:cobblestone", "minecraft:dirt", "minecraft:gravel", "minecraft:netherrack",
            "minecraft:stone"};
    public static java.util.Set<String> junk = com.sva.inventory.CargoToolsSVA.parseJunk(JUNK_DEFAULT);

    // ---------------------------------------------------------------- air-defence wave (category "airdefense")

    /** The air defence works at all (radars, launchers, the command post); off - the blocks stand idle, nobody is fired at. */
    public static boolean adEnabled = true;
    /** "warnings" (default): the launchers only track and warn - nothing is ever fired; "fire": they fire after the warnings. */
    public static final String AD_WARNINGS = "warnings", AD_FIRE = "fire";
    public static String adMode = AD_WARNINGS;
    /** Fire at a target inside a protected region (a private field that refuses the launcher's owner). */
    public static boolean adFireInProtectedRegions = false;
    /** Fire at vehicles of the players on the air defence's access list (the owner's own are never fired at). */
    public static boolean adFireOnFriends = false;
    /** Fire only with the server's PvP on. */
    public static boolean adRequirePvp = true;
    /** Fire at vehicles with nobody aboard. */
    public static boolean adFireOnUnmanned = false;
    /** Launchers / radars one player may place (0 = unlimited). */
    public static int adMaxLaunchers = 4, adMaxRadars = 6;
    /** A launcher this near the world spawn never fires (blocks). */
    public static int adMinSpawnDistance = 500;
    /** The shortest wait between the lock and fire (s) - a launcher's own wait is never shorter. */
    public static int adMinWarningSeconds = 10;
    /** Every launch / burst into logs/airdefense.log (the command post's log always has it). */
    public static boolean adLaunchLog = true;
    /** Air-defence damage to vehicles x this. */
    public static float adDamage = 1F;
    /** «Ключ дня»: the command post's day code and the code cards. */
    public static boolean adDayKey = true;
    /** Multiblock wave (client): the structures as 3D models (off: plain textured boxes over the footprint). */
    public static boolean adModels = true;
    /** ... with their 512 px textures (off: the flat material colours). */
    public static boolean adModelTextures = true;
    /** ... with their small details (bolts, rungs, louvres) only this near (blocks). */
    public static int adModelDetailDistance = 64;
    /** Upgrades wave: the core's upgrade modules in the air-defence blocks' slots (off: they do nothing, new ones don't fit). */
    public static boolean adUpgrades = true;
    // wave A (plan-next §9 A): the air defence finished
    /** A1: a broken structure keeps its upgrade modules in its item (off: they drop loose as before). */
    public static boolean adKeepModules = true;
    /** A3: the gear / stores / cargo doors / reverser / afterburner make a vehicle easier to see. */
    public static boolean adRcsExtras = true;
    /** A2: the pilots' «вход в зону через N с» looks this far ahead (s). */
    public static int adZoneWarnSeconds = 30;
    /** A4: a tower's code is set at once on aircraft without an MCDU (off: only told in the chat). */
    public static boolean adAutoSquawk = true;
    /** A4: an aircraft squawking a friendly airport's code in / near that airport is the air defence's own. */
    public static boolean adAirportFriends = true;
    /** A5: a 7600 track waits for the command post's decision this long (s), «свой» lasts this long (min). */
    public static int adNoRadioDecisionSeconds = 120, adNoRadioFriendMinutes = 10;
    /**
     * Audit 2026-10-22 (bug 8): a vehicle's 7600 episodes are remembered this long after its last one (min) - switching the
     * code away and back within it does not start a fresh decision timer / grace.
     */
    public static int adNoRadioMemoryMinutes = 10;
    /** A9: the radar coverage maps; the beams computed a tick (all maps together). */
    public static boolean adCoverage = true;
    public static int adCoverageBudget = 32;
    /** A10: the towers interrogate the aircraft their radars see near the airport. */
    public static boolean adIff = true;
    /** A10: the pilot's time to answer the tower's call (s). */
    public static int adIffResponseSeconds = 30;
    /** A10: no answer - the intruder is handed to the air defence (off, the default: the tower's warnings only). */
    public static boolean adIffHandoff = false;
    // audit 2026-10-22 (exploits and hardening)
    /** Bug 1: a zone reaches this far from its command post at most (blocks; 0 - no limit). */
    public static int adZoneMaxDistance = 1024;
    /** Bug 1: the zones of one player's command posts together (0 - no limit; each post holds 16 at most anyway). */
    public static int adMaxZonesPerPlayer = 64;
    // audit 2026-10-22 (combat effectiveness and terrain)
    /**
     * Bug 10: the terrain memory - the chunks near radars and launchers leave their ground's height behind, so an unloaded
     * ridge still hides a plane (radars, launchers' eyes, missiles, the coverage map); its reach round a radar (blocks; 0 off).
     */
    public static boolean adTerrainCache = true;
    public static int adTerrainRadius = 640;
    // code-only wave (docs/proposal-ad-strict-iff.md item 28): «Опознавание: ПО КОДУ»
    /** The command posts may identify by the transponder's code alone (false: forbidden - saved code-only posts work the usual way). */
    public static boolean adCodeOnly = true;
    /** Switched on, the mode takes effect this much later (s; the countdown on the post, the chat to its owner and access list). */
    public static int adCodeOnlyArmSeconds = 60;
    /** A target only after two misses in a row at least this far apart (s). */
    public static int adCodeOnlyConfirmSeconds = 5;
    /** The launchers' shortest wait to fire in the code-only mode, the same for everybody (s). */
    public static int adCodeOnlyMinWarningSeconds = 20;
    /** 7700's grace per aircraft and air defence owner, all episodes together (s; 0 - 7700 is never a target). */
    public static int adCodeOnlyEmergencySeconds = 180;
    /** After a key period starts the last period's code still counts this long (s). */
    public static int adCodeOnlyOverlapSeconds = 60;
    /** A code card is printed for at most this many key periods (one period - 20 min of real time). */
    public static int adCodeCardMaxPeriods = 7;
    /** Guessing: this many different wrong codes within 2 minutes - the block «ПОДБОР» for codeOnlyProbeMinutes. */
    public static int adCodeOnlyProbeCodes = 4, adCodeOnlyProbeMinutes = 5;
    /**
     * The user's decision 1: a code dialled by hand (no card) may count - the posts' option «Ручной набор кода», on by default;
     * false - only a code card's code ever counts.
     */
    public static boolean adCodeOnlyManual = true;
    /** The «ключ дня»'s clock: "total" (the world's total time - /time set and sleeping change nothing) or "day" (the time of day, as before). */
    public static String adKeyClock = "total";
    /** The user's decision 3: every vehicle has a transponder, the ground ones too (false: only what flies). */
    public static boolean adAllTransponders = true;
    // embassy airfield (docs/plan-embassy-airfield-addon.md §2.4): the embassy's own air defence (the user's choice 6, 2026-10-26)
    /** The Tuners' embassies' air defence fires (off: the core's emplacements stay decorative). */
    public static boolean adEmbassy = true;
    /** Warnings a player gets a game day at one embassy before a re-entry is fired on at once (1: the core's ledger; 0: none). TODO(balance) */
    public static int adEmbassyWarnPerDay = 1;
    /** The seconds a warned machine has to turn away (the core proposes 10). TODO(balance) */
    public static int adEmbassyWarnSeconds = 10;
    /** During the turn-away: closer to the embassy's core by more than this than at the warning - fired on at once (blocks). TODO(balance) */
    public static int adEmbassyCloseBlocks = 32;
    /** During the turn-away: below this height over the field and lower than at the warning - fired on at once (blocks). TODO(balance) */
    public static int adEmbassyMinHeight = 40;
    /** A machine in the air with nobody aboard is a target too once its owner (not admitted) was warned. */
    public static boolean adEmbassyUnmanned = true;
    /** A2 (client): the zone frames on the HUD / ND / HSI, the entry prediction and its tones. */
    public static boolean zoneFrames = true;
    /**
     * Audit 2026-10-22 (client): the RWR in the cockpits - the jet's / tiltrotor's glare-shield unit (scope + ОБЗОР / ЗАХВАТ
     * / ПУСК lamps), the lamps under the light plane's / helicopter's scope, the threats on the liner's ND and its E/WD
     * caution (off: the HUD line and the sounds only).
     */
    public static boolean rwrCockpit = true;

    // ---------------------------------------------------------------- airport wave B1 (category "airport")
    /** The ILS antennas need their shelters' EU (off: they always have power; a shelter still gives the monitor). */
    public static boolean aptRequirePower = true;
    /** A shelter's upkeep (EU/t) while it serves an antenna. */
    public static int aptShelterEu = 16;
    /** The magnetic variation (deg, east +): the runway numbers come from the magnetic course. */
    public static double aptMagVar = 0;
    /** Fog over the whole world for the RVR (0 none, 1 haze, 2 fog, 3 dense) on top of the biomes' own. */
    public static int aptFog = 0;
    /** The category of the old single «Курсо-глиссадный маяк» (3: as in wave 4, autoland; 1: CAT I «compact» stations). */
    public static int aptLegacyIlsCat = 3;
    /** The critical / sensitive areas degrade the category and bend the beam. */
    public static boolean aptCriticalAreas = true;
    /** A new / changed ILS waits for a flight inspection (CAT I until then); off: calibrated at once. */
    public static boolean aptFlightCheck = true;
    /** The ILS ident in Morse and the marker beacons' tones in the cockpit. */
    public static boolean aptAurals = true;
    /** The wind pushes the liner in the air (a simple drift). Off by default. */
    public static boolean aptWindPhysics = false;
    /** Client: the runways' paint (piano keys, numbers, centre line, touchdown zone). */
    public static boolean aptPaint = true;
    /** Client: the runway / approach lights. */
    public static boolean aptLights = true;
    /** Client: the lights seen beyond the fog (far away). */
    public static boolean aptLightsFar = true;
    /** Client: the antennas', shelters' and devices' 3D models (off: plain boxes). */
    public static boolean aptModels = true;
    /** Client: how far (blocks) the runways' paint and lights are drawn. */
    public static int aptDrawDistance = 1500;

    // ---------------------------------------------------------------- airport wave B2 (category "airport")
    /** The towers' air-traffic services: ATIS, the datalink (CPDLC), the automatic tower, the follow-me lights. */
    public static boolean aptAtc = true;
    /** A tower without a player in its controller seat runs the automatic rules. */
    public static boolean aptAutoTower = true;
    /** A player in the controller seat takes over from the automatic tower (off: the automatic rules keep running beside him). */
    public static boolean aptControllerOverride = true;
    /** The automatic tower's minimum interval between two runway movements (seconds). */
    public static int aptAutoInterval = 20;
    /** The automatic tower sends an arrival around when the runway is occupied and it is below this height over it (blocks). */
    public static int aptGoAroundHeight = 25;
    /** The tower's upkeep (EU/t). */
    public static int aptTowerEu = 32;
    /** A tower makes / keeps an air-defence «аэродром» zone round its airport. */
    public static boolean aptAdZone = true;
    /** ATIS in the chat of the pilots who come within 30 km-eq of an airport (once per letter). */
    public static boolean aptAtisChat = true;
    /** The follow-me taxi lights lit ahead of a cleared aircraft (nodes). */
    public static int aptTaxiAhead = 10;
    // embassy airfield (docs/plan-embassy-airfield-addon.md §2.4): the Tuners' embassies' fields as airports
    /** The embassies' airfields are airports (virtual runways, ILS, tower, ATIS, taxi lights; never saved). */
    public static boolean aptEmbassyAirports = true;
    /** The embassies' «Колонка обслуживания» opens the add-on's service window (off: the core's armour gases only). */
    public static boolean aptEmbassyServices = true;
    /** An embassy moves a machine left with nobody aboard on its runway / pads / taxiway / under its dome to a stand. */
    public static boolean aptEmbassyTowing = true;
    /** An embassy tower's ATIS reaches the chat only this near (blocks) - or the pilots bound for it. TODO(balance) */
    public static int aptEmbassyAtisRange = 600;
    /** Left on the runway / a pad / the taxiway this long (minutes) - towed to a stand. TODO(balance) */
    public static int aptEmbassyTowMinutes = 2;
    /** Left under the dome this long (minutes) - towed to «Отстой». TODO(balance) */
    public static int aptEmbassyDomeMinutes = 5;
    /**
     * The virtual glide slopes' angle (deg; 2.5 and 3.0 - CAT III, 3.5 - CAT II: the threshold crossing height 2.05 > 1.9). TODO(balance):
     * 3.5 if the world test shows trees in the funnels.
     */
    public static double aptEmbassyGlideAngle = 3.0;
    /** The automatic tower clears the liner to land on a SHORT embassy runway (90 blocks: off - the measured fallback of risk 1). */
    public static boolean aptEmbassyLinerShort = false;
    /** Review 2026-10-27: left with nobody aboard on an embassy's apron off the stands this long (minutes) - towed to a stand / «Отстой». TODO(balance) */
    public static int aptEmbassyApronMinutes = 10;

    // ---------------------------------------------------------------- wave C: the handbook (category "book")
    /** The handbook is given once on a player's first join (remembered in the player's persisted NBT). */
    public static boolean bookGiveOnJoin = true;
    /** Client: the checklist pages tick live while the book is opened in the liner's cockpit. */
    public static boolean bookLiveChecklists = true;
    /** Client: the control to work next pulses with an outline in the cockpit / the «Пульты» screen («Показать», the tutorial). */
    public static boolean bookHighlight = true;
    /** Client: the «Голограмма в мире» button of the build schematics. */
    public static boolean bookHologram = true;
    /** Client: the «Обучение» mode (the guided first flight in the liner). */
    public static boolean bookTutorial = true;
    /** Client: the vehicles' 3D models turning in the book. */
    public static boolean bookModels = true;

    private ConfigSVA() {
    }

    /** Fire is allowed at all (the air defence on and in the "fire" mode). */
    public static boolean adFires() {
        return adEnabled && AD_FIRE.equalsIgnoreCase(adMode);
    }

    public static void load(File file) {
        Configuration c = new Configuration(file);
        try {
            c.load();
            String v = "vehicles";
            wear = c.getBoolean("wear", v, true,
                    "Hull wear: attacks and wall hits damage vehicles, at 0 HP a vehicle is wrecked / Износ корпуса: удары и столкновения повреждают технику");
            maxPerPlayer = Math.max(0, c.getInt("maxPerPlayer", v, 0, 0, 10000,
                    "Vehicles one player may have placed at once (0 = unlimited) / Лимит машин на игрока (0 - без ограничений)"));
            chargerRate = num(c, "chargerRate", v, 1F, 0.1F, 100F,
                    "Charging station speed: input voltage x this / Скорость зарядной станции: напряжение входа x это");
            consumption = num(c, "consumption", v, 1F, 0F, 100F,
                    "Vehicles' EU use x this / Расход EU техникой x это");
            seeThroughHull = c.getBoolean("seeThroughHull", v, false,
                    "Client: from the cockpit (first person) see the world through the whole hull, not only through the windows / "
                            + "Клиент: из кабины (от первого лица) видно мир сквозь весь корпус, а не только через стёкла");
            textures = c.getBoolean("textures", v, true,
                    "Client: material textures on vehicles (pixel detail, panel seams, rivets); false = flat colours / "
                            + "Клиент: текстуры материалов на технике (фактура, швы, заклёпки); false - заливка цветом");
            liveries = c.getBoolean("liveries", v, true,
                    "Client: liveries by tier and the side decal with the SA emblem and board number / "
                            + "Клиент: ливреи по уровню машины и бортовая эмблема SA с номером");
            weathering = c.getBoolean("weathering", v, true,
                    "Client: weathering grows with hull wear (soot, oil, chipped paint, mud); a repair cleans it / "
                            + "Клиент: износ корпуса виден на технике (копоть, масло, сколы, грязь); ремонт её очищает");
            markings = c.getBoolean("markings", v, true,
                    "Client: service markings on aircraft (anti-glare panel, walkways, rescue / propeller / jacking stencils, registration) / "
                            + "Клиент: эксплуатационная маркировка авиации (антибликовая панель, зоны хождения, трафареты, регистрация)");
            liveInstruments = c.getBoolean("liveInstruments", v, true,
                    "Client: cockpit dials, screens and warning lamps show the vehicle's real readings / "
                            + "Клиент: приборы в кабине (стрелки, экраны, лампы) показывают реальные данные машины");
            icons3d = c.getBoolean("icons3d", v, true,
                    "Client: vehicle items are drawn as small 3D models (false: flat icons) / "
                            + "Клиент: предметы машин рисуются уменьшенными 3D-моделями (false - плоские иконки)");
            headlights = c.getBoolean("headlights", v, true,
                    "Client: headlight and landing-light beams at night, brighter brake lights / "
                            + "Клиент: лучи фар и посадочных фар ночью, яркие стоп-сигналы при торможении");
            dynamicLight = c.getBoolean("dynamicLight", v, true,
                    "Client: headlights light up the blocks ahead at night (only on your screen, nothing is saved) / "
                            + "Клиент: фары освещают блоки впереди ночью (только у вас на экране, в мир не сохраняется)");
            dynamicLightVehicles = c.getInt("dynamicLightVehicles", v, 4, 0, 16,
                    "Client: how many other vehicles near you light the blocks with their headlights (0 = only your own) / "
                            + "Клиент: сколько соседних машин освещают блоки фарами (0 - только своя)");
            dynamicLightRange = c.getInt("dynamicLightRange", v, 32, 8, 128,
                    "Client: other vehicles' dynamic light only within this many blocks / "
                            + "Клиент: радиус (в блоках), в котором светят фары соседних машин");
            dynamicLightPeriod = c.getInt("dynamicLightPeriod", v, 4, 1, 40,
                    "Client: dynamic light update period in ticks (more = less load) / "
                            + "Клиент: период обновления света фар в тиках (больше - меньше нагрузка)");
            navLights = c.getBoolean("navLights", v, true,
                    "Client: navigation lights - red/green/strobe/beacon on aircraft, amber beacons on work vehicles / "
                            + "Клиент: бортовые огни - красный/зелёный/строб/маячок у авиации, оранжевые маячки у рабочей техники");
            animations = c.getBoolean("animations", v, true,
                    "Client: animated parts - wheels, tracks, steering, control surfaces, landing gear, rotor tilt / "
                            + "Клиент: анимации деталей - колёса, гусеницы, руль, рули самолёта, шасси, наклон винта");
            interiorTrim = c.getBoolean("interiorTrim", v, true,
                    "Client: cabin trim by the vehicle's tier - fabric / leather + wood / alcantara + carbon / velvet + dark glass / "
                            + "Клиент: отделка салона по уровню машины - ткань / кожа и дерево / алькантара и карбон / бархат и тёмное стекло");
            nightLighting = c.getBoolean("nightLighting", v, true,
                    "Client: at night (powered) the cockpit instruments and lamps glow in the tier's colour, the ceiling lights and a floor strip light up; "
                            + "false = plafonds always lit, no tint / "
                            + "Клиент: ночью (с зарядом) приборы и лампы в кабине светятся цветом уровня машины, горят плафоны и полоса у пола");
            cabinLightLevel = num(c, "cabinLightLevel", v, 0.8F, 0F, 1F,
                    "Client: brightness of the night cabin lighting 0..1 / Клиент: яркость ночной подсветки салона 0..1");
            cockpitStartup = c.getBoolean("cockpitStartup", v, true,
                    "Client: boarding as the driver starts the cockpit - lamp test, SA logo on the screens, needles sweep / "
                            + "Клиент: при посадке водителем кабина запускается - тест ламп, логотип SA на экранах, пробег стрелок");
            cockpitSounds = c.getBoolean("cockpitSounds", v, true,
                    "Client: quiet cockpit warning sounds for the riders - overheating beep once a second, stall siren / "
                            + "Клиент: тихие звуки предупреждений в кабине - писк перегрева раз в секунду, сирена сваливания");
            sounds = c.getBoolean("sounds", v, true,
                    "Client: engine, rotor and turbine sounds of vehicles and their own effects (boarding, hatch, landing, hits) / "
                            + "Клиент: звуки моторов, винтов и турбин техники и её собственные звуки (посадка, багажник, касание, удары)");
            soundVolume = num(c, "soundVolume", v, 1F, 0F, 2F,
                    "Client: vehicle engine sounds volume x this (0..2) / Клиент: громкость звуков моторов x это (0..2)");
            vehicleCamera = c.getBoolean("vehicleCamera", v, true,
                    "Client: third-person camera while riding - farther by the vehicle's size, higher, follows turns smoothly, mouse wheel zooms / "
                            + "Клиент: камера от третьего лица в технике - дальше по размеру машины, выше, плавно догоняет, колёсико - дистанция");
            cameraDistance = num(c, "cameraDistance", v, 1F, 0.25F, 4F,
                    "Client: that camera's distance x this / Клиент: дистанция этой камеры x это");
            cruise = c.getBoolean("cruise", v, true,
                    "Cruise control for ground vehicles and the hoverbike (key 'Cruise') / Круиз-контроль наземной техники и ховербайка (клавиша 'Круиз')");
            autopilot = c.getBoolean("autopilot", v, true,
                    "Autopilot for the plane, jet and tiltrotor (airplane mode): holds heading and height / "
                            + "Автопилот самолёта, реактивного и конвертоплана (режим самолёта): держит курс и высоту");
            tuning = c.getBoolean("tuning", v, true,
                    "Tuning upgrades installed in the repair garage change the vehicle (speed, buffer, armour, step, lights, drill, gas) / "
                            + "Улучшения тюнинга, поставленные в ремонтном гараже, меняют машину (скорость, заряд, броня, колёса, фары, бур, газ)");
            tuningVisuals = c.getBoolean("tuningVisuals", v, true,
                    "Client: installed upgrades are drawn on the vehicle model (armour plates, light bar, battery box, extra tank, big wheels) / "
                            + "Клиент: установленные улучшения видны на модели (бронелисты, люстра фар, доп. аккумулятор, бак, большие колёса)");
            garageHologram = c.getBoolean("garageHologram", v, true,
                    "Client: the repair garage shows a hologram ring over the vehicle while it repairs / "
                            + "Клиент: во время ремонта над машиной в гараже - кольцо-голограмма");
            locks = c.getBoolean("locks", v, true,
                    "Vehicle locks and key cards: access rights (drive / passenger / cargo / repair) and lock modes; false = old rules "
                            + "(owner drives, anyone rides as a passenger) / Замки техники и ключ-карты: права доступа и режимы замка; "
                            + "false - старые правила (водит владелец, пассажиром - любой)");
            lockOpBypass = c.getBoolean("lockOpBypass", v, true,
                    "Server operators pass any vehicle lock / Операторы сервера открывают любую машину");
            String pd = c.getString("playerDamage", v, "pvp",
                    "Who may damage vehicles: all / pvp (others only with PvP on, the owner always) / owner / none / "
                            + "Кто может ломать технику: all - все, pvp - чужие только при включённом PvP (владелец всегда), "
                            + "owner - только владелец, none - никто из игроков").trim().toLowerCase(java.util.Locale.ROOT);
            playerDamage = "all".equals(pd) || "owner".equals(pd) || "none".equals(pd) ? pd : "pvp";
            opManage = c.getBoolean("opManage", v, true,
                    "Server operators (creative) may pick up others' vehicles, open their cargo and access screens and change others' "
                            + "charging stations / Операторы сервера (творческий режим) могут забирать чужие машины, открывать их багажник "
                            + "и окно доступа, настраивать чужие зарядные станции");
            debugKeys = c.getBoolean("debugKeys", v, false,
                    "Client: log key presses and the vehicle menu opening (diagnostics) / "
                            + "Клиент: писать нажатия клавиш и открытие меню «Техника» в лог (диагностика)");
            trailerKinds = c.getBoolean("trailerKinds", v, true,
                    "The tanker, car platform and mobile charger trailers can be hitched (false: only the cargo trailer) / "
                            + "Прицепы цистерна, платформа и передвижная зарядка цепляются к вездеходу (false - только грузовой)");
            vehicleMap = c.getBoolean("vehicleMap", v, true,
                    "'My vehicles' map: where your vehicles are, their charge and hull (key, charging station button) / "
                            + "Карта 'Моя техника': где ваши машины, заряд и износ (клавиша, кнопка на зарядной станции)");
            singularJump = c.getBoolean("singularJump", v, true,
                    "The Singular Ship's own portal 'Jump' (to a Coordinator point or home, other dimensions too) / "
                            + "Собственный портал Сингулярного корабля 'Прыжок' (к точке Координатора или домой, в том числе в другие измерения)");
            bridgeDock = c.getBoolean("bridgeDock", v, true,
                    "The shuttle and the ship dock with an open bridge vortex and come out at the bridge's other end / "
                            + "Челнок и корабль стыкуются с открытым порталом моста и выходят на другом конце моста");
            regen = c.getBoolean("regen", v, true,
                    "Regenerative braking: braking, rolling downhill and gliding / autorotation return part of the energy to the buffer / "
                            + "Рекуперация: торможение, спуск с горки и планирование / авторотация возвращают часть энергии в буфер");
            highLift = c.getBoolean("highLift", v, true,
                    "Aircraft high-lift devices: flaps (keys), automatic slats, spoilers / airbrake (plane, jet, tiltrotor flaperons) / "
                            + "Механизация крыла: закрылки (клавиши), автоматические предкрылки, интерцепторы / воздушный тормоз (самолёт, реактивный, флапероны конвертоплана)");
            avionics = c.getBoolean("avionics", v, true,
                    "Cockpit avionics: the light plane's autopilot panel, HSI, flap / trim indicators; the jet's HSI and AP lines (client) / "
                            + "Авионика кабины: панель автопилота, HSI, указатели закрылков и триммера лёгкого самолёта; HSI и строки АП реактивного (клиент)");
            airfield = c.getBoolean("airfield", v, true,
                    "Runway marks and lights: the threshold stripes and designator, centre-line / threshold lights on lit strips (client) / "
                            + "Разметка и огни ВПП: «зебра» и номер порога, огни оси и порога на полосах с огнями (клиент)");
            landingLamps = c.getBoolean("landingLamps", v, true,
                    "The light plane's retractable landing lights under the wing (client) / "
                            + "Выпускные посадочные фары под крылом лёгкого самолёта (клиент)");
            engineStart = c.getBoolean("engineStart", v, true,
                    "Aircraft engine start: battery on + START (or forward) - starter, catch, warm-up; false = ready whenever powered (plane, jet, tiltrotor) / "
                            + "Запуск двигателя авиации: АКБ + ЗАПУСК (или вперёд) - стартер, «схватил», прогрев; false - готов сразу, как раньше (самолёт, реактивный, конвертоплан)");
            failures = c.getBoolean("failures", v, true,
                    "Aircraft failures over 60 % wear: engine power loss, a jammed flap, a dead instrument; the garage repairs them / "
                            + "Отказы авиации при износе выше 60 %: потеря тяги, заклинивший закрылок, отказ прибора; ремонт в гараже снимает их");
            ins = c.getBoolean("ins", v, true,
                    "Aircraft inertial navigation (INS switch, A320 ADIRS style): until aligned no attitude / heading / map, no autopilot; false = always aligned / "
                            + "Инерциальная система авиации (выключатель ИНС, как ADIRS A320): до выставки нет авиагоризонта / курса / карты и автопилота; false - всегда выставлена");
            insAlignSeconds = c.getInt("insAlignSeconds", v, 10, 0, com.sva.vehicle.SystemsMath.ALIGN_MAX_S,
                    "INS alignment time in seconds, standing still with the board powered (0 = instant) / "
                            + "Время выставки ИНС в секундах, стоя на месте с питанием борта (0 - сразу)");
            zoneFrames = c.getBoolean("zoneFrames", v, true,
                    "Air-defence zones framed on the HUD / ND / HSI with the 'zone entry in N s' warning and its tones (client) / "
                            + "Рамки зон ПВО на HUD / ND / HSI и предупреждение о входе в зону с тоном (клиент)");
            rwrCockpit = c.getBoolean("rwrCockpit", v, true,
                    "The radar warning receiver in the cockpits: the jet's / tiltrotor's glare-shield unit, the warning lamps, the liner's ND "
                            + "threats and E/WD caution (client; off: the HUD line and the sounds only) / "
                            + "СПО в кабинах: блок на козырьке реактивного и конвертоплана, лампы, угрозы на ND и предупреждение E/WD лайнера (клиент)");
            gearDoors = c.getBoolean("gearDoors", v, true,
                    "Jet / tiltrotor gear doors open before the legs move and shut after them (client) / "
                            + "Створки шасси реактивного и конвертоплана открываются до уборки ног и закрываются после (клиент)");
            gearStruts = c.getBoolean("gearStruts", v, true,
                    "Oleo struts compress on touchdown and bumps, the light plane's spring legs flex (client) / "
                            + "Амортизаторы обжимаются при касании и на кочках, рессорные стойки лёгкого самолёта пружинят (клиент)");
            jetNozzle = c.getBoolean("jetNozzle", v, true,
                    "Jet nozzle petals follow the throttle; afterburner flame with shock diamonds (client) / "
                            + "Лепестки сопла реактивного следуют за РУД; факел форсажа с ромбами (клиент)");
            jetHud = c.getBoolean("jetHud", v, true,
                    "Jet head-up display on the canopy glass in the pilot's view (client) / "
                            + "Индикатор на стекле фонаря реактивного в виде пилота (клиент)");
            glassCockpit = c.getBoolean("glassCockpit", v, true,
                    "Light plane / helicopter cockpits: PFD, MFD pages, lamp strip, transponder, radar warning, the heli's tach / power / hover /"
                            + " radar altimeter faces (client) / Кабины лёгкого самолёта и вертолёта: PFD, страницы MFD, табло, ответчик, СПО,"
                            + " тахометр / мощность / висение / радиовысотомер вертолёта (клиент)");
            cockpitHiRes = c.getBoolean("cockpitHiRes", v, true,
                    "Client: sharp cockpit text and dial faces for 2K / 4K screens (an anti-aliased font and baked dial faces, mipmaps,"
                            + " anisotropic filtering); false = the vanilla 8 px font, as before / Клиент: чёткие надписи и шкалы в кабине для"
                            + " экранов 2K / 4K (сглаженный шрифт, запечённые шкалы, мипмапы, анизотропная фильтрация); false - обычный шрифт 8 px,"
                            + " как раньше");
            cockpitQuality = com.sva.vehicle.HiResMath.QUALITY_KEYS[com.sva.vehicle.HiResMath.parse(c.getString("cockpitQuality", v, "AUTO",
                    "Cockpit texture quality: AUTO (by the window height: up to 1200 px - 1K, up to 1800 - 2K, else 4K), 1K, 2K, 4K."
                            + " Video memory: 1K - font 0.7 MB + 0.2 MB a dial; 2K - 2.7 MB + 0.7 MB; 4K - 10.7 MB + 2.7 MB"
                            + " (at most 12 dials kept: 4K at most ~43 MB) / Качество текстур кабины: AUTO (по высоте окна: до 1200 px - 1K,"
                            + " до 1800 - 2K, больше - 4K), 1K, 2K, 4K. Видеопамять: 1K - шрифт 0,7 МБ + 0,2 МБ на шкалу; 2K - 2,7 + 0,7 МБ;"
                            + " 4K - 10,7 + 2,7 МБ (хранится не больше 12 шкал: 4K - до ~43 МБ)",
                    com.sva.vehicle.HiResMath.QUALITY_KEYS))];
            linerQuickStart = c.getBoolean("linerQuickStart", v, true,
                    "Liner: 'Quick start' (the menu row and the guarded overhead switch) runs the whole start-up in ~40 s; false - manual only"
                            + " / Лайнер: 'Быстрый запуск' (строка меню и выключатель под колпачком) проходит весь запуск за ~40 с; false - только вручную");
            linerStartFailChance = sanitize((float) c.getFloat("linerStartFailChance", v, 0.02F, 0F, 0.5F,
                    "Liner: the chance of a random hot start and of a hung start of an engine (each); low bleed pressure causes them anyway"
                            + " / Лайнер: вероятность случайного горячего и зависшего запуска двигателя (каждого); при низком давлении отбора - всегда"),
                    0.02F, 0F, 0.5F);
            linerTextures = com.sva.vehicle.LinerTexMath.KEYS[com.sva.vehicle.LinerTexMath.parse(c.getString("linerTextures", v, "AUTO",
                    "Liner cockpit / cabin textures (client): AUTO (by cockpitQuality: 1K / 2K / 4K), LOW (512 px), 1K, 2K, 4K. Video memory:"
                            + " LOW ~2 MB, 1K ~8, 2K ~32, 4K ~128 MB / Текстуры кабины и салона лайнера (клиент): AUTO (по cockpitQuality), LOW"
                            + " (512 px), 1K, 2K, 4K. Видеопамять: LOW ~2 МБ, 1K ~8, 2K ~32, 4K ~128 МБ",
                    com.sva.vehicle.LinerTexMath.KEYS))];
            linerLod = c.getBoolean("linerLod", v, true,
                    "Liner exterior: lighter models far away (64 / 160 blocks) (client) / Лайнер: упрощённые модели вдали (64 / 160 блоков) (клиент)");
            linerCabin = c.getBoolean("linerCabin", v, true,
                    "Liner: draw the passenger cabin (inside it or near the open L1 door) (client) / Лайнер: рисовать салон (внутри или у открытой"
                            + " двери L1) (клиент)");
            linerHydLeakChance = sanitize((float) c.getFloat("linerHydLeakChance", v, 0.03F, 0F, 1F,
                    "Liner: the chance of a hydraulic leak per hour of pressurised running (the reservoir empties in ~40 s - 'Hydraulic fluid'"
                            + " refills it); 0 - never / Лайнер: вероятность течи гидросистемы за час работы под давлением (бак пустеет за ~40 с -"
                            + " 'Гидрожидкость' доливает); 0 - никогда"),
                    0.03F, 0F, 1F);
            linerBrakeGlow = c.getBoolean("linerBrakeGlow", v, true,
                    "Liner: hot brakes glow red on the model (client) / Лайнер: горячие тормоза светятся красным на модели (клиент)");
            linerFuelGravityAlt = sanitize((float) c.getFloat("linerFuelGravityAlt", v, 160F, 64F, 512F,
                    "Liner: the gravity fuel feed's ceiling (Y). Pumps off on a side: under it the engine is fed by gravity, above it the engine"
                            + " flames out over 60 % thrust / Лайнер: потолок самотёчной подачи топлива (Y). Насосы стороны выключены: ниже - двигатель"
                            + " питается самотёком, выше - глохнет на тяге больше 60 %"),
                    160F, 64F, 512F);
            linerLdgMemoHeight = sanitize((float) c.getFloat("linerLdgMemoHeight", v, 60F, 10F, 250F,
                    "Liner: LDG MEMO (and the ECAM's approach phase) under this height over the ground with the gear lever down (blocks)"
                            + " / Лайнер: памятка LDG MEMO (и фаза захода ECAM) ниже этой высоты над землёй при рычаге шасси вниз (блоки)"),
                    60F, 10F, 250F);
            linerAccelHeight = sanitize((float) c.getFloat("linerAccelHeight", v, 30F, 5F, 150F,
                    "Liner: the acceleration / thrust reduction height over the runway (blocks): SRS -> CLB, 'LVR CLB' / Лайнер: высота"
                            + " разгона / снижения тяги над ВПП (блоки): SRS -> CLB, 'LVR CLB'"),
                    30F, 5F, 150F);
            linerIlsRange = sanitize((float) c.getFloat("linerIlsRange", v, 4000F, 500F, 16000F,
                    "Liner: how far an airliner receives a localizer / glide slope beacon (blocks) / Лайнер: дальность приёма курсо-"
                            + "глиссадного маяка (блоки)"),
                    4000F, 500F, 16000F);
            linerCallouts = c.getBoolean("linerCallouts", v, true,
                    "Liner: the height callouts 100.. 50.. 40.. 30.. 20.. 10, RETARD and V1 as sounds / Лайнер: отсчёт высоты 100.. 50.. 40.."
                            + " 30.. 20.. 10, RETARD и V1 - звуком");
            linerIrsAutoInit = c.getBoolean("linerIrsAutoInit", v, true,
                    "Liner: the IRS INIT confirms itself (the INS aligns without the MCDU's 'ALIGN' key) / Лайнер: ИНС ИНИЦ подтверждается"
                            + " сам (выставка ИНС заканчивается без кнопки 'ВЫСТАВИТЬ' на МФПУ)");
            linerMcdu3d = c.getBoolean("linerMcdu3d", v, true,
                    "Liner: the MCDU screens drawn live in the 3D cockpit (client) / Лайнер: экраны МФПУ в 3D-кабине (клиент)");
            linerIcing = c.getBoolean("linerIcing", v, true,
                    "Liner: ice builds up in cold cloud / rain / snow (wing, engines, probes, windscreen) / Лайнер: обледенение в холодных"
                            + " облаках, дожде и снеге (крыло, двигатели, ПВД, стёкла)");
            linerColdEffect = c.getBoolean("linerColdEffect", v, false,
                    "Liner: a long stay in a cold cabin slows the riders (no helmet) / Лайнер: долгое пребывание в холодном салоне замедляет"
                            + " (без шлема)");
            linerSignChime = c.getBoolean("linerSignChime", v, true,
                    "Liner: the cabin chime when the seat-belt / no-smoking signs change / Лайнер: сигнал в салоне при смене табло ремней"
                            + " и 'не курить'");
            linerExtLights = c.getBoolean("linerExtLights", v, true,
                    "Liner: exterior light halos and beams (client) / Лайнер: ореолы и лучи внешних огней (клиент)");
            linerGroundPools = c.getBoolean("linerGroundPools", v, true,
                    "Liner: light pools on the ground under the beams (client) / Лайнер: пятна света на земле под фарами (клиент)");
            linerDynamicLight = c.getBoolean("linerDynamicLight", v, true,
                    "Liner: the landing / taxi lights light the blocks (as dynamicLight, client) / Лайнер: посадочные и рулёжные фары"
                            + " освещают блоки (как dynamicLight, клиент)");
            linerIceVisuals = c.getBoolean("linerIceVisuals", v, true,
                    "Liner: ice on the wings / nacelles and frost on the windscreen (client) / Лайнер: лёд на крыле и мотогондолах, иней"
                            + " на стёклах (клиент)");
            linerCabinLighting = c.getBoolean("linerCabinLighting", v, true,
                    "Liner: the cockpit / cabin light levels - dome, flood, cabin steps, night blue, reading lights (client) / Лайнер:"
                            + " освещение кабины и салона - плафон, заливающий свет, ступени, ночной синий, лампы чтения (клиент)");
            // cockpit view (plan-next 8): the hand hidden in the pilot seats, the quick views
            linerHideHand = c.getBoolean("linerHideHand", v, true,
                    "Liner: hide the first-person hand and the held item in the pilot seats - the lower screens stay free (client) / Лайнер:"
                            + " скрывать руку и предмет в руке в креслах пилотов - нижние экраны не закрыты (клиент)");
            linerQuickViews = c.getBoolean("linerQuickViews", v, true,
                    "Liner: the pilots' quick views - the 'Cockpit view' key (default =): tap - to the last picked panel and back, hold + wheel -"
                            + " pick a view; a look only, it does not steer (client) / Лайнер: быстрые виды пилотов - клавиша 'Вид кабины' (по умолч. =):"
                            + " нажатие - к последней выбранной панели и обратно, удержание + колесо - выбор вида; только взгляд, не рулит (клиент)");
            // liner wave 7: GPS (kept on - the user's decision; no cockpit switch), the ADIRS' random faults, towing, the seat belts
            linerGpsAlwaysOn = c.getBoolean("linerGpsAlwaysOn", v, true,
                    "Liner: GPS always on - the IRs' drift corrected, GPS PRIMARY (no cockpit switch) / Лайнер: GPS всегда включён - дрейф"
                            + " ИНС корректируется, GPS PRIMARY (выключателя в кабине нет)");
            linerAdirsFailChance = sanitize((float) c.getFloat("linerAdirsFailChance", v, 0.02F, 0F, 1F,
                    "Liner: the chance per flight hour of a random IR / ADR fault (only with 'failures') / Лайнер: вероятность за час полёта"
                            + " случайного отказа IR / ADR (только при 'failures')"), 0.02F, 0F, 1F);
            linerTow = c.getBoolean("linerTow", v, true,
                    "Liner: a truck / crawler can tow the liner by its nose gear (БУКСИР switch ON) / Лайнер: грузовик или вездеход может"
                            + " буксировать лайнер за переднюю стойку (тумблер БУКСИР ВКЛ)");
            linerSeatBelts = c.getBoolean("linerSeatBelts", v, true,
                    "Liner: with the seat-belt sign on in flight the passengers are fastened (Shift twice to unbuckle) / Лайнер: при"
                            + " табло 'ремни' в полёте пассажиры пристёгнуты (Shift дважды - отстегнуться)");
            linerPtuSound = c.getBoolean("linerPtuSound", v, true,
                    "Liner: the PTU's 'barking' during single-engine taxi as a sound / Лайнер: 'лай' PTU при рулении на одном двигателе - звуком");
            radarAltCallouts = c.getBoolean("radarAltCallouts", v, true,
                    "Helicopter radar altimeter callouts 50.. 40.. 30.. 20.. 10 on the descent (client) / "
                            + "Радиовысотомер вертолёта отсчитывает 50.. 40.. 30.. 20.. 10 при снижении (клиент)");
            String cs = c.getString("radarAltCalloutStyle", v, "both",
                    "Callouts: sound = a tone each, flash = the number on the HUD (no chat), both / "
                            + "Отсчёт: sound - тон, flash - цифра на экране (без чата), both - и то и другое").trim().toLowerCase(java.util.Locale.ROOT);
            radarAltCalloutStyle = "sound".equals(cs) || "flash".equals(cs) ? cs : "both";
            regenEfficiency = num(c, "regenEfficiency", v, 0.2F, 0F, 0.5F,
                    "Share of the motion energy recovered (0..0.5; aircraft at most 0.3) / Доля возвращаемой энергии движения (0..0,5; авиация не больше 0,3)");
            drill = c.getBoolean("drill", v, true,
                    "The drill vehicle's bore digs blocks (false: it only drives and carries) / "
                            + "Бур буровой машины копает блоки (false - машина только ездит и возит груз)");
            mobCloak = c.getBoolean("mobCloak", v, true,
                    "The Singular Ship's invisibility: hostile mobs don't target its riders / "
                            + "Невидимость Сингулярного корабля: враждебные мобы не нападают на экипаж");
            stationBoost = c.getBoolean("stationBoost", v, true,
                    "Charging station 'Boost' mode: x3 charging, the station heats up (helium from the gas module cools it) / "
                            + "Режим 'Форсаж' зарядной станции: зарядка x3, станция греется (гелий через модуль газа охлаждает)");
            stationStorageLink = c.getBoolean("stationStorageLink", v, true,
                    "A Silicon Age (or IC2) energy storage whose output faces the charging station feeds the vehicle directly, no cable / "
                            + "Накопитель энергии Silicon Age (или IC2), выход которого смотрит на станцию, заряжает машину напрямую, без кабеля");
            stationsAddUp = c.getBoolean("stationsAddUp", v, true,
                    "Several charging stations on one platform charge the same vehicle together, their rates add up / "
                            + "Несколько зарядных станций на одной площадке заряжают одну машину вместе, скорости складываются");
            fuelMode = com.sva.vehicle.FuelMath.mode(c.getString("fuelMode", v, com.sva.vehicle.FuelMath.MODE_HYDROGEN,
                    "HYDROGEN - buggy, hoverbike, crawler, drill vehicle, helicopter, plane, jet and tiltrotor burn hydrogen, a removable battery "
                            + "starts the engine and runs the electrics; EU - the old behaviour (driving on the EU buffer) / "
                            + "HYDROGEN - багги, ховербайк, вездеход, буровая, вертолёт, самолёт, реактивный и конвертоплан на водороде, съёмный "
                            + "аккумулятор запускает двигатель и питает электрику; EU - как раньше (ход от заряда)",
                    new String[] {com.sva.vehicle.FuelMath.MODE_HYDROGEN, com.sva.vehicle.FuelMath.MODE_EU}));
            hydrogen = com.sva.vehicle.FuelMath.hydrogenMode(fuelMode);
            batteryWear = c.getBoolean("batteryWear", v, true,
                    "The vehicle battery's health wears with charge cycles and deep discharges; service in the garage or with the kit / "
                            + "Здоровье аккумулятора техники падает от циклов заряда и глубокого разряда; обслуживание в гараже или набором");
            junk = com.sva.inventory.CargoToolsSVA.parseJunk(c.getStringList("junk", v, JUNK_DEFAULT,
                    "Cargo screen 'Junk' button: items dropped next to the vehicle (modid:name or modid:name@meta) / "
                            + "Кнопка 'Мусор' в окне багажника: что выбрасывается рядом с машиной"));
            loadAirDefense(c);
            loadAirport(c);
            loadBook(c);
        } finally {
            if (c.hasChanged()) {
                c.save();
            }
        }
    }

    /** Air-defence wave: the "airdefense" category (mock-up veh_ad_5_zones_rules, *7 «Правила сервера»). */
    private static void loadAirDefense(Configuration c) {
        String a = "airdefense";
        adEnabled = c.getBoolean("enabled", a, true,
                "Air defence: radars, launchers, the command post, zones; off - everything stands idle / ПВО включено (РЛС, ПУ, КП, зоны)");
        adMode = c.getString("mode", a, AD_WARNINGS,
                "warnings - the launchers only track and warn the pilot, never fire; fire - they fire after the warnings / "
                        + "warnings - только сопровождение и предупреждения; fire - огонь после предупреждений",
                new String[] {AD_WARNINGS, AD_FIRE});
        adFireInProtectedRegions = c.getBoolean("fireInProtectedRegions", a, false,
                "Fire at targets inside a private field that refuses the launcher's owner / Огонь в защищённых регионах");
        adFireOnFriends = c.getBoolean("fireOnFriends", a, false,
                "Fire at vehicles of the players on the air defence's access list (own vehicles: never); not in the code-only mode / "
                        + "Огонь по машинам друзей (по своим - никогда); в режиме ПО КОДУ не действует");
        adRequirePvp = c.getBoolean("requirePvp", a, true, "Fire only with the server's PvP on / Огонь только при включённом PvP");
        adFireOnUnmanned = c.getBoolean("fireOnUnmanned", a, false, "Fire at vehicles with nobody aboard / Огонь по машинам без пилота");
        adMaxLaunchers = c.getInt("maxLaunchersPerPlayer", a, 4, 0, 1000, "Launchers one player may place (0 = unlimited) / ПУ на игрока");
        adMaxRadars = c.getInt("maxRadarsPerPlayer", a, 6, 0, 1000, "Radars one player may place (0 = unlimited) / РЛС на игрока");
        adMinSpawnDistance = c.getInt("minDistanceFromSpawn", a, 500, 0, 100000,
                "A launcher this near the world spawn never fires (blocks) / ПУ ближе к спавну не стреляет");
        adMinWarningSeconds = c.getInt("minWarningSeconds", a, 10, 0, 60,
                "The shortest wait from the lock to fire (s) / Предупреждать не меньше (с)");
        adLaunchLog = c.getBoolean("launchLog", a, true, "Write launches and bursts to logs/airdefense.log / Журнал пусков в logs/airdefense.log");
        adDamage = num(c, "damageMultiplier", a, 1F, 0F, 10F, "Air-defence damage to vehicles x this / Урон по технике x");
        adDayKey = c.getBoolean("dayKey", a, true, "The day key: the command post's day code and the code cards / Ключ дня");
        adModels = c.getBoolean("models", a, true,
                "Client: radars, launchers and the command post as 3D models (off: plain boxes) / Клиент: 3D-модели РЛС, ПУ и КП");
        adModelTextures = c.getBoolean("modelTextures", a, true,
                "Client: the models' detailed textures (off: flat colours) / Клиент: подробные текстуры моделей ПВО");
        adModelDetailDistance = c.getInt("modelDetailDistance", a, 64, 0, 512,
                "Client: the models' small details drawn only this near (blocks) / Клиент: мелкие детали моделей ПВО ближе (блоков)");
        adUpgrades = c.getBoolean("upgrades", a, true,
                "Silicon Age upgrade modules in radars, launchers and the command post (transformers, storage, overclockers...); off - they do nothing / "
                        + "Модули Silicon Age в РЛС, ПУ и КП (трансформаторы, накопитель, ускорители...); выкл. - не действуют");
        // wave A: the air defence finished
        adKeepModules = c.getBoolean("keepModules", a, true,
                "A broken radar / launcher / command post keeps its upgrade modules in its item (off: they drop loose) / Модули остаются в предмете при сломе");
        adRcsExtras = c.getBoolean("rcsExtras", a, true,
                "Gear down / stores +20 %, cargo doors +10 %, reverser / afterburner +15 % of the detection range / Шасси, подвески, грузовые двери, реверс и форсаж повышают заметность");
        adZoneWarnSeconds = c.getInt("zoneWarnSeconds", a, 30, 5, 120,
                "Pilots: 'zone entry in N s' looks this far ahead (s) / Пилотам: предупреждение о входе в зону за столько секунд");
        adAutoSquawk = c.getBoolean("autoSquawk", a, true,
                "A tower's transponder code is set at once on aircraft without an MCDU (off: only told) / Код вышки ставится сам на технике без MCDU");
        adAirportFriends = c.getBoolean("airportFriends", a, true,
                "A friendly airport's codes in / near it count as own for the air defence / Коды своего аэродрома у аэродрома - свои для ПВО");
        adNoRadioDecisionSeconds = c.getInt("noRadioDecisionSeconds", a, 120, 0, 900,
                "7600: the command post's time to decide, s (then the usual rules) / 7600: время на решение КП, с");
        adNoRadioFriendMinutes = c.getInt("noRadioFriendMinutes", a, 10, 1, 120,
                "7600: 'friend' from the command post lasts this long, min / 7600: 'свой' от КП действует, мин");
        adNoRadioMemoryMinutes = c.getInt("noRadioMemoryMinutes", a, 10, 1, 240,
                "7600: a vehicle's 7600 time is remembered this long after it stopped, min (a code change does not restart the timer) / "
                        + "7600: время 7600 помнится столько минут после снятия (смена кода не сбрасывает таймер)");
        adCoverage = c.getBoolean("coverageMap", a, true,
                "Radar coverage maps on the command post and the tower (terrain line of sight) / Карта покрытия РЛС на КП и вышке");
        adCoverageBudget = c.getInt("coverageBudget", a, 32, 1, 1024,
                "Coverage maps: radar beams computed per tick (all maps together) / Карта покрытия: лучей за тик");
        adIff = c.getBoolean("iff", a, true,
                "Towers interrogate the aircraft their radars see near the airport (IFF) / Вышки опрашивают ответчики у аэродрома");
        adIffResponseSeconds = c.getInt("iffResponseSeconds", a, 30, 5, 600,
                "IFF: the pilot's time to answer the tower's call, s / IFF: время пилоту на ответ, с");
        adIffHandoff = c.getBoolean("iffHandoff", a, false,
                "IFF: an intruder that does not answer is handed to the air defence (off: tower warnings only) / IFF: молчащий нарушитель передаётся ПВО");
        // audit 2026-10-22: exploits and hardening
        adZoneMaxDistance = c.getInt("zoneMaxDistance", a, 1024, 0, 30000,
                "A zone may reach this far from its command post, blocks (0 = no limit) / Зона не дальше стольких блоков от своего КП");
        adMaxZonesPerPlayer = c.getInt("maxZonesPerPlayer", a, 64, 0, 10000,
                "Zones of one player's command posts together (0 = no limit) / Зон на игрока (на всех его КП)");
        // audit 2026-10-22: combat effectiveness and terrain
        adTerrainCache = c.getBoolean("terrainCache", a, true,
                "Remember the terrain of unloaded chunks near radars and launchers: an unloaded hill still hides (radars, launchers, missiles, "
                        + "coverage map) / Помнить рельеф выгруженных чанков у РЛС и ПУ: холм прячет и без игрока рядом");
        adTerrainRadius = c.getInt("terrainCacheRadius", a, 640, 0, 2048,
                "Terrain memory: how far round a radar, blocks (a launcher - its missile's reach; 0 = off) / Память рельефа: радиус вокруг РЛС, блоков");
        // code-only wave: «Опознавание: ПО КОДУ» (identification by the transponder's code alone)
        adCodeOnly = c.getBoolean("codeOnly", a, true,
                "Command posts may identify by the transponder's code alone (code-only); false - forbidden, saved code-only posts work the usual way / "
                        + "КП могут опознавать только по коду ответчика (ПО КОДУ); false - запрещено, такие КП работают как обычно");
        adCodeOnlyArmSeconds = c.getInt("codeOnlyArmSeconds", a, 60, 0, 600,
                "Code-only: switched on, it takes effect this much later, s / ПО КОДУ: включается через столько секунд");
        adCodeOnlyConfirmSeconds = c.getInt("codeOnlyConfirmSeconds", a, 5, 1, 60,
                "Code-only: a target only after two misses in a row this far apart, s / ПО КОДУ: цель после двух промахов не ближе, с");
        adCodeOnlyMinWarningSeconds = c.getInt("codeOnlyMinWarningSeconds", a, 20, 0, 60,
                "Code-only: the launchers' shortest wait to fire, the same for everybody, s / ПО КОДУ: ожидание до огня не меньше, с");
        adCodeOnlyEmergencySeconds = c.getInt("codeOnlyEmergencySeconds", a, 180, 0, 3600,
                "Code-only: 7700's grace per aircraft (all episodes, all posts of one owner), s; 0 - 7700 is never a target / "
                        + "ПО КОДУ: льгота 7700 на борт (все эпизоды, все КП владельца), с; 0 - по 7700 не стрелять никогда");
        adCodeOnlyOverlapSeconds = c.getInt("codeOnlyOverlapSeconds", a, 60, 0, 600,
                "Code-only: the last key period's code still counts this long after the change, s / ПО КОДУ: прошлый код действует после смены ключа, с");
        adCodeCardMaxPeriods = c.getInt("codeCardMaxPeriods", a, 7, 1, 72,
                "Code cards are printed for at most this many key periods (a period - 20 min) / Карта кодов действует не больше стольких периодов (период - 20 мин)");
        adCodeOnlyProbeCodes = c.getInt("codeOnlyProbeCodes", a, 4, 2, 32,
                "Code-only: this many different wrong codes within 2 min from one aircraft or pilot - the GUESSING block / "
                        + "ПО КОДУ: столько разных неверных кодов за 2 мин от борта или пилота - блок ПОДБОР");
        adCodeOnlyProbeMinutes = c.getInt("codeOnlyProbeMinutes", a, 5, 1, 120,
                "Code-only: the GUESSING block lasts, min / ПО КОДУ: блок ПОДБОР действует, мин");
        adCodeOnlyManual = c.getBoolean("codeOnlyManual", a, true,
                "Code-only: a code dialled by hand (no card) may count - the posts' option 'Manual code', on by default; false - only a card's code / "
                        + "ПО КОДУ: код, набранный вручную (без карты), может засчитываться - опция КП 'Ручной набор кода' (вкл. по умолчанию); false - только код с карты");
        adKeyClock = c.getString("keyClock", a, "total",
                "The day key's clock: total - the world's total time (/time set and sleeping change nothing), day - the time of day / "
                        + "Часы ключа дня: total - общее время мира (/time set и сон не влияют), day - время суток",
                new String[] {"total", "day"});
        adAllTransponders = c.getBoolean("allTransponders", a, true,
                "Every vehicle has a transponder, the ground ones too (false - only aircraft) / Ответчик у всей техники, и у наземной (false - только у авиации)");
        // embassy airfield: the embassy's own air defence
        adEmbassy = c.getBoolean("embassyAd", a, true,
                "The Tuners' embassies' air defence fires at machines in the air without an admitted player aboard (false - the emplacements are decor) / "
                        + "ПВО посольств Наладчиков стреляет по технике в воздухе без допущенного на борту (false - посты только декор)");
        adEmbassyWarnPerDay = c.getInt("embassyWarnPerDay", a, 1, 0, 10,
                "Embassy AD: warnings a player gets a game day at one embassy (then a re-entry is fired on at once; 0 - no warning) / "
                        + "ПВО посольства: предупреждений игроку в сутки у одного посольства (потом повторный вход - огонь сразу; 0 - без предупреждения)");
        adEmbassyWarnSeconds = c.getInt("embassyWarnSeconds", a, 10, 3, 60,
                "Embassy AD: seconds to turn away after the warning / ПВО посольства: секунд на разворот после предупреждения");
        adEmbassyCloseBlocks = c.getInt("embassyCloseBlocks", a, 32, 4, 256,
                "Embassy AD: a warned machine this much closer to the embassy than at the warning is fired on at once, blocks / "
                        + "ПВО посольства: предупреждённый борт ближе к посольству на столько блоков - огонь сразу");
        adEmbassyMinHeight = c.getInt("embassyMinHeight", a, 40, 0, 255,
                "Embassy AD: a warned machine descending below this height over the field is fired on at once, blocks / "
                        + "ПВО посольства: предупреждённый борт снижается ниже этой высоты над полем - огонь сразу");
        adEmbassyUnmanned = c.getBoolean("embassyUnmanned", a, true,
                "Embassy AD: a machine in the air with nobody aboard is a target after its owner's warning (an admitted owner - never) / "
                        + "ПВО посольства: борт без экипажа в воздухе - цель после предупреждения владельцу (допущенный владелец - никогда)");
    }

    /** Airport wave B1: the "airport" category - every feature switchable. */
    private static void loadAirport(Configuration c) {
        String a = "airport";
        aptRequirePower = c.getBoolean("requirePower", a, true,
                "ILS antennas need EU through their 'ILS Shelter' (off: always powered) / Антеннам ILS нужно питание EU через 'Аппаратную ILS'");
        aptShelterEu = c.getInt("shelterEu", a, 16, 0, 10000, "ILS shelter upkeep, EU/t / Расход аппаратной ILS, EU/т");
        aptMagVar = sanitize((float) c.getFloat("magneticVariation", a, 0F, -30F, 30F,
                "Magnetic variation, deg (east +): runway numbers use the magnetic course / Магнитное склонение, град (восточное +)"), 0F, -30F, 30F);
        aptFog = c.getInt("fog", a, 0, 0, 3, "Fog for the RVR: 0 none, 1 haze, 2 fog, 3 dense / Туман для RVR: 0 нет, 1 дымка, 2 туман, 3 густой");
        aptLegacyIlsCat = c.getInt("legacyIlsCat", a, 3, 1, 3,
                "Category of the old single ILS beacon (3 = as before, autoland) / Категория старого курсо-глиссадного маяка (3 - как раньше)");
        com.sva.vehicle.LinerIls.legacyCat = aptLegacyIlsCat;
        aptCriticalAreas = c.getBoolean("criticalAreas", a, true,
                "Blocks / vehicles in the ILS critical areas lower the category and bend the beam / Критические зоны ILS");
        aptFlightCheck = c.getBoolean("flightCheck", a, true,
                "A new ILS is CAT I until a flight inspection approach / Новый ILS - CAT I до облёта");
        aptAurals = c.getBoolean("aurals", a, true, "ILS ident Morse and marker tones in the cockpit / Морзянка ILS и тоны маркеров");
        aptWindPhysics = c.getBoolean("windPhysics", a, false, "The wind drifts the airliner in flight / Ветер сносит лайнер в полёте");
        aptPaint = c.getBoolean("paint", a, true, "Client: runway markings / Клиент: разметка полос");
        aptLights = c.getBoolean("lights", a, true, "Client: runway and approach lights / Клиент: огни полос и подхода");
        aptLightsFar = c.getBoolean("lightsFar", a, true, "Client: the lights seen beyond the fog / Клиент: огни видны сквозь туман");
        aptModels = c.getBoolean("models", a, true, "Client: 3D models of the ILS antennas and devices / Клиент: 3D-модели антенн и приборов");
        aptDrawDistance = c.getInt("drawDistance", a, 1500, 128, 4000, "Client: runway paint / lights distance, blocks / Клиент: дальность разметки и огней");
        // airport wave B2: the tower, ATIS, CPDLC, the automatic tower, follow-me
        aptAtc = c.getBoolean("atc", a, true, "Towers' services: ATIS, datalink, automatic tower, follow-me lights / Службы вышки: ATIS, CPDLC, авто-вышка");
        aptAutoTower = c.getBoolean("autoTower", a, true,
                "A tower with nobody in its controller seat runs the automatic rules / Вышка без диспетчера работает автоматически");
        aptControllerOverride = c.getBoolean("controllerOverride", a, true,
                "A player controller takes over from the automatic tower / Игрок-диспетчер заменяет авто-вышку");
        aptAutoInterval = c.getInt("autoInterval", a, 20, 5, 300,
                "Automatic tower: minimum interval between runway movements, s / Авто-вышка: интервал между движениями на полосе, с");
        aptGoAroundHeight = c.getInt("goAroundHeight", a, 25, 5, 120,
                "Automatic tower: go-around below this height over an occupied runway, blocks / Уход на второй круг ниже этой высоты, блоков");
        aptTowerEu = c.getInt("towerEu", a, 32, 0, 10000, "Control tower upkeep, EU/t / Расход диспетчерской вышки, EU/т");
        aptAdZone = c.getBoolean("adZone", a, true, "A tower keeps an air-defence 'aerodrome' zone / Вышка создаёт зону ПВО 'аэродром'");
        aptAtisChat = c.getBoolean("atisChat", a, true, "ATIS in the pilots' chat near an airport / ATIS в чат пилотам у аэродрома");
        aptTaxiAhead = c.getInt("taxiAhead", a, 10, 3, 40, "Follow-me: taxi lights lit ahead of the aircraft / Огни рулёжки впереди самолёта");
        // embassy airfield: the Tuners' embassies' fields as airports
        aptEmbassyAirports = c.getBoolean("embassyAirports", a, true,
                "The Tuners' embassies' airfields are airports: runways, ILS, tower, ATIS, taxi lights (nothing saved) / "
                        + "Лётные поля посольств - аэропорты: ВПП, ILS, вышка, ATIS, огни рулёжки (ничего не сохраняется)");
        aptEmbassyServices = c.getBoolean("embassyServices", a, true,
                "An embassy's service column opens the add-on's services: charging, hydrogen, repair, parking, towing, charts (false - the core's armour gases) / "
                        + "Колонка посольства открывает услуги аддона: зарядка, водород, ремонт, стоянка, перегон, карты (false - только газы брони ядра)");
        aptEmbassyTowing = c.getBoolean("embassyTowing", a, true,
                "An embassy moves machines left with nobody aboard on its runway / pads / taxiway / under the dome to a stand / "
                        + "Посольство перегоняет брошенную технику с ВПП, площадок, рулёжки и из-под купола на стоянку");
        aptEmbassyAtisRange = c.getInt("embassyAtisRange", a, 600, 100, 3000,
                "An embassy tower's ATIS reaches the chat only this near, blocks (or bound for it in the MCDU) / "
                        + "ATIS вышки посольства в чат только ближе, блоков (или если она - пункт назначения в МФПУ)");
        aptEmbassyTowMinutes = c.getInt("embassyTowMinutes", a, 2, 1, 60,
                "Left on an embassy's runway / pad / taxiway this long, min - towed to a stand / Оставлен на ВПП, площадке, рулёжке посольства дольше, мин - перегон на стоянку");
        aptEmbassyDomeMinutes = c.getInt("embassyDomeMinutes", a, 5, 1, 120,
                "Left under an embassy's dome this long, min - towed out / Оставлен под куполом посольства дольше, мин - перегон на отстой");
        aptEmbassyGlideAngle = sanitize((float) c.getFloat("embassyGlideAngle", a, 3F, 2.5F, 3.5F,
                "The embassies' ILS glide slope angle, deg (3.5 - the stations are CAT II only) / Угол глиссады ILS посольств, град (3,5 - станции только CAT II)"), 3F, 2.5F, 3.5F);
        aptEmbassyLinerShort = c.getBoolean("embassyLinerShort", a, false,
                "The automatic tower clears the liner to land on a short (90-block) embassy runway / Авто-вышка разрешает лайнеру посадку на короткую (90 блоков) ВПП посольства");
        aptEmbassyApronMinutes = c.getInt("embassyApronMinutes", a, 10, 1, 120,
                "Left on an embassy's apron off the stands this long, min - towed to a stand or the lot / Оставлен на перроне посольства вне МС дольше, мин - перегон на МС или отстой");
    }

    /** Wave C: the "book" category - the handbook. */
    private static void loadBook(Configuration c) {
        String b = "book";
        bookGiveOnJoin = c.getBoolean("giveOnJoin", b, true,
                "Give the handbook once on a player's first join / Выдавать справочник один раз при первом входе игрока");
        bookLiveChecklists = c.getBoolean("liveChecklists", b, true,
                "Client: checklist pages tick live in the liner's cockpit / Клиент: чек-листы книги отмечаются сами в кабине лайнера");
        bookHighlight = c.getBoolean("highlight", b, true,
                "Client: the next control pulses with an outline in the cockpit / Клиент: подсветка нужного органа управления в кабине");
        bookHologram = c.getBoolean("hologram", b, true,
                "Client: the build schematics' hologram in the world / Клиент: голограмма схем постройки в мире");
        bookTutorial = c.getBoolean("tutorial", b, true,
                "Client: the guided first flight in the liner / Клиент: режим обучения (первый полёт на лайнере)");
        bookModels = c.getBoolean("models", b, true,
                "Client: turning 3D vehicle models in the book / Клиент: вращающиеся 3D-модели техники в книге");
    }

    /** NaN / infinity -> default, else clamped into [min, max] (pure; the self-test checks it). */
    public static float sanitize(float value, float def, float min, float max) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return def;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static float num(Configuration c, String name, String cat, float def, float min, float max, String comment) {
        return sanitize(c.getFloat(name, cat, def, min, max, comment), def, min, max);
    }
}
