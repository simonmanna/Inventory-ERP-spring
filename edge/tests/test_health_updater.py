import json
import urllib.error
import urllib.request

import pytest
from fakes import keypair, signed_release

from lp_edge import healthcheck
from lp_edge.health import HealthServer
from lp_edge.signing import TrustStore
from lp_edge.updater import Updater, read_env

OLD = "registry.example.com/lp-edge@sha256:" + "b" * 64
NEW = "registry.example.com/lp-edge@sha256:" + "a" * 64


def get(url):
    try:
        with urllib.request.urlopen(url, timeout=5) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read())


def test_health_endpoint(monkeypatch):
    state = {"code": 200}
    release = {"version": "0.2.0"}
    server = HealthServer(0, lambda: (state["code"], {"status": "ok" if state["code"] == 200 else "stalled"}),
                          lambda: release or None)
    server.start()
    try:
        base = f"http://127.0.0.1:{server.port}"
        assert get(base + "/health") == (200, {"status": "ok"})
        assert get(base + "/release") == (200, {"version": "0.2.0"})
        assert get(base + "/nothing")[0] == 404
        monkeypatch.setenv("LP_EDGE_HEALTH_PORT", str(server.port))
        assert healthcheck.main() == 0
        state["code"] = 503
        assert get(base + "/health") == (503, {"status": "stalled"})
        assert healthcheck.main() == 1
        release.clear()
        assert get(base + "/release")[0] == 404
    finally:
        server.stop()
    assert healthcheck.main() == 1  # nothing listens any more


class Host:
    """docker and the agent's health endpoint, as the updater sees them."""

    def __init__(self, release, running_version, pull_fails=False):
        self.release = release
        self.running_version = running_version
        self.pull_fails = pull_fails
        self.commands = []

    def fetch(self, url):
        if url.endswith("/release"):
            return (200, self.release) if self.release else (404, None)
        return 200, {"status": "ok", "version": self.running_version}

    def run(self, args):
        self.commands.append(list(args))
        if args[:2] == ["docker", "pull"] and self.pull_fails:
            return 1, "pull access denied"
        return 0, ""


@pytest.fixture
def vendor():
    private, pem, _ = keypair()
    return private, TrustStore.parse(pem)


def updater_for(tmp_path, trust, host, **kwargs):
    env_file = tmp_path / ".env"
    if not env_file.exists():
        env_file.write_text(f"# lp-edge\nLP_EDGE_IMAGE={OLD}\nLP_EDGE_DATA=/srv/lp\n")
    clock = [0.0]

    def sleep(seconds):
        clock[0] += seconds

    return Updater(trust, tmp_path / "compose.yml", env_file, runner=host.run, fetch=host.fetch, sleep=sleep,
                   monotonic=lambda: clock[0], **kwargs)


def test_the_updater_moves_to_a_signed_release(tmp_path, vendor):
    private, trust = vendor
    host = Host(signed_release(private), "0.2.0")
    updater = updater_for(tmp_path, trust, host)
    assert updater.check() == "updated"
    assert host.commands[0] == ["docker", "pull", NEW]
    assert host.commands[1][:2] == ["docker", "compose"] and host.commands[1][-3:] == ["-d", "--no-deps", "lp-edge"]
    env = read_env(tmp_path / ".env")
    assert (env["LP_EDGE_IMAGE"], env["LP_EDGE_PREVIOUS_IMAGE"], env["LP_EDGE_DATA"]) == (NEW, OLD, "/srv/lp")
    assert (tmp_path / ".env").read_text().startswith("# lp-edge\n")
    assert updater.check() == "up_to_date"


def test_the_updater_rolls_back_a_release_that_does_not_become_healthy(tmp_path, vendor):
    private, trust = vendor
    host = Host(signed_release(private), "0.1.0")  # the agent keeps reporting the old version
    updater = updater_for(tmp_path, trust, host, timeout=30)
    assert updater.check() == "rolled_back"
    assert read_env(tmp_path / ".env")["LP_EDGE_IMAGE"] == OLD
    assert host.commands[-1][:2] == ["docker", "compose"]


def test_the_updater_ignores_what_it_cannot_trust(tmp_path, vendor):
    private, trust = vendor
    forged = dict(signed_release(private), version="9.9.9")
    host = Host(forged, "9.9.9")
    assert updater_for(tmp_path, trust, host).check() == "invalid"
    assert host.commands == []
    malformed = dict(signed_release(private), digest="latest")
    assert updater_for(tmp_path, trust, Host(malformed, "0.2.0")).check() == "invalid"
    assert updater_for(tmp_path, trust, Host(None, "0.1.0")).check() == "no_release"
    failing = Host(signed_release(private), "0.2.0", pull_fails=True)
    assert updater_for(tmp_path, trust, failing).check() == "pull_failed"
    assert read_env(tmp_path / ".env")["LP_EDGE_IMAGE"] == OLD
