# MediaPresentationState — единый источник истины для медиа

> Реализовано по плану архитектора: **переписать не TransferEngine, а СОСТОЯНИЕ**.
> Транспорт (Rust / сеть / `TransferEngine`) не трогаем — меняем только «мозг» UI.

## Проблема (диагноз архитектора, подтверждён анализом кода)

У каждого медиа-пузыря было **пять хозяев**, и каждый решал, что рисовать:

```
TransferEngine → FileUiState ─┐
Streaming      → isStreaming  ─┤
MediaCache     → availability ─┼─→ Bubble   ( five masters ⇒ конфликты )
Repository     → media_ref    ─┤
Optimistic     → pending msg  ─┘
```

`VideoBubble` (ChatScreen.kt:5703) — хрестоматийный пример: он одновременно жонглировал
`rememberFileUiState` (Transfer), флагом `isStreaming` из `mediaRefJson` (Streaming),
декодированием `poster` (Thumbnail), `durationMs`/`repository`/`mediaLoader` (Repository)
и `StreamingVideoPlayer` (Playback). Отсюда все симптомы:

- **PNG/JPG → File → Photo**: UI не знает заранее, что это фото; тип «всплывает» после reload.
- **Видео теряет ▶ после отправки**: `playable` зависел от Transfer-состояния, а не от типа.
- **Альбом у отправителя без оверлея, у получателя работает**: две разные логики (optimistic vs download).
- **MP3 / файл / видео / фото — у каждого своя логика**.

## Решение: один хозяин — `MediaPresentationState`

```
Transfer ─┐
Streaming ─┤
Cache     ─┼─→ MediaPresenter.present() ─→ MediaPresentationState ─→ Bubble (рисует, ничего не знает)
Playback  ─┤
Repository─┘
```

Пузырь получает **один** immutable `MediaPresentationState` и рисует. Он не знает ни о
трансферах, ни о стриминге, ни о кэше, ни об optimistic — они больше не могут «поспорить»
внутри пузыря.

### Почему каждый баг исчезает

| Баг | Причина | Фикс в новом слое |
|---|---|---|
| PNG/JPG становится файлом | тип определялся поздно, из reload | `MediaKind.from(message)` фиксируется **один раз** на `message.id` и не меняется → тип пузыря стабилен всю жизнь сообщения |
| Видео теряет ▶ после отправки | `playable` зависел от Transfer | `playable = kind.isPlayable` (video/audio/voice ⇒ true), **не зависит** от transfer → Ready всегда даёт ▶ |
| Альбом: 15 отдельных колец | каждый тайл — отдельный transfer | один `MediaPresentationState` с `albumItems` + агрегатный `overlay` → одно кольцо на альбом |
| MP3/файл/видео/фото — разная логика | пять хозяев | один `MediaPresenter`, разный `kind` + контент. Один overlay, разный content |
| ✕ у получателя удалял сообщение | глагол не зависел от направления | `MediaControl` кодирует глагол: `CancelUpload` (✕ отправителя) vs `PauseDownload` (✕ получателя) |

## Файлы нового слоя (пакет `com.alphamessenger.media`)

- **`MediaPresentation.kt`** — `MediaKind` (тип, фиксирован), `MediaControl` (глагол кнопки),
  `MediaPresentationState` (единое состояние), `MediaPresenter` (чистая функция-fold).
  `MediaPresenter.present()` переиспользует battle-tested `fileUiStateOf` для overlay и добавляет
  иммутабельный `kind` + контент сверху — мы НЕ переизобретаем transfer-fold.
- **`RememberMediaPresentation.kt`** — `rememberMediaPresentation(message, mediaActions)`:
  единственный composable, который вызывает пузырь. Подписывается на per-key потоки (как раньше —
  тик файла A не рекомпозит файл B), сам вызывает `consider(...)` (политика prefetch, раньше жила
  в каждом пузыре), декодирует poster, читает длительность/размер, складывает через `MediaPresenter`.
  Для альбома — один агрегатный `overlay` + контент плиток.

## Паттерн перевода пузыря (Stage 3 — постепенно)

Минимальная, безопасная миграция (поверхность изменения минимальна, рендеринг не трогаем):

```kotlin
// БЫЛО (пять хозяев):
val state = rememberFileUiState(mediaActions, message, playable = true, streamable = isStreaming)
LaunchedEffect(refJson) { mediaActions?.consider?.invoke(...) }   // политика в пузыре
// ... отдельное декодирование poster, чтение duration ...

// СТАЛО (один хозяин):
val pres = rememberMediaPresentation(message, mediaActions)
val state = pres.overlay          // ← весь существующий код на `state` продолжает работать
// consider() уже внутри rememberMediaPresentation
// poster = pres.poster, duration = pres.durationLabel, size = pres.sizeLabel
```

Ключевой приём: `val state = pres.overlay` сохраняет тип `FileUiState`, поэтому **весь существующий
рендеринг/интеракции пузыря остаются валидными** — меняется только источник состояния. Это и есть
«постепенно, не ломая» из плана архитектора.

### Порядок перевода (каждый шаг тестируется отдельно)

1. **MediaBubble (фото)** — фиксит PNG→File flip. ✅ переведён.
2. **VideoBubble (видео)** — фиксит пропажу ▶ (`playable` из `kind`).
3. **AudioPlayerBubble (MP3/voice)** — `pres.sizeLabel` («25 КБ / 5 МБ») в нижний ряд, кружок без цифр.
4. **MediaFileBubble (файл)** — `pres.bytesInRow` → байты в строке, кнопка только глифы.
5. **AlbumBubble (альбом)** — `pres.albumItems` + агрегатный `pres.overlay` (один оверлей, и у отправителя).

После перевода всех пузырей старый `rememberFileUiState` и разрозненная логика streaming/poster/cache
в пузырях удаляются — но **только после** того, как новый слой доказал себя на каждом типе.

## Что НЕ меняется

- `TransferEngine` (транспорт) — не трогаем.
- Rust / сеть / `upload_streaming_file` / `download_media_inner` — не трогаем.
- `fileUiStateOf` — переиспользуется внутри `MediaPresenter` (battle-tested fold).
- Per-key потоки (`flowFor`) — сохраняются (scroll-smoothness).

## Проверка (на устройстве, после сборки)

- **PNG/JPG**: отправить фото → сразу `MediaBubble` с миниатюрой, никакого `FileBubble` ни на кадр.
- **Видео**: отправить видео → кольцо загрузки → после отправки ▶ **не исчезает**.
- **Альбом (отправка)**: один общий ⭕% ✕ поверх сетки, плитки без колец.
- **MP3**: кружок без цифр; «25 КБ / 5 МБ» внизу после длительности и `MP3`; после загрузки исчезает.
- **Файл**: «2.3 / 4.7 MB» во 2-м ряду, кнопка только ↓ % ▶ ✕ ↻ ‖.
- **Скролл**: тик одного файла не рекомпозит остальные (per-key потоки сохранены).

## Детальный фикс пропажи ▶ у видео (VideoBubble)

**Баг:** после отправки видео ▶ исчезает — `overlay` становится `Hidden`, когда `upload=Completed`,
а `download` ещё не `Ready`. Пузырь показывает постер «как фото»; ▶ возвращается только после
перезахода в диалог (когда `consider()` разрешает download в `Ready`).

**Корень (в терминах нового слоя):** старый `VideoBubble` решал, показывать ли ▶, по **состоянию
трансфера** (`state is Ready`), а не по **типу**. Архитектор: «`playable=true` не зависит от Transfer».

**Фикс при переводе VideoBubble на слой:**
```kotlin
val pres = rememberMediaPresentation(message, mediaActions)
val state = pres.overlay                       // весь существующий код на `state` валиден
// удалить отдельные isStreaming / consider() — они внутри rememberMediaPresentation

// ▶ рисуем по факту «видео можно воспроизвести», НЕЗАВИСИМО от состояния трансфера:
val canPlay = pres.playable && (pres.isReady || readyPath != null)
// Overlay (кольцо / ✕) рисуется поверх ТОЛЬКО когда state.isActive:
MediaSurface(state = if (state.isActive) state else FileUiState.Hidden, ...) { poster }
if (canPlay && !state.isActive) { PlayAffordance(onClick = onOpen) }   // ▶ всегда, когда играется
```

Так ▶ живёт своей жизнью (зависит от `pres.playable` + доступности байт), а overlay — своей
(зависит от трансфера). Они больше не «спорят»: нет ни «▶→⭕→▶», ни пропажи ▶ после отправки.

## Статус перевода

- ✅ **`MediaPresentation.kt`** + **`RememberMediaPresentation.kt`** — единый слой (тип, презентер, адаптер).
- ✅ **Диспетчер `MessageBubble`** — выбор пузыря по иммутабельному `mediaKind` (фикс PNG→File flip).
- ✅ **`MediaBubble` (фото)** — переведён на `rememberMediaPresentation`.
- ⏳ **`VideoBubble`** — перевести по паттерну выше (фикс пропажи ▶).
- ⏳ **`AudioPlayerBubble`** — `pres.sizeLabel` («25 КБ / 5 МБ») в нижний ряд, кружок без цифр (`showSizeLabel=false`).
- ⏳ **`MediaFileBubble`** — `pres.bytesInRow` → байты в строке, кнопка только глифы.
- ⏳ **`AlbumBubble`** — `pres.albumItems` + агрегатный `pres.overlay` (один оверлей, и у отправителя).

Каждый шаг — отдельная правка, тестируется изолированно (по плану архитектора — постепенно).
Старый `rememberFileUiState` удаляется **только после** перевода всех пузырей.
