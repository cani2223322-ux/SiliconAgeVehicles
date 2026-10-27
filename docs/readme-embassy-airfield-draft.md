# Черновик для README: «Лётное поле посольства» / README draft: “The embassy airfield” (2026-10-27)

Черновик разделов README аддона для фаз А3 + А4 и ПВО посольства (docs/plan-embassy-airfield-addon.md). Вставить при выпуске: русские пункты — в «## Русский», английские — в «## English», на места, указанные в заголовках. Числа — по ядру df459a0 и плану; всё, что помечено TODO(balance), — ключи конфига.
A draft of the add-on's README sections for phases A3 + A4 and the embassy's air defence. To paste at the release: the Russian items under “## Русский”, the English ones under “## English”, where the headings say. The numbers follow core df459a0 and the plan; everything marked TODO(balance) is a config key.

---

## Русский

### Авиация — после пунктов «Аэропорт (волна Б1 / Б2)»

- **Лётное поле посольства** (нужно ядро Silicon Age с API лётных полей; без него всё посольское просто выключено). Новые посольства Наладчиков, построенные при установленном аддоне, получают от ядра лётное поле: FULL — ВПП 200 x 9 вдоль главной улицы сбоку от купола, на тесной земле SHORT — ВПП 90 x 7 и вертодром „Н“ или HELI — только „Н“; Аэровокзал, Вышка КДП со светомаяком, перрон с МС-1…МС-4, Ангар, Колонки обслуживания, памятник «Последний челнок Литографов». Старые посольства не трогаются. Аддон не ставит туда ни одного своего блока — аэропорт виртуальный, из данных ядра, и в сохранения не пишется.
  - *Аэропорт*: покрытие ВПП ядра — полоса для всей техники (ЗАХВ, HSI, разбег x0,5, мягкие тормоза); торцы на смещённых порогах (посадочная дистанция 181 — лайнер садится с автотормозом LO и выше); вышка с кодом ИКАО X + две буквы элемента + буква номера («Тантал-1» — XTAA; вышкам игроков код на X не дают); номера ВПП по истинному курсу; ILS CAT III на торцах FULL с ILS, без облёта («облёт Наладчиков»); вышка в сети, пока в Верхнем мире кто-то ближе 3000 блоков; диспетчер — авто-вышка.
  - *Связь*: ATIS посольства — в чат ближе 600 блоков или если посольство — пункт назначения в МФПУ, в МФПУ — всегда; коды ответчика 5100–5177 всем, кроме «Врага» посольства (ПВО игроков этим кодам не доверяет); «Враг» получает «ОТКАЗ: ВЫ - ВРАГ ПОСОЛЬСТВА», над куполом — «ПОСАДКА В КУПОЛЕ ЗАПРЕЩЕНА», лайнеру на SHORT — отказ; 7700 принимают у всех.
  - *Кто что видит*: посольство в МФПУ, на ND и карте техники видно только тому, кто там был или ближе 1024 блоков (источник «ПОСОЛЬСТВО»); вдвоём по LAN у каждого своё.
  - *Огни*: кромки, пороги, торцы, рулёжку и разметку кладёт ядро блоками; аддон дорисовывает осевые, зону приземления, огни подхода с бегущим огнём, ПАПИ и конус колдуна — 5 ступеней и АВТО.
  - *Услуги*: щелчок по Колонке обслуживания (крадучись — газы брони ядра) — окно «Обслуживание»: зарядка АКБ и буфера (1 КЧ за 2 млн EU, до 16 млн за раз и 32 млн в сутки), водород (1 КЧ за 8 000 мБ, до 48 000 за раз и 64 000 в сутки), ремонт корпуса в Ангаре (1 КЧ за 10% износа, до 60% за раз), «Электролит» (2 КЧ), перегон своей машины, карта подхода на вашей бумаге — с уровня «Знакомый»; «Чужаку» и «Нарушителю» — аварийная заправка до 25% бака и буфера раз в сутки. Цена — по уровню «Доверия», КЧ списывает посольство.
  - *Стоянка и перегон*: машина, вставшая на МС своего вида, — за владельцем (не больше 2 МС на игрока; первые сутки бесплатно, дальше 1 КЧ в сутки, по талону — бесплатно); брошенную на ВПП, площадке или рулёжке дольше 2 минут перегоняют на МС или «Отстой», под куполом дольше 5 минут — на «Отстой». Машина не пропадает.
  - *«Лётная книжка»*: первая посадка в каждом посольстве с включённым ответчиком — 2 КЧ и +5 «Доверия» (один раз на посольство). Прыжок корабля на поле — только на „Н“.

### ПВО — после пункта «Режим ПО КОДУ»

- **ПВО посольства.** У посольства с лётным полем — своё ПВО: 5 «Зенитных постов Наладчиков» (строит ядро, стреляет аддон — снарядами, как зенитка). Зона — 256 блоков от ядра на любой высоте (над базой игрока — нет). Цель — только техника в воздухе, на борту которой нет допущенного: допуск — «Знакомый» и выше у этого посольства, не «Враг»; хватает любого на борту (пилот, экипаж, пассажир) или допущенного товарища по звену рядом (до 64 блоков). Перед огнём — строка посольства в чат, то же по радио (CPDLC) и на HUD, 10 с на разворот; подлёт ближе на 32 блока или снижение ниже 40 блоков над полем за эти секунды — огонь сразу. Одно предупреждение в игровые сутки: повторный вход в те же сутки — огонь без предупреждения. 7700 — пропуск 60 с раз в сутки. Беспилотная машина — цель после предупреждения недопущенному владельцу. Пеших, игроков в броне и на джетпаке и всё на земле не трогает никогда. Коды ответчика и режим ПО КОДУ игроков роли не играют.
- **Нейтральное небо посольства**: ПВО игроков над землёй посольства, полем и створами не стреляет (только предупреждает), запретную зону КП над ним поставить нельзя; база игрока в створе защищает себя сама.

### Настройки — новый абзац

Лётное поле посольства: `airport.embassyAirports` (вкл.), `embassyServices` (вкл.; выкл. — колонка даёт только газы брони ядра), `embassyTowing` (вкл.), `embassyAtisRange` (600), `embassyTowMinutes` (2), `embassyDomeMinutes` (5), `embassyGlideAngle` (3,0), `embassyLinerShort` (выкл.); ПВО посольства: `airdefense.embassyAd` (вкл.; выкл. — посты только декор), `embassyWarnPerDay` (1), `embassyWarnSeconds` (10), `embassyCloseBlocks` (32), `embassyMinHeight` (40), `embassyUnmanned` (вкл.). Конфиг ядра `embassy.airfield` = AUTO (по умолчанию: поле строится только при установленном аддоне) | ALWAYS | NEVER.

### Требования — дополнение

Для лётного поля посольства — Silicon Age с API лётных полей (`com.sc.embassy.api`, версия 1). С более старым ядром аддон работает как раньше, поля посольств нет.

### Известные проблемы — дополнение

- Лётное поле посольства проверено самотестом и мировым тестом на сервере; вид огней посольства (ступени, АВТО, ПАПИ, бегущий огонь), трассеры постов, окно «Обслуживание» вдвоём по LAN и JourneyMap ещё не проверялись в клиенте.
- Пушки постов бьют на 110 блоков: при зоне 256 огонь фактически начинается ближе к постам (предупреждение и правило сближения работают по всей зоне). TODO(balance).
- Один сид даёт разные посольства с аддоном и без (поле решается при генерации) — так задумано.

---

## English

### Aviation — after the “Airport (wave B1 / B2)” items

- **The embassy airfield** (needs a Silicon Age core with the airfield API; without it the embassy features are simply off). The Tuners' new embassies built with the add-on installed get an airfield from the core: FULL — a 200 x 9 runway along the main street beside the dome; on cramped land SHORT — a 90 x 7 runway and an “H” helipad, or HELI — the “H” alone; a terminal, a control tower with a beacon, an apron with stands 1…4, a hangar, service columns and the monument “The Lithographers' Last Shuttle”. Old embassies are left as they are. The add-on places no block of its own there — the airport is virtual, made from the core's data and never saved.
  - *The airport*: the core's runway paving is a runway for every machine (LOC capture, the HSI, the x0.5 take-off run, the soft brakes); runway ends at the displaced thresholds (a landing distance of 181 — a liner lands with the autobrake LO or higher); a tower coded X + two letters of the element + the number's letter (“Tantalum-1” — XTAA; player towers can't take an X code); runway numbers by the true course; a CAT III ILS at the FULL ends with one, no flight check needed (“the Tuners' flight check”); the tower is online while someone in the Overworld is within 3000 blocks; the automatic tower controls it.
  - *Radio*: the embassy's ATIS reaches the chat within 600 blocks or when the embassy is the MCDU's destination, the MCDU — always; transponder codes 5100–5177 for everyone but the embassy's “Enemy” (players' air defence does not trust those codes); an “Enemy” gets “UNABLE: YOU ARE AN ENEMY OF THE EMBASSY”, over the dome — “NO LANDING INSIDE THE DOME”, a liner is refused on a SHORT runway; 7700 is accepted from anyone.
  - *Who sees what*: the MCDU, the ND and the vehicle map show an embassy only to whoever has been there or is within 1024 blocks (source “EMBASSY”); with a friend on LAN each sees his own.
  - *Lights*: the core lays the edge, threshold, end and taxi lights and the markings as blocks; the add-on adds the centre line, the touchdown zone, the approach lights with the sequenced flasher, the PAPI and the windsock's cone — 5 steps and AUTO.
  - *Services*: a click on a service column (sneaking — the core's armour gases) opens the “Service” window: charging the battery and the buffer (1 chip per 2 M EU, at most 16 M at once and 32 M a day), hydrogen (1 chip per 8 000 mB, at most 48 000 at once and 64 000 a day), hull repair in the hangar (1 chip per 10% of wear, at most 60% at once), “Electrolyte” (2 chips), towing your own machine, an approach chart on your own paper — from “Acquaintance” on; a “Stranger” or a “Violator” gets an emergency refuel up to 25% of the tank and the buffer once a day. The price follows your Trust, the embassy takes the chips.
  - *Parking and towing*: a machine stopped on a stand of its kind is booked to its owner (at most 2 stands a player; the first day free, then 1 chip a day, free with a talon); one left on the runway, a pad or the taxiway for more than 2 minutes is towed to a stand or the Overflow lot, under the dome for more than 5 minutes — to the lot. A machine never disappears.
  - *The “Logbook”*: the first landing at each embassy with the transponder on — 2 chips and +5 Trust (once an embassy). A ship's jump onto the field lands only on the “H”.

### Air defence — after the “CODE-ONLY mode” item

- **The embassy's air defence.** An embassy with an airfield has its own air defence: 5 “Tuners' AA posts” (the core builds them, the add-on fires — shells, as its AA gun). The zone is 256 blocks from the core at any height (none over a player's base). A target is only a machine in the air with nobody admitted aboard: admission is “Acquaintance” or higher at that embassy, never its “Enemy”; any one aboard (the pilot, the crew, a passenger) or an admitted squad mate near (within 64 blocks) is enough. Before the fire — the embassy's line in the chat, the same on the radio (CPDLC) and the HUD, 10 s to turn away; closing in by 32 blocks or descending below 40 blocks over the field within those seconds — the fire at once. One warning a game day: back in the zone the same day — the fire without a warning. 7700 — a 60 s pass once a day. An unmanned machine is a target after a warning to its owner if he is not admitted. Players on foot, in armour or a jetpack and anything on the ground — never. Transponder codes and the players' CODE-ONLY mode play no part.
- **The embassy's neutral sky**: players' air defence does not fire over the embassy's land, its field and its approach corridors (it only warns), and no forbidden zone of a command post can be set over it; a player's base in a corridor keeps its own defence.

### Configuration — a new paragraph

The embassy airfield: `airport.embassyAirports` (on), `embassyServices` (on; off — the column gives only the core's armour gases), `embassyTowing` (on), `embassyAtisRange` (600), `embassyTowMinutes` (2), `embassyDomeMinutes` (5), `embassyGlideAngle` (3.0), `embassyLinerShort` (off); the embassy's air defence: `airdefense.embassyAd` (on; off — the posts are decor), `embassyWarnPerDay` (1), `embassyWarnSeconds` (10), `embassyCloseBlocks` (32), `embassyMinHeight` (40), `embassyUnmanned` (on). The core's `embassy.airfield` = AUTO (the default: a field only with the add-on installed) | ALWAYS | NEVER.

### Requirements — an addition

For the embassy airfield — a Silicon Age core with the airfield API (`com.sc.embassy.api`, version 1). With an older core the add-on runs as before, with no embassy airfields.

### Known issues — additions

- The embassy airfield is checked by the self-test and the world test on a server; the look of the embassy's lights (steps, AUTO, PAPI, the flasher), the posts' tracers, the “Service” window for two on LAN and JourneyMap are not yet checked in a client.
- The posts' guns reach 110 blocks: with a 256-block zone the fire in practice starts nearer the posts (the warning and the closing rule work over the whole zone). TODO(balance).
- One seed gives different embassies with and without the add-on (the field is decided at generation) — by design.
