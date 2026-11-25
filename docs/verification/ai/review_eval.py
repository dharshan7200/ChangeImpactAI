"""Runs the AI review on existing analyses and records what the platform's validation found.

  python3 review_eval.py <api base> <email> <password> <out.json> <analysisId> [<analysisId> ...]

For each analysis: POST /analyses/{id}/ai-review, poll until COMPLETED or FAILED, keep the review (findings with their
validation status, evidence list, model, tokens, latency). Correctness of the findings is judged by a person against
the source afterwards; this script only collects the runs.
"""
import json, sys, time, urllib.request, urllib.error

BASE, EMAIL, PW, OUT = sys.argv[1:5]
IDS = sys.argv[5:]


def call(method, path, token=None, body=None):
    h = {"Content-Type": "application/json"}
    if token: h["Authorization"] = "Bearer " + token
    r = urllib.request.Request(BASE + path, method=method, headers=h, data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(r, timeout=120) as resp: return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return json.loads(e.read() or b"{}")


token = call("POST", "/auth/login", body={"email": EMAIL, "password": PW})["data"]["accessToken"]
runs = []
for aid in IDS:
    a = call("GET", f"/analyses/{aid}", token)["data"]
    started = call("POST", f"/analyses/{aid}/ai-review", token)
    t0 = time.time()
    while True:
        review = call("GET", f"/analyses/{aid}/ai-review", token)["data"]
        if review["status"] in ("COMPLETED", "FAILED"): break
        if time.time() - t0 > 900: review = {"status": "TIMEOUT"}; break
        time.sleep(5)
    repo = call("GET", f"/repositories/{a['repositoryId']}", token).get("data") or {}
    run = {"analysisId": aid, "repository": repo.get("fullName"), "base": a.get("baseCommit", "")[:7],
           "head": a.get("headCommit", "")[:7], "riskLevel": a.get("riskLevel"), "start": started.get("message"),
           "wallSeconds": round(time.time() - t0, 1), "review": review}
    runs.append(run)
    v = review.get("validation") or {}
    print(f"{aid[:8]} {run['repository']} {run['base']}..{run['head']}: {review['status']} {review.get('model')} "
          f"findings={v.get('findings')} verified={v.get('verified')} unverified={v.get('unverified')} "
          f"tests={v.get('testSuggestionsVerified')}/{v.get('testSuggestions')} invalidCitations={v.get('invalidCitations')} "
          f"unknownMentions={v.get('unknownMentions')} evidence={v.get('evidenceItems')} tokens={review.get('promptTokens')}+{review.get('outputTokens')} "
          f"latency={review.get('latencyMs')}ms related={v.get('relatedCodeRetrieval', '')[:40]} {review.get('errorMessage') or ''}", flush=True)
    json.dump(runs, open(OUT, "w"), indent=1)
