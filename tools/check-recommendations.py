"""Run the app's recommendation queries for real Korean webtoon titles and summarize what users would see.

Diagnostic only (no account/token): it mirrors RelatedMangaRepository's flow with the exact query strings
read from the Kotlin source, prints a Markdown report and appends it to $GITHUB_STEP_SUMMARY when set.
"""
import json
import os
from pathlib import Path
import re
import sys
import time
import unicodedata
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CODE = (ROOT / 'app/src/main/java/eu/kanade/tachiyomi/data/recommendations/RelatedMangaRepository.kt').read_text()
FIELDS = re.search(r'const val FIELDS = "(.*?)"', CODE).group(1)
MIN_SAME_COUNTRY_FILL = int(re.search(r'MIN_SAME_COUNTRY_FILL = (\d+)', CODE).group(1))
MIN_THEMED = int(re.search(r'MIN_THEMED = (\d+)', CODE).group(1))
MIN_TAG_RANK = int(re.search(r'MIN_TAG_RANK = (\d+)', CODE).group(1))


FALLBACK_TAGS = int(re.search(r'FALLBACK_TAGS = (\d+)', CODE).group(1))


def themes(m):
    tags = [t for t in m.get('tags') or [] if not t.get('isMediaSpoiler') and t.get('name') and not (t.get('category') or '').startswith('Technical')]
    strong = [t for t in tags if t.get('rank', 0) >= MIN_TAG_RANK]
    chosen = strong or sorted(tags, key=lambda t: -t.get('rank', 0))[:FALLBACK_TAGS]
    return {t['name'].lower() for t in chosen}


def query(name):
    raw = re.search(rf'val {name} = "(.*?)"\n', CODE).group(1)
    return raw.replace('\\$', '$').replace('$FIELDS', FIELDS)


SEARCH, RELATED, GENRE, GENRE_COUNTRY = (query(n) for n in ('SEARCH_QUERY', 'RELATED_QUERY', 'GENRE_QUERY', 'GENRE_COUNTRY_QUERY'))
TITLES = sys.argv[1:] or ['나 혼자만 레벨업', '전지적 독자 시점', '외모지상주의', '화산귀환', '여신강림', '독립일기', '신의 탑', '마루는 강쥐']


def post(q, variables):
    request = urllib.request.Request(
        'https://graphql.anilist.co', json.dumps({'query': q, 'variables': variables}).encode(),
        {'Content-Type': 'application/json', 'Accept': 'application/json', 'User-Agent': 'Dtoon/1.0 recommendation-check'},
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        data = json.load(response)
    time.sleep(2.2)  # Stay under the public rate limit.
    if data.get('errors'):
        raise RuntimeError(data['errors'])
    return data['data']


def normalize(title):
    title = unicodedata.normalize('NFKC', title)
    title = re.sub(r'[\[(](완결|연재|단행본|컬러|웹툰|개정판|완전판)[)\]]', '', title).strip()
    return ''.join(c for c in title.lower() if c.isalnum())


def names(m):
    t = m['title']
    return [n for n in [t.get('native'), t.get('english'), t.get('romaji'), *m.get('synonyms', [])] if n]


def label(m):
    hangul = next((n for n in names(m) if any('가' <= c <= '힣' for c in n)), None)
    return f"{hangul or m['title'].get('english') or m['title'].get('romaji')} ({m.get('countryOfOrigin') or '?'})"


def comic(m):
    return m['id'] > 0 and m.get('type') == 'MANGA' and m.get('format') != 'NOVEL'


def recommend(title):
    candidates = [m for m in post(SEARCH, {'search': title})['Page']['media'] if comic(m)]
    exact = [m for m in candidates if any(normalize(n) == normalize(title) for n in names(m))]
    if len(exact) != 1:
        return f'사용자 선택 필요 (정확히 일치 {len(exact)}개, 후보: {", ".join(label(m) for m in candidates[:3]) or "없음"})', []
    seed = post(RELATED, {'id': exact[0]['id']})['Media']
    genres, country, seed_themes = set(seed['genres']), seed.get('countryOfOrigin'), themes(seed)
    eligible = lambda m: m['id'] != seed['id'] and comic(m) and not m.get('isAdult') and genres & set(m['genres'])
    nodes = sorted(seed['recommendations']['nodes'], key=lambda n: -n['rating'])
    items = [dict(n['mediaRecommendation'], direct=True) for n in nodes if n.get('mediaRecommendation') and eligible(n['mediaRecommendation'])]
    source = f'직접 추천 {len(items)}개'
    if len(items) < 3:
        local = []
        if country:
            local = [m for m in post(GENRE_COUNTRY, {'id': seed['id'], 'genres': seed['genres'], 'country': country})['Page']['media'] if eligible(m)]
            source += f' + 같은 나라 장르 {len(local)}개'
        items += local
        if not country or len(local) < MIN_SAME_COUNTRY_FILL:
            extra = [m for m in post(GENRE, {'id': seed['id'], 'genres': seed['genres']})['Page']['media'] if eligible(m)]
            source += f' + 전체 장르 {len(extra)}개'
            items += extra
    unique = list({m['id']: m for m in reversed(items)}.values())[::-1]
    if seed_themes and sum(1 for m in unique if seed_themes & themes(m)) >= MIN_THEMED:
        unique = [m for m in unique if m.get('direct') or seed_themes & themes(m)]
        source += ' · 공통 주제 없는 장르 보충 제외'
    seen, ranked = set(), []
    for m in sorted(unique, key=lambda m: (bool(country) and m.get('countryOfOrigin') != country, -len(seed_themes & themes(m)), -len(genres & set(m['genres'])))):
        if m['id'] not in seen:
            seen.add(m['id'])
            ranked.append(m)
    shown = ', '.join(sorted(seed_themes)[:6]) or '없음'
    return f"{label(seed)} · 장르 {', '.join(sorted(genres))} · 주제 {shown} · {source}", ranked[:18]


def annotate(title, lines):
    """One notice per title keeps under the per-step annotation limit (10)."""
    if not os.environ.get('GITHUB_ACTIONS'):
        return
    message = '\n'.join(lines).replace('%', '%25').replace('\r', '').replace('\n', '%0A')
    print(f'::notice title={title.replace(",", " ").replace("::", ":")}::{message}')


def main():
    lines = ['## 장르별 추천 실제 결과 (AniList)', '']
    same_country = total = 0
    for title in TITLES:
        try:
            summary, items = recommend(title)
        except Exception as error:  # noqa: BLE001 - a diagnostic reports every failure
            summary, items = f'조회 실패: {error}', []
        seed_country = re.search(r'\((\w\w|\?)\) ·', summary)
        if items and seed_country:
            total += len(items[:8])
            same_country += sum(1 for m in items[:8] if m.get('countryOfOrigin') == seed_country.group(1))
        lines.append(f'- **{title}** → {summary}')
        lines += [f'  - {i + 1}. {label(m)}' for i, m in enumerate(items[:8])]
        # Annotations are readable through the public check-runs API, unlike the job summary.
        annotate(f'추천 · {title}', [summary] + [f'{i + 1}. {label(m)}' for i, m in enumerate(items[:8])])
    lines += ['', f'상위 8개 중 원작과 같은 나라 작품: {same_country}/{total}']
    annotate('추천 · 요약', [f'상위 8개 중 원작과 같은 나라 작품: {same_country}/{total}'])
    report = '\n'.join(lines)
    print(report)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary_file:
            summary_file.write(report + '\n')


if __name__ == '__main__':
    main()
