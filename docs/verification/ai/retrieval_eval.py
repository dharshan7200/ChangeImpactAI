"""Retrieval evaluation for the AI search index: Hit@1, Hit@5 and MRR@10 per retriever.

  python3 retrieval_eval.py <api base> <email> <password> <repositoryId> <queries.json> <out.json> [modes]

modes: comma-separated subset of fts,vector,hybrid,auto (default: all; "fts" alone needs no embeddings).

For every query it asks the API for the full-text top 50, the vector top 50 and the server's hybrid and auto top 10.
When fts and vector both run it also reports Reciprocal Rank Fusion of the two top-50 lists with the full-text list weighted by
w in {0.25, 0.5} (score = w / (60 + rank_fts) + 1 / (60 + rank_vec)); w = 1 reproduces the server's hybrid.
A hit is a result whose qualified name, without the file's prefix and parameter list, is in the query's labels.
"""
import json, sys, time, urllib.request

BASE, EMAIL, PW, REPO, QF, OUT = sys.argv[1:7]
MODES = sys.argv[7].split(",") if len(sys.argv) > 7 else ["fts", "vector", "hybrid", "auto"]
spec = json.load(open(QF))
PREFIX = spec.get("prefix", "org.springframework.samples.petclinic.")
K = 60


def call(method, path, token=None, body=None):
    h = {"Content-Type": "application/json"}
    if token: h["Authorization"] = "Bearer " + token
    r = urllib.request.Request(BASE + path, method=method, headers=h, data=None if body is None else json.dumps(body).encode())
    with urllib.request.urlopen(r, timeout=300) as resp: return json.loads(resp.read())


def relevant(name, labels):
    name = name.removeprefix(PREFIX)
    return name in labels or name.split("(")[0] in labels


def first_rank(names, labels):
    return next((i + 1 for i, n in enumerate(names[:10]) if relevant(n, labels)), None)


def fuse(fts, vec, w):
    score = {}
    for i, n in enumerate(fts): score[n] = score.get(n, 0) + w / (K + i + 1)
    for i, n in enumerate(vec): score[n] = score.get(n, 0) + 1 / (K + i + 1)
    return [n for n, _ in sorted(score.items(), key=lambda kv: (-kv[1], kv[0]))]


token = call("POST", "/auth/login", body={"email": EMAIL, "password": PW})["data"]["accessToken"]
FUSED = "fts" in MODES and "vector" in MODES
rankings = {m: [] for m in MODES + (["rrf_w0.25", "rrf_w0.5"] if FUSED else [])}
per_query, ms, fallbacks = [], {m: [] for m in MODES}, []
for item in spec["queries"]:
    lists = {}
    for mode in MODES:
        t = time.time()
        res = call("POST", f"/repositories/{REPO}/rag/search", token,
                   {"query": item["q"], "mode": mode, "k": 50 if mode in ("fts", "vector") else 10})["data"]
        ms[mode].append((time.time() - t) * 1000)
        if res.get("note"): fallbacks.append({"q": item["q"], "mode": mode, "note": res["note"]})
        lists[mode] = [h["qualifiedName"] + "@" + str(h["startLine"]) for h in res["hits"]]
    if FUSED:
        lists["rrf_w0.25"] = fuse(lists["fts"], lists["vector"], 0.25)
        lists["rrf_w0.5"] = fuse(lists["fts"], lists["vector"], 0.5)
    row = {"q": item["q"]}
    for mode, names in lists.items():
        plain = [n.rsplit("@", 1)[0] for n in names]
        rankings[mode].append(first_rank(plain, item["relevant"]))
        row[mode] = rankings[mode][-1]
    first = "vector" if "vector" in lists else MODES[0]
    row[first + "Top3"] = [n.rsplit("@", 1)[0].removeprefix(PREFIX) for n in lists[first][:3]]
    per_query.append(row)

n = len(spec["queries"])
summary = {}
for mode, ranks in rankings.items():
    summary[mode] = {"hit@1": round(sum(r == 1 for r in ranks) / n, 3),
                     "hit@5": round(sum(bool(r and r <= 5) for r in ranks) / n, 3),
                     "mrr@10": round(sum(1 / r for r in ranks if r) / n, 3)}
    if mode in ms: summary[mode]["medianMs"] = round(sorted(ms[mode])[n // 2])
json.dump({"repository": spec["repository"], "queries": n, "summary": summary, "fallbacks": fallbacks, "perQuery": per_query},
          open(OUT, "w"), indent=1)
if fallbacks: print(f"WARNING: {len(fallbacks)} searches fell back to full-text, e.g. {fallbacks[0]['note']}")
for mode, s in summary.items(): print(f"{mode:10} " + "  ".join(f"{k}={v}" for k, v in s.items()))
