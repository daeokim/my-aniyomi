"""Run the production recommendation query once against the public catalogue (no account/token)."""
import json
from pathlib import Path
import re
import urllib.error
import urllib.request

root = Path(__file__).resolve().parents[1]
code = (root / 'app/src/main/java/eu/kanade/tachiyomi/data/recommendations/RelatedMangaRepository.kt').read_text()
fields = re.search(r'const val FIELDS = "(.*?)"', code).group(1)
query = re.search(r'val RELATED_QUERY = "(.*?)"', code).group(1).replace('\\$', '$').replace('$FIELDS', fields)
output = root / 'personal/output/anilist-api-check.json'
output.parent.mkdir(parents=True, exist_ok=True)
try:
    request = urllib.request.Request(
        'https://graphql.anilist.co',
        data=json.dumps({'query': query, 'variables': {'id': 30011}}).encode(),
        headers={'Content-Type': 'application/json', 'Accept': 'application/json', 'User-Agent': 'Dtoon/1.0 API-contract-check'},
        method='POST',
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        data = json.load(response)
    if data.get('errors'):
        raise ValueError(json.dumps(data['errors'], ensure_ascii=False))
    media = data['data']['Media']
    assert media['type'] == 'MANGA' and media['id'] == 30011
    recommendations = media.get('recommendations', {}).get('nodes', [])
    result = {'status': 'passed', 'seed_id': media['id'], 'title': media['title'], 'recommendation_nodes': len(recommendations)}
except (Exception,) as error:
    result = {'status': 'unavailable', 'reason': str(error)}
output.write_text(json.dumps(result, ensure_ascii=False, indent=2))
print(json.dumps(result, ensure_ascii=False))
if result['status'] != 'passed':
    raise SystemExit(1)
