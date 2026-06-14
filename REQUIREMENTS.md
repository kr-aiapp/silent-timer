# Silent Timer — Wymagania

## Funkcjonalne

1. **Uprawnienia** — przy starcie aplikacja sprawdza WSZYSTKIE potrzebne uprawnienia
   i jeśli któregoś brakuje, prosi o nie użytkownika:
   - `ACCESS_NOTIFICATION_POLICY` (Do Not Disturb access) — do wyciszania
   - `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` (Android 12+) — do dokładnego timera
   - `POST_NOTIFICATIONS` (Android 13+) — do powiadomienia o stanie wyciszenia

2. **Synchronizacja pickerów** — przewijanie jednej kolumny dynamicznie (na żywo,
   nie tylko po puszczeniu) przewija drugą kolumnę.

3. **Zapamiętywanie ustawień dźwięku** — przed wyciszeniem aplikacja zapisuje
   pełny stan audio (ringer mode, poziomy głośności strumieni). Przy przywracaniu
   odtwarza DOKŁADNIE te zapisane ustawienia (nie suwak "restore volume" jako jedyne
   źródło — suwak jest opcjonalnym nadpisaniem poziomu dzwonka).

4. **Wibracje przy wyciszeniu** — przełącznik włącz/wyłącz wibracje w trybie
   wyciszenia. Domyślnie WŁĄCZONE (RINGER_MODE_VIBRATE). Wyłączone =
   RINGER_MODE_SILENT (cisza całkowita).

5. **Powiadomienie o stanie wyciszenia** — gdy telefon jest wyciszony, w pasku
   powiadomień jest aktywne (trwałe) powiadomienie z informacją:
   - o której godzinie wyciszenie się skończy (np. "do 20:34")
   - za ile czasu (np. "za 14 min")

6. **Przycisk Restore volume w stanie wyciszenia** — gdy użytkownik otworzy
   aplikację, a telefon JEST już wyciszony przez tę aplikację, zamiast "Mute now"
   pokazuje się przycisk "Restore volume", który natychmiast przywraca dźwięk.

7. **Zamykanie aplikacji** — po kliknięciu "Mute now" lub "Silent for a while"
   aplikacja się zamyka (finish()).

## Stan zapisywany (SharedPreferences)
- `is_silenced` (Boolean) — czy aktualnie wyciszone przez aplikację
- `saved_ringer_mode` (Int) — ringer mode sprzed wyciszenia
- `saved_ring_volume` (Int) — poziom STREAM_RING sprzed wyciszenia
- `saved_notif_volume` (Int) — poziom STREAM_NOTIFICATION sprzed wyciszenia
- `restore_at_millis` (Long) — timestamp przywrócenia (dla powiadomienia)
- `vibrate_enabled` (Boolean) — preferencja wibracji (domyślnie true)
- `restore_volume_override` (Int 0-100) — pozycja suwaka "restore volume"
