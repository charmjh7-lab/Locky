#!/usr/bin/env python3
"""Reconstruct ROKI adaptive icon layers from the original user-supplied illustration.
The green gradient is the full, opaque background. The lime door and white L are the
only foreground pixels. Avoid recycling intermediate artwork or colored masks.
"""
from pathlib import Path
import numpy as np, cv2
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parents[1]
IN=ROOT/'scripts/assets/roki_original_crop_lossless.webp'
OUT=ROOT/'app/src/main/res/drawable-nodpi'
OUT.mkdir(exist_ok=True)
src=np.array(Image.open(IN).convert('RGB').resize((340,340),Image.Resampling.LANCZOS))
S=340
r,g,b=[src[:,:,i].astype('int16') for i in range(3)]
# isolate bright green light; white L through connected component nearest center
lime=(g>115)&(g-r>32)&(g-b>38)&(r>30)
white=(r>140)&(g>148)&(b>130)&(abs(r-g)<65)&(abs(g-b)<65)
def comps(mask,min_area,restrict=None):
 n,lab,stats,_=cv2.connectedComponentsWithStats(mask.astype('uint8'),8)
 out=np.zeros((S,S),'uint8')
 for k in range(1,n):
  x,y,w,h,a=stats[k]
  if a>=min_area and (restrict is None or restrict(x,y,w,h)):
   out[lab==k]=255
 return out
bright=comps(lime,500,lambda x,y,w,h:x<120 and y<130)
letter=comps(white,300,lambda x,y,w,h:x>120 and y>100 and y<280)
# Add original glow/shadow along the original green light path; keep L crisp.
bright_close=cv2.morphologyEx(bright,cv2.MORPH_CLOSE,cv2.getStructuringElement(cv2.MORPH_ELLIPSE,(5,5)))
door=cv2.dilate(bright_close,cv2.getStructuringElement(cv2.MORPH_ELLIPSE,(15,15)))
door_alpha=cv2.GaussianBlur(door,(0,0),4.2)
letter_mask=cv2.dilate(letter,cv2.getStructuringElement(cv2.MORPH_ELLIPSE,(5,5)))
letter_alpha=cv2.GaussianBlur(letter_mask,(0,0),0.9)
# Spatially keep the actual door and L components, not stray bright pixels from
# the original rounded border / antialiased exterior.
yy,xx=np.mgrid[0:S,0:S]
door_support=(xx<133) | (yy<143)
letter_support=(xx>139)&(xx<285)&(yy>111)&(yy<266)
alpha=np.maximum(door_alpha*door_support,letter_alpha*letter_support).astype('uint8')
# Reconstruct continuous green background from exclusively GREEN pixels in the source.
# This rejects every exterior cream corner AND all white glyphs, even if those
# pixels touch the original crop edge. The gradient is estimated from source.
green_valid=(r<115)&(g<152)&(b<116)&(g>r*1.1)&(g>b*1.15)
# omit the door and white L when interpolating background colors
repair=np.maximum(
    cv2.dilate(bright,cv2.getStructuringElement(cv2.MORPH_ELLIPSE,(29,29))),
    cv2.dilate(letter,cv2.getStructuringElement(cv2.MORPH_ELLIPSE,(17,17))))
valid=(green_valid & (repair==0)).astype(np.float32)
weighted=cv2.GaussianBlur(valid,(0,0),sigmaX=25.)
model=np.zeros((S,S,3),dtype='float32')
for channel in range(3):
 model[:,:,channel]=cv2.GaussianBlur(src[:,:,channel].astype('float32')*valid,(0,0),sigmaX=25.)/np.maximum(weighted,.00001)
# retain physical grain sampled from actual green in the image
clean=src[172:238,273:337].astype('float32')
noise=clean-cv2.GaussianBlur(clean,(0,0),sigmaX=2.2)
texture=np.tile(noise,(6,6,1))[:S,:S,:]
base=np.uint8(np.clip(model+texture*.85,0,255))
# Alpha must not include screenshot's cream exterior, only door/letter shading.
shape=Image.new('L',(S,S),0)
ImageDraw.Draw(shape).rounded_rectangle((0,0,S-1,S-1),radius=69,fill=255)
inside=np.asarray(shape)>0
alpha[~inside]=0
# The original left light extends off its card; end it with a subtle 4%-high
# optical feather after insetting, rather than a flat horizontal cut.
for row in range(325,S):
    fraction = max(0.0, (S-1-row)/14.0)
    alpha[row] = (alpha[row].astype(np.float32)*fraction).astype('uint8')
# The entire Android background drawable is opaque. Keep almost the original full gradient
# visible within the 72dp launcher viewport by a ~300px original-gradient view centered.
bg_core=Image.fromarray(base,'RGB').resize((302,302),Image.Resampling.LANCZOS)
# Extend clean original gradient to invisible overscan margins by edge replication.
bg_numpy=np.array(bg_core)
bg_full=cv2.copyMakeBorder(bg_numpy,65,65,65,65,cv2.BORDER_REPLICATE)
assert bg_full.shape==(432,432,3)
Image.fromarray(bg_full,'RGB').save(OUT/'roki_icon_background.png',optimize=True)
# Foreground only: keep original source RGB, with a subtle feather at the old bottom cut.
# Artwork lives well within the 72dp visible region (central 288px of 432px).
fg_src=Image.fromarray(np.dstack([src,alpha]).astype('uint8'),'RGBA')
fg_size=234  # 54.2% of 108dp; leaves real margins after central 72dp crop
fg_res=fg_src.resize((fg_size,fg_size),Image.Resampling.LANCZOS)
fg_canvas=Image.new('RGBA',(432,432),(0,0,0,0))
fg_canvas.alpha_composite(fg_res,((432-fg_size)//2,(432-fg_size)//2))
fg_canvas.save(OUT/'roki_icon_foreground.png',optimize=True)