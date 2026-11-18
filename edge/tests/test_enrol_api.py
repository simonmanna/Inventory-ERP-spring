import hashlib
import stat

import pytest
from fakes import FakeResponse, FakeSession

from lp_edge import credentials as credentials_file
from lp_edge.api import (
    ApiClient,
    ApiError,
    CredentialsRejected,
    TokenProvider,
    check_transport,
    error_message,
    retry_after,
)
from lp_edge.credentials import Credentials
from lp_edge.enrol import EnrolmentError, enrol, normalise_code, split_server
from lp_edge.settings import Settings

ENROLMENT = {
    "agentId": "agent-1",
    "organizationId": "org-1",
    "branchId": "branch-1",
    "clientId": "lpa_1",
    "clientSecret": "s3cret",
    "tokenUrl": "/api/v1/oauth/token",
    "scope": "lp:ingest",
    "heartbeatSeconds": 60,
    "apiBase": "/api/v1",
}

CREDS = Credentials(
    server="https://erp.example.com",
    api_base="/api/v1",
    token_url="/api/v1/oauth/token",
    client_id="lpa_1",
    client_secret="s3cret",
    agent_id="agent-1",
    organization_id="org-1",
    branch_id="branch-1",
)


def settings_for(tmp_path):
    return Settings.from_env({"LP_EDGE_DATA_DIR": str(tmp_path), "LP_EDGE_TMP_DIR": str(tmp_path / "tmp")})


def token_or(answer):
    def responder(method, url, kwargs):
        if url.endswith("/oauth/token"):
            return FakeResponse(200, {"access_token": "tok", "expires_in": 3600})
        return answer(method, url, kwargs)

    return responder


def test_server_and_code_forms():
    assert split_server("https://erp.example.com") == ("https://erp.example.com", "/api/v1")
    assert split_server("https://erp.example.com/erp/api/v1/") == ("https://erp.example.com", "/erp/api/v1")
    assert normalise_code(" abcd-efgh-ijkl \n") == "ABCD-EFGH-IJKL"
    for bad in ("ftp://erp.example.com", "https://user:pw@erp.example.com", "erp.example.com"):
        with pytest.raises(EnrolmentError):
            split_server(bad)
    with pytest.raises(EnrolmentError):
        normalise_code("   ")


def test_enrol_saves_private_credentials(tmp_path):
    session = FakeSession(lambda m, u, k: FakeResponse(201, ENROLMENT))
    creds = enrol("https://erp.example.com", "abcd-efgh-ijkl", settings_for(tmp_path), hostname="box-1", session=session)
    method, url, kwargs = session.calls[0]
    assert (method, url) == ("POST", "https://erp.example.com/api/v1/loss-prevention/agents/enrol")
    assert kwargs["json"] == {"code": "ABCD-EFGH-IJKL", "agentVersion": "0.1.0", "hostname": "box-1"}
    assert "Authorization" not in kwargs["headers"]
    assert creds.api_url == "https://erp.example.com/api/v1"
    assert creds.token_endpoint == "https://erp.example.com/api/v1/oauth/token"
    assert (creds.agent_id, creds.branch_id, creds.client_id) == ("agent-1", "branch-1", "lpa_1")
    path = tmp_path / "credentials.json"
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert credentials_file.load(path) == creds
    assert "s3cret" not in repr(creds)


def test_enrol_shows_the_server_message(tmp_path):
    refusal = {"statusCode": 400, "message": "Enrolment code is invalid or has expired", "error": "Bad Request"}
    session = FakeSession(lambda m, u, k: FakeResponse(400, refusal))
    with pytest.raises(EnrolmentError, match="invalid or has expired"):
        enrol("https://erp.example.com", "XXXX-XXXX-XXXX", settings_for(tmp_path), session=session)
    assert not (tmp_path / "credentials.json").exists()


def test_enrol_refuses_a_token_url_on_another_origin(tmp_path):
    body = dict(ENROLMENT, tokenUrl="https://elsewhere.example.net/oauth/token")
    session = FakeSession(lambda m, u, k: FakeResponse(201, body))
    with pytest.raises(EnrolmentError):
        enrol("https://erp.example.com", "AAAA-BBBB-CCCC", settings_for(tmp_path), session=session)
    assert not (tmp_path / "credentials.json").exists()


def test_token_uses_basic_auth_and_is_cached():
    now = [0.0]
    session = FakeSession(lambda m, u, k: FakeResponse(200, {"access_token": "tok-1", "expires_in": 3600}))
    tokens = TokenProvider(CREDS, session, monotonic=lambda: now[0])
    assert tokens.get() == "tok-1"
    assert tokens.get() == "tok-1"
    assert len(session.calls) == 1
    _, url, kwargs = session.calls[0]
    assert url == "https://erp.example.com/api/v1/oauth/token"
    assert kwargs["data"] == {"grant_type": "client_credentials", "scope": "lp:ingest"}
    assert kwargs["auth"] == ("lpa_1", "s3cret")
    now[0] = 3600 - 59  # a minute before expiry
    tokens.get()
    assert len(session.calls) == 2


def test_refused_credentials_are_told_apart():
    answer = {"error": "invalid_client", "error_description": "Client authentication failed"}
    session = FakeSession(lambda m, u, k: FakeResponse(401, answer))
    with pytest.raises(CredentialsRejected):
        TokenProvider(CREDS, session).get()


def test_a_401_fetches_a_new_token_once():
    answers = iter([FakeResponse(401, {"message": "Unauthorized"}), FakeResponse(200, {"configVersion": 3})])
    issued = []

    def responder(method, url, kwargs):
        if url.endswith("/oauth/token"):
            issued.append(1)
            return FakeResponse(200, {"access_token": f"tok-{len(issued)}", "expires_in": 3600})
        return next(answers)

    session = FakeSession(responder)
    api = ApiClient(CREDS.api_url, tokens=TokenProvider(CREDS, session), session=session)
    assert api.get_config() == {"configVersion": 3}
    calls = [c for c in session.calls if not c[1].endswith("/oauth/token")]
    assert [c[2]["headers"]["Authorization"] for c in calls] == ["Bearer tok-1", "Bearer tok-2"]


def test_errors_carry_the_erp_message_and_retry_after():
    answer = FakeResponse(429, {"statusCode": 429, "message": ["slow", "down"]}, headers={"Retry-After-short": "7"})
    api = ApiClient("https://erp.example.com/api/v1", session=FakeSession(lambda m, u, k: answer))
    with pytest.raises(ApiError) as caught:
        api.call("GET", "/anything", auth=False)
    assert caught.value.status == 429
    assert caught.value.message == "slow; down"
    assert caught.value.retry_after == 7
    assert caught.value.retryable
    assert not ApiError(409, "Expected offset 5").retryable
    assert error_message({"message": "Expected offset 5"}, "x") == "Expected offset 5"
    assert retry_after({"Retry-After": "3"}) == 3
    assert retry_after({}) is None


def test_plain_http_is_refused():
    with pytest.raises(ValueError):
        check_transport("http://erp.example.com/api/v1", allow_http=False)
    check_transport("http://localhost:3001/api/v1", allow_http=False)
    check_transport("http://erp.local/api/v1", allow_http=True)
    with pytest.raises(ValueError):
        ApiClient("http://erp.example.com/api/v1")


def test_protocol_calls():
    def answer(method, url, kwargs):
        if url.endswith("/agents/commands"):
            return FakeResponse(200, {"commands": [{"id": "c1", "kind": "snapshot"}, "junk"]})
        if url.endswith("/frames"):
            return FakeResponse(200, {"continue": False})
        if url.endswith("/result"):
            return FakeResponse(200)
        return FakeResponse(200, {"receivedBytes": 3, "complete": True})

    session = FakeSession(token_or(answer))
    api = ApiClient(CREDS.api_url, tokens=TokenProvider(CREDS, session), session=session)
    assert api.poll_commands(25) == [{"id": "c1", "kind": "snapshot"}]
    assert api.post_live_frame("s 1", b"jpeg") is False
    api.upload_chunk("ev1", 0, 3, "ab" * 32, b"abc")
    api.upload_chunk("ev2", 0, 3, "ab" * 32, b"abc", content_type="video/webm")
    assert api.command_result("c1", "failed", "gone", "not_found") is None
    by_path = {c[1].split("/api/v1", 1)[1]: c[2] for c in session.calls if "/oauth/" not in c[1]}
    poll = by_path["/loss-prevention/agents/commands"]
    assert poll["params"] == {"waitSeconds": 25}
    assert poll["timeout"][1] == 45
    assert by_path["/loss-prevention/agents/live/s%201/frames"]["headers"]["Content-Type"] == "image/jpeg"
    chunk = by_path["/loss-prevention/evidence/ev1/upload"]
    assert chunk["params"] == {"offset": 0, "total": 3, "sha256": "ab" * 32}
    assert by_path["/loss-prevention/evidence/ev2/upload"]["params"] == {
        "offset": 0,
        "total": 3,
        "sha256": "ab" * 32,
        "contentType": "video/webm",
    }
    assert chunk["headers"]["Content-Type"] == "application/octet-stream"
    assert chunk["data"] == b"abc"
    result = by_path["/loss-prevention/agents/commands/c1/result"]
    assert result["json"] == {"status": "failed", "message": "gone", "code": "not_found"}


def test_download_sends_the_token_only_to_its_own_origin(tmp_path):
    session = FakeSession(token_or(lambda m, u, k: FakeResponse(200, chunks=[b"model", b"bytes"])))
    api = ApiClient(CREDS.api_url, tokens=TokenProvider(CREDS, session), session=session)
    digest = api.download("/models/m.onnx", tmp_path / "a.onnx")
    assert digest == hashlib.sha256(b"modelbytes").hexdigest()
    assert (tmp_path / "a.onnx").read_bytes() == b"modelbytes"
    api.download("https://cdn.example.net/m.onnx", tmp_path / "b.onnx")
    downloads = [c for c in session.calls if "/oauth/" not in c[1]]
    assert downloads[0][1] == "https://erp.example.com/models/m.onnx"
    assert downloads[0][2]["headers"]["Authorization"] == "Bearer tok"
    assert "Authorization" not in downloads[1][2]["headers"]
    with pytest.raises(ApiError):
        api.download("https://cdn.example.net/big.onnx", tmp_path / "c.onnx", max_bytes=4)
