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
    /** Fast charging (2026-10-10): the station's «Форсаж» mode. */
    public static boolean stationBoost = true;
    /** A core (or IC2) energy storage touching the station feeds the vehicle directly. */
    public static boolean stationStorageLink = true;
    /** Several stations on one platform charge the same vehicle together (false: only one of them). */
    public static boolean stationsAddUp = true;
    /** Cargo screen «Мусор»: items thrown out next to the vehicle ("modid:name" or "modid:name@meta"). */
    public static final String[] JUNK_DEFAULT = {"minecraft:cobblestone", "minecraft:dirt", "minecraft:gravel", "minecraft:netherrack",
            "minecraft:stone"};
    public static java.util.Set<String> junk = com.sva.inventory.CargoToolsSVA.parseJunk(JUNK_DEFAULT);

    private ConfigSVA() {
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
            junk = com.sva.inventory.CargoToolsSVA.parseJunk(c.getStringList("junk", v, JUNK_DEFAULT,
                    "Cargo screen 'Junk' button: items dropped next to the vehicle (modid:name or modid:name@meta) / "
                            + "Кнопка 'Мусор' в окне багажника: что выбрасывается рядом с машиной"));
        } finally {
            if (c.hasChanged()) {
                c.save();
            }
        }
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
