# Handover

## Stable baseline — 2026-09-20

The tested target is a Sony KD-55X8000H at `192.168.1.124:5555`, running the patched receiver package `io.github.jqssun.airplay` and companion package `dev.frank.airplayguard`.

### Do not regress the Douyin audio path

Rapid video swiping, seeking, and switching between videos and live rooms are currently reported as working very well. Preserve the following behavior unless a new reproducible failure proves that it must change:

- `0001-portrait-rotation.patch` flushes decoder PCM and the already-open AAudio device queue without reopening the output stream.
- `0002-uxplay-audio-queue.patch` clears the encrypted RTP/retransmit queue on AirPlay `FLUSH`.
- A missing RTP sequence waits up to 80 ms for resend, then advances to the next received packet instead of holding a multi-second backlog.
- There is no arrival-gap reset, hard backlog trim, or 150 ms stale-packet filter. Those experiments caused stutter or missing audio and must not be restored.
- The software timeline ring is eight seconds; adaptive output cushion is capped at 160 ms.

Recommended receiver audio settings on this TV: low latency off, override audio delay 250 ms, automatic software buffer off, software buffer 120 ms, hardware output buffer automatic/0.

### Phone-lock sleep path

UxPlay emits `video_pause` when iOS locks/suspends mirrored video and `video_resume` when frames resume. The JNI bridge now forwards those callbacks to `AirPlayService`, which broadcasts:

- `active=true, stalled=true` on lock/pause;
- `active=true, stalled=false` on resume;
- `active=false, stalled=false` when mirroring ends.

AirPlayGuard already consumes these states. A stalled session starts the normal cancellable black countdown over the frozen last frame; resume cancels it. An ADB simulation verified both transitions in the installed Guard (`iPhone 已锁屏 — 10s countdown to sleep`, followed by `countdown cancelled`). A real iPhone lock/unlock test is the final device-level confirmation.

### Display and power constraints

- When the TV panel is already on, Guard launches the receiver directly and must not open `WakeActivity`; doing so destroys the receiver SurfaceView and removes the picture.
- Exact receiver broadcasts permanently supersede traffic-based wake for this receiver. This prevents residual/audio traffic from waking the panel immediately after automatic sleep.
- TCP liveness probing remains disabled during an active session because a raw probe looks like a real AirPlay connection and can flush playback.

### Reproducible build

The receiver is pinned to upstream commit `c8defdd70d7e6a04f4f1b71d353653682d594106`. `airplay-portrait/build.sh` applies the parent patch, initializes UxPlay, then applies `0002-uxplay-audio-queue.patch` inside the submodule. The release APK is copied to `airplay-portrait/airplay-portrait.apk`.
