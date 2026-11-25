"""Fake GitHub (OAuth web flow + the REST endpoints the platform uses). Verifies what the platform sends; logs requests.
python3 fake_github.py <port> <client_id> <client_secret> <log.jsonl>"""
import json, sys, urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT, CID, SECRET, LOG = int(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
TOKEN = "gho_fakeOAuthToken000000000000000000"
issued = {}  # code -> (redirect_uri, state)
USER = {"id": 424242, "login": "octo-dev", "name": "Octo Dev", "email": None, "avatar_url": "https://avatars.githubusercontent.com/u/1"}
REPOS = [
    {"id": 1001, "name": "private-service", "full_name": "octo-dev/private-service", "private": True, "fork": False,
     "description": "A private repository", "clone_url": "https://github.com/octo-dev/private-service.git",
     "html_url": "https://github.com/octo-dev/private-service", "default_branch": "main", "language": "Java",
     "forks_count": 0, "stargazers_count": 0, "size": 120, "updated_at": "2026-11-20T10:00:00Z", "pushed_at": "2026-11-20T10:00:00Z"},
    {"id": 1002, "name": "public-lib", "full_name": "octo-dev/public-lib", "private": False, "fork": False,
     "description": "A public repository", "clone_url": "https://github.com/octo-dev/public-lib.git",
     "html_url": "https://github.com/octo-dev/public-lib", "default_branch": "main", "language": "Java",
     "forks_count": 1, "stargazers_count": 3, "size": 80, "updated_at": "2026-11-19T10:00:00Z", "pushed_at": "2026-11-19T10:00:00Z"},
]


def log(entry):
    with open(LOG, "a") as f: f.write(json.dumps(entry) + "\n")


class H(BaseHTTPRequestHandler):
    def _json(self, code, body):
        b = json.dumps(body).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(b)))
        self.end_headers(); self.wfile.write(b)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path); q = dict(urllib.parse.parse_qsl(u.query))
        if u.path == "/login/oauth/authorize":
            log({"event": "authorize", "query": q})
            if q.get("client_id") != CID: return self._json(400, {"error": "unknown client"})
            if q.get("deny") == "1":
                target = q["redirect_uri"] + "?" + urllib.parse.urlencode({"error": "access_denied", "error_description": "The user has denied your application access.", "state": q["state"]})
            else:
                code = "code-" + str(len(issued) + 1)
                issued[code] = (q["redirect_uri"], q["state"])
                target = q["redirect_uri"] + "?" + urllib.parse.urlencode({"code": code, "state": q["state"]})
            self.send_response(302); self.send_header("Location", target); self.end_headers(); return
        auth = self.headers.get("Authorization", "")
        if u.path.startswith("/api/v3/"):
            log({"event": "api", "path": u.path, "auth_ok": auth == "Bearer " + TOKEN})
            if auth != "Bearer " + TOKEN: return self._json(401, {"message": "Bad credentials"})
            if u.path == "/api/v3/user": return self._json(200, USER)
            if u.path == "/api/v3/user/repos": return self._json(200, REPOS)
            for r in REPOS:
                if u.path == "/api/v3/repos/" + r["full_name"]: return self._json(200, r)
            return self._json(404, {"message": "Not Found"})
        self._json(404, {"message": "Not Found"})

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
        if u.path == "/login/oauth/access_token":
            f = dict(urllib.parse.parse_qsl(body))
            ok_client = f.get("client_id") == CID and f.get("client_secret") == SECRET
            entry = issued.pop(f.get("code"), None)  # single use, like GitHub
            log({"event": "token", "client_ok": ok_client, "code": f.get("code"), "code_known": entry is not None,
                 "redirect_matches": entry is not None and entry[0] == f.get("redirect_uri"),
                 "accept": self.headers.get("Accept")})
            if not ok_client: return self._json(200, {"error": "incorrect_client_credentials", "error_description": "The client_id and/or client_secret passed are incorrect."})
            if entry is None or entry[0] != f.get("redirect_uri"):
                return self._json(200, {"error": "bad_verification_code", "error_description": "The code passed is incorrect or expired."})
            return self._json(200, {"access_token": TOKEN, "token_type": "bearer", "scope": "repo,read:user,user:email"})
        self._json(404, {"message": "Not Found"})

    def log_message(self, *a): pass


HTTPServer(("127.0.0.1", PORT), H).serve_forever()
