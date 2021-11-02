# vifora
Command-line program to create highly compressed videos for easy sharing

Video FOR Ants 

Params:
- input
- mode (default 0)
- target size (default 7MB)
- number of clips (default 1, negative means max clip len in seconds)
- audio stream (default 1, 0 means none)
- subtitle stream (default 0, 0 means none, negative means burn in [required for compatible])
- output (default uenc-$input [add suffix for clip index, Lpad zeros based on log of num clips])

mode 0 (compatible, default, fastest):
H264 High Profile Level 4.1 8 bit web optimized, aac-lc, mp4

mode 1:
Av1, opus, webm

mode 2 (slowest, best compression):
Av1 (-b 10 --lag-in-frames=35 --enable-fwd-kf=1 --tune-content=screen --cpu 0), opus, webm

Ideas:
- 2 pass, parse data from first pass instead of pyscene detect
- auto resolution select
- change params based on content detect (on simple cartoon, low noise, low motion, don't add --tune-content=screen) https://www.reddit.com/i6o5l0
- audio is always single track
- do subtitles and audio first so we can determine how much space is left for video
- optimize h264 for web
- create single executable using pyinstaller (use py2exe if not possible)
- add to pipy registry

Remove pyscene detect, use first pass to extract keyframes
