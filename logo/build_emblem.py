# -*- coding: utf-8 -*-
"""
从石河子大学校徽位图中提取「内部圆形徽标」（深色圆 + 白色书法图形 + 1949），
输出：emblempaths.json（归一化贝塞尔路径，圆心为原点、半径=1、y 轴向上）
"""
from PIL import Image, ImageDraw
import numpy as np
from skimage import measure, filters
from scipy import ndimage as ndi
import json

SRC = r'C:/Users/m1Su/Downloads/OIP-C_gray.png'
OUT = r'C:/Users/m1Su/WorkBuddy/石大课表/logo_out'

cx, cy, r = 199.5, 159.0, 62.5
x0, y0 = int(cx - r - 2), int(cy - r - 2)
x1, y1 = int(cx + r + 2) + 1, int(cy + r + 2) + 1

src = Image.open(SRC).convert('L')
crop = src.crop((x0, y0, x1, y1))
S = 6
big = crop.resize((crop.width * S, crop.height * S), Image.LANCZOS)
a = np.array(big).astype(float)
H, W = a.shape
bcx, bcy = (cx - x0) * S, (cy - y0) * S
yy, xx = np.mgrid[0:H, 0:W]
inside = np.hypot(xx - bcx, yy - bcy) < (r - 1.2) * S

thr = filters.threshold_otsu(a[inside])
white = ndi.binary_opening(ndi.binary_closing((a > thr) & inside, np.ones((3, 3))), np.ones((3, 3)))

contours = measure.find_contours(white.astype(float), 0.5)


def poly_area(c):
    x, y = c[:, 1], c[:, 0]
    return 0.5 * abs(np.dot(x, np.roll(y, 1)) - np.dot(y, np.roll(x, 1)))


MIN_AREA = 220 * S * S / 36


def catmull(pts, t=0.9):
    n, segs = len(pts), []
    for i in range(n):
        p0, p1, p2, p3 = pts[(i - 1) % n], pts[i], pts[(i + 1) % n], pts[(i + 2) % n]
        segs.append((p1, p1 + (p2 - p0) * (t / 6.0), p2 - (p3 - p1) * (t / 6.0), p2))
    return segs


shapes = []   # 每个 shape: {'segs': [...], 'area': float}
for c in contours:
    ar = poly_area(c)
    if ar < MIN_AREA:
        continue
    simp = measure.approximate_polygon(c, tolerance=1.6)
    if len(simp) < 4:
        continue
    if np.allclose(simp[0], simp[-1]):
        simp = simp[:-1]
    pts = np.stack([(simp[:, 1] - bcx) / (r * S), -(simp[:, 0] - bcy) / (r * S)], axis=1)
    shapes.append({'segs': catmull(pts), 'area': float(ar)})

shapes.sort(key=lambda s: -s['area'])


def to_d(sh):
    f = lambda p: f"{p[0]*1000:.1f} {p[1]*1000:.1f}"
    d = "M " + f(sh['segs'][0][0])
    for _, c1, c2, p2 in sh['segs']:
        d += " C " + f(c1) + " " + f(c2) + " " + f(p2)
    return d + " Z"


ds = [to_d(s) for s in shapes]
json.dump({'paths': ds, 'areas': [s['area'] for s in shapes]}, open(OUT + '/emblem_paths.json', 'w'), indent=1)
print('paths:', len(ds), 'chars:', sum(len(d) for d in ds))


# ---------- 预览：用贝塞尔采样多边形填充，检查还原度 ----------
def flatten(segs, n=10):
    pts = []
    for p1, c1, c2, p2 in segs:
        for i in range(n):
            t = i / n
            mt = 1 - t
            p = mt**3 * p1 + 3 * mt**2 * t * c1 + 3 * mt * t**2 * c2 + t**3 * p2
            pts.append((p[0], p[1]))
    return pts


R = 300
preview = Image.new('L', (R * 2 * 3, R * 2 + 40), 255)
# 左：原图放大
left = crop.resize((R * 2, R * 2), Image.LANCZOS).convert('RGB')
disk = Image.new('L', (R * 2, R * 2), 0)
ImageDraw.Draw(disk).ellipse([0, 0, R * 2 - 1, R * 2 - 1], fill=255)
left = Image.composite(left, Image.new('RGB', left.size, (255, 255, 255)), disk)
preview = Image.new('RGB', (R * 2 * 3 + 40, R * 2 + 40), (255, 255, 255))
preview.paste(left, (0, 20))
# 中：矢量还原
mid = Image.new('L', (R * 2, R * 2), 0)
dm = ImageDraw.Draw(mid)
for sh in shapes:
    dm.polygon([((x + 1) * R, (1 - y) * R) for x, y in flatten(sh['segs'])], fill=255)
mask = Image.new('L', (R * 2, R * 2), 0)
ImageDraw.Draw(mask).ellipse([0, 0, R * 2 - 1, R * 2 - 1], fill=255)
mid = Image.composite(mid, Image.new('L', mid.size, 0), mask)
vec = Image.merge('RGB', [mid.point(lambda v: 30 + v * 225 // 255)] * 3)
preview.paste(vec, (R * 2 + 20, 20))
# 右：叠加比较（红=矢量，黑=原图）
ov = Image.new('RGB', (R * 2, R * 2), (255, 255, 255))
orig = crop.resize((R * 2, R * 2), Image.LANCZOS).convert('RGB')
ov.paste(orig, (0, 0))
red = Image.new('RGB', ov.size, (230, 60, 50))
ov = Image.composite(red, ov, mid)
preview.paste(ov, (R * 2 * 2 + 40, 20))
preview.save(OUT + '/_trace_check.png')
print('saved check')
