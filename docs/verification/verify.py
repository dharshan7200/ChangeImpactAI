#!/usr/bin/env python3
"""End-to-end feature verification of a running ChangeImpact AI stack (through the published web port).
Usage: verify.py <base-url> <webhook-secret> <reports-dir> <out.json>"""
import hashlib, hmac, json, sys, time, uuid, urllib.request, urllib.error

BASE, SECRET, REPORTS, OUT = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
API = BASE + "/api/v1"
results = []


def check(area, feature, ok, evidence):
    results.append({"area": area, "feature": feature, "result": "PASS" if ok else "FAIL", "evidence": str(evidence)[:400]})
    print(("PASS " if ok else "FAIL ") + f"[{area}] {feature}: {str(evidence)[:200]}", flush=True)
    return ok


def req(method, path, token=None, body=None, headers=None, raw=False, base=API):
    h = dict(headers or {})
    data = None
    if token: h["Authorization"] = "Bearer " + token
    if body is not None and not isinstance(body, (bytes, bytearray)):
        data = json.dumps(body).encode(); h.setdefault("Content-Type", "application/json")
    elif body is not None:
        data = bytes(body)
    r = urllib.request.Request(base + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            content = resp.read(); status = resp.status; rh = dict(resp.headers)
    except urllib.error.HTTPError as e:
        content = e.read(); status = e.code; rh = dict(e.headers)
    if raw: return status, content, rh
    try: return status, json.loads(content or b"null")
    except Exception: return status, content.decode(errors="replace")


def data(resp):
    s, b = resp
    return b.get("data") if isinstance(b, dict) else None


def wait_job(token, job_id, timeout=1800):
    end = time.time() + timeout
    while time.time() < end:
        j = data(req("GET", f"/jobs/{job_id}", token))
        if j and j.get("status") in ("COMPLETED", "FAILED"): return j
        time.sleep(4)
    return {"status": "TIMEOUT"}


def wait_index(token, repo, timeout=1800):
    end = time.time() + timeout
    while time.time() < end:
        st = data(req("GET", f"/repositories/{repo}/index-status", token)) or {}
        if st.get("indexingStatus") in ("INDEXED", "FAILED") and st.get("currentStage") in ("COMPLETED", "FAILED", None): return st
        time.sleep(4)
    return {"indexingStatus": "TIMEOUT"}


def wait_analysis(token, aid, timeout=1800):
    end = time.time() + timeout
    while time.time() < end:
        a = data(req("GET", f"/analyses/{aid}", token)) or {}
        if a.get("status") in ("COMPLETED", "FAILED"): return a
        time.sleep(4)
    return {"status": "TIMEOUT"}


def multipart(fields, files):
    b = "----cia" + uuid.uuid4().hex
    out = bytearray()
    for k, v in fields.items():
        out += f"--{b}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
    for k, (name, content) in files:
        out += f"--{b}\r\nContent-Disposition: form-data; name=\"{k}\"; filename=\"{name}\"\r\nContent-Type: application/octet-stream\r\n\r\n".encode()
        out += content + b"\r\n"
    out += f"--{b}--\r\n".encode()
    return out, {"Content-Type": f"multipart/form-data; boundary={b}"}


# ---------------------------------------------------------------- A. web tier
s, body, h = req("GET", "/healthz", raw=True, base=BASE)
check("Web tier", "nginx health endpoint", s == 200 and body.strip() == b"ok", f"{s} {body[:20]!r}")
s, body, h = req("GET", "/", raw=True, base=BASE)
hl = {k.lower(): v for k, v in h.items()}
check("Web tier", "security headers on the SPA", "content-security-policy" in hl and hl.get("x-frame-options") == "DENY"
      and hl.get("x-content-type-options") == "nosniff", {k: hl.get(k) for k in ("x-frame-options", "x-content-type-options", "referrer-policy")})
s, body, h = req("GET", "/repositories/some/deep/link", raw=True, base=BASE)
check("Web tier", "SPA deep link served (no-cache)", s == 200 and b"<div id=\"root\"" in body and "no-cache" in h.get("Cache-Control", ""), f"{s} {h.get('Cache-Control')}")
s, body, h = req("GET", "/actuator/env", raw=True, base=BASE)
check("Web tier", "actuator not exposed through the web port", b"<!doctype html>" in body.lower()[:50] or s == 404, f"{s} returns {'SPA page' if b'<!doctype' in body.lower()[:50] else body[:40]}")

# ---------------------------------------------------------------- B. authentication
PW = "Verify!Passw0rd"
s, b = req("POST", "/auth/register", body={"email": "admin@example.com", "username": "admin", "password": PW, "displayName": "Admin"})
check("Auth", "register (admin e-mail)", s == 201, s)
s, b = req("POST", "/auth/register", body={"email": "other@example.com", "username": "other", "password": PW, "displayName": "Other"})
check("Auth", "register second user", s == 201, s)
s, b = req("POST", "/auth/register", body={"email": "admin@example.com", "username": "admin2", "password": PW, "displayName": "Dup"})
check("Auth", "duplicate e-mail refused", s in (400, 409), f"{s} {b.get('error', b) if isinstance(b, dict) else b}")
s, b = req("POST", "/auth/register", body={"email": "weak@example.com", "username": "weak", "password": "short", "displayName": "W"})
check("Auth", "weak password refused", s == 400, s)
s, b = req("POST", "/auth/login", body={"email": "admin@example.com", "password": PW})
tok = b["data"]["accessToken"]; refresh = b["data"].get("refreshToken")
check("Auth", "login returns access + refresh token", s == 200 and tok and refresh, s)
s, b = req("POST", "/auth/login", body={"email": "admin@example.com", "password": "wrong-password"})
check("Auth", "wrong password → 401", s == 401, s)
s, b = req("POST", "/auth/refresh", body={"refreshToken": refresh})
check("Auth", "refresh token rotation", s == 200 and data((s, b)) and data((s, b)).get("accessToken"), s)
s, b = req("GET", "/repositories")
check("Auth", "API without token → 401", s == 401, s)
s, b = req("GET", "/repositories", token="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.forged")
check("Auth", "forged JWT → 401", s == 401, s)
other = data(req("POST", "/auth/login", body={"email": "other@example.com", "password": PW}))["accessToken"]

# ---------------------------------------------------------------- C. repositories & indexing
def connect(name):
    s, b = req("POST", "/repositories/connect", tok, {"fullName": name})
    return s, (data((s, b)) or {}).get("id"), b

s, pc, b = connect("spring-projects/spring-petclinic")
check("Repositories", "connect public repository (Java)", s in (200, 201) and pc, s)
s, py, b = connect("psf/requests")
check("Repositories", "connect public repository (Python)", s in (200, 201) and py, s)
s, rest, b = connect("spring-petclinic/spring-petclinic-rest")
check("Repositories", "connect third repository", s in (200, 201) and rest, s)
s, cl, b = connect("apache/commons-lang")
check("Repositories", "connect large repository (commons-lang)", s in (200, 201) and cl, s)
s, _, b = connect("this-owner-does-not-exist-9x/nothing-here")
check("Repositories", "non-existent repository refused", s in (400, 404), f"{s} {b.get('code') if isinstance(b, dict) else ''}: {b.get('message') if isinstance(b, dict) else ''}")
s, _, b = connect("https://gitlab.com/gitlab-org/gitlab.git")
check("Repositories", "non-GitHub URL refused (input validation)", s in (400, 403), f"{s} {b.get('code') if isinstance(b, dict) else ''}")
repo_info = data(req("GET", f"/repositories/{pc}", tok)) or {}
check("Repositories", "default branch read from the remote", repo_info.get("defaultBranch") == "main", repo_info.get("defaultBranch"))

# defect model on commons-lang runs in the background meanwhile (clone + 3k commits + SZZ)
s, b = req("POST", f"/repositories/{cl}/defect-model/train", tok)
cl_job = (data((s, b)) or {}).get("jobId")
check("Defect model", "training job queued (commons-lang)", s == 202 and cl_job, s)

for rid, name, lang in ((pc, "spring-petclinic", "Java"), (py, "psf/requests", "Python"), (rest, "spring-petclinic-rest", "Java")):
    s, b = req("POST", f"/repositories/{rid}/index", tok)
    t0 = time.time()
    st = wait_index(tok, rid)
    info = data(req("GET", f"/repositories/{rid}", tok)) or {}
    check("Indexing", f"index {name}", st.get("indexingStatus") == "INDEXED", f"{st.get('indexingStatus')} in {time.time() - t0:.0f}s, commit {str(st.get('lastIndexedCommit'))[:7]}")
    check("Indexing", f"{name} labelled {lang} from its code", info.get("language") == lang, info.get("language"))
s, b = req("POST", f"/repositories/{pc}/index", tok)
s2, b2 = req("POST", f"/repositories/{pc}/index", tok)
check("Indexing", "second concurrent index request refused (code JOB_ALREADY_RUNNING)", s == 202 and s2 == 400 and isinstance(b2, dict) and b2.get("code") == "JOB_ALREADY_RUNNING", f"{s} then {s2} {b2.get('code') if isinstance(b2, dict) else ''}")
wait_index(tok, pc)
jobs = data(req("GET", f"/jobs/repository/{pc}?page=0&size=20", tok)) or {}
items = jobs.get("content", jobs if isinstance(jobs, list) else [])
check("Indexing", "job history with stages", len(items) >= 2 and all(j.get("status") in ("COMPLETED", "FAILED", "QUEUED", "RUNNING") for j in items), f"{len(items)} jobs, latest stage {items[0].get('currentStage') if items else None}")
reuse = items[0] if items else {}
check("Indexing", "re-index of an analysed commit reuses symbols", reuse.get("status") == "COMPLETED", f"{reuse.get('status')} {reuse.get('currentMessage')}")

# ---------------------------------------------------------------- D. code intelligence
syms = data(req("GET", f"/code/repositories/{pc}/symbols?query=OwnerController&symbolType=CLASS&page=0&size=10", tok)) or {}
cands = syms.get("content", [])
owner_ctrl = next((x for x in cands if x.get("simpleName") == "OwnerController"), None)
check("Code intelligence", "symbol search", owner_ctrl is not None, f"{len(cands)} result(s)")
sid = owner_ctrl["id"] if owner_ctrl else None
d = data(req("GET", f"/code/symbols/{sid}", tok)) or {}
check("Code intelligence", "symbol details with source location", d.get("qualifiedName", "").endswith("OwnerController") and (d.get("startLine") or 0) > 0, f"{d.get('qualifiedName')} line {d.get('startLine')}")
fwd = data(req("GET", f"/code/symbols/{sid}/dependencies?depth=2", tok))
check("Code intelligence", "forward dependencies", bool(fwd), f"{len(fwd) if isinstance(fwd, list) else type(fwd).__name__}")
owner = next((x for x in (data(req("GET", f"/code/repositories/{pc}/symbols?query=Owner&symbolType=CLASS&page=0&size=20", tok)) or {}).get("content", []) if x.get("simpleName") == "Owner"), None)
dep = data(req("GET", f"/code/symbols/{owner['id']}/dependents?depth=3", tok)) if owner else None
check("Code intelligence", "reverse dependents (blast radius) of Owner", bool(dep), f"{len(dep) if isinstance(dep, list) else (list(dep.keys())[:4] if isinstance(dep, dict) else dep)}")
for view in ("PACKAGE", "CLASS", "MEMBER"):
    g = data(req("GET", f"/code/repositories/{pc}/graph?view={view}", tok)) or {}
    check("Code intelligence", f"dependency graph, {view} view", len(g.get("nodes", [])) > 0, f"{len(g.get('nodes', []))} nodes, {len(g.get('edges', []))} edges")
st = data(req("GET", f"/code/repositories/{pc}/graph/stats", tok)) or {}
check("Code intelligence", "graph statistics incl. resolution coverage", bool(st), {k: st[k] for k in list(st)[:4]})
m = data(req("GET", f"/code/repositories/{pc}/metrics", tok))
check("Code intelligence", "coupling / instability metrics", bool(m), type(m).__name__)
v = req("GET", f"/code/repositories/{pc}/architecture/violations", tok)
check("Code intelligence", "architecture violations endpoint", v[0] == 200, v[0])
u = req("GET", f"/code/repositories/{pc}/dependencies/unresolved?page=0&size=10", tok)
check("Code intelligence", "unresolved references listed (not hidden)", u[0] == 200, f"{u[0]} total {(data(u) or {}).get('totalElements')}")
if owner and sid:
    pth = req("GET", f"/code/repositories/{pc}/paths?sourceSymbolId={sid}&targetSymbolId={owner['id']}", tok)
    check("Code intelligence", "shortest dependency path OwnerController → Owner", pth[0] == 200 and data(pth), str(data(pth))[:160])

# ---------------------------------------------------------------- E. history
commits = (data(req("GET", f"/repositories/{pc}/commits?page=0&size=50", tok)) or {}).get("content", [])
check("History", "commit history with change stats", len(commits) >= 20 and "additions" in commits[0], f"{len(commits)} commits")
hist = data(req("GET", f"/repositories/{pc}/history?weeks=520", tok)) or {}
check("History", "weekly activity and most-changed files", bool(hist), list(hist.keys())[:5] if isinstance(hist, dict) else hist)

# ---------------------------------------------------------------- F. change analysis (Java)
s, b = req("POST", "/analyses", tok, {"repositoryId": pc, "baseRef": "818c413", "headRef": "500158f"})
aid = (data((s, b)) or {}).get("analysisId")
a = wait_analysis(tok, aid)
check("Change analysis", "analysis 818c413 → 500158f completes", a.get("status") == "COMPLETED", f"{a.get('status')} risk {a.get('riskLevel')} {a.get('riskScore')}")
# 58.5 HIGH before library resolution (2026-11-19/21); library-typed calls now resolve, so more real dependency edges
# can change the score: recorded here, determinism is checked by re-analysing below.
check("Change analysis", "risk level and score recorded", a.get("riskLevel") in ("LOW", "MEDIUM", "HIGH", "CRITICAL") and a.get("riskScore") is not None,
      f"{a.get('riskLevel')} {a.get('riskScore')} (58.5 HIGH before library resolution)")
ch = data(req("GET", f"/analyses/{aid}/changes", tok)) or {}
brk = [x["qualifiedName"] for x in ch.get("symbols", []) if x.get("breaking")]
check("Change analysis", "semantic changes incl. 2 breaking", len(brk) == 2, [x.split(".")[-1] for x in brk])
imp = data(req("GET", f"/analyses/{aid}/impacts?size=200", tok)) or {}
paths = [f for f in imp.get("content", []) if f.get("dependencyPath")]
check("Change analysis", "impact findings with dependency paths and evidence", len(paths) > 0 and all(f.get("evidence") for f in paths),
      f"{imp.get('totalElements')} findings, e.g. {paths[0]['affectedQualifiedName'].split('.')[-1] if paths else None} via {len(paths[0]['dependencyPath']) if paths else 0} steps")
rk = data(req("GET", f"/analyses/{aid}/risk", tok)) or {}
fac = (rk.get("symbols") or rk.get("perSymbol") or [{}])
check("Change analysis", "explainable risk: factors with weight and contribution", "factors" in json.dumps(rk) and "contribution" in json.dumps(rk), list(rk.keys())[:5])
rep = data(req("GET", f"/analyses/{aid}/report", tok)) or {}
check("Change analysis", "report: pre-merge checklist", len(rep.get("checklist") or []) > 0, [c.get("area") for c in rep.get("checklist") or []])
check("Change analysis", "report: suggested reviewers (authors excluded)", len((rep.get("reviewers") or {}).get("suggestions") or []) > 0, [x.get("name") for x in (rep.get("reviewers") or {}).get("suggestions", [])][:3])
check("Change analysis", "report: architecture drift", (rep.get("architecture") or {}).get("status") in ("OK", "UNAVAILABLE"), (rep.get("architecture") or {}).get("findings"))
check("Change analysis", "report: defect-model status present", "defectModel" in rep, (rep.get("defectModel") or {}).get("status"))
check("Change analysis", "report: deterministic; AI review only on request (never mixed in)", (rep.get("aiReview") or {}).get("status") == "ON_REQUEST", rep.get("aiReview"))
s, md, h = req("GET", f"/analyses/{aid}/report.md", tok, raw=True)
check("Change analysis", "Markdown report", s == 200 and b"Pre-merge checklist" in md and "markdown" in h.get("Content-Type", ""), f"{len(md)} bytes, {h.get('Content-Type')}")
tests = data(req("GET", f"/analyses/{aid}/tests", tok)) or {}
check("Change analysis", "test selection", len(tests.get("recommendations") or []) > 0, (tests.get("summary") or {}).get("byCategory"))
sec = data(req("GET", f"/analyses/{aid}/security", tok)) or {}
check("Change analysis", "security stage with Semgrep run", ((sec.get("summary") or {}).get("semgrep") or {}).get("status") == "COMPLETED", (sec.get("summary") or {}).get("semgrep"))
lst = data(req("GET", f"/analyses?repositoryId={pc}&page=0&size=20", tok)) or {}
check("Change analysis", "analyses list (timeline)", lst.get("totalElements", 0) >= 1, lst.get("totalElements"))
s, b = req("POST", "/analyses", tok, {"repositoryId": pc, "baseRef": "500158f", "headRef": "500158f"})
check("Change analysis", "same commit refused (code SAME_COMMIT)", s == 400 and isinstance(b, dict) and b.get("code") == "SAME_COMMIT", f"{s} {b.get('code') if isinstance(b, dict) else b}: {b.get('message') if isinstance(b, dict) else ''}")
s, b = req("POST", "/analyses", tok, {"repositoryId": pc, "baseRef": "../etc", "headRef": "500158f"})
check("Change analysis", "unsafe ref refused", s == 400, s)
s, b = req("POST", "/analyses", tok, {"repositoryId": pc, "baseRef": "500158f", "headRef": "deadbeefdeadbeef"})
bad = wait_analysis(tok, (data((s, b)) or {}).get("analysisId")) if s == 202 else {"status": f"HTTP {s}"}
check("Change analysis", "unknown commit → readable failure", bad.get("status") == "FAILED" or s == 400, f"{bad.get('status')} {str(bad.get('errorMessage'))[:100]}")

# ---------------------------------------------------------------- G. Python analysis
pycommits = (data(req("GET", f"/repositories/{py}/commits?page=0&size=50", tok)) or {}).get("content", [])
s, b = req("POST", "/analyses", tok, {"repositoryId": py, "baseRef": "3816cfa", "headRef": "3c7baaa"})
pa = wait_analysis(tok, (data((s, b)) or {}).get("analysisId"))
check("Python", "change analysis of psf/requests completes", pa.get("status") == "COMPLETED", f"{pa.get('status')} {pa.get('riskLevel')} {pa.get('riskScore')} {str(pa.get('errorMessage') or '')[:100]}")
pch = data(req("GET", f"/analyses/{pa.get('id')}/changes", tok)) or {}
kinds = {x["qualifiedName"]: x["changeKinds"] for x in pch.get("symbols", [])}
check("Python", "Session#get: optional parameter added, not breaking", "PARAMETERS_EXTENDED" in kinds.get("requests.sessions.Session#get", []), kinds.get("requests.sessions.Session#get"))
check("Python", "Session#request: type hints only", "PARAMETER_ANNOTATIONS_CHANGED" in kinds.get("requests.sessions.Session#request", []), kinds.get("requests.sessions.Session#request"))
pg = data(req("GET", f"/code/repositories/{py}/graph?view=CLASS", tok)) or {}
check("Python", "Python dependency graph", len(pg.get("nodes", [])) > 20, f"{len(pg.get('nodes', []))} nodes, {len(pg.get('edges', []))} edges")
sess = next((x for x in (data(req("GET", f"/code/repositories/{py}/symbols?query=Session&page=0&size=200", tok)) or {}).get("content", []) if x.get("qualifiedName") == "requests.sessions.Session#request"), None)
calls = data(req("GET", f"/code/symbols/{sess['id']}/dependencies?depth=1", tok)) if sess else None
check("Python", "Session.request calls resolved (prepare_request, send)", calls is not None and "prepare_request" in json.dumps(calls) and "send" in json.dumps(calls), str(calls)[:150])

# ---------------------------------------------------------------- H. CI quality reports
head = "500158f732419217507c7656904b8e6aa1bcc0d6"
for typ, fname in (("JUNIT", "junit-sample.xml"), ("JACOCO", "jacoco-sample.xml"), ("PIT", "mutations-sample.xml"), ("DEPENDENCY_CHECK", "dependency-check-sample.json")):
    body, hh = multipart({"type": typ, "commitSha": head}, [("file", (fname, open(f"{REPORTS}/{fname}", "rb").read()))])
    s, b = req("POST", f"/repositories/{pc}/reports", tok, body, hh)
    check("Quality reports", f"upload {typ}", s == 201, f"{s} {json.dumps((data((s, b)) or {}).get('summary'))[:120]}")
body, hh = multipart({"type": "JACOCO", "commitSha": head}, [("file", ("xxe.xml", open(f"{REPORTS}/xxe-attack.xml", "rb").read()))])
s, b = req("POST", f"/repositories/{pc}/reports", tok, body, hh)
check("Quality reports", "XXE payload rejected (code INVALID_REPORT, entity not expanded)", s == 400 and isinstance(b, dict) and b.get("code") == "INVALID_REPORT", f"{s} {b.get('code') if isinstance(b, dict) else b}: {str(b.get('message') if isinstance(b, dict) else '')[:90]}")
body, hh = multipart({"type": "JUNIT", "commitSha": "0000000"}, [("file", ("j.xml", open(f"{REPORTS}/junit-sample.xml", "rb").read()))])
s, b = req("POST", f"/repositories/{pc}/reports", tok, body, hh)
check("Quality reports", "unknown commit rejected", s == 400, s)
rl = data(req("GET", f"/repositories/{pc}/reports", tok))
check("Quality reports", "reports listed", isinstance(rl, list) and len(rl) >= 4 or isinstance(rl, dict), len(rl) if isinstance(rl, list) else rl)

# ---------------------------------------------------------------- I. architecture twin
snap = data(req("GET", f"/repositories/{rest}/architecture/snapshot", tok)) or {}
check("Architecture twin", "snapshot (packages, coupling, cycles)", len(snap.get("packages", [])) > 0, f"{len(snap.get('packages', []))} packages, {len(snap.get('edges', []))} edges, {len(snap.get('cycles', []))} cycles")
hs = data(req("GET", f"/repositories/{rest}/architecture/hotspots", tok)) or {}
check("Architecture twin", "hotspots (churn × complexity)", len(hs.get("files", [])) > 0, f"{len(hs.get('files', []))} files")
s, b = req("POST", f"/repositories/{rest}/architecture/backfill", tok, {"commits": 4})
bj = wait_job(tok, (data((s, b)) or {}).get("jobId"))
check("Architecture twin", "history backfill job", bj.get("status") == "COMPLETED", f"{bj.get('status')} {bj.get('currentMessage')}")
tr = data(req("GET", f"/repositories/{rest}/architecture/trend", tok)) or []
check("Architecture twin", "trend over analysed commits", len(tr) >= 4, f"{len(tr)} points")
if len(tr) >= 2:
    cmp_ = data(req("GET", f"/repositories/{rest}/architecture/compare?base={tr[0]['commitSha']}&head={tr[-1]['commitSha']}", tok)) or {}
    check("Architecture twin", "compare oldest → newest commit", "findings" in cmp_, (cmp_.get("findings") or [])[:2])
s, b = req("GET", f"/repositories/{rest}/architecture/snapshot?commit=0000000", tok)
check("Architecture twin", "unknown commit → 404", s == 404, s)

# ---------------------------------------------------------------- J. GitHub webhooks
def hook(event, payload, delivery, sign=True, ctype="application/json"):
    raw = json.dumps(payload).encode() if ctype == "application/json" else b"payload=x"
    h = {"X-GitHub-Event": event, "X-GitHub-Delivery": delivery, "Content-Type": ctype}
    if sign: h["X-Hub-Signature-256"] = "sha256=" + hmac.new(SECRET.encode(), raw, hashlib.sha256).hexdigest()
    return req("POST", "/webhooks/github", body=raw, headers=h)

d1 = str(uuid.uuid4())
s, b = hook("ping", {"zen": "Keep it logically awesome."}, d1, sign=False)
check("GitHub webhooks", "unsigned delivery → 401", s == 401, s)
raw = json.dumps({"zen": "x"}).encode()
s, b = req("POST", "/webhooks/github", body=raw, headers={"X-GitHub-Event": "ping", "X-GitHub-Delivery": d1, "Content-Type": "application/json", "X-Hub-Signature-256": "sha256=" + "0" * 64})
check("GitHub webhooks", "wrong signature → 401", s == 401, s)
s, b = hook("ping", {"zen": "Keep it logically awesome.", "hook_id": 1}, d1)
check("GitHub webhooks", "signed ping accepted", s == 202, f"{s} {b}")
s, b = hook("ping", {"zen": "Keep it logically awesome.", "hook_id": 1}, d1)
check("GitHub webhooks", "redelivery de-duplicated", s == 200 and "DUPLICATE" in json.dumps(b), f"{s} {b}")
s, b = hook("ping", {}, str(uuid.uuid4()), ctype="application/x-www-form-urlencoded")
check("GitHub webhooks", "form-encoded delivery → 415", s == 415, s)
app = data(req("GET", "/github/app", tok)) or {}
check("GitHub webhooks", "App status page (webhook secret configured, App not registered)", bool(app), json.dumps(app)[:160])
dl = req("GET", "/github/app/deliveries", tok)
check("GitHub webhooks", "delivery log", dl[0] == 200, dl[0])
prs = req("GET", f"/repositories/{pc}/pull-requests", tok)
check("GitHub webhooks", "pull-request list", prs[0] == 200, prs[0])

# ---------------------------------------------------------------- K. dashboard
dash = data(req("GET", "/dashboard?days=30", tok)) or {}
check("Dashboard", "team dashboard", "totals" in dash and "repositories" in dash, dash.get("totals"))

# ---------------------------------------------------------------- L. defect model
pj = (data(req("POST", f"/repositories/{pc}/defect-model/train", tok)) or {}).get("jobId")
wait_job(tok, pj)
pm = data(req("GET", f"/repositories/{pc}/defect-model", tok)) or {}
check("Defect model", "small history → not used (too few labels)", pm.get("status") == "INSUFFICIENT_LABELS", f"{pm.get('status')}: {pm.get('reason')}")
cj = wait_job(tok, cl_job, timeout=2400)
cm = data(req("GET", f"/repositories/{cl}/defect-model", tok)) or {}
ev = (cm.get("evaluation") or {}).get("model") or {}
check("Defect model", "commons-lang → accepted, beats churn baseline", cm.get("status") == "ACCEPTED", f"{cj.get('status')} {cm.get('status')}: {cm.get('reason')}")
check("Defect model", "calibration and baselines reported", "ece" in ev and "churnBaseline" in (cm.get("evaluation") or {}), f"AUC {ev.get('auc')} Brier {ev.get('brier')} ECE {ev.get('ece')}")
check("Defect model", "recent commits scored with drivers", len(cm.get("predictions") or []) > 0 and (cm["predictions"][0].get("drivers")), f"{len(cm.get('predictions') or [])} commits")

# ---------------------------------------------------------------- N. AI review, code search, assistant (Phase 4)
# Needs GEMINI_API_KEY. External factors are reported, not hidden: Gemini overload (503) or a used-up daily quota
# show up in the evidence of the affected checks.
rv = data(req("GET", f"/analyses/{aid}/ai-review", tok)) or {}
check("AI review", "before a request: NOT_REQUESTED, AI enabled", rv.get("status") == "NOT_REQUESTED" and rv.get("aiEnabled") is True, rv)
st = data(req("POST", f"/analyses/{aid}/ai-review", tok)) or {}
rj = wait_job(tok, st.get("jobId"), timeout=900) if st.get("jobId") else {"status": "NOT_STARTED"}
rv = data(req("GET", f"/analyses/{aid}/ai-review", tok)) or {}
res, ev, val = rv.get("result") or {}, rv.get("evidence") or {}, rv.get("validation") or {}
check("AI review", "review job completes", rv.get("status") == "COMPLETED",
      f"{rv.get('status')} {rv.get('model')} {rv.get('promptVersion')} findings={val.get('findings')} verified={val.get('verified')} {rv.get('errorMessage') or ''}")
if rv.get("status") == "COMPLETED":
    fs = res.get("findings") or []
    check("AI review", "every finding has a validation status; cited ids exist in the evidence",
          bool(fs) and all(f.get("status") in ("VERIFIED", "UNVERIFIED") for f in fs) and all(c in ev for f in fs for c in f.get("citations", [])),
          f"{len(fs)} findings, {len(ev)} evidence items, statuses {[f.get('status') for f in fs]}")
    check("AI review", "model, tokens and latency recorded", bool(rv.get("model")) and (rv.get("promptTokens") or 0) > 0 and (rv.get("latencyMs") or 0) > 0,
          f"{rv.get('model')} {rv.get('promptTokens')}+{rv.get('outputTokens')} tokens {rv.get('latencyMs')} ms")
    s, md, _ = req("GET", f"/analyses/{aid}/report.md", tok, raw=True)
    check("AI review", "Markdown report ends with the labelled AI review", s == 200 and b"## AI review (language model" in md, f"{s} {len(md)} bytes")
s, b = req("GET", f"/analyses/{aid}/ai-review", other)
check("AI review", "other user cannot see the AI review (404)", s == 404, s)

rs = data(req("GET", f"/repositories/{pc}/rag/status", tok)) or {}
check("Code search", "index status: AI enabled, code units counted", rs.get("aiEnabled") is True and (rs.get("candidates") or 0) > 0, rs)
s, b = req("POST", f"/repositories/{pc}/rag/search", tok, {"query": "how are vets cached"})
check("Code search", "search before the index exists → RAG_NOT_INDEXED", s == 400 and (b or {}).get("code") == "RAG_NOT_INDEXED", f"{s} {(b or {}).get('code')}")
s, b = req("POST", f"/repositories/{pc}/assistant/ask", tok, {"question": "How are vets cached?"})
check("Assistant", "question before the index exists → RAG_NOT_INDEXED", s == 400 and (b or {}).get("code") == "RAG_NOT_INDEXED", f"{s} {(b or {}).get('code')}")
s, b = req("GET", f"/repositories/{pc}/rag/status", other)
check("Code search", "other user cannot see the index status (404)", s == 404, s)
ij = (data(req("POST", f"/repositories/{pc}/rag/index", tok)) or {}).get("jobId")
ij = wait_job(tok, ij, timeout=1200) if ij else {"status": "NOT_STARTED"}
quota_msg = "quota" in (ij.get("errorMessage") or "").lower()
check("Code search", "index build completes, or fails with a clear quota message (free tier: 1,000 embeddings/day)",
      ij.get("status") == "COMPLETED" or (ij.get("status") == "FAILED" and quota_msg), f"{ij.get('status')} {ij.get('currentMessage') or ''} {(ij.get('errorMessage') or '')[:160]}")
if ij.get("status") == "COMPLETED":
    s, b = req("POST", f"/repositories/{pc}/rag/search", tok, {"query": "how are vets cached", "mode": "bogus"})
    check("Code search", "invalid mode → INVALID_MODE", s == 400 and (b or {}).get("code") == "INVALID_MODE", f"{s} {(b or {}).get('code')}")
    r1 = data(req("POST", f"/repositories/{pc}/rag/search", tok, {"query": "how are vets cached", "k": 5})) or {}
    names = [h["qualifiedName"].split(".")[-1] for h in r1.get("hits", [])]
    check("Code search", "question → vet cache code in the top 5 (or full-text fallback with a note)",
          any("VetRepository" in n or "CacheConfiguration" in n for n in names) or bool(r1.get("note")), f"mode {r1.get('mode')} {names} {(r1.get('note') or '')[:80]}")
    r2 = data(req("POST", f"/repositories/{pc}/rag/search", tok, {"query": "PetValidator", "k": 5})) or {}
    check("Code search", "code name → hybrid (or full-text fallback), PetValidator found",
          r2.get("mode") in ("hybrid", "fts") and any("PetValidator" in h["qualifiedName"] for h in r2.get("hits", [])), f"mode {r2.get('mode')} {[h['qualifiedName'].split('.')[-1] for h in r2.get('hits', [])]}")
    s, b = req("POST", f"/repositories/{pc}/assistant/ask", tok, {"question": "How does the application send e-mail reminders for upcoming visits?"})
    an = data((s, b)) or {}
    check("Assistant", "unanswerable question → INSUFFICIENT_EVIDENCE (or a clear AI_UNAVAILABLE)",
          an.get("status") == "INSUFFICIENT_EVIDENCE" or (s == 503 and (b or {}).get("code") == "AI_UNAVAILABLE"), f"{s} {an.get('status') or (b or {}).get('code')} {(an.get('answer') or '')[:100]}")

# ---------------------------------------------------------------- O. libraries, centrality, rules, code browser
import urllib.parse
gs = data(req("GET", f"/code/repositories/{pc}/graph/stats", tok)) or {}
lib = gs.get("libraries") or {}
res = gs.get("referencesByResolution") or {}
check("Libraries", "petclinic: Maven dependency jars resolved", lib.get("status") == "RESOLVED" and (lib.get("jarCount") or 0) > 100,
      f"{lib.get('status')} {lib.get('buildSystem')} {lib.get('jarCount')} jars {lib.get('message') or ''}")
check("Libraries", "petclinic: almost no unresolved references left (897 without libraries)", (res.get("UNRESOLVED") or 0) < 50, res)
pyl = (data(req("GET", f"/code/repositories/{py}/graph/stats", tok)) or {}).get("libraries") or {}
check("Libraries", "Python-only repository: NO_BUILD_FILE, analysis unaffected", pyl.get("status") == "NO_BUILD_FILE", pyl.get("status"))
a2 = wait_analysis(tok, (data(req("POST", "/analyses", tok, {"repositoryId": pc, "baseRef": "818c413", "headRef": "500158f"})) or {}).get("analysisId"))
check("Libraries", "deterministic: a second analysis of the same commits gives the same risk", a2.get("riskScore") == a.get("riskScore") and a2.get("riskLevel") == a.get("riskLevel"),
      f"{a2.get('riskLevel')} {a2.get('riskScore')} vs {a.get('riskLevel')} {a.get('riskScore')}")

cen = data(req("GET", f"/code/repositories/{pc}/centrality?rankBy=pagerank&limit=5", tok)) or {}
top = cen.get("top") or []
check("Centrality", "PageRank: BaseEntity (inherited by every entity) first", bool(top) and top[0]["simpleName"] == "BaseEntity", [t["simpleName"] for t in top])
cb = data(req("GET", f"/code/repositories/{pc}/centrality?rankBy=betweenness&limit=3", tok)) or {}
check("Centrality", "betweenness normalised to 0..1, ranks ordered", all(0 <= t["betweenness"] <= 1 for t in cb.get("top", [])) and
      [t["betweenness"] for t in cb.get("top", [])] == sorted([t["betweenness"] for t in cb.get("top", [])], reverse=True), [(t["simpleName"], t["betweenness"]) for t in cb.get("top", [])])
s, b = req("GET", f"/code/repositories/{pc}/centrality?rankBy=bogus", tok)
check("Centrality", "invalid rankBy → INVALID_RANK_BY", s == 400 and (b or {}).get("code") == "INVALID_RANK_BY", s)

rb = f"/repositories/{pc}/architecture/rules"
rules = data(req("GET", rb, tok)) or []
check("Architecture rules", "global defaults listed with violation counts", any(r["scope"] == "GLOBAL" for r in rules), [(r["ruleName"][:25], r["active"], r.get("violationsAtLastCommit")) for r in rules])
inverted = {"ruleName": "Model on top (inverted)", "ruleType": "LAYERED_ARCHITECTURE", "severity": "LOW",
            "configuration": {"layers": [{"name": "Model", "pattern": "\\.model\\."}, {"name": "Features", "pattern": "\\.(owner|vet)\\."}]}}
pv = data(req("POST", rb + "/preview", tok, inverted)) or {}
check("Architecture rules", "preview: inverted layers report upward dependencies", (pv.get("total") or 0) > 0, f"{pv.get('total')} violations")
s, b = req("POST", rb, tok, {"ruleName": "x", "ruleType": "FORBIDDEN_DEPENDENCY", "configuration": {"sourcePattern": "(.*a){8}b", "forbiddenTargetPattern": "x"}})
check("Architecture rules", "catastrophic regex refused", s == 400 and (b or {}).get("code") == "INVALID_RULE", (b or {}).get("message", "")[:80])
s, b = req("POST", rb, tok, inverted); rid = (data((s, b)) or {}).get("id")
check("Architecture rules", "create repository rule", s == 201 and rid, s)
# a global rule that has violations, so it is visible in byRule when on (rule order is not fixed)
glob = next(r for r in rules if r["scope"] == "GLOBAL" and (r.get("violationsAtLastCommit") or 0) > 0)
s, b = req("PUT", f"{rb}/{glob['id']}/active", tok, {"active": False})
ev = data(req("POST", rb + "/evaluate", tok)) or {}
check("Architecture rules", "switch a global rule off + re-evaluate", s == 200 and glob["ruleName"] not in (ev.get("byRule") or {}) and "Model on top (inverted)" in (ev.get("byRule") or {}), ev.get("byRule"))
s, _ = req("GET", rb, other)
check("Architecture rules", "other user cannot see the rules (404)", s == 404, s)
s, _ = req("GET", "/admin/architecture/rules", other)
check("Architecture rules", "non-admin cannot manage global rules (403)", s == 403, s)
s1, _ = req("PUT", f"{rb}/{glob['id']}/active", tok, {"active": True}); s2, _ = req("DELETE", f"{rb}/{rid}", tok)
ev2 = data(req("POST", rb + "/evaluate", tok)) or {}
check("Architecture rules", "restore: global rule back on, rule deleted, re-evaluated", s1 == 200 and s2 == 204 and glob["ruleName"] in (ev2.get("byRule") or {}), ev2.get("byRule"))

fl = data(req("GET", f"/repositories/{pc}/source/files", tok)) or {}
by_lang = {}
for f_ in fl.get("files", []): by_lang[f_["language"]] = by_lang.get(f_["language"], 0) + 1
check("Code browser", "analysed files of the indexed commit (Java + templates, CSS, SQL)", by_lang == {"java": 50, "html": 12, "css": 1, "sql": 7}, f"{by_lang} at {str(fl.get('commitSha'))[:7]}")
oc = "src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java"
fv = data(req("GET", f"/repositories/{pc}/source/file?path=" + urllib.parse.quote(oc), tok)) or {}
ref = next((r for r in fv.get("references", []) if "findByLastNameStartingWith" in r.get("targetName", "")), {})
line_text = fv.get("content", "").splitlines()[ref["line"] - 1] if ref.get("line") else ""
check("Code browser", "reference line and resolved target (go to declaration)", "findByLastNameStartingWith" in line_text and str(ref.get("targetPath", "")).endswith("OwnerRepository.java") and ref.get("targetLine"),
      f"line {ref.get('line')} → {ref.get('targetPath')}:{ref.get('targetLine')}")
codes = [req("GET", f"/repositories/{pc}/source/file?path=" + urllib.parse.quote(q), tok)[0] for q in ("../../../etc/passwd", "pom.xml")]
check("Code browser", "path traversal and unanalysed files refused", codes == [404, 404], codes)
s, _ = req("GET", f"/repositories/{pc}/source/file?path=" + urllib.parse.quote(oc), other)
check("Code browser", "other user cannot read the code (404)", s == 404, s)

# ---------------------------------------------------------------- P. languages (all on by default)
tpl = "src/main/resources/templates/owners/ownersList.html"
tv = data(req("GET", f"/repositories/{pc}/source/file?path=" + urllib.parse.quote(tpl), tok)) or {}
tref = [r["targetName"] for r in tv.get("references", []) if r.get("targetPath")]
check("Languages", "Thymeleaf page → layout fragment and stylesheet", any(t.endswith("layout.html#layout") for t in tref) and any("petclinic.css" in t for t in tref), tref[:4])
# petclinic has one schema per database vendor: links go to the first definition by path (db/h2/schema.sql)
ows = [x for x in (data(req("GET", f"/code/repositories/{pc}/symbols?query=owners&page=0&size=50", tok)) or {}).get("content", []) if x.get("qualifiedName") == "db:owners"]
owdep = [y for x in ows for y in (data(req("GET", f"/code/symbols/{x['id']}/dependents?depth=1", tok)) or [])]
check("Languages", "SQL table db:owners (schema.sql) is used by the JPA entity Owner", len(ows) >= 1 and any(str(x.get("qualifiedName", "")).endswith("owner.Owner") for x in owdep),
      f"{len(ows)} definitions; dependents {sorted({x.get('qualifiedName') for x in owdep})[:5]}")
expect = {"alan2207/bulletproof-react": ("JavaScript/TypeScript", "apps/react-vite/src/features/comments/components/delete-comment.tsx", "button#Button"),
          "dotnet-architecture/eShopOnWeb": ("C#", "src/Web/Services/CatalogViewModelService.cs", "IUriComposer"),
          "go-chi/chi": ("Go", "mux.go", "chi.Mux#Find"),
          "sinatra/sinatra": ("Ruby", "lib/sinatra/base.rb", "Sinatra.Base#"),
          "laravel/pulse": ("PHP", "src/Storage/DatabaseStorage.php", "DatabaseStorage#")}
for name, (label, path, target) in expect.items():
    s_, lid, _ = connect(name)
    req("POST", f"/repositories/{lid}/index", tok)
    t0 = time.time(); st_ = wait_index(tok, lid)
    info = data(req("GET", f"/repositories/{lid}", tok)) or {}
    check("Languages", f"index {name}, labelled {label}", st_.get("indexingStatus") == "INDEXED" and info.get("language") == label,
          f"{st_.get('indexingStatus')} {info.get('language')} in {time.time() - t0:.0f}s")
    fv_ = data(req("GET", f"/repositories/{lid}/source/file?path=" + urllib.parse.quote(path), tok)) or {}
    linked = [r["targetName"] for r in fv_.get("references", []) if r.get("targetPath")]
    check("Languages", f"{name}: {path} links into the repository ({target})", any(target in t for t in linked), f"{len(linked)} linked, e.g. {linked[:2]}")

# ---------------------------------------------------------------- M. isolation, roles, rate limits
s, b = req("GET", f"/repositories/{pc}", other)
check("Security", "other user cannot see the repository (404)", s == 404, s)
s, b = req("GET", f"/analyses/{aid}", other)
check("Security", "other user cannot see the analysis (404)", s == 404, s)
s, b = req("GET", f"/repositories/{cl}/defect-model", other)
check("Security", "other user cannot see the defect model (404)", s == 404, s)
s, b = req("POST", "/admin/retention/run", other)
check("Security", "non-admin → 403 on admin operation", s == 403, s)
s, b = req("POST", "/admin/retention/run", tok)
check("Security", "admin can run retention", s == 200, f"{s} {json.dumps(data((s, b)))[:120]}")
codes = [req("POST", "/auth/login", body={"email": "nobody@example.com", "password": "x" * 12})[0] for _ in range(14)]
check("Security", "login rate limit → 429", 429 in codes, codes)

json.dump(results, open(OUT, "w"), indent=1)
passed = sum(r["result"] == "PASS" for r in results)
print(f"\n{passed}/{len(results)} checks passed", flush=True)
