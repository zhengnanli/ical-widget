# iCal Widget

Android home-screen widgets for an iCalendar (`.ics` / `webcal://`) subscription, such as a published Outlook / Office 365 calendar. No account sign-in, no extra libraries.

## Widgets

| Widget | Portrait | Landscape |
|---|---|---|
| **Calendar agenda** | Header + upcoming events grouped by day | Date panel on the left, events on the right |
| **Month calendar** | Month grid with today highlighted and a dot on days with events; ‹ › to change month, tap the title for this month. Tap a date to open that day | Month grid + events side by side; tap a date to start the list there (tap it again to go back) |
| **Month (simple)** | The current month with today highlighted and a dot on days with events; tap a date to open that day | Same |

Tapping a date or an event opens a day view with that day's events (links clickable) and ‹ › to step through days.

Colors follow the system's Material You palette on Android 12+, light and dark. Recurring meetings use shades of the primary color and one-time events use shades of the tertiary color. Each title keeps a stable shade, so a given meeting always looks the same.

## Setup

1. Build and install (needs JDK 17+ and the Android SDK):
   ```sh
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. Open **iCal Widget**, paste your calendar's `.ics` URL, and tap **Save**.
3. Tap **Add agenda**, **Add month** or **Add simple**, or add the widgets from your launcher's widget picker.

To build with your URL already filled in, add it to `local.properties`, which git ignores:
```properties
ical.url=https\://example.com/path/to/calendar.ics
```

Keep your feed URL private. For published Outlook calendars, anyone with the link can read the calendar.

## Behavior

- The feed is downloaded at most every 15 minutes, on the widget's 30-minute update, after midnight, and when you tap refresh. The last good copy is cached, so the widgets still work offline.
- The settings screen also sets how many days the agenda shows (default 14).

## iCalendar support

`app/src/main/java/com/paulli/icalwidget/Ics.kt` is a small RFC 5545 reader covering what calendar feeds publish in practice:

- line folding and text escapes
- time zones, including Windows names from Outlook/Exchange (`Central Standard Time` → `America/Chicago`)
- all-day, UTC and floating times; `DTEND` or `DURATION`
- `RRULE` with `DAILY`/`WEEKLY`/`MONTHLY`/`YEARLY`, `INTERVAL`, `COUNT`, `UNTIL`, `BYDAY` (including `1WE`, `-1FR`), `BYMONTHDAY`, `BYMONTH`, `BYSETPOS`, `WKST`
- `RDATE`, `EXDATE`, moved or cancelled instances (`RECURRENCE-ID`), and `STATUS:CANCELLED`

Run the unit tests with `./gradlew testDebugUnitTest`. To print the expansion of a real feed:
```sh
ICS_FILE=/path/to/calendar.ics ./gradlew testDebugUnitTest
```

## Layout

- `Ics.kt`: parser and recurrence expansion
- `CalendarRepository.kt`: download, cache, settings
- `AgendaRows.kt`: groups events into day sections
- `Widgets.kt`, `CalendarWidgetProvider.kt`, `CalendarWidgetService.kt`: widget rendering and updates
- `tools/gen_month_layouts.py`: generates the fixed 6×7 month-grid layouts. Edit the script, then run it from the repo root.

## License

MIT. See [LICENSE](LICENSE).
