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
- Move remaining playlist orchestration from Monitor/Maintenance into PlayerService.
- Implement watchdog/retry and schedule switching in service.
- Add basic telemetry to log memory and player state periodically.
