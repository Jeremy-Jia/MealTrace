#!/usr/bin/env python3
"""Reproducible offline USDA catalog: all SR Legacy entries and curated Chinese labels."""
import csv
import hashlib
import io
import json
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PIN = 'b80817294b8850530aaedf2e515c02593b1824f763a0ff356e5c2081643e6fd0'
archive = ROOT / 'tools/usda-sr.zip'
if not archive.exists():
    urllib.request.urlretrieve('https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip', archive)
assert hashlib.sha256(archive.read_bytes()).hexdigest() == PIN, 'USDA archive pin changed'
labels = json.loads((ROOT / 'tools/food_labels.json').read_text())
assert len({f['id'] for f in labels}) == len(labels)
assert len({f['fdc_id'] for f in labels}) == len(labels)
curated = {str(f['fdc_id']): f for f in labels}
groups = {'1': '奶类', '2': '调味', '3': '婴幼儿食品', '4': '油脂', '5': '肉类',
          '6': '汤与酱料', '7': '加工肉类', '8': '主食', '9': '水果', '10': '肉类',
          '11': '蔬菜', '12': '坚果种子', '13': '肉类', '14': '饮品', '15': '水产',
          '16': '豆类', '17': '肉类', '18': '主食', '19': '甜食', '20': '主食',
          '21': '快餐', '22': '混合菜', '23': '零食', '24': '其他', '25': '餐馆食品', '28': '酒类'}
with zipfile.ZipFile(archive) as z:
    base = next(n for n in z.namelist() if n.endswith('/food.csv')).removesuffix('food.csv')
    def rows(name):
        return csv.DictReader(io.StringIO(z.read(base + name).decode()))
    source = {r['fdc_id']: r for r in rows('food.csv')}
    values = {k: {} for k in source}
    nutrients = {'1008': 'kcal', '1003': 'protein', '1004': 'fat', '1005': 'carbs'}
    for r in rows('food_nutrient.csv'):
        if r['nutrient_id'] in nutrients:
            values[r['fdc_id']][nutrients[r['nutrient_id']]] = float(r['amount'])
    foods = []
    for key in [str(f['fdc_id']) for f in labels] + sorted(set(source) - set(curated), key=int):
        row = source[key]
        assert len(values[key]) == 4, f'Missing nutrient data for {key}'
        food = dict(curated[key]) if key in curated else dict(id='usda_' + key, name=row['description'], fdc_id=int(key), serving=100, aliases=[])
        food.update(description=row['description'], category=groups.get(row['food_category_id'], '其他'), common=key in curated)
        if row['food_category_id'] == '1' and row['description'].startswith('Egg'):
            food['category'] = '蛋类'
        if food['id'] == 'coconut': food['category'] = '水果'
        food.update(values[key])
        foods.append(food)
data = dict(source='USDA FoodData Central SR Legacy 2018-04', url='https://fdc.nal.usda.gov/download-datasets/',
            units='per 100 g edible portion; kcal and grams', archive_sha256=PIN, foods=foods)
encoded = json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n'
for path in [ROOT / 'app/src/main/assets/foods.json', ROOT / 'iOS/Resources/foods.json']:
    path.write_text(encoded)
aliases = {f['id']: f['aliases'] for f in labels}
(ROOT / 'iOS/Resources/aliases.json').write_text(json.dumps(aliases, ensure_ascii=False, indent=2) + '\n')
print(f'{len(foods)} USDA foods; {len(labels)} curated Chinese entries; {len(encoded.encode())} bytes')
