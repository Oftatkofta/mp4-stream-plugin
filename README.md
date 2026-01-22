# MP4 Stream — Micro-Manager 2.x Processor Plugin

**Purpose:** Record grayscale scientific camera frames to MP4 video using CPU-only H.264 encoding (libx264), with an optional Δt timestamp overlay.

## Features

- **Real-time MP4 encoding** via FFmpeg pipe (no intermediate files)
- **Three recording modes:**
  - **Constant FPS** — Maintains steady output framerate (duplicates/drops frames as needed)
  - **Real-time (VFR)** — Records every frame exactly once, preserving actual timing
  - **Time-lapse compression** — Compresses playback time (e.g., 10x = plays 10× faster)
- **Automatic segment numbering** — Prevents file overwrites, auto-increments filenames
- **Live display scaling** — Uses Micro-Manager's current brightness/contrast settings
- **Customizable overlays:**
  - **Δt timestamp** — Shows elapsed time (HH:MM:SS.mmm) in top-left corner
  - **Scale bar** — Automatic scale bar in bottom-right (uses pixel size from MM config)
  - **Text color** — White or black text with optional contrasting background
- **Immediate finalization** — MP4 closes properly when Live/MDA stops (event-driven)
- **Watchdog timeout** — Finalizes recording if no frames arrive (prevents hung sessions)

## Requirements

- **Micro-Manager 2.x** (tested with 2.0-gamma)
- **FFmpeg** — Either on system PATH or configured via settings
- **JDK 8+** for building
- **Apache Ant** for building

## Installation

1. Build the plugin:
   ```bash
   ant jar
   ```

2. Copy `build/jar/MP4Stream.jar` to your Micro-Manager `mmplugins/` folder

3. Restart Micro-Manager

4. Enable via **Plugins → On-The-Fly Image Processing → Configure Processor Pipeline**

## Configuration

Click the gear icon (⚙) next to the plugin to open settings:

### General Settings

| Setting | Description |
|---------|-------------|
| **Output file** | Base path for MP4 output (actual files get `_WxH_segNNN.mp4` suffix) |
| **FFmpeg path** | Path to ffmpeg.exe (leave empty to use system PATH) |
| **Recording Mode** | See Recording Modes below |
| **FPS** | Target output framerate (for Constant FPS mode) |
| **Time-lapse factor** | Playback speedup multiplier (for Time-lapse mode) |

### Overlay Settings

| Setting | Description |
|---------|-------------|
| **Show timestamp (Δt)** | Enable/disable elapsed time overlay in top-left corner |
| **Text color** | White or Black text color |
| **Contrasting background** | Adds semi-transparent background box behind text for readability |
| **Show scale bar** | Draws a scale bar in bottom-right corner (requires pixel size to be configured in MM) |

The scale bar automatically calculates an appropriate length based on image pixel size and displays it in µm or mm.

### Recording Modes

#### Constant FPS (default)
- Output video plays at exactly the specified FPS
- If camera is slower: duplicates last frame to fill gaps
- If camera is faster: drops frames to match target rate
- **Best for:** Fast acquisitions where smooth playback is desired

#### Real-time (VFR)
- Every frame from the camera is written exactly once
- Playback timing matches actual capture timing
- **Best for:** Recording at maximum camera speed, forensic/audit use

#### Time-lapse Compression
- Compresses real time into shorter playback time
- Factor of 10× means 10 minutes of recording plays in 1 minute
- **Best for:** Long acquisitions with slow frame rates (long exposures)

## Output Files

Files are named: `{basename}_{width}x{height}_seg{NNN}.mp4`

Example: `experiment_2304x2304_seg001.mp4`

- Segment numbers auto-increment to avoid overwrites
- New segment starts if resolution changes mid-session
- Each Live start/stop creates a new segment

## Testing Protocol

### Prerequisites
- [ ] Micro-Manager 2.x installed and working with your camera
- [ ] FFmpeg installed and accessible
- [ ] Plugin installed in `mmplugins/` folder
- [ ] VLC or similar player for verification

### Test 1: Basic Recording (Constant FPS)
1. Configure plugin with output path and Constant FPS @ 30fps
2. Set camera exposure to ~33ms (matching 30fps)
3. Start Live mode
4. Record for 30 seconds
5. Stop Live mode
6. **Verify:** 
   - [ ] Log shows "FFmpeg finalized successfully"
   - [ ] File exists with ~900 frames (30fps × 30s)
   - [ ] Video plays smoothly in VLC
   - [ ] Δt overlay shows correct elapsed time

### Test 2: Fast Camera (Frame Dropping)
1. Set mode to Constant FPS @ 30fps
2. Set camera exposure to 10ms (~100fps actual)
3. Start Live for 10 seconds
4. **Verify:**
   - [ ] Output file plays at 30fps (smooth)
   - [ ] Duration is ~10 seconds
   - [ ] Some frames were dropped (log shows frame count < actual captures)

### Test 3: Slow Camera (Frame Duplication)
1. Set mode to Constant FPS @ 30fps  
2. Set camera exposure to 500ms (2fps actual)
3. Start Live for 10 seconds
4. **Verify:**
   - [ ] Output file plays at 30fps (smooth)
   - [ ] Frames appear duplicated/held (expected)
   - [ ] Duration is ~10 seconds

### Test 4: Real-time Mode
1. Set mode to Real-time
2. Set camera exposure to 100ms (10fps)
3. Start Live for 10 seconds
4. **Verify:**
   - [ ] Frame count matches actual captures (~100 frames)
   - [ ] No frame duplication or dropping
   - [ ] Playback matches real-time timing

### Test 5: Time-lapse Mode
1. Set mode to Time-lapse @ 10x
2. Set camera exposure to 1000ms (1fps)
3. Start Live for 60 seconds
4. **Verify:**
   - [ ] Video duration is ~6 seconds (60s / 10x)
   - [ ] Δt overlay shows real elapsed time (not compressed)
   - [ ] Playback is accelerated 10×

### Test 6: Immediate Finalization
1. Start any recording
2. Click Stop Live
3. **Verify:**
   - [ ] Log shows "Live mode stopped - finalizing MP4 immediately"
   - [ ] File is playable immediately (not corrupted)
   - [ ] No watchdog timeout needed

### Test 7: Settings Persistence
1. Configure all settings
2. Close and reopen Micro-Manager
3. **Verify:**
   - [ ] FFmpeg path persisted
   - [ ] Output path persisted
   - [ ] Recording mode persisted

### Test 8: Resolution Change
1. Start recording at one resolution
2. Change camera ROI/binning mid-session
3. **Verify:**
   - [ ] New segment file created with new resolution
   - [ ] Both files are valid and playable

### Test 9: MDA Recording
1. Configure a simple MDA (e.g., time series, 10 frames, 1s interval)
2. Start acquisition
3. **Verify:**
   - [ ] Recording starts with first frame
   - [ ] Recording stops when MDA completes
   - [ ] Log shows "Acquisition ended - finalizing MP4 immediately"

### Test 10: Long Recording Stability
1. Set Constant FPS @ 30fps
2. Record continuously for 10+ minutes
3. **Verify:**
   - [ ] No memory leaks (check Java heap)
   - [ ] File size grows linearly
   - [ ] Final file is playable
   - [ ] No dropped frames in log

### Test 11: Overlay Customization
1. Enable timestamp with white text and background
2. Record for 5 seconds
3. Change to black text, no background
4. Record for 5 seconds
5. **Verify:**
   - [ ] First video has white text with semi-transparent background
   - [ ] Second video has black text without background
   - [ ] Timestamp is readable in both cases

### Test 12: Scale Bar
1. Configure pixel size in Micro-Manager (Devices → Pixel Size Calibration)
2. Enable scale bar in plugin settings
3. Record for 5 seconds
4. **Verify:**
   - [ ] Scale bar appears in bottom-right corner
   - [ ] Label shows correct units (µm or mm)
   - [ ] Scale bar length is reasonable (~15% of image width)

## Troubleshooting

### "FFmpeg not found"
- Ensure ffmpeg.exe is on system PATH, or
- Set explicit path in plugin settings

### Files are 0 bytes or corrupt
- Check that Live/MDA was running when recording started
- Verify FFmpeg path is correct
- Check Micro-Manager log for errors

### Playback issues with short videos
- Videos under 1 second may not play in all players
- Use VLC for best compatibility
- Verify with `ffprobe` that file is valid

### Recording doesn't start
- Ensure plugin is enabled (checkbox checked)
- Verify output path is set and writable
- Check log for error messages

### Mode changes don't take effect
- Mode changes apply to the **next** recording, not current
- Stop and restart Live to use new settings

## Technical Details

### FFmpeg Command
```
ffmpeg -y -f rawvideo -pix_fmt gray -s WxH -r FPS -i - 
       -an -c:v libx264 -preset veryfast -crf 18 -pix_fmt yuv420p output.mp4
```

### Architecture
- `MP4StreamProcessor` — Frame processing and FFmpeg pipe management
- `MP4StreamConfigurator` — Settings UI and persistence
- `MP4StreamFactory` — Processor instantiation
- `MP4StreamPlugin` — Plugin registration

### Event Handling
- Subscribes to `LiveModeEvent` and `AcquisitionEndedEvent`
- Finalizes MP4 immediately when Live/MDA stops
- Watchdog thread provides backup timeout

## License

Copyright (c) 2026 — See source files for details.

## Contributing

Issues and pull requests welcome. Please include:
- Micro-Manager version
- Camera model
- Relevant log excerpts
- Steps to reproduce
