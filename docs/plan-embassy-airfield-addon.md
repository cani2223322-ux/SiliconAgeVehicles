# Лётное поле посольства — техплан аддона (фазы А3 + А4 и ПВО посольства) и разбивка на пакеты (2026-10-27)

Дизайн: ядро `C:/Users/Aleksandr/Documents/MineMod/SiliconAge/docs/idea-embassy-airfield.md` (+ «Решения пользователя 2026-10-26» в конце — главнее предложения). Сводный план и **точный список работы аддона**: `SiliconAge/docs/plan-embassy-airfield.md` §3. Техплан ядра и контракт API: `SiliconAge/docs/plan-embassy-airfield-core.md` §5 и исходники `SiliconAge/src/main/java/com/sc/embassy/api/` (`EmbassyAirApiSC`, `AirfieldInfoSC`, `IEmbassyAirHookSC`, **API_VERSION = 1**; ядро — коммит df459a0).
Здесь — архитектура аддона `SiliconAgeVehicles` (`com.sva`, MODID `SiliconAgeVehicles`, 0.1.1; ветка `wave-embassy-airfield` от master 5e3788e, включая «ПО КОДУ» 0fc6397) и разбивка на 6 параллельных пакетов (**A, C, L, S, E, D**) с **непересекающимися файлами**. Номера строк ниже проверены по этому дереву после правок координатора (2026-10-27).
Java 6 (без лямбд, diamond, try-with-resources, switch по String), MCP-имена, Forge 1.7.10-10.13.4.1614. Никогда не звать `@SideOnly(CLIENT)` из общего / серверного кода; FakePlayer пакетов не получает.
Сборка:
`T=/c/Users/Aleksandr/Documents/MineMod/.build-tools; export JAVA_HOME=$T/jdk8/jdk8u504-b01 PATH=$T/jdk8/jdk8u504-b01/bin:$T/gradle/gradle-2.14/bin:$PATH; cd C:/Users/Aleksandr/Documents/MineMod/SAV-wt-emb; gradle clean build --offline -q`
Строки «Read timed out» / 404 безвредны; runServer не запускать; сборка — всегда clean build; аддон собирается против `../SiliconAge/build/classes/main`. Проверки: `unzip -p build/libs/SiliconAgeVehicles-0.1.1.jar com/sva/block/AirfieldSVA.class | grep -a -c func_147439_a` = 1; в jar нет `com/sc/`; `com/sc/embassy/api` упоминают только `com/sva/compat/EmbassyCompatSVA*.class`. Каждый пакет обязан собираться сам по себе поверх общего ядра.

**Решения пользователя (кратко, обязательны):** поле только с аддоном (AUTO), старые посольства не трогаются; ВПП FULL 200 × 9 (пороги смещены на 20, посадочная дистанция 181), SHORT 90 × 7 + „Н“, HELI; ИКАО X + две буквы элемента + буква номера, X закрыт для новых вышек игроков; купол без стены и штрафов; **у посольства своё ПВО** (огонь, снаряды, радио — аддон; посты, зона, допуск, журналы — ядро); услуги — таблица §7 предложения; новых жителей нет; памятник, светомаяк, ATIS, «Лётная книжка». Решения координатора для этого прогона (a–f, все — ключи конфига): одно предупреждение на игрока на посольство в сутки (журнал ядра), 10 с на разворот; сближение > 32 или снижение ниже 40 над полем за это время — огонь сразу; беспилотный борт в воздухе — цель после предупреждения владельцу, если владелец не допущен; коды ответчика / «ПО КОДУ» игроков для ПВО посольства роли не играют; пушки — существующие снаряды аддона с позиций постов ядра; `airdefense.embassyAd` выключает ПВО посольства.

## 0. Общее ядро аддона (написано координатором, собирается)

Правит **только координатор**. Нужна правка такого файла — описать её в отчёте (файл, место, текст). Заготовки принадлежат пакетам, указанным в их Javadoc: сигнатуры, имена, ключи NBT, коды и числа в них фиксированы, тела — пакетов.

| Файл (`com/sva/…`) | Что |
|---|---|
| `compat/EmbassyCompatSVA.java` | **Единственный** класс аддона (с вложенным `Hook`), который называет `com.sc.embassy.api.*`. `init()` (CommonProxySVA.init, обе стороны): `Class.forName("com.sc.embassy.api.EmbassyAirApiSC")` в try → `API_VERSION == 1` → `registerHook(Hook.make())`; иначе `on()` = false и всё «посольское» молчит (пусто / null / false / −1 / 0), причина — `why()`. Каждый вызов — за `on()` и `catch (LinkageError)` (выключает). Без типов API в полях и сигнатурах (верификатор старого ядра не грузит их): инфо ядра хранится как `Object` в `EmbassyFieldSVA.handle`. Зеркала констант API (KIND, SPOT_*, PAY_*, PASS_*, AD_*, SERVICE_KEYS, уровни ReputationSC, меты огней 5–8, TAB_ROWS); `fields/field/fieldAt/domeAt/neutralAt/isRunwaySurface/isEmbassyBlock/revision/inZone/neutral/ownerOf`, `levelOf/knows/hasTalon`, `quote/pay/firstLanding`, `adRadius/warnSeconds/adZoneAt/admitted/escortOf/requestPass/passUntil/requestWarn/warnUntil/holdFire/adLine/tell`, `debugApiConstants/mirrorConstants/debugHookIsOurs`. Крюк: `openService/serviceRows/tabAction` → `block.EmbassyServicesSVA`, `atisLine` → `airport.EmbassyAtcSVA`, `airfieldsChanged` → только `EmbassyAirportSVA.markDirty` (зовётся из генерации); исключения тел ловятся и пишутся в лог |
| `compat/EmbassyFieldSVA.java` | Неизменяемая копия `AirfieldInfoSC` в типах аддона (все числа, ящики, точки, огни по метам, МС, колонки, посты ПВО; массивы — копии); чистые `inDome`, `inAdCircle` (голый круг), `fromCore`, `onRunway`, `spotAt`, `in(box, x, z)`. Форма зоны и нейтрального неба — `EmbassyCompatSVA.inZone / neutral(f, x, z)` |
| `vehicle/RunwaySurfaceSVA.java` | **Одна точка признака полосы**: блок ВПП аддона (любая мета) или `EmbassyCompatSVA.isRunwaySurface` (меты 0, 3, 5, 6, 7 `embassyAirfieldSC`); `is(block, meta)`, `is(world, x, y, z)` (только загруженные), `embassy(world, x, y, z)` (полоса посольства, не своя) |
| `airdefense/EmbassyAdMath.java` | **Чистые правила ПВО посольства, написаны целиком** (§3.10): `Look`, `verdict` (A_NONE / HOLD / WARN / TURN / FIRE), `why` (TIME / CLOSE / DESCEND / REENTRY), `earliest`, `turnEnd`, `extraWarning`, `noWarnings`, `ownerHolds`, `passLine`, `pickPost` |
| Правки в общих файлах (готово) | см. §0.1 |
| Заготовки A | `airport/EmbassyAirportSVA.java` (реестр виртуальных ключей `isVirtual / embassyOf / markVirtual / virtualKeys` — написан; `markDirty`, `tick`, `clear`, `knownBy`, `hiddenKeys`, `towerKey`, `fieldOf` — тела A), `airport/EmbassyAirportMath.java` (первые тела: `key`, `playerIcaoAllowed`, `uniqueIcao`, `locIdent`, `locFreq`, `endElev`) |
| Заготовки C | `airport/EmbassyAtcSVA.java` (`atisLine`, `radio` — пустые), `airport/EmbassyAtcMath.java` (первые тела: `CODE_LO/HI`, `atisToChat`, `landingRefusal`, `domeRefused`, `holdTaxi`) |
| Заготовки L | `airport/EmbassyLightsMath.java` (первые тела: `lights(...)` без кромочных и торцевых, `papiWhites`) |
| Заготовки S | `block/EmbassyServicesSVA.java` (`openService`, `serviceRows`, `tabAction`, `command`, `onTick`, `clear` — пустые; на шине FML), `block/EmbassyParkingDataSVA.java` (**данные мира, модель и NBT написаны**), `vehicle/EmbassyServiceMath.java` (первые тела), `client/GuiEmbassyServiceSVA.java` (`receive` написан: открытие в потоке клиента через `Minecraft.func_152344_a`) |
| Заготовки E | `airdefense/EmbassyAdSVA.java` (`tick`, `clear`, `debugTrack`, `debugLook`; `Track` — поля памяти борта) |
| Заготовки D | `debug/SelfTestEmbassySVA.java` (`run`), `debug/WorldTestEmbassySVA.java` (`START` 3400, `done`, `tick`, `abort`) |

### 0.1 Правки координатора в общих файлах (готово, остальным их не трогать)
- `ReferenceSVA.CORE_DEP` **не поднят** (`[0.2.0,)`, комментарий): API ищется по имени, старое ядро — поле посольств просто выключено; поднять до версии релиза ядра с API при выпуске.
- `SVAMod.serverStopped`: `EmbassyAirportSVA.INSTANCE.clear()`, `EmbassyAdSVA.INSTANCE.clear()`, `EmbassyServicesSVA.INSTANCE.clear()`, `EmbassyCompatSVA.clear()`.
- `proxy/CommonProxySVA.init`: `EmbassyCompatSVA.init()`; `EmbassyServicesSVA.INSTANCE` на шине FML.
- `ConfigSVA`: ключи §2.4 (поля + загрузка в `loadAirDefense` / `loadAirport`).
- `net/NetAptSVA`: Cmd `EMB = 4` (x — id посольства, y — колонка, −1 из вкладки; маршрут до проверки дистанции → `EmbassyServicesSVA.command(p, x, y, data)`), Data `D_EMB = 4`. Новых каналов и дискриминаторов нет.
- `client/AirportClientSVA.receive`: `D_EMB` → `GuiEmbassyServiceSVA.receive(t)`.
- `block/AirportServerSVA.onTick`: `EmbassyAirportSVA.INSTANCE.tick(w, d, now)` перед `d.tick`.
- `airdefense/AirDefenseSVA.onTick`: `EmbassyAdSVA.INSTANCE.tick(ticks)` после `moveShots()`.
- Признак полосы (§3.2 сводного плана): `block/AirfieldSVA.java:35` (вид `RunwayMath` — `RunwaySurfaceSVA.is`), `entity/EntityVehicleSVA.java:1443–1452` (`runway()`), `:2631` (`onRunway()`), `client/GuiMapSVA.java:1105–1117` — заменены. Остальные два места (`RunwayLightsSVA`, `AirportRenderSVA`) — пакет L.
- Перечисления (только в конец): `vehicle/NavDb.SRC_EMBASSY = 6` (+ `SRC_KEYS` "embassy", `Airport.embassy` id, не сохраняется); `airport/Cpdlc.U_REFUSED = 15`, `U_DOME = 16`, `U_EMB_AD = 17`, `U_TYPES = 18` (ответа не требуют — `needsResponse`; `args`: U_REFUSED → `#sva.cpdlc.refused.<enemy|liner>`, U_EMB_AD → {позывной, `#sva.cpdlc.embAd.<AD_KEYS>`}); `vehicle/SpaceMath.EMBASSY = 12` (прыжок на поле — только на „Н“).
- `airport/TowerMath`: `Site.emb` (id посольства виртуальной вышки, −1 — игрок), `assign(sites, rws, rwyEmb)` — правило в обе стороны (старый `assign(sites, rws)` = все полосы игроков).
- `debug/SelfTestSVA.run`: `SelfTestEmbassySVA.run()` последним; `debug/WorldTestSVA`: поле `embassy`, блок вызова после `adCodeMore`, `embassy.done` в `maybeSummary`.

### 0.2 Что уже работает с одним общим ядром
Сборка зелёная, reobf верный, `com/sc/` в jar нет, API зовёт только `EmbassyCompatSVA`. С ядром df459a0: `on()` = true, крюк зарегистрирован (пока колонка отдаёт клик ядру: `openService` = false → газы брони); признак полосы уже видит ВПП посольства (ЗАХВ, HSI, разбег ×0,5, мягкие тормоза, карта техники). Виртуального аэропорта, ПВО посольства, услуг ещё нет (тела пакетов).

## 1. Регистрация и порядок

| Этап | Что | Где |
|---|---|---|
| preInit | `ConfigSVA.load` (новые ключи §2.4), `NetAptSVA.init` (те же дискриминаторы 0/1) | координатор |
| init | `EmbassyCompatSVA.init()` (обе стороны): API по имени, версия, крюк; `EmbassyServicesSVA` на шине FML | координатор |
| сервер, каждый тик | `AirportServerSVA.onTick` → `EmbassyAirportSVA.tick` (перестроить виртуальные записи при смене ревизии / пометке, отчёт вышек, знание игроков) → `AirportDataSVA.tick` → `AtcServerSVA.tick` | A, C |
| сервер, каждый тик | `AirDefenseSVA.onTick` → `moveShots` → `EmbassyAdSVA.tick` (работа раз в `LOOK_TICKS` 5) | E |
| сервер, каждые 20 тиков | `EmbassyServicesSVA.onTick`: стоянки, перегон, «Лётная книжка» | S |
| ядро → аддон | клик по колонке (не крадучись) → `Hook.openService`; вкладка Диспетчера → `serviceRows`, `atisLine`, `tabAction`; генерация → `airfieldsChanged` (только пометка) | S, C, A |
| `serverStopped` | очистка памяти (§0.1) | координатор |

## 2. Общие решения

### 2.1 Граница с ядром
- Только `EmbassyCompatSVA` называет `com.sc.embassy.api.*`. Исключение для отладки: `debug/WorldTestEmbassySVA` может звать **тестовые** помощники ядра (`EmbassySiteSC`, `EmbassyAirfieldSC.decideFor`, `EmbassySC.mapGen(w).startAt`, `SubstrateConfigSC.setEmbassyAirfield`, `SettlementRegistrySC` для уровня «Доверия» тестовых игроков) — он грузится только с `-Dsva.worldtest`; без API ядра — SKIP. `debug/SelfTestEmbassySVA` может читать `com.sc.embassy.EmbassyAirRulesSC` для сверки зеркал (только при `on()`).
- Аддон **не ставит в посольство ни одного своего блока** (охрана поля не даст, «Достройка» откатила бы): аэропорт, ILS, огни, вышка, стоянки — всё виртуальное из данных ядра.
- Ядро не правится в этом прогоне (API хватает; см. «CORE CHANGES: нет» в отчёте).

### 2.2 Данные и NBT (только дописываются)
| Что | Где | Ключи | Владелец |
|---|---|---|---|
| Виртуальные записи аэропорта (торцы, курсовой, глиссадный, колдун, огни рулёжки, вышка) | `AirportDataSVA` (память) | обычный `dim:x:y:z` по точкам API; в реестре `EmbassyAirportSVA.VIRTUAL` (ключ → id посольства); **в NBT не пишутся**: `AirportDataSVA.writeToNBT` и `IlsDataSVA.writeToNBT` пропускают `isVirtual(key)`; `checks` (облёт) виртуальных тоже | A |
| Станции ILS посольств | `IlsDataSVA` (память) | ключ курсового; не сохраняются | A |
| Стоянки и «Отстой» | `block/EmbassyParkingDataSVA` (WorldSavedData `SiliconAgeVehicles_embassy`, карта мира Верхнего мира) | `Stands` [{E id, S место, V vehId, O UUID владельца, T с (тики мира), D с (игровые сутки), P оплачено по (сутки, −1)}], `Lot` [{E, V, O, T}] | S |
| Память ПВО посольства по борту | `EmbassyAdSVA.tracks` (память, по `AirDefenseSVA.vehKey`) | — (журналы предупреждений и 7700 — ядра по UUID) | E |
| Знание посольств игроками | `EmbassyAirportSVA.known` (память, UUID → id) | — | A |
| Таймеры перегона, последнее «в воздухе» | `EmbassyServicesSVA` (память, по vehId) | — | S |
| `Settlement.extra`, `LocalRec`, `PlayerRec` | **аддон не пишет** (это ядро: «X») | — | — |

### 2.3 Сеть, GUI, реестры
- Каналы и дискриминаторы — прежние. `NetAptSVA`: Cmd `EMB` 4 → сервер (`EmbassyServicesSVA.command`: Op `refresh/buy/park/tow/chart/close`, E — entity id машины, K — ключ услуги, A — количество), Data `D_EMB` 4 → клиент (снимок окна, `Open` — открыть). Данные аэропорта (`D_SYNC`) — те же, но **по игроку** (A): посольства, которых игрок не знает, вырезаны; поля для L: у записи ВПП посольства `Emb` (id), `Ext` (блоков покрытия за порогом: FULL 20, SHORT 1), `Ils` (бит 1 — ILS над A-торцом, 2 — над B-торцом); в корне `EmbSock` int[] {x, y, z…} колдунов известных посольств, `EmbPapi` int[] {x, y, z, курс×10, угол×10…} (точка PAPI каждого торца с полосой).
- HUD-строки ПВО посольства — существующий `NetADSVA.D_MSG` (K, A, C).
- **GUI-id не нужен**: окно услуг — `GuiScreen` без контейнера, открывается по `D_EMB` (колонка — блок ядра). Свободны id 9+ (`GuiHandlerSVA`: 0–8 заняты).
- Блоков, мет, предметов, сущностей, кусков структур (`MapGenStructureIO`), рецептов — **новых нет**; подсказки TooltipSC и NEI не нужны (ничего нового видимого в инвентаре; карта подхода — существующий предмет).

### 2.4 Конфиг (`config/SiliconAgeVehicles.cfg`)
| Категория.ключ | По умолчанию | Поле | Что |
|---|---|---|---|
| `airport.embassyAirports` | true | `aptEmbassyAirports` | поля посольств — аэропорты (ВПП, ILS, вышка, ATIS, огни рулёжки), ничего не сохраняется |
| `airport.embassyServices` | true | `aptEmbassyServices` | колонка открывает окно аддона (false — газы брони ядра) |
| `airport.embassyTowing` | true | `aptEmbassyTowing` | перегон брошенной техники |
| `airport.embassyAtisRange` | 600 (100…3000) | `aptEmbassyAtisRange` | ATIS вышки посольства в чат ближе (или пункт назначения в МФПУ). TODO(balance) |
| `airport.embassyTowMinutes` | 2 (1…60) | `aptEmbassyTowMinutes` | на ВПП / площадке / рулёжке без седока дольше — перегон. TODO(balance) |
| `airport.embassyDomeMinutes` | 5 (1…120) | `aptEmbassyDomeMinutes` | под куполом дольше — на «Отстой». TODO(balance) |
| `airport.embassyApronMinutes` | 10 (1…120) | `aptEmbassyApronMinutes` | ревью 2026-10-27: на перроне вне МС без седока дольше — перегон на МС / «Отстой» (на чужом или неподходящем МС — через `embassyTowMinutes`). TODO(balance) |
| `airport.embassyGlideAngle` | 3,0 (2,5…3,5) | `aptEmbassyGlideAngle` | угол виртуальных глиссад. TODO(balance): 3,5 при деревьях в створе |
| `airport.embassyLinerShort` | false | `aptEmbassyLinerShort` | авто-вышка разрешает лайнеру посадку на SHORT (замер §4: не помещается без MAX) |
| `airdefense.embassyAd` | true | `adEmbassy` | ПВО посольства стреляет (false — посты декор) |
| `airdefense.embassyWarnPerDay` | 1 (0…10) | `adEmbassyWarnPerDay` | предупреждений игроку в сутки у одного посольства (1 — журнал ядра; >1 — сверх него счёт аддона в памяти; 0 — огонь сразу). TODO(balance) |
| `airdefense.embassyWarnSeconds` | 10 (3…60) | `adEmbassyWarnSeconds` | секунд на разворот. TODO(balance) |
| `airdefense.embassyCloseBlocks` | 32 (4…256) | `adEmbassyCloseBlocks` | сближение за разворот — огонь сразу. TODO(balance) |
| `airdefense.embassyMinHeight` | 40 (0…255) | `adEmbassyMinHeight` | снижение ниже над полем (и ниже, чем при предупреждении, −2) — огонь сразу. TODO(balance) |
| `airdefense.embassyUnmanned` | true | `adEmbassyUnmanned` | беспилотный борт в воздухе — цель после предупреждения недопущенному владельцу |
ПВО посольства работает при `airdefense.enabled` (общий выключатель) и `embassyAd`; **не** зависит от `mode` (warnings/fire игроков), `requirePvp`, `fireOnUnmanned`, `minDistanceFromSpawn`, `fireInProtectedRegions` (это правила ПВО игроков); урон × `damageMultiplier`; журнал — `launchLog`.

### 2.5 Числа (из предложения и ядра; TODO(balance) все, где не сказано иное)
ВПП FULL a = −100…+100 (201), ширина 9, пороги a = ∓80, посадочная дистанция 181; SHORT 91 × 7, порог на 1 внутрь, 91. Курсовой — на оси в 15 за дальним торцом; глиссадный — в 30 за порогом, в 15 от оси со стороны купола; угол 3,0°. Виртуальная ВПП аддона — между рядами пороговых огней (FULL 160 между центрами, SHORT 88). Зона ПВО — круг 256 от ядра, любая высота; 10 с на разворот; пропуск 7700 — 60 с, раз в сутки на игрока на посольство; прикрытие звеном — 64 (ядро). Коды ответчика вышек посольств 5100–5177 (восьм.). ATIS — 600. Перегон — 2 мин (ВПП, площадки, рулёжка), 5 мин (купол). Стоянка: 2 МС на игрока в посольстве, первые сутки 0, далее 1 КЧ/сутки, 0 при талоне. Цены, уровни, потолки — ядро (`quote`/`pay`): park 1/сутки («Чужак»+), emergency 1 (≤ 25% бака и буфера, раз в сутки, «Нарушитель»…«Чужак»), charge 1 за 2 млн EU (16 млн за услугу, 32 млн в сутки), hydrogen 1 за 8 000 мБ (48 000 / 64 000), repair 1 за 10% износа (60% за услугу), electrolyte 2/шт, tow 1 (первый за сутки 0), chart 1 (первая 0); газы брони — ядро. Пушки посольства — как зенитка аддона: `GUN_RANGE` 110, `GUN_PERIOD` 10 тиков, `GUN_BURST` 3 × `SHELL_DAMAGE` 10, `GUN_SPEED` 4, `SHELL_LIFE` 40.

## 3. Пакеты — что делать (подробно)

### 3.1 Пакет A — «Полоса и виртуальный аэропорт» (А3: сводный §3.3, §3.7)
- `airport/EmbassyAirportSVA` (тела): `tick(w, d, now)` — только Верхний мир, `ConfigSVA.aptEmbassyAirports`, `EmbassyCompatSVA.on()`; перестройка при `dirty` или `revision(w) != builtAt` (и при первом тике мира): удалить старые виртуальные ключи (`d.remove`), для каждого `fields(w)`:
  - торцы `IlsPairing.End` в `thresholdKey(low/high)` (центр блока +0,5; `elev` = `EmbassyAirportMath.endElev(floorY)`; `facing` = `headingLow` у низкого торца (смотрит вдоль полосы), `headingHigh` у высокого; `width`; `lights` = `RunwayLayout.LIGHTS_TOWER`; `als` = 1 только у FULL-торцов с ILS) — `d.putEnd`, `markVirtual`;
  - вышка `Tower`: ключ `towerDesk()` (SHORT / HELI — последняя `columns()`), `owner` = `ownerOf(id)`, `icao` = `uniqueIcao(info.icao, d.icaosExcept(key))`, `name` = "" (клиенты показывают ИКАО), `codeLo/Hi` = `EmbassyAtcMath.CODE_LO/HI`; `putTower`;
  - FULL, торцы с `ilsLow` / `ilsHigh`: `LocAnt` в `localizer(low)` (`ident` = `locIdent(icao)`, `freq` = `locFreq(id, low)`, `intact/monitor/power` = true), `GsAnt` в `glideSlope(low)` (`angle` = `aptEmbassyGlideAngle`);
  - колдун `Device` K_SOCK в `windsock()`; огни рулёжки: K_TAXI по оси `taxiBox()` через 6 (на тех же a, что лампы ядра `lights(LIGHT_TAXI)`), K_STOP в точке ожидания (стык рулёжки и площадки +a торца, 2 блока от края площадки), K_STAND в центре каждого `spotBox`.
  - Отчёт вышек: раз в `REPORT_EVERY` 20 — `d.towerReport(key, true, "", now)`, пока в Верхнем мире игрок ближе `TowerMath.ATC_RANGE`.
  - Знание: раз в `KNOW_TICKS` 100 для каждого игрока Верхнего мира — `knows(p, id)` по всем полям; изменилось — `AirportServerSVA.syncPlayer(p)` и сброс его кэша NavDb.
- `block/AirportDataSVA`: `rebuild` (стр. 646) — имена ВПП посольств из `numberLow/High` (истинный курс, без `aptMagVar`); `applyTowers` (стр. 737, `TowerMath.assign` стр. 745) — `Site(dim, x, z, embassyOf(t.key))` и `rwyEmb[i]` = `embassyOf(r.a.key)` → `assign(sites, runways, rwyEmb)`; `status` (стр. 817) — у виртуальных антенн `intact/monitor/power` = true, `calibCat` = 3 (без облёта), `u.towerOff` как у игрока; `inspections` (стр. 1007) — виртуальные пропустить; `obstacle` (стр. 959) — `EmbassyCompatSVA.isEmbassyBlock(b)` не препятствие; `clientData(w, dim, Set<String> hidden)` (стр. 1245; старая подпись = пустой набор) — без записей скрытых посольств, плюс поля `Emb`, `Ext`, `Ils`, `EmbSock`, `EmbPapi` (§2.3); `writeToNBT` (стр. 1417) — пропуск `isVirtual` во всех списках и в `Checks`.
- `block/IlsDataSVA.writeToNBT` (стр. 107) — пропуск станций с `isVirtual(key)`.
- `block/AirportServerSVA`: `syncDim` (стр. 112) — копия **по игроку** (`clientData(w, dim, EmbassyAirportSVA.hiddenKeys(p))`, одинаковые наборы — одна копия); `syncPlayer` — то же; публичный `resync(EntityPlayerMP)` для `EmbassyAirportSVA`.
- `block/NavDataSVA.db(w, dim, base, viewers)` (новая перегрузка; старая = все известные никому — без посольств): записи посольств (вышка `SRC_EMBASSY`, `Airport.embassy` = id; станции ILS и точки входа посольства) — только если хоть один из `viewers` знает посольство (`EmbassyAirportSVA.knownBy`); кэш по подписи набора известных, `CACHE_TICKS`.
- `entity/LinerLogicSVA.mcduEnv` (стр. 328): `nd.db(..., v.riders() игроки)`.
- `airport/TowerMath`: `assign` в обе стороны (готово); `playerIcaoAllowed` — через `EmbassyAirportMath`.
- `block/TowerScreenSVA` (стр. 502): ввод ИКАО на X — отказ (`sva.tower.msg.icaoX`); `block/TileEntityTowerSVA` (стр. 228): новая вышка не берёт X (её код игрока на X, уже сохранённый, не трогать — посольство возьмёт следующий свободный).
- `vehicle/NavDb`: фильтр-помощник `without(Set<Integer> hiddenEmbassies)` (копия без SRC_EMBASSY чужих, их ILS и точек).
- Тесты (присылает D): `TowerMath.assign` в обе стороны, `EmbassyAirportMath.*`, ни одного виртуального ключа в NBT обоих данных после `writeToNBT`, станции FULL CAT III у торцов с ILS (по `IlsCat`), фильтр NavDb.
- Lang (присылает D): `sva.mcdu.src.embassy`, `sva.tower.msg.icaoX`.

### 3.2 Пакет C — «Связь посольства: ATIS, CPDLC, авто-вышка, коды» (А3: сводный §3.4, зоны §3.6)
- `block/AtcServerSVA`: ATIS в чат (`sessions`, стр. 526; `tellAtis` 578) у вышки посольства — только `EmbassyAtcMath.atisToChat(dist до центра аэропорта, ИКАО = пункт назначения МФПУ лайнера, aptEmbassyAtisRange)`; у вышек игроков — как было. «Враг» (`levelOf(pilot, id) == ENEMY`, пилот — седок-игрок у руля) — на запрос посадки / руления / взлёта `U_REFUSED` "enemy" (7700 — всем); посадка над куполом (`f.inDome`, не на финале своей ВПП) — `U_DOME`; лайнер на SHORT (`!aptEmbassyLinerShort`) — `U_REFUSED` "liner". Коды: `offerSquawk` (стр. 730) у вышки посольства — без `mayAssign` (всем, кроме «Врага»), диапазон 5100–5177; `AirportCodeMath.friendOf` не трогать (ПВО игроков кодам посольств не доверяет). `zone()` (стр. 405) — **для вышек посольств не публиковать** зону в `AirDefenseDataSVA` (иначе она сохранилась бы в NBT и всем клиентам выдала бы неизвестные посольства); `IffServerSVA.zoneOf(t)` по-прежнему считает «Аэродром» для кодов. Предупреждение над куполом — серверное: борт в воздухе входит в цилиндр купола → `AirDefenseSVA.tellPilot(v, "embDome"+id, 600, …, "sva.emb.dome.warn", icao)`. Помощник `embassyRadio(w, v, id, adLine, callsign)`: `U_EMB_AD` в журнал сеанса борта (`arg` — строка AD_*, `s` — позывной; ответа не требуется) — вызывается из `EmbassyAtcSVA.radio`.
- `airport/AutoTower`: «занимают» ВПП ещё игроки пешком и враждебные мобы на **физической** полосе посольства (`Params.blocked` — сервер передаёт); занятость борта — по физической полосе (`Rwy.ext` — блоков за порогами, FULL 20); руление по «А» за +a торцом держится (`EmbassyAtcMath.holdTaxi`), пока идёт посадка над высоким торцом (риск 2 ядра).
- `airport/AtisMath`: примечание ATIS посольства («ПОСАДКА В КУПОЛЕ ЗАПРЕЩЕНА», на SHORT — «ЛАЙНЕР НЕ ПРИНИМАЕТСЯ»).
- `block/IffServerSVA`: вышки посольств IFF-вызовов и передачи цели ПВО не делают (проверить, пропустить явно).
- `airport/Cpdlc`: константы и `args` готовы; при необходимости — только тексты-аргументы.
- `airport/EmbassyAtcSVA` (тела): `atisLine(w, f)` — ATIS вышки посольства (`atisChat(t)`), `radio(...)`.
- Тесты: `EmbassyAtcMath.*`, `AutoTower` с `blocked` / `ext` / `holdTaxi`, ATIS посольства не уходит в чат на 700 и уходит на 500 (мировой).
- Lang: `sva.cpdlc.u.15/16/17`, `sva.cpdlc.refused.enemy/liner`, `sva.cpdlc.embAd.warn/fire/pass/passUsed/passOver/left`, `sva.emb.dome.warn`, `sva.atis.embDome`, `sva.atis.embShort` (или как назовёт C).

### 3.3 Пакет L — «Огни и вид посольских полос» (клиент, сводный §3.2 вид, §3.5)
- `client/RunwayLightsSVA` (стр. 62, 109): найденная `RunwayMath` полоса на покрытии посольства (`RunwaySurfaceSVA.embassy` под серединой) — **ничего** не рисовать (ни старой «зебры», ни огней): разметка и кромки у ядра в блоках.
- `client/AirportRenderSVA`: разметка уже только над блоками аддона (стр. 154) — для ВПП с `Emb` не строить список вовсе; огни (стр. 174) — `EmbassyLightsMath.lights(len, width, dir, alsA, alsB, ext)` вместо `RunwayLayout.lights`: осевые (и над смещённой частью), зона приземления, ALS с бегущим огнём, пороговые «крылья», **PAPI** (4 огня у `EmbPapi`, цвет по углу зрителя — `papiWhites`), 5 ступеней и АВТО (`AirportClientSVA.step/alsStep`, режим вышки). Конус колдуна по ветру у `EmbSock` (как у устройства K_SOCK).
- `client/AirportClientSVA`: разбор `Emb`, `Ext`, `Ils`, `EmbSock`, `EmbPapi` (ветка `D_EMB` — координатора, не трогать).
- `airport/RunwayLayout`: только если нужен признак вида огня.
- Тесты: `EmbassyLightsMath.lights` — нет кромочных и красных торцевых, осевые есть, ALS только с `als`; `papiWhites` 0…4.
- Lang: нет (если появятся — прислать D).

### 3.4 Пакет S — «Услуги, стоянка, перегон, «Лётная книжка», прыжок» (А4: сводный §3.8, §3.9)
- `block/EmbassyServicesSVA` (тела):
  - `openService(p, f, column)`: `aptEmbassyServices` → снимок `D_EMB` (`Open`): посольство (`f.name()` JSON), ИКАО, колонка, уровень (`levelOf`), машины игрока в `SERVE_REACH` 16 от колонки и на МС этого посольства (тип, борт, заряд / буфер, H2 / бак, износ, здоровье АКБ, место), цены (`quote` на предлагаемое количество; −PAY_* — отказ со строкой ядра `sc.airfield.pay.<key>`), его стоянки и долг → true. «Враг» — окно с отказом. FakePlayer — никогда (ядро и так).
  - `command(p, id, column, data)`: игрок ещё у этого посольства (`fieldAt` / `inZone` его столбца или ≤ 12 от колонки), машина — его (`canManage`) и в досягаемости; сначала `pay(p, id, key, amount, free)` == PAY_OK, потом дело: **charge** — буфер и АКБ (`BatteryMath`, EU не больше `PER_SERVICE` и оставшегося на сутки: `quote` с уменьшением при PAY_LIMIT), **hydrogen** — бак (`FuelMath`), **repair** — машина в `hangarBox()`, 10% износа за единицу, ≤ 60% за услугу (`GarageMath`), **electrolyte** — «Обслужить АКБ» как в гараже (`BatteryMath.garageService`), **emergency** — «Чужак» / «Нарушитель», ≤ 25% бака и буфера (`EmbassyServiceMath.emergencyAmount`), **park** — оплата долга стоянки (сутки), **tow** — свою машину с «Отстоя» / другого МС на свободное МС своего вида, **chart** — карта подхода ILS посольства (`TowerScreenSVA.chart(w, d, tower, approach)`) на листе бумаги игрока (как у вышки: нет бумаги — отказ). Отказы ядра — его строкой; после — новый снимок.
  - `serviceRows(p, f)` (≤ 8): его машины на МС («МС-2: „Ястреб“ B-12, оплачено по 3-й день»), долг, цены зарядки / водорода сегодня, вышка («XTAA: в сети, ВПП 36, ILS CAT III»). `tabAction(p, f, "service")` — то же окно без колонки (`column` −1).
  - `onTick` (раз в `TICK_EVERY` 20, Верхний мир): по загруженным `EntityVehicleSVA` у полей — (1) **стоянка**: машина стоит (`STOPPED`) на `spotBox` своего вида (`EmbassyServiceMath.fits`) → `EmbassyParkingDataSVA.take` за её владельцем, если у него < 2 МС здесь; больше — сообщение и (без седока) на «Отстой»; ушла / улетела — `release`; долг стоянки (`parkDaysDue`, талон — `hasTalon` при онлайне владельца продлевает оплату) больше суток — на «Отстой»; (2) **перегон** (`aptEmbassyTowing`): без седока на ВПП / площадке / рулёжке дольше `aptEmbassyTowMinutes` — на свободное МС своего вида, нет — на «Отстой»; в куполе дольше `aptEmbassyDomeMinutes` — на «Отстой»; машина не исчезает (только перемещается: позиция, `syncNow`; строка владельцу, если в сети); (3) **«Лётная книжка»**: машина, бывшая в воздухе ≤ `LANDED_WINDOW` назад, остановилась на МС / „Н“ с отвечающим ответчиком (`AirDefenseSVA.xpdrOn`) → `firstLanding(пилот, id)` (ядро даёт один раз).
  - «Отстой»: FULL — свободные клетки `apronBox()` вне всех `spotBox`, ряд вдоль стороны Аэровокзала через 8; SHORT / HELI — вдоль „Н“ с внешней от купола стороны в зоне поля (`inZone`); занято (машина ближе 3) — следующее место; места нет — сам „Н“.
- `block/EmbassyParkingDataSVA` — данные (готово; помощники — по нужде).
- `vehicle/EmbassyServiceMath` — уточнять тела (сигнатуры фиксированы).
- `client/GuiEmbassyServiceSVA` — окно: список машин, по машине — кнопки услуг с ценой («1 КЧ», «бесплатно», отказ серым с причиной), стоянка / перегон / карта, «Обновить», «Закрыть»; тон и цвета — как `GuiTowerSVA`; отправка `NetAptSVA.cmd(EMB, id, column, 0, data)`.
- `entity/SpaceLogicSVA.destination` (стр. 414): цель прыжка в зоне поля посольства (`EmbassyCompatSVA.fieldAt`) вне `helipadBox()` — `SpaceMath.EMBASSY`; на „Н“ — можно.
- Тесты: `EmbassyServiceMath.*` (вид МС, `fits`, лимит 2, дни стоянки, 25%, таймеры, `offer`, зеркала = ядро), мировой — стоянка, перегон, оплата, книжка.
- Lang: `sva.emb.gui.*` (заголовок, колонки, услуги, «бесплатно», «нет бумаги», «только в ангаре», кнопки), `sva.emb.msg.*` (на МС, лимит 2, долг, перегнан на МС / «Отстой», обслужено, прыжок), `sva.emb.tab.*`, `sva.msg.jump.no.12`, `sva.hud.jump.why.12`.

### 3.5 Пакет E — «ПВО посольства и нейтральное небо» (сводный §3.6, §3.10)
- `airdefense/EmbassyAdSVA` (тела): раз в 5 тиков, `adEnabled && adEmbassy && on()`, Верхний мир; для полей с игроком ближе `adRadius + 64`: машины в `inAdCircle` → `Look` (`airborneForAtc`; `adZoneAt(w, x, z) == f.id`; `riders()` игроки → `holdFire` любого; без седока — `ownerHolds(adEmbassyUnmanned, владелец в сети, admitted(владелец))`; `dist` = `fromCore`, `h` = y − floorY) → `EmbassyAdMath.verdict`:
  - A_WARN: каждому на борту `requestWarn`, конец — `turnEnd(earliest(…), now, adEmbassyWarnSeconds)` (сверх журнала ядра — `extraWarning` по счёту в памяти на UUID|id|сутки); новое — `EmbassyCompatSVA.tell(AD_WARN, позывной `AtcServerSVA.callsign(v)`)` каждому на борту + HUD `sva.emb.ad.hud.warn` (секунды) + радио `EmbassyAtcSVA.radio`; беспилотный — владельцу в сети (не в сети — секунды от первого взгляда); `perDay` 0 — сразу огонь;
  - A_FIRE: один раз за заход `AD_FIRE` (+ HUD, радио); пост — `pickPost` по `adPosts()` (пушка +0,5 / +1,5 вверх), линия — `AirDefenseSVA.losClear` от пушки к точке прицела `BallisticsMath.gunAim` (упреждение по `AirDefenseSVA.INSTANCE.velocity(v)`); очередь как у зенитки: `GUN_BURST` снарядов `Projectile` (`damage` `SHELL_DAMAGE`, `launcher` "emb:<id>:<пост>", `hx/hy/hz` = пушка, `homeCells` = площадка 5 × 5 × (−2…+1) вокруг пушки, `dim` 0) → `AirDefenseSVA.INSTANCE.fire`, раз в `GUN_PERIOD`; звук и дым у поста; RWR — `paint(v, RadarMath.T_LAUNCHER, x, z, now + 40)`; журнал `launchLog("EMB AA <ИКАО> post <n> (x y z): fire at <owner> <type> <код> - <why>")`;
  - A_HOLD: огонь не ведётся (память остаётся: кончился пропуск — снова цель без нового предупреждения);
  - A_NONE: вне зоны после предупреждения / огня — `AD_LEFT`, память забыта; на земле — не стрелять никогда;
  - 7700 (`xpdrOn` и `XpdrMath.EMERGENCY`): раз за эпизод `requestPass(пилот)` → `passLine` → `AD_PASS` / `AD_PASS_USED` (+HUD); конец пропуска (`passUntil` в прошлом) — `AD_PASS_OVER`; правила `RoeMath` для ПВО игроков не трогать.
  - LAN вдвоём: всё по каждому игроку (ядро по UUID), двое в одном борту — допущен любой → не цель; FakePlayer пакетов не получает (ядро его не обслуживает, HUD только `EntityPlayerMP` с `playerNetServerHandler`).
- `airdefense/AirDefenseSVA`: при нужде помощник выстрела без TE (`fireFrom(...)`) — внутри своего файла; хук `EmbassyAdSVA.tick` — координатора, не трогать.
- `block/TileEntityLauncherSVA` (стр. 340): `prot = (прежнее) || EmbassyCompatSVA.neutralAt(worldObj, c.v.posX, c.v.posY, c.v.posZ)` — **без** оглядки на `adFireInProtectedRegions` (B_PROTECTED: предупреждения разрешены, огонь нет).
- `block/TileEntityCommandPostSVA` (op "zone", стр. 1196): запретная зона (`ZoneMath.FORBIDDEN`), чей контур или центр — под нейтральным небом (`neutral(f, x, z)` по углам / окружности через 8 блоков и по `approachBoxes`), — отказ `sva.emb.zoneNeutral`; `ZoneEditMath` не трогать (его `CHECK_KEYS` сверяет самотест).
- Тесты: матрица `EmbassyAdMath` (D), мировые — ниже.
- Lang: `sva.emb.ad.hud.warn/fire/pass/passUsed/passOver/left`, `sva.emb.zoneNeutral`.

### 3.6 Пакет D — тексты, справочник, тесты, README
- `lang/ru_RU.lang`, `en_US.lang` (UTF-8, CRLF; ru и en синхронно; значения без « » — „…“ / “…”; без символов 128–255 таблицы шрифта ванили: ×, ±, ·, °… → x, +/-, •, ˚; самотест `GlyphCheckSVA.scanLang` уже есть и строг). Ключи всех пакетов (§3.1–§3.5) + присланные; `SelfTestAirportB2SVA:895` (все `sva.cpdlc.u.0…17`), `SelfTestLinerFmsSVA:805` (`sva.mcdu.src.embassy`), `SelfTestSVA.langKeys` (`sva.hud.jump.why.N` / `sva.msg.jump.no.N` до `SpaceMath.EMBASSY` — поднять границу цикла до 12) — честно дописать.
- Справочник `book/ru|en`: `airport.txt` — раздел «Лётное поле посольства» (как садиться: допуск, ATIS ближе 600, ILS только FULL, коды 5100–5177, купол, SHORT без лайнера, стоянка, перегон, услуги и цены, карта, „Н“ для прыжка), `airdefense.txt` — «ПВО посольства» (зона 256, предупреждение, 10 с, разворот, 7700 раз в сутки, звено, пассажиры, беспилотные, нейтральное небо для ПВО игроков), `whatsnew.txt`; `book/index.txt` — только если добавятся страницы.
- NEI — ничего нового (проверить).
- `debug/SelfTestEmbassySVA`, `debug/WorldTestEmbassySVA` (§6), правки старых тестов — только честные.
- `docs/readme-embassy-airfield-draft.md` (ru + en), `CHANGELOG.md` «Не выпущено».

## 4. Замер `LinerSimSVA`: посадочная дистанция лайнера (2026-10-27)
Чистая симуляция `debug/LinerSimSVA` (та же `LinerSystems` + `LinerFlight`, что в игре), заход APPR + AP1 + AP2 с 1000 блоков, закрылки 3, шасси выпущены, спойлеры взведены, масса 17 500; станция FULL как у посольства: глиссадный в 30 за порогом и 15 от оси, курсовой в 15 за дальним торцом (181 + 15).
| Тормоз | 3,0°: над порогом / касание / стоп (от порога) | 3,5° |
|---|---|---|
| автотормоз LO | 1,8 / 57 / **154** | 2,2 / 65 / **161** |
| автотормоз MED | 1,8 / 57 / **123** | 2,2 / 65 / **130** |
| автотормоз MAX | 1,8 / 57 / **82** | 2,2 / 65 / **90** |
| педали после касания | 1,8 / 57 / **80** | 2,2 / 65 / **88** |
| без тормозов (только спойлеры) | 57 / не останавливается в 450 | — |
Скорость касания 72 км/ч. **FULL помещается**: посадочная дистанция 181, самый длинный случай (LO, 3,5°) — 161, запас ≥ 20; числа 200 / 20 ядра не меняются. Без автотормоза и педалей лайнер не останавливается (как и везде) — справочник и ATIS: «автотормоз LO и выше».
**SHORT (91, без смещения порогов, без ILS)**: визуальный заход — касание ~25–35 от начала (компактная станция в симуляции: 27–35), MED — стоп ~92–100 > 91, MAX — ~52–60. **Запасной вариант риска 1 (геометрия ядра не меняется):** авто-вышка посольства на SHORT не разрешает лайнеру посадку (`U_REFUSED` "liner"; `airport.embassyLinerShort` = false по умолчанию), ATIS SHORT — примечание «лайнер не принимается», справочник — то же. Лёгкая техника (самолёт ~25/50, реактивный ~25/50) — без ограничений.

## 5. Сводка текстов (ключи — D пишет ru/en)
`sva.mcdu.src.embassy`; `sva.tower.msg.icaoX`; `sva.cpdlc.u.15`, `.16`, `.17`; `sva.cpdlc.refused.enemy`, `.liner`; `sva.cpdlc.embAd.<warn|fire|pass|passUsed|passOver|left>`; `sva.emb.dome.warn`; `sva.atis.embDome`, `sva.atis.embShort`; `sva.emb.ad.hud.<warn|fire|pass|passUsed|passOver|left>`; `sva.emb.zoneNeutral`; `sva.emb.gui.*`, `sva.emb.msg.*`, `sva.emb.tab.*` (S присылает полный список); `sva.msg.jump.no.12`, `sva.hud.jump.why.12`. Строки самого ПВО посольства в чате, оплаты, услуг, мест, «Лётной книжки» — **ядра** (`sc.airfield.*`), аддон их не дублирует.

## 6. Тесты — пакет D

### 6.1 Самотест `debug/SelfTestEmbassySVA` (из `SelfTestSVA.run`, чистая логика)
1. `RunwaySurfaceSVA`: блок ВПП аддона (все меты) — да; `embassyAirfieldSC` меты 0, 3, 5, 6, 7 — да, 1, 2, 4, 8 — нет; камень / воздух / `null` — нет; `embassy(...)` только для блока ядра.
2. `EmbassyCompatSVA`: `on()` с этим ядром, `why()` пусто; `mirrorConstants()` == `debugApiConstants()`; `SERVICE_KEYS`, `PAY_KEYS`, `AD_KEYS`, `LEVEL_KEYS` = ядра (`EmbassyAirRulesSC`, `EmbassyAirDefenceSC`, `ReputationSC`); `ownerOf(7)` = «[Поселение-7]».
3. Матрица ПВО (`EmbassyAdMath.verdict` по всем 2 × 2 × 2 × 2 × стадиям × беспилотный/владелец): не в воздухе — A_NONE при любом прочем; вне зоны — A_NONE; на борту хоть один `anyHold` — A_HOLD; без седока: `ownerHold` — A_HOLD, иначе как с седоком; не предупреждён — A_WARN; предупреждён: до конца — A_TURN, конец — FIRE (TIME); сближение > 32 — FIRE (CLOSE), ровно 32 — TURN; ниже 40 и ниже h0 − 2 — FIRE (DESCEND), ниже 40 на той же высоте — TURN; конец в прошлом при предупреждении — FIRE (REENTRY); `earliest` (нули пропускаются), `turnEnd` (будущее → now + s×20, прошлое — как есть, 0 — 0), `extraWarning` (perDay 0/1/3), `passLine`, `pickPost` (ближайший с открытой линией в радиусе).
4. Аэропорт: `EmbassyAirportMath.key/uniqueIcao` (свободный — свой; занятый XTAA → XTAB; все 676 заняты — ""), `playerIcaoAllowed` (XABC — нет, ZABC — да), `locIdent`, `locFreq` (в сетке `clampFreq`, разные у двух торцов); `TowerMath.assign(…, rwyEmb)` — полоса посольства только его вышке, вышка посольства не берёт полос игроков, старый `assign` прежний.
5. ATC: `EmbassyAtcMath` (коды 05100–05177 валидны и не пересекают 4100–4177 и особые; `atisToChat` 599/600/601/пункт назначения; `landingRefusal` — 7700 всегда можно, «Враг», лайнер на SHORT); `Cpdlc.needsResponse(U_REFUSED/U_DOME/U_EMB_AD)` = false; `Cpdlc.args` U_EMB_AD — два аргумента.
6. Услуги: `EmbassyServiceMath` (`spotKindFor` всех `VehicleType`, `fits`, `mayPark(0/1/2)`, `parkDaysDue` — первые сутки 0, талон, `emergencyAmount`, `towDue` 2 и 5 мин, `offer`, `hint`); зеркала `BASE/UNIT/PER_SERVICE/PER_DAY/EMERGENCY_PCT/SPOTS_PER_PLAYER` = `EmbassyAirRulesSC`.
7. Огни: `EmbassyLightsMath.lights` — ни одного кромочного (|u| = w/2 + 0,15, h 0,25) и красного торцевого, осевые и TDZ есть, ALS только при `als`; `papiWhites` 0…4 по углу.
8. Перечисления: `NavDb.SRC_KEYS.length == SRC_EMBASSY + 1`, `Cpdlc.U_TYPES == 18`, `SpaceMath.EMBASSY == 12`; `EmbassyParkingDataSVA` NBT туда-обратно (стоянки и «Отстой»).
9. Lang: все ключи §5 в ru и en; `GlyphCheckSVA` зелёный (уже есть).

### 6.2 Мировой тест `debug/WorldTestEmbassySVA` (`-Dsva.worldtest`, из `WorldTestSVA` с тика `START` 3400)
Посольство с полем: **новое**, как `WorldTestAirfieldSC.generate` ядра — `SubstrateConfigSC.setEmbassyAirfield(AIRFIELD_ALWAYS)` на время решения (старое — обратно), свежее место (`EmbassySiteSC.site/explored/zoneNear`, кольца регионов от спавна), `decideFor` → FULL, FakePlayer в середину будущей ВПП, `EmbassySC.mapGen(w).startAt`, загрузка чанков старта, тикеты ForgeChunkManager; либо уже сделанное ядром с меткой `SCTestAirfield` в `rec.extra`. Без API ядра / нет FULL — SKIP. Тестовые игроки — свои `EntityPlayerMP` без сети (как `WorldTestArmourerSC.StationPlayer`: чат копится), уровень «Доверия» — через реестр ядра (`SettlementRegistrySC.player(uuid).reputation`), двое одновременно (LAN). Шаги:
1. **Аэропорт**: после тика — торцы, вышка в сети (игрок ближе 3000), станции FULL CAT III у торцов с ILS (`AirportDataSVA.station/result`), номера = `numberLow/High`, ИКАО уникален, `writeToNBT` обоих данных без единого виртуального ключа; копия клиента игрока, не знающего посольство, — без него, знающего — с ним (`clientData` по игроку), NavDb `SRC_EMBASSY` только у знающего.
2. **ATIS**: лайнер с пилотом на 700 от поля — нет строки ATIS посольства в чате, на 500 — есть; пункт назначения в МФПУ — есть и на 2000.
3. **ПВО посольства** (`EmbassyAdSVA.debugLook`, время — тики мира): самолёт с пилотом-«Чужаком» в воздухе на 200 от ядра — `AD_WARN` в чате (строка ядра), HUD, радио; через 10 с — `AD_FIRE`, снаряды из позиции поста (`AirDefenseSVA.shots()` с `hx/hy/hz` = пушка), урон; сближение на 40 за 3 с — огонь сразу; снижение ниже 40 — огонь сразу; вышел из зоны — `AD_LEFT`, повторный вход в те же сутки — огонь без предупреждения; **допущенный** пилот («Знакомый») — ни предупреждения, ни огня; «Чужак» + допущенный **пассажир** — не цель; «Чужак» + допущенный товарищ **по звену** рядом — не цель; **7700** — `AD_PASS`, 60 с без огня, `AD_PASS_OVER`, второй 7700 в те же сутки — `AD_PASS_USED` и огонь; **пеший** игрок и игрок в броне / на джетпаке в зоне — ничего; машина **на земле** в зоне — ничего; беспилотная в воздухе, владелец «Чужак» в сети — предупреждение владельцу, затем огонь; над базой игрока (Генератор поля в зоне) — ничего; `embassyAd` false — ничего.
4. **Нейтральное небо**: ПУ игрока в режиме fire у створа — по борту над полем не стреляет (предупреждает); запретная зона КП над полем — отказ.
5. **Посадка по ILS FULL**: лайнер на заходе 1000 по курсу `headingLow`, APPR + AP1 + AP2, автотормоз MED — ROLL OUT и стоп на ВПП посольства до дальнего торца (`RunwaySurfaceSVA`), разрешение авто-вышки получено; на SHORT — `U_REFUSED` "liner".
6. **Стоянка, перегон, оплата**: машина остановилась на МС-2 — запись за владельцем; третья машина того же игрока — не на МС; без седока на ВПП дольше 2 мин (время мира сдвинуть) — на свободное МС / «Отстой», не пропала; окно услуг (`openService` → снимок), `command` зарядка — КЧ сняты ядром, буфер полон; «Враг» — отказ; FakePlayer — отказ; первая посадка с ответчиком — «Лётная книжка» один раз.
7. Всё вернуть: блоки теста, машины, игроки, конфиг, тикеты.

### 6.3 Только в игре
Вид огней посольства днём / ночью / в дождь (5 ступеней, АВТО), PAPI с разных высот, бегущий огонь ALS; снег на полосе зимой (не ложится — проверить); трассеры и звук постов; HUD-строки ПВО и RWR; окно услуг вдвоём по LAN (каждому своё); карта техники и ND не показывают незнакомое посольство; FPS (~70 огней ядра + огни аддона); `-Dsc.packcheck` на сборке пользователя (TLauncher Forge, его javaw не закрывать); JourneyMap.

## 7. Жёсткие правила для всех
1. Работать только в `SAV-wt-emb`; `SiliconAgeVehicles` и другие `SAV-wt-*` не трогать; ядро не править. Файлы не удалять. Не коммитить.
2. `com.sc.embassy.api.*` — только `compat/EmbassyCompatSVA`; остальные — через него и `EmbassyFieldSVA`.
3. Клиентский код — только в `client/`; общий код не зовёт `@SideOnly(CLIENT)`; FakePlayer пакетов не получает.
4. LAN вдвоём: решает сервер; всё по игроку — по UUID; двое у одной колонки — каждому своё окно и снимок.
5. Сохраняемое — WorldSavedData / NBT; виртуальные записи посольств в NBT аддона не пишутся; меты, порядки перечислений, виды пакетов, источники NavDb — только в конец.
6. Тексты добавляет только D (ru + en, CRLF, „…“ / “…”, без « » и символов 128–255 таблицы шрифта); остальные присылают `ключ=ru | en`.
7. Каждый пакет собирается сам поверх общего ядра; чужой файл — только через отчёт координатору.

## 8. Риски
1. Посадочная дистанция (§4) — FULL помещается; SHORT без лайнера (конфиг).
2. Рулёжка «А» за +a торцом под заходом — C держит руление при посадке над высоким торцом.
3. Дальность пушек 110 меньше зоны 256: огонь фактически с ~110 от постов (посты в ~45–110 от ядра) — предупреждение и правило сближения работают по всей зоне. TODO(balance): ракеты постов (280) — не по решению (e), только если пользователь попросит.
4. Отбор по игроку — больше пакетов D_SYNC (на LAN вдвоём незаметно); кэш копий по набору скрытых.
5. Снег на покрытии — проверить в игре; запасной вариант (признак под слоем снега) — в `RunwaySurfaceSVA`.
6. Старое ядро без API — всё выключено (`on()` false); ядро с API другой версии — тоже.
7. Вышки игроков с кодом на X, сохранённые до этого прогона, остаются; посольство берёт следующий свободный (XTAB…).

## 9. Неясно / решено координатором
- **CORE_DEP не поднят**: API ищется по имени, старое ядро не ломает аддон (иначе текущее ядро 0.2.3 с API не прошло бы `[0.2.4,)`).
- **Зона «Аэродром» и «ограниченная» над куполом не публикуются в данных ПВО** (сохранились бы и выдали бы неизвестные посольства всем клиентам): коды по «Аэродрому» считает `IffServerSVA.zoneOf`, предупреждение над куполом — серверной строкой пилоту.
- **Окно услуг без GUI-id**: открывается по пакету `D_EMB` (колонка — блок ядра).
- **Снижение «ниже 40»** — и ниже высоты при предупреждении на 2: низко летящий ровно борт, который разворачивается, не цель до конца секунд.
- **`embassyWarnPerDay` > 1** — сверх журнала ядра счёт аддона в памяти (после перезапуска сервера — снова один из журнала ядра).
- **Беспилотный борт, владелец не в сети** — не допущен; предупреждать некого, секунды — от первого взгляда.
- **Прикрытие звеном** — правило ядра (допущенный товарищ ближе 64, в том числе пеший рядом); «товарищ тоже в воздухе» не добавлено.
- **Автоматический перегон бесплатный** (ядро не списывает); платный «tow» — просьба игрока перегнать свою машину (первый за сутки — 0, считает ядро).
- **Карта подхода** — печать на бумаге игрока, как у вышки (услуги не продают предметов).
- **Стоянка**: первые сутки — 0; долг > суток — на «Отстой» (бесплатно); талон продлевает оплату, пока владелец в сети.
- **Имя виртуальной вышки** пустое (клиенты показывают ИКАО; перевод имени посольства — у клиента ядра, серверу его не собрать в строку).

## 10. Как проверить в игре
1. Новый мир (или новые земли далеко) с ядром df459a0+ и этим аддоном; конфиг ядра `embassy.airfield` = AUTO. Найти новое посольство Пеленгатором — у FULL сбоку от купола ВПП 200 × 9 с огнями, Аэровокзал, Вышка КДП со светомаяком, перрон, МС-1…4, „Н“, Ангар, 5 зенитных постов.
2. Подлететь на самолёте «Чужаком» (без визитов по земле): на 256 от ядра в чате красная строка посольства «Борт …, вы в зоне ПВО посольства без допуска. Покиньте зону» с подсказкой про «Знакомого», на HUD — «ПВО ПОСОЛЬСТВА: ПОКИНЬТЕ ЗОНУ»; развернуться и уйти — `left`; лететь дальше — через 10 с огонь с ближайшего поста (трассеры, урон, RWR); вернуться в те же сутки — огонь сразу.
3. Набрать 7700 в зоне — «пропуск 60 с», огонь держат; второй раз в те же сутки — «использован».
4. Прийти по земле (Кольцом или пешком), набрать «Знакомого» — теперь в зону можно; взять друга по LAN «Чужаком» пассажиром или в звено — по нему не стреляют.
5. На подлёте ближе 600 — ATIS посольства в чате; МФПУ лайнера — аэропорт XTAA (источник «ПОСОЛЬСТВО»), заход ILS 36 / 18; посадка APPR + AP1 + AP2 с автотормозом MED — стоп до дальнего торца; ПВО игрока с пусковой у створа над полем не стреляет.
6. Зарулить на МС-2, заглушить: «Лётная книжка» (2 КЧ, +5 «Доверия») один раз; щёлкнуть колонку — окно «Обслуживание»: зарядка, водород, ремонт в ангаре, «Электролит», карта подхода; КЧ снимаются по уровню; крадучись — газы брони ядра.
7. Оставить машину на ВПП на 2+ минуты — её перегонят на МС / «Отстой»; в куполе на 5+ минут — на «Отстой»; прыжок корабля на ВПП — отказ, на „Н“ — можно.
8. У Диспетчера посольства вкладка «Лётное поле»: строки аддона (свои машины на МС, цены, вышка), ATIS, кнопка «Обслуживание».
9. На SHORT-посольстве лайнеру посадку не разрешают; на HELI — только „Н“.
10. Вдвоём по LAN: у каждого своё окно услуг, свои предупреждения ПВО и пропуск 7700; незнакомое посольство не видно на карте и ND у того, кто там не был.
