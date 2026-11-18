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
lakes = json.load(open(f'{SRC}/ne_10m_lakes.geojson'))['features'] + \
    json.load(open(f'{SRC}/ne_10m_lakes_europe.geojson'))['features']
rivers = json.load(open(f'{SRC}/ne_10m_rivers_lake_centerlines.geojson'))['features'] + \
    json.load(open(f'{SRC}/ne_10m_rivers_europe.geojson'))['features']
roads = json.load(open(f'{SRC}/ne_10m_roads.geojson'))['features']
places = json.load(open(f'{SRC}/ne_10m_populated_places_simple.geojson'))['features']

stats = [0, 0, 0]
with open(OUT, 'wb') as f:
    f.write(b'MGW5')
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
        if mz > 10:
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
    # Lines: rivers (kind 3) and roads (0 motorway, 1 main, 2 other), with the zoom they appear at.
    def lines_of(geom):
        parts = geom['coordinates'] if geom['type'] == 'MultiLineString' else [geom['coordinates']]
        for part in parts:
            yield [merc(lon, lat) for lon, lat in part]
    out_lines = []
    for r in rivers:
        pr = r['properties']
        if r['geometry'] is None: continue
        mz = pr.get('min_zoom') or 6
        for line in lines_of(r['geometry']):
            sl = dp(line, 0.000006)
            if len(sl) >= 2: out_lines.append((mz, 3, sl))
    road_kind = {'Major Highway': 0, 'Beltway': 0, 'Secondary Highway': 1, 'Road': 2, 'Unknown': 2, 'Bypass': 1}
    for r in roads:
        pr = r['properties']
        if r['geometry'] is None: continue
        k = road_kind.get(pr.get('type'))
        if k is None: continue
        if pr.get('expressway') == 1: k = 0
        mz = pr.get('min_zoom') or 7
        for line in lines_of(r['geometry']):
            sl = dp(line, 0.000005)
            if len(sl) >= 2: out_lines.append((mz, k, sl))
    out_lines.sort(key=lambda t: (0 if t[1] == 3 else 1, -t[1]))  # rivers first, then minor -> major roads on top
    f.write(struct.pack('<I', len(out_lines)))
    pts = 0
    for mz, k, sl in out_lines:
        xs = [p[0] for p in sl]; ys = [p[1] for p in sl]
        f.write(struct.pack('<4f2BI', min(xs), min(ys), max(xs), max(ys), int(math.floor(mz)), k, len(sl)))
        for x, y in sl:
            f.write(struct.pack('<2f', x, y))
        pts += len(sl)
    print('lines', len(out_lines), 'points', pts)
print('points coarse/mid/fine', stats, 'lakes', len(lake_rings), 'cities', len(cities))
