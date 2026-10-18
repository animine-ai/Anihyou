"""Prints what the calendar and the recent-episodes page of the source site say about the series in the given file:
the season/episode label and the link of each row. A diagnostic for the matcher, not part of the app."""
import re, sys, urllib.request

ROOT = "https://aniworld.to/"
HEADERS = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
           "Accept-Language": "de,en;q=0.8"}
slugs = [line.strip() for line in open(sys.argv[1], encoding="utf-8") if line.strip() and not line.startswith("#")]
for page in ("animekalender", "neu"):
    try:
        request = urllib.request.Request(ROOT + page, headers=HEADERS)
        body = urllib.request.urlopen(request, timeout=30).read().decode("utf-8", "replace")
    except Exception as error:
        print(page, "failed:", error)
        continue
    print("=" * 80)
    print(page, len(body), "bytes")
    for slug in slugs:
        for match in re.finditer(r'<a[^>]+href="[^"]*' + re.escape(slug) + r'[^"]*"[^>]*>.*?</a>', body, re.S):
            print(" ", slug, "|", re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", match.group(0))).strip()[:160],
                  "|", re.search(r'href="([^"]*)"', match.group(0)).group(1))
