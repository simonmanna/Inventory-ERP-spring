import base64
import os
import stat

import pytest

from lp_edge import files, logs, timeutil
from lp_edge.crypto import Cipher, IntegrityError, load_or_create_key
from lp_edge.settings import Settings
from lp_edge.store import Store


def test_iso_and_parse_iso():
    assert timeutil.iso(0) == "1970-01-01T00:00:00.000Z"
    assert timeutil.iso(1.2345) == "1970-01-01T00:00:01.234Z"
    assert timeutil.parse_iso("1970-01-01T00:00:01.234Z") == pytest.approx(1.234)
    assert timeutil.parse_iso("1970-01-01T02:00:00+02:00") == 0
    assert timeutil.parse_iso("1970-01-01T00:00:00.123456789Z") == pytest.approx(0.123456)
    with pytest.raises(ValueError):
        timeutil.parse_iso("1970-01-01T00:00:00")
    with pytest.raises(ValueError):
        timeutil.parse_iso("")


def test_redact_masks_credentials():
    text = (
        "open rtsp://admin:secret@10.0.0.5/stream; Authorization: Bearer abc.def; "
        "client_secret=xyz password: hunter2"
    )
    out = logs.redact(text)
    assert "rtsp://***@10.0.0.5/stream" in out
    for secret in ("admin", "secret@", "abc.def", "xyz", "hunter2"):
        assert secret not in out


def test_private_files(tmp_path):
    target = tmp_path / "sub" / "f.bin"
    files.ensure_private_dir(target.parent)
    files.write_private(target, b"data")
    assert target.read_bytes() == b"data"
    assert stat.S_IMODE(target.stat().st_mode) == 0o600
    assert stat.S_IMODE(target.parent.stat().st_mode) == 0o700
    assert files.safe_name("a/b c:d") == "a_b_c_d"
    with pytest.raises(ValueError):
        files.safe_name("..")


def test_settings_from_env(tmp_path):
    settings = Settings.from_env(
        {"LP_EDGE_DATA_DIR": str(tmp_path), "LP_EDGE_TMP_DIR": str(tmp_path / "t"), "LP_EDGE_HEALTH_PORT": "9000"}
    )
    assert settings.health_port == 9000
    assert settings.buffer_fps == 2.0
    assert settings.analysis_fps == 5.0
    assert settings.streams_file == tmp_path / "streams.yaml"
    assert settings.credentials_file == tmp_path / "credentials.json"
    assert settings.tls_verify is True
    assert Settings.from_env({"LP_EDGE_DATA_DIR": str(tmp_path), "LP_EDGE_CA_BUNDLE": "/ca.pem"}).tls_verify == "/ca.pem"
    with pytest.raises(ValueError):
        Settings.from_env({"LP_EDGE_DATA_DIR": str(tmp_path), "LP_EDGE_BUFFER_FPS": "100"})
    with pytest.raises(ValueError):
        Settings.from_env({"LP_EDGE_DATA_DIR": str(tmp_path), "LP_EDGE_LOG_FORMAT": "xml"})


def test_store_is_durable_and_transactional(tmp_path):
    store = Store(tmp_path / "agent.db")
    assert store.scalar("PRAGMA journal_mode") == "wal"
    assert store.scalar("PRAGMA synchronous") == 2  # FULL
    assert stat.S_IMODE((tmp_path / "agent.db").stat().st_mode) == 0o600
    store.put_meta("kept", "1")
    with pytest.raises(RuntimeError):
        with store.transaction() as conn:
            conn.execute("INSERT INTO meta (key, value) VALUES ('lost', '1')")
            with store.transaction() as inner:  # joins the outer transaction
                inner.execute("INSERT INTO meta (key, value) VALUES ('lost too', '1')")
            raise RuntimeError("boom")
    assert store.get_meta("lost") is None
    assert store.get_meta("lost too") is None
    assert store.get_meta("kept") == "1"
    store.put_meta("camera:a", "x")
    store.put_meta("camera:b", "y")
    assert store.meta_with_prefix("camera:") == {"camera:a": "x", "camera:b": "y"}
    store.close()


def test_cipher_authenticates():
    cipher = Cipher(os.urandom(32))
    blob = cipher.seal(b"hello", b"aad")
    assert b"hello" not in blob
    assert cipher.open(blob, b"aad") == b"hello"
    with pytest.raises(IntegrityError):
        cipher.open(blob, b"other aad")
    tampered = bytearray(blob)
    tampered[-1] ^= 1
    with pytest.raises(IntegrityError):
        cipher.open(bytes(tampered), b"aad")
    with pytest.raises(ValueError):
        Cipher(b"short")


def test_buffer_key_is_created_once(tmp_path):
    path = tmp_path / "keys" / "buffer.key"
    key = load_or_create_key(path)
    assert len(key) == 32
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert load_or_create_key(path) == key
    assert load_or_create_key(path, base64.b64encode(b"k" * 32).decode()) == b"k" * 32
    with pytest.raises(ValueError):
        load_or_create_key(path, base64.b64encode(b"short").decode())
