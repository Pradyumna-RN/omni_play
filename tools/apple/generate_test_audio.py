"""Reproduce owned, synthetic 24-bit regression tones; never downloads music."""
import math, pathlib, struct, subprocess, json
root=pathlib.Path(__file__).resolve().parents[2]; out=root/'dsp/src/test/resources/audio'
report=[]
for rate,channels in [(192000,2),(96000,6)]:
 raw=out/f'.fixture-{rate}-{channels}.raw'
 n=8192
 with raw.open('wb') as f:
  for i in range(n):
   for c in range(channels):
    # Exactly representable 24-bit values with deliberately populated low bits.
    value=round(1000000*math.sin(2*math.pi*(311+211*c)*i/rate))+((i*7+c*11)%127)
    f.write(struct.pack('<i',value<<8))
 target=out/f'owned-24bit-{rate}-{channels}ch.m4a'
 subprocess.run(['ffmpeg','-v','error','-f','s32le','-ar',str(rate),'-ac',str(channels),'-i',str(raw),'-c:a','alac','-sample_fmt','s32p','-movflags','empty_moov+frag_keyframe+default_base_moof','-f','mp4','-y',str(target)],check=True)
 report.append({'rate':rate,'channels':channels,'samples':n,'file':str(target),'size':target.stat().st_size})
 raw.unlink()
print(json.dumps(report))
