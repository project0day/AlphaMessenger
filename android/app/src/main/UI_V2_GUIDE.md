# BottomSurface V2 — Architecture Guide

> Новая архитектура нижней поверхности чата AlphaMessenger.
> Полностью независима от legacy-реализации. Оригинал не тронут.

## 📁 Структура файлов

```
ui/chat/v2/
├── BottomSurfaceState.kt   — единый state-владелец (BottomSurfaceState)
├── BottomSurfaceV2.kt      — постоянный контейнер (Divider + InputBar + BottomPanel)
├── InputBarV2.kt           — неуничтожаемый InputBar (новый дизайн)
├── BottomPanelV2.kt        — один слот, контент меняется по displayMode
├── EmojiPanelV2.kt         — панель эмодзи (9 категорий + backspace)
├── AttachPanelV2.kt        — панель вложений (плавный drag, 3 detents)
└── RecordingPanelV2.kt     — панель записи (waveform + pause/cancel/send)
```

## 🏗 Главные принципы

### 1. InputBar никогда не уничтожается

`InputBarV2` создаётся **один раз** и живёт до закрытия ChatScreen.
`FocusRequester`, `ScrollState`, `TextFieldValue` хранятся в общем
`BottomSurfaceState` — поэтому они переживают любую смену режима без
пересоздания. В READ_ONLY / ATTACH / RECORDING InputBar скрыт обёрткой
нулевой высоты (`height(0.dp).clipToBounds()`), **но остаётся в дереве**.

### 2. Один владелец геометрии

`BottomSurfaceState.bottomPanelHeight` — **единственное** значение, задающее
высоту нижней панели. Один `Box(height = bottomPanelHeight)` в `BottomPanelV2`.
Никаких вторых spacer'ов, никаких компенсаторов.

### 3. Никаких взаимоисключающих Layout-веток

Запрещено:
```kotlin
if (mode == EMOJI) EmojiPanel() else if (mode == ATTACH) AttachPanel() ...
```
Вместо этого `BottomPanelV2` — это **один постоянный слот**, внутри которого
`AnimatedContent` меняет только **контент** (slide + fade, 160ms). Сам слот
никогда не исчезает.

### 4. Единая точка actuation

`BottomSurfaceV2` содержит единственный `LaunchedEffect(panelMode)`:
- KEYBOARD → requestFocus + show IME
- EMOJI → requestFocus + **hide** IME (панель заменяет клавиатуру)
- ATTACH / NONE / READ_ONLY → hide IME + clearFocus
- RECORDING → keep IME только если `recordingKeepsKb`

`WindowInsets.ime` **только читается**, никогда не пишется → нет циклов.

## 🔄 Переходы режимов

### Тап по полю ввода (NONE → KEYBOARD)
```
поле получает focus → onInputFocused → enterKeyboard()
  holdHeight = 0 (из NONE)
  inputMode = KEYBOARD
→ LaunchedEffect(KEYBOARD): requestFocus + show IME
→ IME поднимается, bottomPanel едет вверх 1:1 (spring TrackSpec)
→ InputBar стоит на месте, без скачков
```

### Тап по эмодзи (KEYBOARD → EMOJI)
```
onEmoji → enterEmoji()
  lastKbHeight = imeContentDp (запомнили высоту КВ)
  panelHeight = max(panelHeight, imeContentDp) (1:1 замена)
  expectingImeHide = true (подавляем auto-switch пока IME прячется)
  inputMode = EMOJI
→ LaunchedEffect(EMOJI): hide IME
→ IME плавно опускается, bottomPanel держит panelHeight
→ InputBar стоит на месте
→ когда IME < 120dp: expectingImeHide = false
```

### Тап по ⌨ (EMOJI → KEYBOARD)
```
onEmoji → enterKeyboard()
  holdHeight = kbFloor (держим высоту пока IME поднимается)
  inputMode = KEYBOARD
→ LaunchedEffect(KEYBOARD): requestFocus + show IME
→ IME поднимается, target = max(ime, holdHeight) — нет провала до 0
→ через 250ms после подъёма IME: holdHeight = 0 (незаметно)
```

### Кнопка Назад (EMOJI/KEYBOARD → NONE)
```
BackHandler → dismiss()
  holdHeight = 0, imeWasUp = false, expectingImeHide = false
  inputMode = NONE
→ LaunchedEffect(NONE): hide IME + clearFocus
→ bottomPanel glide до 0
```

### Системное закрытие IME (Back/swipe при KEYBOARD)
```
bindImeInset видит: imeContentDp < 10 && imeWasUp
  → inputMode = NONE, holdHeight = 0, imeWasUp = false
→ LaunchedEffect(NONE): hide IME + clearFocus
→ InputBar опускается вместе с панелью
```

### Тап по полю при открытой EMOJI
```
Система повторно показывает IME → imeContentDp > 120
→ bindImeInset: inputMode == EMOJI && !expectingImeHide
  → enterKeyboard() (та же точка входа, holdHeight = kbFloor)
→ плавный переход EMOJI → KEYBOARD без скачков
```

## 🎨 Дизайн (новый, не копия оригинала)

### InputBarV2
- **Emoji** — отдельная круглая кнопка СЛЕВА от поля (42dp)
- **Текстовое поле** — мягкая pill-форма (22dp radius, subtle shadow)
- **Attach** — отдельная круглая кнопка СПРАВА
- **Camera** — появляется только при пустом поле (scale + fade анимация)
- **Send/Mic** — 48dp accent-круг, плавный scale-morph

### EmojiPanelV2
- **9 категорий** (Smileys / Gestures / Hearts / Animals / Food / Activity /
  Travel / Objects / Symbols) — таб-бар с accent-подсветкой активной
- **7-колоночная сетка**, 44dp touch-targets
- **Backspace** кнопка снизу-справа (корректно удаляет emoji-суррогаты)
- Drag grabber для изменения высоты (2 detents + close)

### AttachPanelV2
- Переиспользует `MediaPickerContent` (100% функционала сохранено)
- **3 detents**: compact (0.35) / half (0.55) / tall (0.80)
- Плавный continuous drag 1:1 + spring release к ближайшему detent
- Caption bar с accent count-badge и accent send-pill
- Анимированное появление/исчезновение caption bar (expand/shrink)

### RecordingPanelV2
- Timer pill (красная точка + время) слева
- Taller smoother equalizer (56 bars, accent-tinted, rounded caps)
- 3 круглые кнопки: pause/resume, cancel, send (accent)
- Invisible focus anchor для keepKeyboard ( preserves IME при hands-free)

## 🔌 Подключение в ChatScreen.kt

Переключатель:
```kotlin
internal const val USE_BOTTOM_SURFACE_V2 = true
```
- `true` → новая V2 система
- `false` → legacy (оригинал, нетронут)

Точки входа в ChatScreen:
```kotlin
onEmoji = {
    if (v2PanelMode == PanelModeV2.EMOJI) v2State.enterKeyboard()
    else v2State.enterEmoji()
}
onInputFocused = {
    if (v2State.inputMode == PanelModeV2.NONE) v2State.enterKeyboard()
}
onAttach = { v2State.dismiss(); viewModel.setShowAttachPanel(true) }
onEmojiBackspace = { /* cursor-aware delete via codePointCount */ }
BackHandler → v2State.dismiss()
```

## 🚫 Что НЕ используется (удалено из V2)

| Старый компенсатор | Почему удалён |
|---|---|
| `kbRiseFloor` | заменён на чистый `kbFloor` (только чтение) |
| `measuredKbHeight` (false seed) | заменён на `lastKbHeight` (растёт, не падает) |
| `imeUpThisSession` | заменён на `imeWasUp` (state-machine flag, не latch) |
| `imeCollapsing` | удалён — ride IME напрямую через `max(ime, holdHeight)` |
| `imeBottomPx` raw guard | заменён на `imeWasUp` |
| `prevImeDp` direction latch | удалён — `imeWasUp` надёжнее |
| Несколько spacer'ов | один слот `bottomPanelHeight` |

## ✅ Что сохранено

- Весь `ChatViewModel` API (sendMessage, editMessage, sendForward, recording, и т.д.)
- `MediaPickerContent` (галерея, бакеты, камера, файлы, музыка, гео, секрет)
- Hold-to-record gesture (slide-left cancel, slide-up lock, quick-tap lock)
- Char counter (MAX_MESSAGE_CHARS, red при overflow)
- Reply / Edit / Forward preview
- Read-only strips (blocked / removed / closed / restricted / muted)
- Все существующие темы (AlphaColorScheme, 5 тем)
