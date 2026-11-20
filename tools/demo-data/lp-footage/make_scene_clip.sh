#!/bin/bash
# make_real.sh IMG OUT.mp4 EPOCH_UTC DURATION "CAMERA" -> CCTV-style clip from a still (slow drift, grain, overlays)
set -euo pipefail
IMG=$1; OUT=$2; EPOCH=$3; DUR=$4; CAM=$5
F=/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf
FPS=15; N=$((DUR*FPS)); LOCAL=$((EPOCH+10800))
ffmpeg -hide_banner -loglevel error -y -loop 1 -i "$IMG" -t "$DUR" -vf "scale=2560:1440,\
zoompan=z='1.0+0.035*on/$N':x='(iw-iw/zoom)*(0.5+0.25*sin(on/$N*3.14))':y='(ih-ih/zoom)/2':d=1:s=1280x720:fps=$FPS,\
noise=alls=7:allf=t,eq=contrast=1.04:saturation=0.92,\
drawbox=x=0:y=ih-40:w=iw:h=40:color=black@0.55:t=fill,\
drawtext=fontfile=$F:fontsize=20:fontcolor=white:x=14:y=h-30:text='%{pts\:gmtime\:$LOCAL} EAT',\
drawtext=fontfile=$F:fontsize=20:fontcolor=white:x=w-tw-120:y=h-30:text='$CAM',\
drawbox=x=w-100:y=h-28:w=16:h=16:color=red@1:t=fill:enable='lt(mod(t,1),0.6)',\
drawtext=fontfile=$F:fontsize=20:fontcolor=white:x=w-76:y=h-30:text='REC'" \
  -c:v libx264 -preset veryfast -crf 26 -pix_fmt yuv420p -movflags +faststart -r $FPS "$OUT"
