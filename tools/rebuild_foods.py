#!/usr/bin/env python3
"""Re-extract the pinned USDA per-100g values; preserve curated Chinese names and selection."""
import csv, hashlib, io, json, urllib.request, zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
path = ROOT / 'app/src/main/assets/foods.json'
data = json.loads(path.read_text())
archive = ROOT / 'tools/usda-sr.zip'
if not archive.exists():
    urllib.request.urlretrieve('https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip', archive)
if hashlib.sha256(archive.read_bytes()).hexdigest() != data['archive_sha256']:
    raise SystemExit('USDA archive changed; inspect before updating the source pin.')
with zipfile.ZipFile(archive) as z:
    base = next(n for n in z.namelist() if n.endswith('/food.csv')).removesuffix('food.csv')
    def rows(n): return csv.DictReader(io.StringIO(z.read(base+n).decode()))
    descriptions = {r['fdc_id']: r['description'] for r in rows('food.csv')}
    selected = {str(f['fdc_id']): f for f in data['foods']}
    values = {k:{} for k in selected}
    for r in rows('food_nutrient.csv'):
        if r['fdc_id'] in selected and r['nutrient_id'] in ['1008','1003','1004','1005']:
            values[r['fdc_id']][r['nutrient_id']] = float(r['amount'])
    for key, food in selected.items():
        food['description'] = descriptions[key]
        for name, nutrient in [('kcal','1008'),('protein','1003'),('fat','1004'),('carbs','1005')]:
            food[name] = values[key][nutrient]
path.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n')
print('Updated', len(selected), 'source-backed food records')
