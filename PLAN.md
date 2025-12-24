# Telefyna Migration Plan (MVVM + Foreground Service)

This plan tracks the ongoing migration to a resilient, 24/7 playback architecture. It complements README with more execution detail.

## Goals
- Decouple playback from Activity lifecycle.
- Survive UI destruction, errors, and device reboots.
- Minimize memory leaks and stabilize over multi‑day operation.

## Architecture Overview
- UI: Monitor Activity bound to PlayerService via MediaController; UI state in MonitorViewModel (StateFlow).
- Playback: PlayerService (MediaSessionService) owns one ExoPlayer + MediaSession, runs in foreground with media notification.
- Repos: MediaRepository (I/O helpers), ScheduleRepository (playlist → MediaItems).
- Persistence: PrefsStore for lightweight playback state.
- Receivers: BootReceiver starts PlayerService; MaintenanceReceiver routes events to the service.

## Work Breakdown
1. UI → MVVM
   - MonitorViewModel for ticker, overlays, diagnostics using StateFlow.
   - Replace GlobalScope/handlers with lifecycleScope/viewModelScope where appropriate.
2. Foreground playback service
   - PlayerService: single ExoPlayer + MediaSession, notification, START_STICKY, watchdog loop.
   - Persist/restore playback position via PrefsStore.
3. Repositories & I/O
   - Move blocking I/O to Dispatchers.IO in repositories.
   - Duration probing and config loading off main thread.
4. Activity ↔ Service binding
   - Build MediaController using SessionToken; attach to PlayerView in onStart; detach in onStop.
5. Receivers and boot
   - BootReceiver → startForegroundService(PlayerService).
   - MaintenanceReceiver → ACTION_MAINTENANCE → PlayerService.
6. Cleanup & stability
   - Remove static Handlers and Monitor.instance usages incrementally.
   - Ensure PlayerView.setKeepContentOnPlayerReset(true); avoid swapping players.
7. Manifest
   - Declare PlayerService with foregroundServiceType="mediaPlayback" and MediaSessionService intent-filter.

## Verification Checklist
- Playback continues after closing Activity.
- Reboot → BootReceiver relaunches PlayerService → playback resumes.
- Network loss → fallback to filler; network restore → recover previous stream.
- Memory usage stable over 24–72 hours (no unbounded growth).

## Next Actions
1. Bind UI to MonitorViewModel and render overlays/ticker/diagnostics purely from state.*
   - Collect MonitorViewModel.uiState in Monitor.onStart with lifecycle-aware collection (repeatOnLifecycle).✓
   - Render ticker text, overlays, and diagnostics from ViewModel only (remove direct UI mutations from Activity).*
   - Expose minimal intents on ViewModel for UI events (e.g., setDiagnostics, setOverlay, setTicker).✓
2. Remove Monitor.instance and legacy playback/scheduling code from Activity.*
   - Find all usages of Monitor.instance and replace with service/repository calls.*
   - Remove/disable Activity-owned ExoPlayer logic and static Handlers; Activity stays UI-only and binds MediaController.*
   - Keep permission prompts and simple UI hooks only in Activity.*
3. Move scheduling (Maintenance) into PlayerService via a watchdog loop.*
   - Create a serviceScope watchdog (SupervisorJob + Dispatchers.Main.immediate) to recompute schedules and switch playlists as needed.*
   - Replace AlarmManager-based timers with internal, idempotent ticks; keep receivers (Boot/DateChange/Maintenance) as nudges to the service.*
   - Handle ACTION_MAINTENANCE inside PlayerService (already routed by MaintenanceReceiver).*
4. Persist and restore playback position via PrefsStore fully inside PlayerService.*
   - Save {playlistIndex, mediaItemIndex, seekPosition} on item transitions and periodically (e.g., every 30–60s).*
   - On service start, load and restore position before preparing media items.*
5. Repository enhancements
   - MediaRepository: move duration probing and config loading to Dispatchers.IO; avoid main-thread I/O.
   - ScheduleRepository: build MediaItems for local/HLS/RTMP/SRT and compute daily playlist ordering.
6. Receivers and boot
   - BootReceiver already starts PlayerService with ACTION_START.✓
   - MaintenanceReceiver routes ACTION_MAINTENANCE and must not reference Activity.✓
7. Cleanup & stability
   - Ensure no GlobalScope/static Handlers remain; use lifecycleScope/viewModelScope/serviceScope.
   - PlayerView.setKeepContentOnPlayerReset(true) is set; ensure no duplicate ExoPlayer instances.✓
8. Verification & logging
   - Manual scenarios: close Activity → playback continues; reboot → resumes; network loss → fallback → recovery; long-run 24–72h memory stability check.
   - Add debug logs around watchdog ticks, schedule switches, and state persistence.
