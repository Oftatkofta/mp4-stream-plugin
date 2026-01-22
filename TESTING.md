# MP4 Stream Plugin — Testing Checklist

**Date:** _______________  
**Tester:** _______________  
**System:** _______________  
**Camera:** _______________  
**MM Version:** _______________  

---

## Prerequisites

- [ ] Plugin JAR copied to `mmplugins/` folder
- [ ] FFmpeg installed and accessible
- [ ] Micro-Manager starts without errors
- [ ] Plugin appears in processor pipeline list

---

## Core Recording Tests

### Test 1: Basic Recording (Constant FPS)
- [ ] Set mode: Constant FPS @ 30fps
- [ ] Set exposure: ~33ms
- [ ] Start Live, record 30 seconds, stop Live
- [ ] **Verify:**
  - [ ] Log shows "Starting FFmpeg" and "FFmpeg finalized successfully"
  - [ ] File created: `*_seg001.mp4`
  - [ ] ~900 frames written
  - [ ] Video plays in VLC
  - [ ] Δt timestamp visible and correct

### Test 2: Fast Camera (Frame Dropping)
- [ ] Set mode: Constant FPS @ 30fps
- [ ] Set exposure: 10ms (~100fps)
- [ ] Start Live, record 10 seconds, stop Live
- [ ] **Verify:**
  - [ ] Video plays smoothly at 30fps
  - [ ] Duration ~10 seconds
  - [ ] Frame count < camera captures (dropping occurred)

### Test 3: Slow Camera (Frame Duplication)
- [ ] Set mode: Constant FPS @ 30fps
- [ ] Set exposure: 500ms (~2fps)
- [ ] Start Live, record 10 seconds, stop Live
- [ ] **Verify:**
  - [ ] Video plays smoothly at 30fps
  - [ ] Frames appear held/duplicated
  - [ ] Duration ~10 seconds

### Test 4: Real-time Mode (VFR)
- [ ] Set mode: Real-time
- [ ] Set exposure: 100ms (~10fps)
- [ ] Start Live, record 10 seconds, stop Live
- [ ] **Verify:**
  - [ ] Frame count ≈ 100 (1:1 capture)
  - [ ] Playback matches real timing

### Test 5: Time-lapse Mode
- [ ] Set mode: Time-lapse @ 10x
- [ ] Set exposure: 1000ms (1fps)
- [ ] Start Live, record 60 seconds, stop Live
- [ ] **Verify:**
  - [ ] Video duration ~6 seconds (60s / 10x)
  - [ ] Δt overlay shows real elapsed time
  - [ ] Playback is 10x accelerated

### Test 6: Immediate Finalization
- [ ] Start any recording
- [ ] Stop Live immediately (<2 seconds)
- [ ] **Verify:**
  - [ ] Log shows "Live mode stopped - finalizing MP4 immediately"
  - [ ] File is valid and playable
  - [ ] No watchdog timeout message

---

## Overlay Tests

### Test 7: Timestamp Overlay
- [ ] Enable timestamp, white text, with background
- [ ] Record 5 seconds
- [ ] **Verify:**
  - [ ] Δt visible in top-left
  - [ ] White text on dark background
  - [ ] Time increments correctly

### Test 8: Timestamp Colors
- [ ] Change to black text, no background
- [ ] Record 5 seconds
- [ ] **Verify:**
  - [ ] Black text visible
  - [ ] No background box
  - [ ] Readable on light areas

### Test 9: Scale Bar
- [ ] Configure pixel size in MM (Devices → Pixel Size Calibration)
- [ ] Enable scale bar
- [ ] Record 5 seconds
- [ ] **Verify:**
  - [ ] Scale bar visible in bottom-right
  - [ ] Label shows correct units (µm or mm)
  - [ ] Length is reasonable (~15% of image width)

### Test 10: Scale Bar Without Pixel Size
- [ ] Clear pixel size calibration (or use uncalibrated objective)
- [ ] Enable scale bar
- [ ] Start recording
- [ ] **Verify:**
  - [ ] Log shows warning about missing pixel size
  - [ ] Scale bar not drawn
  - [ ] Recording otherwise works

---

## Settings & Persistence

### Test 11: Settings Persistence
- [ ] Configure all settings (paths, mode, overlays)
- [ ] Close Micro-Manager completely
- [ ] Reopen Micro-Manager
- [ ] Open plugin settings
- [ ] **Verify:**
  - [ ] FFmpeg path persisted
  - [ ] Output path persisted
  - [ ] Recording mode persisted
  - [ ] Overlay settings persisted

### Test 12: Settings Apply Immediately
- [ ] Start recording in Constant FPS mode
- [ ] Open settings, change to Real-time mode
- [ ] Stop and restart Live
- [ ] **Verify:**
  - [ ] New segment uses Real-time mode
  - [ ] Log shows "realtime/VFR"

---

## Edge Cases

### Test 13: Resolution Change
- [ ] Start recording at full resolution
- [ ] Change ROI or binning mid-session
- [ ] Continue recording
- [ ] **Verify:**
  - [ ] New segment file created with new resolution
  - [ ] Both files are valid

### Test 14: Long Recording (10+ minutes)
- [ ] Set Constant FPS @ 30fps
- [ ] Record continuously for 10+ minutes
- [ ] Monitor system resources
- [ ] **Verify:**
  - [ ] No memory leaks (Java heap stable)
  - [ ] File size grows linearly
  - [ ] Final file is playable
  - [ ] No errors in log

### Test 15: Rapid Start/Stop
- [ ] Start Live
- [ ] Stop Live immediately
- [ ] Repeat 5 times
- [ ] **Verify:**
  - [ ] 5 segment files created (seg001-seg005)
  - [ ] No crashes or errors
  - [ ] All files valid (even if short)

### Test 16: No Output Path
- [ ] Clear output path in settings
- [ ] Start Live
- [ ] **Verify:**
  - [ ] No recording occurs
  - [ ] No errors or crashes
  - [ ] Live view works normally

### Test 17: Objective Change (Scale Bar)
- [ ] Configure multiple objectives with different pixel sizes in MM
- [ ] Enable scale bar (auto mode, length=0)
- [ ] Record with objective A (e.g., 10×), stop Live
- [ ] Switch to objective B with different magnification (e.g., 40×)
- [ ] Start Live, record again
- [ ] **Verify:**
  - [ ] Scale bar label changes appropriately (e.g., 200 µm → 50 µm)
  - [ ] Log shows different pixelSize values for each segment
  - [ ] Both videos have correct scale bars

### Test 18: Binning Change
- [ ] Start recording at 1×1 binning (full resolution)
- [ ] Stop Live
- [ ] Change camera binning to 2×2
- [ ] Start Live, record again
- [ ] **Verify:**
  - [ ] New segment created with smaller resolution (e.g., 1608×1608 → 804×804)
  - [ ] Scale bar adjusts for binned image width
  - [ ] Both files are valid

---

## Results Summary

| Test | Pass | Fail | Notes |
|------|:----:|:----:|-------|
| 1. Basic Recording | ✓ | | Demo, ORCA-Fusion, Prime 95B |
| 2. Fast Camera | ✓ | | Demo, ORCA-Fusion |
| 3. Slow Camera | ✓ | | Demo, ORCA-Fusion |
| 4. Real-time Mode | ✓ | | Demo, ORCA-Fusion |
| 5. Time-lapse Mode | ✓ | | Demo, ORCA-Fusion |
| 6. Immediate Finalization | ✓ | | Demo |
| 7. Timestamp Overlay | ✓ | | ORCA-Fusion, Prime 95B |
| 8. Timestamp Colors | ✓ | | ORCA-Fusion (white/black) |
| 9. Scale Bar | ✓ | | Multiple objectives, auto-scaling |
| 10. Scale Bar No Pixel | | | |
| 11. Settings Persistence | ✓ | | All settings verified |
| 12. Settings Apply | | | |
| 13. Resolution Change | ✓ | | Prime 95B via binning |
| 14. Long Recording | | | |
| 15. Rapid Start/Stop | ✓ | | Prime 95B (frankenscope log) |
| 16. No Output Path | | | |
| 17. Objective Change | ✓ | | Prime 95B: 10×→40× |
| 18. Binning Change | ✓ | | Prime 95B: 1×1→2×2 |


## Notes

```
(Additional observations, log excerpts, etc.)


```
