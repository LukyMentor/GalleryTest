"""Builds app/src/main/assets/world.bin from Natural Earth (public domain) GeoJSON.

Countries (2 levels of detail) + lakes + populated places, projected to normalized Web
Mercator (0..1) and simplified with Douglas-Peucker.
"""
import json, math, struct, sys

SRC = sys.argv[1]
OUT = sys.argv[2]

def merc(lon, lat):
    lat = max(-85.0, min(85.0, lat))
    x = (lon + 180.0) / 360.0
    r = math.radians(lat)
    y = (1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0
    return x, y

def dp(points, tol):
    if len(points) < 4:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    t2 = tol * tol
    while stack:
        a, b = stack.pop()
        ax, ay = points[a]; bx, by = points[b]
        dx, dy = bx - ax, by - ay
        L = dx * dx + dy * dy
        best, idx = -1.0, -1
        for i in range(a + 1, b):
            px, py = points[i]
            if L == 0:
                d = (px - ax) ** 2 + (py - ay) ** 2
            else:
                t = ((px - ax) * dx + (py - ay) * dy) / L
                t = max(0.0, min(1.0, t))
                d = (ax + t * dx - px) ** 2 + (ay + t * dy - py) ** 2
            if d > best:
                best, idx = d, i
        if best > t2 and idx > 0:
            keep[idx] = True
            stack.append((a, idx)); stack.append((idx, b))
    return [p for p, k in zip(points, keep) if k]

def area(r):
    s = 0.0
    for i in range(len(r)):
        x1, y1 = r[i]; x2, y2 = r[(i + 1) % len(r)]
        s += x1 * y2 - x2 * y1
    return abs(s) / 2

def rings_of(geom):
    polys = geom['coordinates'] if geom['type'] == 'MultiPolygon' else [geom['coordinates']]
    for poly in polys:
        for ring in poly:
            yield [merc(lon, lat) for lon, lat in ring]

def lod(rings, tol, min_area):
    out = []
    for r in rings:
        if area(r) < min_area:
            continue
        s = dp(r, tol)
        if len(s) >= 4:
            out.append(s)
    return out

COARSE = (0.0004, 2e-7)    # zoom < 3.5
MID = (0.0001, 1e-8)       # zoom < 6
FINE = (0.00002, 1e-11)    # zoom >= 6

def write_rings(f, rings):
    f.write(struct.pack('<I', len(rings)))
    for r in rings:
        xs = [p[0] for p in r]; ys = [p[1] for p in r]
        f.write(struct.pack('<4fI', min(xs), min(ys), max(xs), max(ys), len(r)))
        for x, y in r:
            f.write(struct.pack('<2f', x, y))

countries = json.load(open(f'{SRC}/ne_10m_admin_0_countries.geojson'))['features']
lakes = json.load(open(f'{SRC}/ne_50m_lakes.geojson'))['features']
places = json.load(open(f'{SRC}/ne_10m_populated_places_simple.geojson'))['features']

stats = [0, 0, 0]
with open(OUT, 'wb') as f:
    f.write(b'MGW4')
    f.write(struct.pack('<I', len(countries)))
    for c in countries:
        rings = list(rings_of(c['geometry']))
        coarse = lod(rings, *COARSE)
        mid = lod(rings, *MID)
        fine = lod(rings, *FINE)
        stats[0] += sum(map(len, coarse)); stats[1] += sum(map(len, mid)); stats[2] += sum(map(len, fine))
        write_rings(f, coarse)
        write_rings(f, mid)
        write_rings(f, fine)
    lake_rings = []
    for l in lakes:
        lake_rings += lod(list(rings_of(l['geometry'])), *FINE)
    write_rings(f, lake_rings)
    cities = []
    for p in places:
        pr = p['properties']
        mz = pr.get('min_zoom') or 10
        if mz > 9:
            continue
        x, y = merc(pr['longitude'], pr['latitude'])
        name = (pr.get('name') or '').encode('utf-8')
        country = (pr.get('adm0name') or '').encode('utf-8')
        cities.append((mz, -(pr.get('pop_max') or 0), x, y, name, country))
    cities.sort()  # most important first
    f.write(struct.pack('<I', len(cities)))
    for mz, _, x, y, name, country in cities:
        f.write(struct.pack('<2fBH', x, y, int(math.floor(mz)), len(name)))
        f.write(name)
        f.write(struct.pack('<H', len(country)))
        f.write(country)
    # Country name labels (Natural Earth label points and zoom range), most important first.
    labels = []
    for c in countries:
        pr = c['properties']
        x, y = merc(pr['LABEL_X'], pr['LABEL_Y'])
        name = (pr.get('NAME_EN') or pr.get('NAME') or '').encode('utf-8')
        labels.append((pr.get('MIN_LABEL') or 5, x, y, pr.get('MAX_LABEL') or 10, name))
    labels.sort()
    f.write(struct.pack('<I', len(labels)))
    for mn, x, y, mx, name in labels:
        f.write(struct.pack('<2f2BH', x, y, int(math.floor(mn)), int(math.ceil(mx)), len(name)))
        f.write(name)
print('points coarse/mid/fine', stats, 'lakes', len(lake_rings), 'cities', len(cities))
