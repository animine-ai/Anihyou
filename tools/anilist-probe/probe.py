"""Asks AniList for the entries a title search returns and prints what a matcher can use: every title and synonym, the
format, the start date, the episode count and the prequel/sequel chain. A diagnostic for the AniWorld matcher, not part of the app."""
import json, sys, time, urllib.error, urllib.request

QUERY = """
query($s:String){ Page(perPage:6){ media(search:$s, type:ANIME, sort:SEARCH_MATCH){
  id title{romaji english native} synonyms format status season seasonYear episodes
  startDate{year month day} endDate{year month day}
  relations{ edges{ relationType node{ id type format title{romaji english} seasonYear episodes startDate{year month day} } } }
}}}
"""

def ask(term):
    body = json.dumps({"query": QUERY, "variables": {"s": term}}).encode()
    request = urllib.request.Request("https://graphql.anilist.co", body, {"Content-Type": "application/json", "Accept": "application/json", "User-Agent": "Kiyori-matcher-probe/1.0 (diagnostic)"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)["data"]["Page"]["media"]
        except urllib.error.HTTPError as error:
            body = error.read()[:300]
            print("  HTTP", error.code, body, file=sys.stderr)
            if error.code not in (429, 500, 502, 503, 504):
                return []
            time.sleep(int(error.headers.get("Retry-After", 10)))
        except Exception as error:
            print("  retry", attempt, error, file=sys.stderr)
            time.sleep(8)
    return []

def date(d):
    return "-".join(str(d[k]).zfill(2) if d.get(k) else "??" for k in ("year", "month", "day")) if d else "?"

for line in open(sys.argv[1], encoding="utf-8"):
    term = line.strip()
    if not term or term.startswith("#"):
        continue
    print("=" * 100)
    print("SEARCH:", term)
    for media in ask(term):
        titles = media["title"]
        print(f"- {media['id']} [{media['format']}, {media['status']}, {media['seasonYear']} {media['season']}, eps={media['episodes']}] "
              f"start={date(media['startDate'])} end={date(media['endDate'])}")
        print("    romaji :", titles["romaji"])
        print("    english:", titles["english"])
        print("    native :", titles["native"])
        print("    synonyms:", media["synonyms"])
        for edge in media["relations"]["edges"]:
            if edge["relationType"] in ("PREQUEL", "SEQUEL", "PARENT", "SIDE_STORY", "ALTERNATIVE") and edge["node"]["type"] == "ANIME":
                node = edge["node"]
                print(f"    {edge['relationType']:<11} {node['id']} [{node['format']}, {node['seasonYear']}, eps={node['episodes']}] "
                      f"{node['title']['romaji']} / {node['title']['english']}")
    time.sleep(2)
