"""Reads the public season list of series on the source site: how many seasons, how many episodes each. A diagnostic
for the matcher (which season of the source belongs to which AniList entry), not part of the app."""
import re, sys, time, urllib.request

BASE = "https://aniworld.to/anime/" + "stream/"
HEADERS = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
           "Accept-Language": "de,en;q=0.8"}

def get(url):
    request = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read().decode("utf-8", "replace")

for line in open(sys.argv[1], encoding="utf-8"):
    slug = line.strip()
    if not slug or slug.startswith("#"):
        continue
    print("=" * 80)
    print("SERIES:", slug)
    try:
        page = get(BASE + slug)
    except Exception as error:
        print("  page failed:", error)
        continue
    title = re.search(r"<h1[^>]*>\s*<span>(.*?)</span>", page, re.S)
    print("  title:", re.sub(r"<[^>]+>", "", title.group(1)).strip() if title else "?")
    desc = re.search(r'<p class="seri_des"[^>]*data-full-description="([^"]*)"', page)
    print("  description:", (desc.group(1) if desc else "")[:300])
    seasons = sorted(set(re.findall(r'/staffel-(\d+)"', page)), key=int)
    print("  seasons:", seasons, "| films:", "filme" in page)
    for season in seasons:
        time.sleep(1)
        try:
            body = get(f"{BASE}{slug}/staffel-{season}")
        except Exception as error:
            print(f"  staffel {season}: failed {error}")
            continue
        episodes = sorted(set(re.findall(r'/staffel-%s/episode-(\d+)"' % season, body)), key=int)
        titles = re.findall(r'<strong>([^<]+)</strong>', body)[:2]
        print(f"  staffel {season}: {len(episodes)} episodes ({episodes[0] if episodes else '-'}..{episodes[-1] if episodes else '-'}) first titles: {titles}")
    time.sleep(2)
