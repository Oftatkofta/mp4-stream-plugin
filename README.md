# MP4 stream — Micro‑Manager 2.x ProcessorPlugin

**Purpose:** Record grayscale scientific‑camera frames to MP4 using CPU‑only H.264 (libx264), with a top‑left Δt overlay (starts at t=0).  
**Camera:** Monochrome sCMOS (e.g., 2304×2304 ORCA‑Fusion).  
**Workflow:** Insert into Image Processing Pipeline, enable → choose output `.mp4` → record Live/MDA until disabled.

## Requirements
- Windows (Ant packaging step is Windows‑oriented)
- Micro‑Manager 2.x installed (path to `mmstudio.jar`)
- JDK 8 (`JAVA_HOME`), Apache Ant (`ANT_HOME`), FFmpeg on PATH

## Build
```bash
ant jar
