<p align="center"><img src="docs/mockups/veh_design_3_liveries.png" width="720" alt="Silicon Age: Vehicles"></p>

# Silicon Age: Vehicles

![Minecraft 1.7.10](https://img.shields.io/badge/Minecraft-1.7.10-green) ![Forge 10.13.4.1614](https://img.shields.io/badge/Forge-10.13.4.1614-orange) ![Status: alpha](https://img.shields.io/badge/status-alpha-orange)

**[Русский](#русский) | [English](#english)**

---

## Русский

**Кремниевый век: Техника** — аддон к моду [Silicon Age](https://github.com/cani2223322-ux/SiliconAge) для Minecraft 1.7.10: одиннадцать машин — от электробагги до пассажирского лайнера SA-320 и сингулярного корабля, их зарядка, заправка водородом и газами и сборка на Инженерном верстаке, а также противовоздушная оборона: ответчики, РЛС, зоны, зенитные установки и командный пункт. У каждой машины своя ниша: груз, группа игроков, тяжёлая работа, дальние перелёты, ранняя мобильность. Двигатели большинства машин работают на водороде ядра, электрика — от съёмного аккумулятора; газы ядра дают и особые режимы (подъём, охлаждение, форсаж, поле корпуса, кислород, дальние прыжки). Крафты — только из предметов Silicon Age и ванили.

> ⚠️ **Альфа-версия 0.1.1.** Всё основное работает и покрыто автотестами, но многое (особенно отрисовка лайнера, ПВО и новых кабин) ещё не обкатано в игре — см. «Известные проблемы». Возможны ошибки — делайте резервные копии миров и пишите о проблемах в [Issues](https://github.com/cani2223322-ux/SiliconAgeVehicles/issues).

### Машины
| Машина | Ур. | Буфер EU | Скорость | Мест | Груз | Особенность |
|---|---|---|---|---|---|---|
| Электробагги | MV | 400 тыс. | 36 км/ч | 2 | 9 | первая машина, разведка |
| Ховербайк | QV | 4 млн | 60 км/ч | 1 | 9 | висит над землёй, водой и лавой на водороде |
| Лёгкий вертолёт | HV | 3 млн | 50 км/ч | 2 | 9 | вертикальный взлёт, висение, авторотация |
| Лёгкий самолёт | HV | 4 млн | 81 км/ч | 3 | 9 | дёшево далеко, разбег, полоса ×0,5 |
| Грузовой вездеход | HV | 2 млн | 28 км/ч | 2 | 54 (+54 прицеп) | гусеницы, прицеп, автодоставка A ↔ B |
| Буровая машина | EV | 8 млн | 12 км/ч | 1 | 108 | туннели 3×3 / 5×5, «только руда», выравнивание |
| Реактивный самолёт | QV | 16 млн | 121 км/ч | 1 | 9 | форсажная камера (до 180 км/ч) |
| Конвертоплан | XV | 32 млн | 101 км/ч | 6 | 27 | вертикальный режим + режим самолёта |
| Транспортный челнок | XV | 32 млн | 81 км/ч | 8 | 27 | герметичная кабина с O₂, «Дальний режим» на дейтерии, стыковка с мостом ядра |
| Сингулярный корабль | SV | 256 млн | 121 км/ч | 6 | 54 | свой портал «Прыжок», гравитационный якорь, невидимость для мобов |
| **Лайнер SA-320** | EV | 4 млн + H₂ 48 000 мБ | 144 км/ч | 10 | 54 | двухдвигательный пассажирский самолёт с системами в духе A320 |

- **Авиация** летает до Y 200; без мощности вертолёты уходят в авторотацию, самолёты планируют 1:8; седоки не получают урона от падения. Скорость любой машины — не больше 180 км/ч (сервер успевает загружать мир).
- **Транспортный челнок**: свободный полёт во все стороны, 200 EU/т; экипаж дышит кислородом кабины под водой (1 мБ/с на человека); выше Y 120 на скорости «Дальний режим» тратит дейтерий 1 мБ/с вместо EU. Влетите в открытый вихрь Наземного или Космического моста ядра — челнок пройдёт на другую сторону (и в другое измерение) вместе с людьми и грузом; нужен доступ к мосту, челнок сам платит за массу перехода.
- **Сингулярный корабль**: всё, что умеет челнок, быстрее, 400 EU/т; «Прыжок» открывает портал к точке Координатора ядра в багажнике или домой (кровать пилота): от 16 блоков, 20 млн EU + 2 млн за 1000 блоков и 10 мБ + 10 за 1000 блоков сингулярной материи (в другое измерение — 100 млн EU и 100 мБ), перезарядка 30 с, место прибытия должно быть свободно и не в чужом привате. «Гравитационный якорь» держит корабль неподвижно (40 EU/т), «Невидимость» прячет экипаж от враждебных мобов (1 мБ/с СМ). Сингулярность над корпусом пульсирует.
- **Износ корпуса** (конфиг `vehicles.wear`), нагрев с остановкой мотора (гелий охлаждает), аварийный ход без заряда, владелец и доступ, чужие приваты не трогаются, лимит машин на игрока (`vehicles.maxPerPlayer`).

### Водород и съёмный аккумулятор
- **Багги, ховербайк, вездеход, буровая, вертолёт, лёгкий самолёт, реактивный и конвертоплан** ездят и летают на водороде ядра: бак H₂ → двигатель → привод и генератор → аккумулятор → электрика. Полного бака хватает на 40–65 минут; расход растёт от газа, груза, прицепа, набора высоты и тюнинга. Челнок и Сингулярный корабль — как раньше, на EU.
- **Запуск двигателя** — клавиша «Запуск двигателя» (`;`) или просто «вперёд»; без водорода двигатель глохнет (машина катится, самолёт планирует, вертолёт уходит в авторотацию).
- **Заправка:** газовая колонка, прицеп-цистерна, модуль газа зарядной станции, ячейки и вёдра ядра.
- **Аккумулятор** — свой предмет у каждой машины (8 видов, MV–EV): без него двигатель не запустить и электрика не работает. Слот «Аккумулятор» — на вкладке «Машина» багажника; заряжается в накопителях ядра и IC2, от генератора и на станции. Износ, обслуживание в гараже («Электролит») или «Набором обслуживания АКБ»; «Пусковые провода» — прикурить от другой машины.
- **HUD:** полоса H₂, расход и запас хода, строка аккумулятора; «МАЛО ТОПЛИВА» / «РЕЗЕРВ». Старые миры: машина получает полный бак и аккумулятор сама; `vehicles.fuelMode = EU` возвращает всё как было.

### Авиация: кабины и бортовые системы
- **Новые кабины C+:** лёгкий самолёт — «Гибрид + MFD» (табло ламп, панель автопилота, PFD, MFD со страницами ДВИГ / КАРТА / МАРШ / СПО, ответчик); вертолёт — «Гибрид + верхний пульт» (табло, радиовысотомер с отсчётом высоты, индикатор висения, верхний пульт 3 × 5, окно в полу).
- **Чёткие приборы для 2K / 4K:** сглаженный шрифт с кириллицей вместо 8-пиксельного, запечённые шкалы, мипмапы; `vehicles.cockpitHiRes`, `vehicles.cockpitQuality` (AUTO / 1K / 2K / 4K).
- **ИНС в стиле ADIRS A320:** выставка 10 с на месте при питании борта; до выставки — флаги ПРОСТР / КУРС на PFD, нет карты, автопилот не включается.
- **Без питания — тёмная кабина:** выключен, разряжен или снят аккумулятор — экраны, табло и автопилот не горят, работают только скорость, высотомер и вариометр.
- **Механизация крыла и автопилот:** закрылки, предкрылки, интерцепторы / воздушный тормоз; панель автопилота HDG / ALT / VS / SPD / CAP / МАРШ, HSI, план полёта на карте; верхний пульт с тумблерами, запуск двигателя, отказы при износе; створки шасси, амортизаторы, сопло и ИЛС реактивного.
- **Аэродром:** блоки «Огни PAPI» и «Огни подхода», разметка и огни ВПП появляются сами; посадочные фары под крылом.
- **Износ и маркировка:** копоть, потёки, сколы, грязь; надписи и регистрация на самолётах.
- **Рекуперация** при торможении, спуске и планировании (`vehicles.regen`).

### Лайнер SA-320
Двухдвигательный водородный пассажирский самолёт (уровень EV, ~18,5 × 17,6 × 5,7 блока): 10 мест (КВС слева, второй пилот справа, 8 пассажиров), посадка через дверь L1 с трапом. Сделан в 7 волн, системы упрощены, но следуют логике A320:
- **Электросистема, ВСУ, отбор воздуха, запуск двигателей** (ENG MODE IGN/START, ENG MASTER, запуск сжатым воздухом от ВСУ, X BLEED, отказы при запуске), быстрый запуск одной кнопкой, наземное питание от зарядной станции.
- **Гидравлика** Зелёная / Синяя / Жёлтая (насосы, PTU, RAT, течи, «Гидрожидкость»), **рули и законы управления** (ELAC / SEC / FAC; NORMAL / ALTN / DIRECT / MECH), **шасси** с аварийным выпуском, **тормоза**, антиюз, автотормоз, температура тормозов, поворот передней стойки.
- **Топливо:** баки Л / Ц / П с внутренними и внешними секциями, 6 насосов, кольцевание, дисбаланс; **ECAM** с сообщениями, действиями, памятками, страницами SD и проверочными листами; MASTER WARN / CAUT.
- **FCU, автопилот и директор, автомат тяги** (РУД с упорами IDLE / CL / FLX / TOGA, реверс), FMA в 5 колонок, защиты (alpha floor, VMO, крен); **блок «Курсо-глиссадный маяк»** (ILS), **автопосадка** с FLARE / RETARD / ROLL OUT, отсчёт высоты, уход на второй круг.
- **МФПУ (MCDU) / FMS:** два МФПУ (клавиша M в кресле пилота), INIT, IRS INIT, F-PLN с ревизиями, DIR TO, вертикальный профиль T/C / T/D, PERF и V-скорости, FUEL PRED, PROG, RAD NAV, база точек и аэропортов.
- **Огни, ПОС и обледенение, кондиционирование:** внешние огни (видны всем), свет кабины и салона, табло ремней; лёд на крыле, двигателях и ПВД, обогрев; зоны температуры, нагрузки на генераторы.
- **ADIRS:** три ADIRU (NAV / ATT, выставка, IRS INIT, дрейф и GPS), голосование ADR, недостоверная скорость, флаги PFD по стороне, независимый ISIS.
- **Наземное обслуживание:** двери R1 и грузовые люки, колодки, буксировка тягачом (своё водило); **пассажиры** пристёгиваются по табло; **пожар** двигателей и ВСУ с баллонами; **приоритет ручки** (второй пилот может взять управление).
- **Кабина:** 3D-клик по органам управления (держите **Левый Alt** — «Курсор»), окно **«Пульты»** (верхний пульт, пьедестал, FCU, приборная доска с живыми экранами; масштаб и сдвиг), **быстрые виды** (клавиша **=**: PFD / ND, ECAM, верхний пульт, пьедестал, окна), скрытая рука в креслах пилотов. Текстуры пультов запекаются по уровню качества 4K / 2K / 1K (`vehicles.linerTextures`), у модели три уровня детализации.

### ПВО
- **Ответчики** на всём, что летает (OFF / STBY / A / C / S, коды 0000–7777, IDENT, «ключ дня» и «Карта кодов»).
- **РЛС:** обзорная, сопровождения и высотомер; ЭПР по типу машины, погода, рельеф и невидимость Сингулярного корабля влияют на дальность. **СПО** (тюнинг авиации) показывает облучение.
- **Зоны** (запретная / ограниченная / аэродром / коридор) видны пилотам на карте, HSI и HUD; **правила огня** (ROE) с обязательными предупреждениями.
- **Зенитная установка, ракетный комплекс, командный пункт** (карта, треки, журнал, тревога, редактор зон, настройки). По умолчанию сервер только предупреждает; огонь — `airdefense.mode = fire`.
- **Многоблочные 3D-модели** с анимацией и лампами, окна блоков, **4 слота модулей ядра** (Универсальный трансформатор, Трансформатор, Накопитель, Ускоритель, Экономайзер, Перегрузка, Захват). Зарядная станция техники тоже принимает Универсальный трансформатор.

### Внешний вид и кабины
- **Текстуры и ливреи:** фактура материалов 16×16, швы и заклёпки; ливрея по уровню машины (I рабочая с чёрно-жёлтой полосой, II гражданская, III кремниевая со светящимися линиями, Сингулярная — с фиолетовыми); на бортах эмблема SA, личный бортовой номер и лючок зарядки.
- **Свет:** лучи фар и подсветка блоков впереди ночью, стоп-сигналы, бортовые огни авиации (красный / зелёный, строб, маячок), оранжевые маячки вездехода и буровой.
- **Анимации:** колёса, руление, гусеницы, элероны и рули, уборка шасси, наклон винта вертолёта, покачивание ховербайка, искры бура; седоки наклоняются вместе с корпусом.
- **Кабины:** мир виден только через стёкла; приборные панели под каждый тип (автомобильный щиток, вертолётная кабина с верхним пультом и окном в полу, «шестёрка» самолёта); живые стрелки и экраны (скорость, высота, заряд, авиагоризонт, курс), лампы с ролями и реакцией на события, запуск с тестом ламп; подвижные руль, ручка, шаг-газ, штурвал, РУД и педали; отделка салона по уровню; ночная подсветка в цвет уровня.
- **Предметы-машины** — вращающиеся 3D-модели в инвентаре.

### Вождение
- **Звуки:** у каждой машины свой синтезированный звук, тон зависит от скорости и нагрузки, в закрытой кабине тише.
- **Камера техники** от третьего лица — дальше по размеру машины, плавная; колёсико меняет дистанцию.
- **Круиз-контроль** (O) у наземных машин и ховербайка, **автопилот** самолётов (курс + высота).
- **Дороги:** асфальт (+30% скорости, износ ×0,5), бетон (+20%), разметка, бордюр, знаки, светофор от редстоуна, отбойник.

### Мастерская и логистика
- **Ремонтный гараж:** контроллер + плиты пола; ремонт корпуса и остывание мотора за EU и сталь, голограмма, выход компаратора.
- **Тюнинг** (до 4 на машину, ставится в гараже): Мотор+ I/II, Аккумулятор+, Броня, Лёгкий корпус, Большие колёса, Люстра фар, Усиленный бур, Доп. бак — видно на модели.
- **Ключ-карта и права:** Водить / Пассажир / Багажник / Ремонт для каждого игрока, режимы Открыта / Друзья / Владелец, смена замка.
- **Прицепы вездехода:** грузовой, цистерна (16 000 мБ жидкости или газа, заправка техники рядом), платформа (везёт багги или ховербайк), передвижная зарядка (4 млн EU, солнце).
- **Карта техники** (M или кнопка на станции): где ваши машины, их заряд и износ.

### Блоки и предметы
- **Инженерный верстак** (MV): сетка 5×5, делает все компоненты (3×3, мгновенно) и все машины (5×5, EU и время); слоты улучшений ядра — Трансформаторный модуль нужен машинам QV–SV (вход HV–IV).
- **Зарядная станция техники** + **плиты зарядной платформы** (обычная, «H», с огнём): площадка любой формы до 121 плиты, 3×3 / 3×5 / 5×5 / 7×7 по размеру машины, бонус за лишние плиты (до +200%); модули газа, ремонта, доступа и быстрой зарядки (×2).
  - **Быстрая зарядка:** режим «Форсаж» (×3, станция греется — через 2 мин пауза 30 с; гелий из колонки через модуль газа, 1 мБ/с, охлаждает); несколько станций на одной площадке складывают скорость; накопитель энергии Silicon Age или IC2 выходом к станции заряжает машину напрямую, без кабеля; **Суперконденсатор станции** (50 млн EU, от сети до 2 048 EU/т) отдаёт машине до 500 000 EU/т; **Сингулярная зарядная станция** (SV, быстрая зарядка встроена). Корабль 256 млн EU: SV-станция с накопителем рядом, площадка от 69 плит — ~16 с, с форсажем — ~5 с.
- **Газовая колонка** (HV): баки всех газов ядра, заправляет машины владельца рядом.
- **Взлётно-посадочная полоса**: разбег самолётов ×0,5, мягкое торможение, огни по краю.
- **Прицеп** вездехода; 24 компонента (колесо, гусеница, моторы, турбо-, антигравитационный и сингулярный двигатели, винт, крыло, бортовой компьютер, газовый бак, кабина, корпуса I–V, энергоблоки MV–SV, расширитель бура).

### Управление
| Клавиша | Действие |
|---|---|
| **ПКМ** по машине | Сесть (владелец — за руль, остальные — пассажирами) |
| **Shift + ПКМ** | Багажник (и кнопка «Убрать в инвентарь»); гаечный ключ ядра — сразу в инвентарь |
| **W / S, A / D** | Газ / тормоз и задний ход, поворот (у авиации — вперёд / назад, разворот) |
| **Пробел** | Тормоз; ховербайк — выше; вертолёт, челнок, корабль — вверх; самолёт — нос вверх |
| **X** («Снижение»)\*, **Ctrl** (бег) | Авиация — вниз / нос вниз |
| **F** («Форсаж»)\* | Ховербайк, реактивный, конвертоплан, челнок, корабль |
| **B**\* | Бур: вкл / выкл |
| **N** («Режим»)\* | Режим бура; конвертоплан — вертикальный / самолёт; челнок и корабль — «Дальний режим» |
| **V**\* | «Прыжок» (Сингулярный корабль) |
| **Y**\* | Цель прыжка: Координатор / дом |
| **Z**\* | Гравитационный якорь |
| **C**\* | Невидимость для мобов |
| **Shift** (выйти) | Встать с места |
| ПКМ ячейкой / ведром газа | Заправить машину (может любой) |
| ПКМ Координатором ядра | Точки A / B автодоставки вездехода |
| **L** («Багажник»)\* | Багажник, сидя в машине |
| **O** («Круиз»)\* | Круиз-контроль / автопилот |
| **Колёсико** | Дистанция камеры; при круизе — скорость ±5 км/ч |
| **M**\* | Карта техники (в машине; пешком - кнопка в меню) |
| **&#96;** / **Ё** («Техника») | Меню функций машины: состояние, кнопки, назначение клавиш |
| **;** («Запуск двигателя»)\* | Запустить / заглушить двигатель |
| **[** / **]**\* | Закрылки − / + |
| **U**\* | Воздушный тормоз / интерцепторы |
| **I**\* | Окно панели автопилота (лёгкие самолёты) |
| **'**\* | Страница MFD |
| **\\**\* | IDENT ответчика |
| **.** («Шасси»)\* | Шасси (лайнер) |
| **Левый Alt** («Курсор»)\* | Лайнер: держать — курсор в кабине, клик по кнопкам и ручкам |
| **M**\* | Лайнер, кресло пилота: МФПУ |
| **,** / **-**\* | Лайнер: отключить автопилот / автомат тяги |
| **=** («Вид из кабины»)\* | Лайнер: быстрые виды (нажатие — вид / назад; удержание + колёсико — выбор) |
| Пустая ключ-карта: ПКМ / Shift + ПКМ по машине | Карта владельца / окно доступа |

\* Клавиши функций машины назначаются в меню «Техника» (клавиша **&#96;** / **Ё**): любая клавиша, с Ctrl / Shift / Alt, или средняя / боковая кнопка мыши; хранятся в `config/SiliconAgeVehicles_keys.cfg`. Работают только в машине и не конфликтуют с другими модами. В настройках управления (раздел «Кремниевый век: Техника») осталась одна клавиша - самого меню.

### Требования и установка
1. Minecraft **1.7.10**, Forge **10.13.4.1614** (или новее для 1.7.10), Java 8.
2. Мод **[Silicon Age](https://github.com/cani2223322-ux/SiliconAge/releases) 0.2.0** (SiliconAgeBeta-0.2.0) или новее (обязателен; со старым ядром Forge не загрузит аддон).
3. Положите jar аддона в папку `.minecraft/mods` рядом с Silicon Age. IndustrialCraft 2 и WAILA — необязательны.
4. NEI (Not Enough Items) — необязателен: если он установлен, все рецепты Инженерного верстака видны в NEI (страница «Инженерный верстак»: сетка 5×5, EU, время, нужный вход и трансформаторы). R — как сделать, U — где используется (и U по самому верстаку — все рецепты), клик по стрелке в окне верстака — список рецептов, «?» — подсказка раскладки в сетке, Shift + «?» — разложить, как кнопкой «Разложить». Без NEI аддон работает как обычно.

### Сборка из исходников
1. JDK 8 и Gradle 2.14 (ForgeGradle 1.2).
2. Рядом должен лежать проект ядра `../SiliconAge` с собранными классами (`build/classes/main`), в `libs/` — `industrialcraft-2-2.2.828-experimental.jar` и для плагина NEI `NotEnoughItems-1.7.10-1.0.5.120-universal.jar`, `CodeChickenCore-1.7.10-1.0.7.48-universal.jar`, `CodeChickenLib-1.7.10-1.1.3.141-universal.jar` (только для компиляции, в jar не попадают; берутся из `../SiliconAge/libs`).
3. `gradle build` — jar появится в `build/libs/`.

### Настройки
Всё новое выключается в `config/SiliconAgeVehicles.cfg` (раздел `vehicles`): `textures`, `liveries`, `headlights`, `dynamicLight`, `navLights`, `animations`, `liveInstruments`, `icons3d`, `interiorTrim`, `nightLighting`, `cockpitStartup`, `cockpitSounds`, `sounds`, `vehicleCamera`, `cruise`, `autopilot`, `tuning`, `locks`, `trailerKinds`, `vehicleMap`, `stationBoost`, `stationStorageLink`, `stationsAddUp` и другие.

Новое в 0.1.1 (почти всё по умолчанию включено): `fuelMode` (HYDROGEN / EU), `batteryWear`, `ins`, `insAlignSeconds`, `glassCockpit`, `cockpitHiRes`, `cockpitQuality`, `highLift`, `avionics`, `airfield`, `landingLamps`, `engineStart`, `failures`, `gearDoors`, `gearStruts`, `jetNozzle`, `jetHud`, `regen`, `radarAltCallouts`, параметры лайнера `liner*` (`linerTextures`, `linerLod`, `linerQuickStart`, `linerIcing`, `linerQuickViews`, `linerHideHand`, `linerTow`, `linerSeatBelts` и др.); раздел `airdefense` (`enabled`, `mode`, `minWarningSeconds`, `models`, `upgrades` и др.).

### Известные проблемы
- Большая часть клиентской отрисовки (лайнер: кабина, экраны, огни, лёд, окна «Пульты» и МФПУ; модели и окна ПВО; кабины C+) проверена автотестами и программными превью, но мало — в самой игре.
- Отложено: у лайнера — DUAL INPUT, кабель наземного питания, объявления голосом, модель тягача, второй план полёта (SEC F-PLN), зоны ожидания, VOR / ADF; у ПВО — перетаскивание зон, рамка зоны на HUD реактивного; у водорода — шланг и непрерывная заправка от цистерны.
- В журнале (`latest.log`) пока остаются диагностические строки `[SVA-MOVE]` и `[SVA-AFLOOR]` — они нужны для поиска рывков и ложной альфа-защиты; если заметите такую проблему, приложите их к отчёту.

### Лицензия
© 2026 Aleksandr. Все права защищены. Код открыт для просмотра; копирование, изменение — только с разрешения автора. Разрешено использовать мод в сборках и распространять.

---

## English

**Silicon Age: Vehicles** is an add-on to the [Silicon Age](https://github.com/cani2223322-ux/SiliconAge) mod for Minecraft 1.7.10: eleven vehicles - from an electric buggy to the SA-320 airliner and the Singular Ship - their charging, hydrogen and gas refuelling and assembly on the Engineering Workbench, plus air defence: transponders, radars, zones, AA weapons and a command post. Every vehicle has its own niche: cargo, a group of players, heavy work, long trips, early mobility. Most vehicles' engines burn the core's hydrogen and their electrics run on a removable battery; the core's gases also power the special modes (lift, cooling, afterburner, hull field, oxygen, long jumps). Recipes use only Silicon Age and vanilla items.

> ⚠️ **Alpha 0.1.1.** Everything main works and is covered by automated tests, but much (especially the airliner's, the air defence's and the new cockpits' rendering) is not yet played through in game - see "Known issues". Expect bugs: back up your worlds and report problems in [Issues](https://github.com/cani2223322-ux/SiliconAgeVehicles/issues).

### Vehicles
| Vehicle | Tier | EU buffer | Speed | Seats | Cargo | Special |
|---|---|---|---|---|---|---|
| Electric Buggy | MV | 400k | 36 km/h | 2 | 9 | the first vehicle, scouting |
| Hoverbike | QV | 4M | 60 km/h | 1 | 9 | hovers over ground, water and lava on hydrogen |
| Light Helicopter | HV | 3M | 50 km/h | 2 | 9 | vertical take-off, hover, autorotation |
| Light Plane | HV | 4M | 81 km/h | 3 | 9 | cheap long trips, take-off run, runway x0.5 |
| Cargo Crawler | HV | 2M | 28 km/h | 2 | 54 (+54 trailer) | tracks, trailer, A ↔ B auto-delivery |
| Drilling Vehicle | EV | 8M | 12 km/h | 1 | 108 | 3x3 / 5x5 tunnels, ore only, levelling |
| Jet | QV | 16M | 121 km/h | 1 | 9 | afterburner (up to 180 km/h) |
| Tiltrotor | XV | 32M | 101 km/h | 6 | 27 | vertical mode + airplane mode |
| Transport Shuttle | XV | 32M | 81 km/h | 8 | 27 | sealed cabin with O₂, long-range mode on deuterium, core bridge docking |
| Singular Ship | SV | 256M | 121 km/h | 6 | 54 | its own portal (Jump), gravity anchor, invisibility to mobs |
| **SA-320 Liner** | EV | 4M + H₂ 48,000 mB | 144 km/h | 10 | 54 | a twin-engine airliner with A320-style systems |

- **Aircraft** fly up to Y 200; without power helicopters autorotate, planes glide 1:8; seated riders take no fall damage. No vehicle goes faster than 180 km/h (the server keeps loading the world ahead).
- **Transport Shuttle**: free flight in every direction, 200 EU/t; the crew breathes the cabin's oxygen under water (1 mB/s a person); above Y 120 at speed the long-range mode burns deuterium 1 mB/s instead of EU. Fly into an open vortex of the core's Ground or Space Bridge and the shuttle comes out on the other side (another dimension too) with its people and cargo; the pilot needs access to the bridge, the shuttle pays the pass's mass toll itself.
- **Singular Ship**: everything the shuttle does, faster, 400 EU/t; the Jump opens a portal to the point of a core Coordinator in its cargo or home (the pilot's bed): 16 blocks or more, 20M EU + 2M per 1000 blocks and 10 mB + 10 per 1000 blocks of singular matter (another dimension - 100M EU and 100 mB), a 30 s cooldown, the arrival spot must be free and not in a foreign private field. The Gravity Anchor holds the ship still (40 EU/t), Invisibility hides the crew from hostile mobs (1 mB/s SM). The singularity over the hull pulses.
- **Hull wear** (config `vehicles.wear`), motor heat with a stall (helium cools), limp mode without charge, owner and access, foreign private fields are never touched, a per-player vehicle limit (`vehicles.maxPerPlayer`).

### Hydrogen and a removable battery
- **The buggy, hoverbike, crawler, drilling vehicle, helicopter, light plane, jet and tiltrotor** run on the core's hydrogen: H₂ tank → engine → drive and generator → battery → electrics. A full tank lasts 40-65 minutes; the burn grows with throttle, cargo, a trailer, climbing and tuning. The shuttle and the Singular Ship stay on EU.
- **Engine start** - the "Engine start" key (`;`) or just forward; out of hydrogen the engine stops (a car rolls out, a plane glides, a helicopter autorotates).
- **Refuelling:** the gas column, the tanker trailer, the charging station's gas module, core cells and buckets.
- **The battery** is each vehicle's own item (8 kinds, MV-EV): without it the engine won't start and the electrics are dead. The "Battery" slot is on the cargo screen's "Vehicle" tab; it charges in core and IC2 storages, from the generator and at a station. Wear, service in the garage ("Electrolyte") or with the "Battery Service Kit"; "Jumper Cables" jump-start from another vehicle.
- **HUD:** an H₂ bar, the flow and range, a battery line; "LOW FUEL" / "RESERVE". Old worlds: a vehicle gets a full tank and a battery by itself; `vehicles.fuelMode = EU` keeps everything as before.

### Aircraft: cockpits and board systems
- **New C+ cockpits:** the light plane - "Hybrid + MFD" (a lamp strip, an autopilot bar, a PFD, an MFD with ENG / MAP / ROUTE / RWR pages, a transponder); the helicopter - "Hybrid + overhead panel" (a lamp strip, a radar altimeter with callouts, a hover indicator, a 3 x 5 overhead panel, a floor window).
- **Sharp instruments for 2K / 4K:** an anti-aliased font with Cyrillic instead of the 8 px one, baked dial faces, mipmaps; `vehicles.cockpitHiRes`, `vehicles.cockpitQuality` (AUTO / 1K / 2K / 4K).
- **INS, A320 ADIRS style:** aligns in 10 s standing still with board power; until then ATT / HDG flags on the PFD, no map, no autopilot.
- **No power - dark cockpit:** with the battery off, flat or removed the screens, lamps and autopilot are dark; only airspeed, altimeter and vario still read.
- **High-lift devices and autopilot:** flaps, slats, spoilers / airbrake; an autopilot panel HDG / ALT / VS / SPD / CAP / ROUTE, an HSI, a flight plan on the map; an overhead panel with switches, engine start, failures with wear; gear doors, struts, the jet's nozzle and HUD.
- **Airfield:** "PAPI Lights" and "Approach Lights" blocks, runway marks and lights appear by themselves; landing lights under the wing.
- **Weathering and markings:** soot, oil streaks, chipped paint, mud; stencils and registrations on aircraft.
- **Regenerative braking** when braking, rolling downhill and gliding (`vehicles.regen`).

### SA-320 Liner
A twin-engine hydrogen airliner (EV tier, ~18.5 x 17.6 x 5.7 blocks): 10 seats (the captain on the left, the F/O on the right, 8 passengers), boarding through door L1 with its stairs. Built in 7 waves; the systems are simplified but follow the A320's logic:
- **Electrics, APU, bleed air, engine start** (ENG MODE IGN/START, ENG MASTER, an air start with the APU bleed, X BLEED, start faults), a one-button quick start, ground power from a charging station.
- **Hydraulics** Green / Blue / Yellow (pumps, PTU, RAT, leaks, "Hydraulic Fluid"), **flight controls and laws** (ELAC / SEC / FAC; NORMAL / ALTN / DIRECT / MECH), **gear** with gravity extension, **brakes**, anti-skid, autobrake, brake temperatures, nose-wheel steering.
- **Fuel:** L / C / R tanks with inner and outer cells, 6 pumps, crossfeed, imbalance; **ECAM** with messages, actions, memos, SD pages and checklists; MASTER WARN / CAUT.
- **FCU, autopilot and flight director, autothrust** (levers with IDLE / CL / FLX / TOGA detents, reverse), a 5-column FMA, protections (alpha floor, VMO, bank); the **"ILS Beacon" block**, **autoland** with FLARE / RETARD / ROLL OUT, height callouts, go-around.
- **MCDU / FMS:** two MCDUs (the M key in a pilot seat), INIT, IRS INIT, F-PLN with revisions, DIR TO, a T/C / T/D vertical profile, PERF and V-speeds, FUEL PRED, PROG, RAD NAV, a waypoint and airport database.
- **Lights, anti-ice and icing, air conditioning:** exterior lights (everyone sees them), cockpit and cabin lighting, seat-belt signs; ice on the wing, engines and probes, heating; temperature zones, generator loads.
- **ADIRS:** three ADIRUs (NAV / ATT, alignment, IRS INIT, drift and GPS), ADR voting, unreliable speed, per-side PFD flags, an independent ISIS.
- **Ground handling:** door R1 and the cargo doors, wheel chocks, towing by a tug (its own tow bar); **passengers** buckle up by the sign; **fire** in the engines and the APU with extinguisher bottles; **sidestick priority** (the F/O can take control).
- **Cockpit:** 3D clicking on the controls (hold **Left Alt** - "Cursor"), the **"Panels"** screen (overhead, pedestal, FCU, main panel with live displays; zoom and pan), **quick views** (key **=**: PFD / ND, ECAM, overhead, pedestal, windows), the hand hidden in the pilot seats. The panel textures are baked by quality tier 4K / 2K / 1K (`vehicles.linerTextures`), the model has three levels of detail.

### Air defence
- **Transponders** on everything that flies (OFF / STBY / A / C / S, codes 0000-7777, IDENT, a "day key" and the "Code Card").
- **Radars:** surveillance, tracking and height finder; the RCS by vehicle type, weather, terrain and the Singular Ship's invisibility change the range. The **RWR** (an aircraft tuning) shows who is painting you.
- **Zones** (forbidden / restricted / aerodrome / corridor) show to pilots on the map, the HSI and the HUD; **rules of engagement** with mandatory warnings.
- **AA gun, SAM launcher, command post** (map, tracks, log, alarm, zone editor, settings). By default the server only warns; fire - `airdefense.mode = fire`.
- **Multiblock 3D models** with animation and lamps, block screens, **4 slots for core modules** (Universal Transformer, Transformer, Energy Storage, Overclocker, Economizer, Overdrive, Puller). The vehicle charging station takes the Universal Transformer too.

### Look and cockpits
- **Textures and liveries:** 16x16 material textures, panel seams and rivets; a livery by tier (I work with a black-yellow band, II civil, III silicon with glowing lines, Singular with purple ones); an SA emblem, a personal board number and a charge hatch on the sides.
- **Lights:** headlight beams and block light ahead at night, brake lights, aircraft navigation lights (red / green, strobe, beacon), amber beacons on the crawler and the drill.
- **Animations:** wheels, steering, tracks, ailerons and rudders, retracting gear, helicopter rotor tilt, hoverbike bob, drill sparks; riders lean with the body.
- **Cockpits:** the world shows only through the glass; a dashboard per type (car cluster, helicopter cockpit with an overhead panel and a floor window, the plane's six-pack); live needles and screens (speed, altitude, charge, attitude, heading), lamps with roles that react to events, a start-up lamp test; moving wheel, cyclic, collective, yoke, throttle and pedals; interior trim by tier; night lighting in the tier colour.
- **Vehicle items** are rotating 3D models in the inventory.

### Driving
- **Sounds:** every vehicle has its own synthesized sound, pitch follows speed and load, quieter inside a closed cabin.
- **Vehicle camera** in third person: farther by the vehicle's size, smooth; the mouse wheel changes the distance.
- **Cruise control** (O) for ground vehicles and the hoverbike, **autopilot** for planes (heading + altitude).
- **Roads:** asphalt (+30% speed, wear x0.5), concrete (+20%), markings, curb, signs, a redstone traffic light, guard rail.

### Workshop and logistics
- **Repair garage:** a controller + floor plates; repairs the hull and cools the motor for EU and steel, hologram, comparator output.
- **Tuning** (up to 4 per vehicle, fitted in the garage): Motor+ I/II, Battery+, Armour, Light hull, Big wheels, Light bar, Reinforced drill, Extra tank - visible on the model.
- **Key card and access:** Drive / Passenger / Cargo / Repair per player, modes Open / Friends / Owner, change the lock.
- **Crawler trailers:** cargo, tanker (16,000 mB of a fluid or gas, refuels vehicles nearby), platform (carries a buggy or hoverbike), mobile charger (4M EU, solar).
- **Vehicle map** (M or the station button): where your vehicles are, their charge and wear.

### Blocks and items
- **Engineering Workbench** (MV): a 5x5 grid, makes every component (3x3, instant) and every vehicle (5x5, EU and time); core upgrade slots - QV-SV vehicles need Transformer upgrades (HV-IV input).
- **Vehicle Charging Station** + **charging platform plates** (plain, "H", edge light): a platform of any shape up to 121 plates, 3x3 / 3x5 / 5x5 / 7x7 by the vehicle's size, a bonus for extra plates (up to +200%); gas, repair, access and fast charge (x2) modules.
  - **Fast charging:** the "Boost" mode (x3, the station heats up - a 30 s pause after 2 min; helium from the column through the gas module, 1 mB/s, cools it); several stations on one platform add up; a Silicon Age or IC2 energy storage with its output towards the station charges the vehicle directly, no cable; the **Station Supercapacitor** (50M EU, up to 2,048 EU/t from the grid) gives the vehicle up to 500,000 EU/t; the **Singular Charging Station** (SV, fast charge built in). The 256M EU ship: an SV station with a storage beside it on a 69+ plate platform - ~16 s, with the boost - ~5 s.
- **Gas Column** (HV): tanks of every core gas, refuels its owner's vehicles nearby.
- **Runway**: plane take-off run x0.5, soft braking, edge lights.
- The crawler's **Trailer**; 24 components (wheel, track, motors, turbo, antigravity and singular engines, rotor, wing, on-board computer, gas tank, cabin, hulls I-V, energy blocks MV-SV, drill widener).

### Controls
| Key | Action |
|---|---|
| **Right-click** a vehicle | Get in (the owner drives, others ride as passengers) |
| **Shift + right-click** | Cargo (and the "Pick up" button); a core wrench - straight into the inventory |
| **W / S, A / D** | Throttle / brake and reverse, steer (aircraft: forward / back, turn) |
| **Space** | Brake; hoverbike - higher; helicopter, shuttle, ship - up; plane - nose up |
| **X** (Descend)\*, **Ctrl** (sprint) | Aircraft - down / nose down |
| **F** (Boost)\* | Hoverbike, jet, tiltrotor, shuttle, ship |
| **B**\* | Drill on / off |
| **N** (Mode)\* | Drill mode; tiltrotor - vertical / airplane; shuttle and ship - long-range mode |
| **V**\* | Jump (Singular Ship) |
| **Y**\* | Jump target: Coordinator / home |
| **Z**\* | Gravity anchor |
| **C**\* | Invisibility to mobs |
| **Shift** (dismount) | Get off |
| Right-click with a gas cell / bucket | Refuel the vehicle (anyone may) |
| Right-click with a core Coordinator | The crawler's auto-delivery points A / B |
| **L** (Cargo)\* | Cargo while seated |
| **O** (Cruise)\* | Cruise control / autopilot |
| **Mouse wheel** | Camera distance; with cruise on - speed ±5 km/h |
| **M**\* | Vehicle map (riding; on foot - the menu's button) |
| **&#96;** (Vehicle menu) | The vehicle's functions: state, buttons, key setup |
| **;** (Engine start)\* | Start / stop the engine |
| **[** / **]**\* | Flaps - / + |
| **U**\* | Airbrake / spoilers |
| **I**\* | Autopilot panel screen (light aircraft) |
| **'**\* | MFD page |
| **\\**\* | Transponder IDENT |
| **.** (Gear)\* | Gear (liner) |
| **Left Alt** (Cursor)\* | Liner: hold - the cockpit cursor, click buttons and knobs |
| **M**\* | Liner, a pilot seat: the MCDU |
| **,** / **-**\* | Liner: autopilot / autothrust disconnect |
| **=** (Cockpit view)\* | Liner: quick views (tap - view / back; hold + wheel - pick) |
| A blank key card: right-click / Shift + right-click a vehicle | Owner card / access screen |

\* The vehicle function keys are set in the vehicle menu (key **&#96;**): any key, with Ctrl / Shift / Alt, or a middle / side mouse button; kept in `config/SiliconAgeVehicles_keys.cfg`. They work only while riding and don't clash with other mods. The controls settings ("Silicon Age: Vehicles") keep just one key - the menu's.

### Requirements and installation
1. Minecraft **1.7.10**, Forge **10.13.4.1614** (or newer for 1.7.10), Java 8.
2. **[Silicon Age](https://github.com/cani2223322-ux/SiliconAge/releases) 0.2.0** (SiliconAgeBeta-0.2.0) or newer (required; Forge won't load the add-on with an older core).
3. Put the add-on jar into `.minecraft/mods` next to Silicon Age. IndustrialCraft 2 and WAILA are optional.
4. NEI (Not Enough Items) is optional: with it every Engineering Workbench recipe shows in NEI (the "Engineering Workbench" page: 5x5 grid, EU, time, required input and transformers). R - how to make, U - where it is used (U on the workbench itself - all recipes), clicking the arrow in the workbench screen lists the recipes, "?" shows the layout over the grid, Shift + "?" lays it out like the "Lay out" button. Without NEI the add-on works as usual.

### Building from source
1. JDK 8 and Gradle 2.14 (ForgeGradle 1.2).
2. The core project `../SiliconAge` must sit next to it with its classes built (`build/classes/main`); `libs/` holds `industrialcraft-2-2.2.828-experimental.jar` and, for the NEI plugin, `NotEnoughItems-1.7.10-1.0.5.120-universal.jar`, `CodeChickenCore-1.7.10-1.0.7.48-universal.jar`, `CodeChickenLib-1.7.10-1.1.3.141-universal.jar` (compile only, never packed into the jar; copy them from `../SiliconAge/libs`).
3. `gradle build` - the jar appears in `build/libs/`.

### Configuration
Everything new can be switched off in `config/SiliconAgeVehicles.cfg` (category `vehicles`): `textures`, `liveries`, `headlights`, `dynamicLight`, `navLights`, `animations`, `liveInstruments`, `icons3d`, `interiorTrim`, `nightLighting`, `cockpitStartup`, `cockpitSounds`, `sounds`, `vehicleCamera`, `cruise`, `autopilot`, `tuning`, `locks`, `trailerKinds`, `vehicleMap`, `stationBoost`, `stationStorageLink`, `stationsAddUp` and more.

New in 0.1.1 (almost all on by default): `fuelMode` (HYDROGEN / EU), `batteryWear`, `ins`, `insAlignSeconds`, `glassCockpit`, `cockpitHiRes`, `cockpitQuality`, `highLift`, `avionics`, `airfield`, `landingLamps`, `engineStart`, `failures`, `gearDoors`, `gearStruts`, `jetNozzle`, `jetHud`, `regen`, `radarAltCallouts`, the liner's `liner*` options (`linerTextures`, `linerLod`, `linerQuickStart`, `linerIcing`, `linerQuickViews`, `linerHideHand`, `linerTow`, `linerSeatBelts` and more); the `airdefense` category (`enabled`, `mode`, `minWarningSeconds`, `models`, `upgrades` and more).

### Known issues
- Most of the client-side rendering (the liner's cockpit, displays, lights, ice, the "Panels" and MCDU screens; the air-defence models and screens; the C+ cockpits) is checked by automated tests and software previews, but little in the game itself.
- Deferred: for the liner - DUAL INPUT, a ground power cable, voice announcements, a tug model, the secondary flight plan (SEC F-PLN), holdings, VOR / ADF; for air defence - dragging zones, the zone frame on the jet's HUD; for hydrogen - a hose and continuous refuelling from the tanker.
- The log (`latest.log`) still has the diagnostic lines `[SVA-MOVE]` and `[SVA-AFLOOR]` - they help hunt snap-backs and a false alpha floor; if you see such a problem, attach them to your report.

### License
© 2026 Aleksandr. All rights reserved. The code is open to read; copying and changing it only with the author's permission. You may use the mod in modpacks and redistribute it.
