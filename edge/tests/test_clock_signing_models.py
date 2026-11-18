import base64
import hashlib

import pytest
from cryptography.hazmat.primitives import serialization
from fakes import T0, keypair

from lp_edge.clock import DriftMonitor, server_offset, sntp_offset, to_ntp
from lp_edge.models import ModelError, ModelManager
from lp_edge.remote_config import ModelSpec
from lp_edge.signing import TrustStore, decode_signature, model_message
from lp_edge.timeutil import iso


class FakeNtpSocket:
    """An NTP server 5 s ahead of this box, 0.1 s away."""

    def __init__(self, ahead=5.0, stratum=2, echo=True):
        self.ahead = ahead
        self.stratum = stratum
        self.echo = echo
        self.request = b""

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return None

    def settimeout(self, timeout):
        pass

    def sendto(self, data, address):
        self.request = data

    def recvfrom(self, size):
        answer = bytearray(48)
        answer[0] = 0x1C  # version 3, mode 4 (server)
        answer[1] = self.stratum
        answer[24:32] = self.request[40:48] if self.echo else b"\x00" * 8
        answer[32:40] = to_ntp(1000.1 + self.ahead)
        answer[40:48] = to_ntp(1000.1 + self.ahead)
        return bytes(answer), ("ntp", 123)


def ntp_now():
    times = iter([1000.0, 1000.2])
    return lambda: next(times)


def test_sntp_offset():
    sock = FakeNtpSocket()
    offset, delay = sntp_offset("ntp.example", now=ntp_now(), sock_factory=lambda *a: sock)
    assert offset == pytest.approx(5.0, abs=1e-6)
    assert delay == pytest.approx(0.2, abs=1e-6)
    assert sock.request[0] == 0x1B
    with pytest.raises(ValueError):
        sntp_offset("ntp.example", now=ntp_now(), sock_factory=lambda *a: FakeNtpSocket(stratum=0))
    with pytest.raises(ValueError):
        sntp_offset("ntp.example", now=ntp_now(), sock_factory=lambda *a: FakeNtpSocket(echo=False))


def test_server_offset_is_taken_halfway_through_the_call():
    assert server_offset(iso(1010.0), 1000.0, 1000.4) == pytest.approx(9.8)


def test_drift_monitor():
    monitor = DriftMonitor(max_drift_ms=2000)
    assert monitor.record(0.5, T0, outbox_seq=3, source="server") is None
    assert monitor.last_good_seq == 3
    # 2.2 s off, give or take 0.3 s: not provably over the limit
    assert monitor.record(2.2, T0 + 60, outbox_seq=9, source="server", uncertainty=0.3) is None
    assert monitor.last_good_seq == 9
    change = monitor.record(-3.0, T0 + 120, outbox_seq=12, source="ntp", uncertainty=0.1)
    assert (change.drifting, change.last_good_at, change.last_good_seq) == (True, T0 + 60, 9)
    assert monitor.drifting and monitor.drift_ms == 3000
    assert monitor.record(-3.0, T0 + 180, outbox_seq=14, source="ntp") is None  # still off: no new change
    back = monitor.record(0.01, T0 + 240, outbox_seq=15, source="ntp")
    assert (back.drifting, back.last_good_seq) == (False, 15)
    assert not monitor.drifting


def test_trust_store_formats_and_key_rotation(tmp_path):
    old, old_pem, _ = keypair()
    new, _, new_raw = keypair()
    trust = TrustStore.parse(old_pem + "," + new_raw)
    message = model_message("1.0", "ab" * 32, "https://erp.example.com/models/m.onnx")
    assert trust.verify(message, base64.b64encode(old.sign(message)).decode())
    assert trust.verify(message, new.sign(message).hex())
    assert trust.verify(message, base64.urlsafe_b64encode(old.sign(message)).decode().rstrip("="))
    assert not trust.verify(model_message("1.1", "ab" * 32, "https://erp.example.com/models/m.onnx"),
                            base64.b64encode(old.sign(message)).decode())
    assert not trust.verify(message, "not a signature")
    assert decode_signature("abc") is None
    assert TrustStore.parse(None) is None
    assert TrustStore.parse("  ") is None
    key_file = tmp_path / "vendor.pub"
    key_file.write_text(old_pem)
    assert TrustStore.parse(str(key_file)).verify(message, old.sign(message).hex())
    der = old.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    assert TrustStore.parse(base64.b64encode(der).decode()).verify(message, old.sign(message).hex())


def test_a_model_is_used_only_when_signed_and_intact(tmp_path):
    private, pem, _ = keypair()
    trust = TrustStore.parse(pem)
    content = b"onnx model bytes"
    sha = hashlib.sha256(content).hexdigest()
    url = "https://erp.example.com/models/m.onnx"
    downloads = []

    def download(source, dest):
        downloads.append(source)
        dest.write_bytes(content)
        return hashlib.sha256(content).hexdigest()

    def spec(version, digest, signed_url=url, actual_url=url):
        signature = base64.b64encode(private.sign(model_message(version, digest, signed_url))).decode()
        return ModelSpec(version, "yolox", actual_url, digest, signature, classes=("person",))

    manager = ModelManager(tmp_path / "models", trust, download)
    path = manager.ensure(spec("1.0", sha))
    assert path.name == f"{sha}.onnx" and path.read_bytes() == content
    assert manager.ensure(spec("1.0", sha)) == path
    assert len(downloads) == 1  # kept, not downloaded again
    with pytest.raises(ModelError):
        manager.ensure(spec("1.0", sha, actual_url="https://elsewhere.example.net/m.onnx"))  # not what was signed
    with pytest.raises(ModelError, match="does not match"):
        manager.ensure(spec("2.0", "0" * 64))
    assert [p.name for p in (tmp_path / "models").iterdir()] == [f"{sha}.onnx"]
    with pytest.raises(ModelError):
        ModelManager(tmp_path / "other", None, download).ensure(spec("1.0", sha))
