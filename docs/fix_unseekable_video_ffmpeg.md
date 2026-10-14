# Fix videos that play but cannot seek (ffmpeg remux)

## Symptom

- Video plays in the in-app player (or VLC).
- Scrubber / seek / jump-forward does not work (or seek jumps to the start).
- Common with some fragmented MP4 (fMP4) files that lack usable seek metadata (`sidx` / `mfra`), or files with a damaged/incomplete tail.

## What this fix does

**Lossless remux** with ffmpeg: rewrite the container only. Video and audio streams are **copied** (`-c copy`). Quality is unchanged. A second file is written (needs roughly the same free space as the input).

This is **not** an app bug fix. Some files cannot be made seekable in-app without rewriting the media.

## Prerequisites

- Portable or installed ffmpeg on a PC (Windows / Linux / macOS).
- Enough free disk space for a full copy of the file.
- Input and output on local disk (not only on a phone).

## MP4

```bat
cd /d "C:\path\to\ffmpeg\bin"
ffmpeg.exe -i "input.mp4" -c copy -movflags +faststart "output_fixed.mp4"
```

`-movflags +faststart` moves the `moov` atom to the beginning so players can seek without scanning the whole file.

Success signs in the log:

- `Stream #0:0 -> #0:0 (copy)` and audio likewise `(copy)`
- Near the end: `moving the moov atom to the beginning of the file`
- A new `output_fixed.mp4` with similar size and duration

Harmless / expected on some broken inputs:

- `Packet corrupt` / `partial file` near EOF — remux may still succeed; verify seek afterward.

## MKV

`+faststart` is MP4-only. For Matroska:

```bat
ffmpeg.exe -i "input.mkv" -c copy -reserve_index_space 200k "output_fixed.mkv"
```

If MKV still will not seek in the app after remux, try remuxing to MP4 (still lossless if codecs are MP4-compatible):

```bat
ffmpeg.exe -i "input.mkv" -c copy -movflags +faststart "output_fixed.mp4"
```

## Verify

1. Open `*_fixed` in VLC; scrub mid-file and near the end.
2. Upload the fixed file to the remote; replace or keep the original until confirmed.
3. Play in RoundSync and confirm scrub / double-tap seek.

## Quality

| Flag | Effect |
|------|--------|
| `-c copy` | No re-encode; quality unchanged |
| Re-encode (`libx264`, etc.) | Not required for this seek fix; would change quality/size |

## What not to expect

- Remux will not fix every corrupt file (severe truncation may still fail).
- In-app fragment-walk / SeekMap injection is not a substitute for a broken container on some files.
- ExifTool and similar metadata tools do **not** remux or restore seekability.

## Optional probe (before/after)

```bat
ffprobe.exe -hide_banner "input.mp4"
ffprobe.exe -hide_banner "output_fixed.mp4"
```

Compare duration and that streams look intact. Do not paste real basenames or remote paths into shared logs/docs.
