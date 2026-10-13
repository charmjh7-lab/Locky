"""Delete only old complete Locky automatic releases, after a verified new upload."""
import json, subprocess, os, re
repo=os.environ['GITHUB_REPOSITORY']
releases=json.loads(subprocess.check_output(['gh','api','--paginate','--slurp',f'repos/{repo}/releases?per_page=100']))
items=[r for page in releases for r in page if re.fullmatch(r'locky-build-\d+',r['tag_name']) and not r['draft'] and any(a['name']=='Locky.apk' for a in r['assets'])]
items.sort(key=lambda r:int(r['tag_name'].rsplit('-',1)[1]),reverse=True)
current=f"locky-build-{os.environ['GITHUB_RUN_NUMBER']}"
assert items and items[0]['tag_name']==current, 'Newest release must exist before cleanup'
for release in items[3:]:
    subprocess.run(['gh','release','delete',release['tag_name'],'--repo',repo,'--yes','--cleanup-tag'],check=True)
print(f'Kept {min(3,len(items))} releases; removed {max(0,len(items)-3)} old automatic releases.')
