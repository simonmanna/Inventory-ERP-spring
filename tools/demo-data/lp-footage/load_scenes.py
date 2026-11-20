"""Realistic demo scenes for loss prevention (DEMO, KRD): a CCTV-style clip and a still per chosen case, made from
the stills in scenes/ (AI-generated images supplied by the owner) and uploaded through the edge agent's upload route.
Re-run safe: a case that already has an "AI scene" is skipped. Run on the server after a database backup:
  LP_PASSWORD=... python3 load_scenes.py <path to loss-prevention/state.DEMO.json>
"""
import json,os,urllib.request,urllib.error,uuid,sys
B='http://127.0.0.1:3001/api/v1'
pw=os.environ['LP_PASSWORD']
def call(m,p,tok=None,body=None):
    h={'Content-Type':'application/json'}
    if tok: h['Authorization']='Bearer '+tok
    if m!='GET': h['Idempotency-Key']=str(uuid.uuid4())
    r=urllib.request.Request(B+p,method=m,data=json.dumps(body).encode() if body is not None else None,headers=h)
    try:
        with urllib.request.urlopen(r,timeout=60) as x: t=x.read(); return x.status,(json.loads(t) if t else None)
    except urllib.error.HTTPError as e: return e.code,e.read().decode()[:400]
def login(): return call('POST','/auth/login',body={'organizationCode':'DEMO','email':'admin@demo.test','password':pw})[1]['accessToken']


import hashlib, subprocess, datetime as dt, base64, urllib.parse, tempfile
from pathlib import Path
HERE = Path(__file__).resolve().parent
STATE = sys.argv[1]  # the LP pilot seeder's state.DEMO.json (agent credentials)
M = tempfile.mkdtemp(prefix='lp-scenes-')
# case -> (scene image, camera name, fallback UTC time if the case has no event)
PLAN = {
  'LP-000023': ('till', 'Counter till', '2026-11-19T15:42:10Z'),
  'LP-000020': ('till', 'Counter till', '2026-11-18T15:38:40Z'),
  'LP-000015': ('till', 'Counter till', '2026-11-16T15:51:05Z'),
  'LP-000013': ('till', 'Counter till', '2026-11-14T15:47:30Z'),
  'LP-000029': ('counter', 'Counter till', None),
  'LP-000030': ('till', 'Counter till', None),
  'LP-000049': ('pickup', 'Pick-up counter', None),
  'LP-000050': ('pickup', 'Kitchen pass', None),
  'LP-000051': ('pickup', 'Dining room', None),
  'LP-000028': ('till', 'Counter till', None),
  'LP-000017': ('counter', 'Counter till', None),
  'LP-000014': ('pickup', 'Pick-up counter', None),
}
HALF = 6
a = json.load(open(STATE))['agent']
basic = base64.b64encode(f"{a['clientId']}:{a['clientSecret']}".encode()).decode()
r = urllib.request.Request(B + '/oauth/token', method='POST', data=urllib.parse.urlencode(
    {'grant_type': 'client_credentials', 'scope': 'lp:ingest'}).encode(),
    headers={'Content-Type': 'application/x-www-form-urlencoded', 'Authorization': 'Basic ' + basic})
agent = json.loads(urllib.request.urlopen(r).read())['access_token']
t = login()

def upload(eid, path, ctype):
    blob = open(path, 'rb').read(); sha = hashlib.sha256(blob).hexdigest()
    q = urllib.parse.urlencode({'offset': 0, 'total': len(blob), 'sha256': sha, 'contentType': ctype})
    req = urllib.request.Request(f"{B}/loss-prevention/evidence/{eid}/upload?{q}", data=blob, method='POST',
        headers={'Authorization': 'Bearer ' + agent, 'Content-Type': 'application/octet-stream'})
    return urllib.request.urlopen(req, timeout=120).status

cams = call('GET', '/loss-prevention/cameras?pageSize=50', t)[1]
cams = {c['name']: c['id'] for c in (cams if isinstance(cams, list) else cams['data'])}
cases = {c['caseNumber']: c for c in call('GET', '/loss-prevention/cases?pageSize=100', t)[1]['data']}
iso = lambda d: d.strftime('%Y-%m-%dT%H:%M:%SZ')
for num, (scene, cam, fallback) in PLAN.items():
    c = cases[num]
    have = call('GET', '/loss-prevention/evidence?' + urllib.parse.urlencode({'caseId': c['id'], 'pageSize': 50}), t)[1]['data']
    if any((e.get('requestReason') or '').startswith('AI scene') for e in have):
        print(num, 'already has an AI scene'); continue
    if fallback: at = dt.datetime.fromisoformat(fallback.replace('Z', '+00:00'))
    else:
        d = call('GET', f"/loss-prevention/cases/{c['id']}", t)[1]
        at = dt.datetime.fromisoformat(d['events'][0]['occurredAt'].replace('Z', '+00:00')) + dt.timedelta(seconds=20)
    at = at.replace(microsecond=0); frm = at - dt.timedelta(seconds=HALF)
    s, clip = call('POST', '/loss-prevention/evidence/range', t, {'cameraId': cams[cam], 'from': iso(frm),
        'to': iso(at + dt.timedelta(seconds=HALF)), 'caseId': c['id'], 'reason': f'AI scene - {c["title"]}'[:200]})
    if s != 201: print(num, 'clip refused', s, clip); continue
    mp4 = f"{M}/{clip['id']}.mp4"
    subprocess.run([str(HERE / 'make_scene_clip.sh'), str(HERE / 'scenes' / f'{scene}.jpg'), mp4, str(int(frm.timestamp())), str(2 * HALF), f'KRD - {cam}'], check=True)
    upload(clip['id'], mp4, 'video/mp4')
    s, still = call('POST', '/loss-prevention/evidence/still', t, {'cameraId': cams[cam], 'at': iso(at),
        'caseId': c['id'], 'reason': f'AI scene - still'})
    if s != 201: print(num, 'still refused', s, still); continue
    jpg = f"{M}/{still['id']}.jpg"
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-ss', str(HALF), '-i', mp4,
                    '-frames:v', '1', '-q:v', '2', jpg], check=True)
    upload(still['id'], jpg, 'image/jpeg')
    print(num, scene, cam, iso(at), 'clip+still uploaded')
