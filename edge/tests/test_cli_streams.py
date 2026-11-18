import io
import os
import stat
import sys
import time

import pytest

from lp_edge.cli import main
from lp_edge.streams import StreamRegistry, check_url, describe


@pytest.fixture
def data_dir(tmp_path, monkeypatch):
    monkeypatch.setenv("LP_EDGE_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("LP_EDGE_TMP_DIR", str(tmp_path / "tmp"))
    monkeypatch.delenv("LP_EDGE_STREAMS_FILE", raising=False)
    return tmp_path


def test_stream_commands_never_print_the_address(data_dir, monkeypatch, capsys):
    monkeypatch.setattr(sys, "stdin", io.StringIO("rtsp://viewer:pa55word@10.0.0.9:554/stream1\n"))
    assert main(["streams", "set", "till-1", "-"]) == 0
    assert main(["streams", "list"]) == 0
    out = capsys.readouterr().out
    assert "till-1" in out and "rtsp with credentials" in out
    assert "pa55word" not in out and "10.0.0.9" not in out
    path = data_dir / "streams.yaml"
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert StreamRegistry(path).url_for("till-1") == "rtsp://viewer:pa55word@10.0.0.9:554/stream1"
    assert main(["streams", "set", "Not An Alias", "-"]) == 2
    assert main(["streams", "remove", "till-1"]) == 0
    assert main(["streams", "remove", "till-1"]) == 1
    assert main(["version"]) == 0
    assert "lp-edge 0.1.0" in capsys.readouterr().out


def test_stream_addresses_are_checked():
    assert check_url(" rtsp://cam/1 ") == "rtsp://cam/1"
    assert describe("https://cam.local/mjpeg") == "https"
    for bad in ("ftp://cam/1", "cam/1", "javascript:alert(1)"):
        with pytest.raises(ValueError):
            check_url(bad)


def test_the_registry_reads_the_file_again_when_it_changes(tmp_path):
    path = tmp_path / "streams.yaml"
    registry = StreamRegistry(path)
    assert registry.url_for("bar-1") is None
    path.write_text("streams:\n  bar-1: rtsp://cam-a/1\n  BAD ALIAS: rtsp://x/1\n  bar-2: {url: 'https://cam-b/m'}\n")
    assert registry.aliases() == ["bar-1", "bar-2"]
    time.sleep(0.01)
    path.write_text("streams:\n  bar-1: rtsp://cam-c/1\n")
    os.utime(path, (time.time() + 5, time.time() + 5))
    assert registry.url_for("bar-1") == "rtsp://cam-c/1"
    assert registry.aliases() == ["bar-1"]
