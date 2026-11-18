import os
import threading

import pytest

from lp_edge.api import ApiError
from lp_edge.uploader import CHUNK, UploadFailed, upload


class UploadServer:
    """The backend's side of the resumable upload."""

    def __init__(self, total, checksum_fails=False, failures=0):
        self.total = total
        self.received = bytearray()
        self.checksum_fails = checksum_fails
        self.failures = failures
        self.offsets = []
        self.content_types = []
        self.rejected = None

    def upload_status(self, evidence_id):
        return {
            "evidenceId": evidence_id,
            "receivedBytes": len(self.received),
            "totalBytes": self.total,
            "complete": len(self.received) >= self.total,
            "state": "uploading",
        }

    def upload_chunk(self, evidence_id, offset, total, sha256, chunk, content_type=None):
        self.content_types.append(content_type)
        if self.rejected:
            raise ApiError(409, self.rejected)
        if self.failures:
            self.failures -= 1
            raise ApiError(503, "Service Unavailable")
        if len(chunk) > CHUNK:
            raise ApiError(413, "Chunk too large")
        if offset != len(self.received):
            raise ApiError(409, f"Expected offset {len(self.received)}")
        if self.checksum_fails and offset + len(chunk) >= total:
            raise ApiError(409, "Checksum does not match the clip registered at capture")
        self.offsets.append(offset)
        self.received += chunk
        return self.upload_status(evidence_id)


class NoWait(threading.Event):
    def __init__(self):
        super().__init__()
        self.waits = []

    def wait(self, timeout=None):
        self.waits.append(timeout)
        return False


def test_an_upload_resumes_where_the_server_is():
    data = os.urandom(2 * CHUNK + 123)
    server = UploadServer(len(data))
    server.received += data[:CHUNK]  # a previous attempt got one chunk through
    status = upload(server, "ev-1", total=len(data), sha256="00" * 32, read=lambda o, n: data[o : o + n])
    assert status["complete"]
    assert server.offsets == [CHUNK, 2 * CHUNK]
    assert bytes(server.received) == data


def test_an_offset_conflict_reads_the_position_again():
    data = b"0123456789"
    server = UploadServer(len(data))
    server.received += data[:4]
    stale = iter([{"receivedBytes": 0, "complete": False}])
    real_status = server.upload_status
    server.upload_status = lambda evidence_id: next(stale, None) or real_status(evidence_id)
    upload(server, "ev", total=10, sha256="00" * 32, read=lambda o, n: data[o : o + n], chunk_size=4)
    assert server.offsets == [4, 8]
    assert bytes(server.received) == data


def test_a_checksum_mismatch_fails_the_upload():
    server = UploadServer(4, checksum_fails=True)
    with pytest.raises(UploadFailed, match="Checksum does not match"):
        upload(server, "ev", total=4, sha256="00" * 32, read=lambda o, n: b"abcd"[o : o + n])


def test_the_content_type_goes_with_every_chunk():
    data = b"0123456789"
    server = UploadServer(len(data))
    upload(server, "ev", total=10, sha256="00" * 32, read=lambda o, n: data[o : o + n], chunk_size=4,
           content_type="video/webm")
    assert server.content_types == ["video/webm"] * 3


def test_a_clip_that_fails_its_check_or_is_no_longer_wanted_is_not_sent_again():
    for message in ("The clip does not match its checksum; it may have been altered", "The clip was not requested"):
        server = UploadServer(4)
        server.rejected = message
        with pytest.raises(UploadFailed, match=message.split(";")[0]):
            upload(server, "ev", total=4, sha256="00" * 32, read=lambda o, n: b"abcd"[o : o + n])
        assert len(server.content_types) == 1


def test_server_errors_are_retried_with_backoff():
    server = UploadServer(4, failures=2)
    stop = NoWait()
    upload(server, "ev", total=4, sha256="00" * 32, read=lambda o, n: b"abcd"[o : o + n], stop=stop)
    assert stop.waits == [2.0, 4.0]
    assert bytes(server.received) == b"abcd"


def test_a_clip_shorter_than_registered_is_not_found():
    server = UploadServer(10)
    with pytest.raises(UploadFailed) as caught:
        upload(server, "ev", total=10, sha256="00" * 32, read=lambda o, n: b"abc")
    assert caught.value.code == "not_found"


def test_a_refused_upload_fails_at_once():
    class Gone(UploadServer):
        def upload_status(self, evidence_id):
            raise ApiError(404, "Evidence not found")

    with pytest.raises(UploadFailed, match="Evidence not found"):
        upload(Gone(4), "ev", total=4, sha256="00" * 32, read=lambda o, n: b"abcd")
