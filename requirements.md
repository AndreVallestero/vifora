# Vifora

Video compression app (create VIdeos FOR Ants)

Lightweight, minimal, and simple as possible video compression android app to compress videos on the fly locally, targetting a specific size so it can easily be shared.


presets (radio button):

compatability:
- 8bit h264 2 pass
- mp3
- mp4
- web optimized

discord:
- 10 bit 265 2 pass
- AAC-LC
- mp4
- web optimized

high:
- 10 bit av1 2 pass
- opus
- webm

advanced options (check boxes)
- optimize for very low motion / still
- crop (preview + scrubber + rectangle selection tool + save button)
- denoise
  - dynamic shots (hqdn3d)
  - static shorts (atadenoise)
- exposure balancing / midkey
- trim (preview + scrubber + save button)
- resolution scale
- grayscale
- single channel audio
- remove audio
- output file name template
- output dir

add toggle for very low motion / still image for codecs
- enables static profiles and optimizations
- uses adadenoise instead of hqdn3d denoise

Add guard so we don't make the file larger than it already is

Choose video and audio target based on input length. At 1m or less, allocate 90% to video, 10% to audio. At 30m, allocate 50/50. Linearly interpolate inbetween. If no audio, allocate 100% to video.

Since we're doing 2 pass, we can target a bitrate, and thus target the video size. If overshoot, reduce video bitrate by 10% and retry until within the target size. Fill the remaining space with audio, if overshoot, reduce bitrate by 5% and retry until the the entire file is less than the target size.

When crop selected, show crop rectangle in preview, and show save button, and save when pressed.

When trim selected, show start and end trip fields, and save button

@vifora_design.png is just a loose layout reference. Use a different design if it can be cleaner, simpler, more modern, or more ergonomic.

Always use the following flags:
-sn -map_chapters -1 -map_metadata -1 -fflags -movflags +faststart
