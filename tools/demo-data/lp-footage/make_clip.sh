#!/bin/bash
# Synthetic CCTV-style demo clip (H.264, 640x360, 10 fps) with the capture time (UTC) burnt in.
# Usage: make_clip.sh OUT EPOCH DURATION "CAMERA NAME" SCENE
#   SCENE "till": a till whose cash drawer is open from 18 s to 30 s; anything else: a pick-up counter.
# Every frame says it is synthetic. Needs ffmpeg with libx264 and the DejaVu fonts.
set -euo pipefail
OUT=$1; EPOCH=$2; DUR=$3; CAM=$4; SCENE=$5
F=/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf
FB=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf
if [ "$SCENE" = till ]; then
  # counter top, till with drawer that opens 18-30 s, a cashier moving along the counter, a customer arriving
  SCN="drawbox=x=0:y=250:w=640:h=110:color=0x4a3a2a@1:t=fill,
drawbox=x=260:y=170:w=120:h=80:color=0x2b2b2b@1:t=fill,
drawbox=x=270:y=180:w=100:h=45:color=0x3c6e8f@1:t=fill,
drawbox=x=260:y='250+if(between(t,18,30),40,0)':w=120:h=24:color=0x777777@1:t=fill,
drawbox=x='120+60*sin(t/4)':y=90:w=70:h=160:color=0x8a5a44@1:t=fill,
drawbox=x='if(lt(t,10),660-30*t,360+5*sin(t))':y=110:w=80:h=150:color=0x2f4f6f@1:t=fill"
else
  # pick-up counter: trays sliding along the pass, a person walking through
  SCN="drawbox=x=0:y=230:w=640:h=130:color=0x5a5a5a@1:t=fill,
drawbox=x='mod(40*t,700)-60':y=205:w=60:h=25:color=0xc8b27a@1:t=fill,
drawbox=x='mod(40*t+350,700)-60':y=205:w=60:h=25:color=0xc8b27a@1:t=fill,
drawbox=x='mod(25*t,760)-80':y=70:w=70:h=160:color=0x6b4f3a@1:t=fill"
fi
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "color=c=0x1d2128:s=640x360:r=10:d=$DUR" -f lavfi -i "anoisesrc=d=$DUR:a=0.0" -map 0:v \
  -vf "$SCN,
noise=alls=12:allf=t,
drawbox=x=0:y=0:w=640:h=26:color=black@0.6:t=fill,
drawtext=fontfile=$F:fontsize=15:fontcolor=white:x=8:y=6:text='%{pts\:gmtime\:$EPOCH} UTC',
drawtext=fontfile=$F:fontsize=15:fontcolor=white:x=w-tw-8:y=6:text='$CAM',
drawtext=fontfile=$FB:fontsize=13:fontcolor=yellow@0.85:x=8:y=h-22:text='DEMO FOOTAGE - synthetic, not a real recording'" \
  -c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p -movflags +faststart -t $DUR "$OUT"
