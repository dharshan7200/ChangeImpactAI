import os, subprocess, pickle, hashlib, yaml, requests

def handler(cursor, name, cmd, blob, doc, url):
    cursor.execute("SELECT * FROM users WHERE name = '" + name + "'")  # expect: cia.python.sql-injection.formatted-query
    cursor.execute(f"SELECT * FROM users WHERE name = '{name}'")  # expect: cia.python.sql-injection.formatted-query
    cursor.execute("SELECT * FROM users WHERE name = %s", (name,))  # ok
    os.system(cmd)  # expect: cia.python.injection.command-execution
    os.system("ls -l")  # ok
    subprocess.run(cmd, shell=True)  # expect: cia.python.injection.command-execution
    subprocess.run(["ls", name])  # ok
    eval(name)  # expect: cia.python.injection.eval
    pickle.loads(blob)  # expect: cia.python.deserialization.unsafe-load
    yaml.load(doc)  # expect: cia.python.deserialization.unsafe-load
    yaml.load(doc, Loader=yaml.SafeLoader)  # ok
    hashlib.md5(blob)  # expect: cia.python.crypto.weak-hash
    hashlib.sha256(blob)  # ok
    requests.get(url, verify=False)  # expect: cia.python.tls.verification-disabled
    requests.get(url)  # ok

API_KEY = "sk-live-1234567890"  # expect: cia.python.secrets.hardcoded-credential
password = os.environ["DB_PASSWORD"]  # ok
