"""Command line: ``lp-edge enrol | run | status | streams | clock | version``.

Stream addresses are typed at a hidden prompt (or piped in with ``-``) and are never printed, logged or sent.
"""

from __future__ import annotations

import argparse
import getpass
import json
import logging
import os
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Sequence

from lp_edge import __version__
from lp_edge import credentials as credentials_file
from lp_edge.logs import quiet_native_video_logs, setup
from lp_edge.settings import Settings
from lp_edge.streams import StreamRegistry, check_alias, check_url, describe

log = logging.getLogger("lp_edge")

EX_CONFIG = 78


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="lp-edge", description="Loss-prevention edge agent for Inventory-ERP.")
    sub = parser.add_subparsers(dest="command", required=True)

    enrol = sub.add_parser("enrol", help="exchange a one-time enrolment code for this agent's credentials")
    enrol.add_argument("--server", default=os.environ.get("LP_EDGE_SERVER"),
                       help="the ERP, e.g. https://erp.example.com (or LP_EDGE_SERVER)")
    enrol.add_argument("--code", default=os.environ.get("LP_EDGE_ENROL_CODE"),
                       help="the code shown in the ERP (asked for when left out)")
    enrol.add_argument("--hostname", help="the name shown in the ERP (default: this host's name)")

    run = sub.add_parser("run", help="run the agent")
    run.add_argument("--wait-for-enrolment", action=argparse.BooleanOptionalAction, default=True,
                     help="wait until the box is enrolled instead of exiting (default: wait)")

    sub.add_parser("status", help="the running agent's health, from its local endpoint")

    streams = sub.add_parser("streams", help="stream addresses of this box (they never leave it)")
    streams_sub = streams.add_subparsers(dest="streams_command", required=True)
    streams_sub.add_parser("list", help="aliases, and only the scheme of each address")
    set_ = streams_sub.add_parser("set", help="set the address of an alias (asked for without echo)")
    set_.add_argument("alias")
    set_.add_argument("url", nargs="?", help="'-' reads the address from standard input; leave out to be asked")
    remove = streams_sub.add_parser("remove", help="forget the address of an alias")
    remove.add_argument("alias")
    test = streams_sub.add_parser("test", help="open the stream of an alias and read one picture")
    test.add_argument("alias")
    test.add_argument("--timeout", type=float, default=15.0)

    clock = sub.add_parser("clock", help="how far this box's clock is from the time server's")
    clock.add_argument("--ntp", default=None, help="an NTP server (default LP_EDGE_NTP_SERVER)")

    sub.add_parser("version", help="print the version")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    if args.command == "version":
        print(f"lp-edge {__version__}")
        return 0
    try:
        settings = Settings.from_env()
    except ValueError as exc:
        print(f"lp-edge: {exc}", file=sys.stderr)
        return EX_CONFIG
    setup(settings.log_level, settings.log_format)
    handlers = {
        "enrol": _enrol,
        "run": _run,
        "status": _status,
        "streams": _streams,
        "clock": _clock,
    }
    return handlers[args.command](args, settings)


def _enrol(args: argparse.Namespace, settings: Settings) -> int:
    from lp_edge.enrol import EnrolmentError, enrol  # noqa: PLC0415

    if not args.server:
        print("lp-edge enrol: --server is required (or LP_EDGE_SERVER)", file=sys.stderr)
        return 2
    code = args.code
    if not code:
        if not sys.stdin.isatty():
            code = sys.stdin.readline()
        else:
            code = input("Enrolment code (XXXX-XXXX-XXXX): ")
    try:
        creds = enrol(args.server, code, settings, hostname=args.hostname)
    except EnrolmentError as exc:
        print(f"lp-edge enrol: {exc}", file=sys.stderr)
        return 1
    print(f"Enrolled as agent {creds.agent_id} for branch {creds.branch_id}.")
    print(f"Credentials saved to {settings.credentials_file} (readable by this user only).")
    return 0


def _run(args: argparse.Namespace, settings: Settings) -> int:
    quiet_native_video_logs()
    creds = credentials_file.load(settings.credentials_file)
    if creds is None and args.wait_for_enrolment:
        log.warning("this box is not enrolled yet; run `lp-edge enrol --server https://…` (waiting)")
        while creds is None:
            time.sleep(10.0)
            creds = credentials_file.load(settings.credentials_file)
    if creds is None:
        print("lp-edge run: this box is not enrolled; run `lp-edge enrol` first", file=sys.stderr)
        return EX_CONFIG
    from lp_edge.agent import Agent  # noqa: PLC0415

    return Agent(settings, creds).run()


def _health(settings: Settings) -> tuple[int, dict | None]:
    url = f"http://127.0.0.1:{settings.health_port}/health"
    try:
        with urllib.request.urlopen(url, timeout=5) as response:  # noqa: S310 - loopback only
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        try:
            return exc.code, json.loads(exc.read().decode("utf-8"))
        except ValueError:
            return exc.code, None
    except (urllib.error.URLError, OSError, ValueError):
        return 0, None


def _status(args: argparse.Namespace, settings: Settings) -> int:
    code, body = _health(settings)
    if body is None:
        print("lp-edge status: the agent is not running (no answer on its local health endpoint)", file=sys.stderr)
        return 1
    print(json.dumps(body, indent=2, sort_keys=True))
    return 0 if code == 200 else 1


def _streams(args: argparse.Namespace, settings: Settings) -> int:
    registry = StreamRegistry(settings.streams_file)
    command = args.streams_command
    try:
        if command == "list":
            aliases = registry.aliases()
            if not aliases:
                print(f"No streams yet. Add one with: lp-edge streams set <alias>  ({settings.streams_file})")
            for alias in aliases:
                print(f"{alias}\t{describe(registry.url_for(alias) or '')}")
            return 0
        alias = check_alias(args.alias)
        if command == "set":
            url = args.url
            if url == "-":
                url = sys.stdin.readline()
            elif url is None:
                url = getpass.getpass(f"Stream address for {alias} (not shown): ")
            registry.set(alias, check_url(url))
            print(f"{alias} saved ({describe(url.strip())}). The agent picks it up within a few seconds.")
            return 0
        if command == "remove":
            if registry.remove(alias):
                print(f"{alias} removed.")
                return 0
            print(f"lp-edge streams: no stream {alias}", file=sys.stderr)
            return 1
        if command == "test":
            return _test_stream(registry, alias, args.timeout)
    except ValueError as exc:
        print(f"lp-edge streams: {exc}", file=sys.stderr)
        return 2
    return 2


def _test_stream(registry: StreamRegistry, alias: str, timeout: float) -> int:
    url = registry.url_for(alias)
    if url is None:
        print(f"lp-edge streams: no stream {alias}", file=sys.stderr)
        return 1
    quiet_native_video_logs()
    try:
        from lp_edge.video import CaptureSource  # noqa: PLC0415

        source = CaptureSource(url, open_timeout_ms=int(timeout * 1000), read_timeout_ms=int(timeout * 1000))
    except ImportError:
        print("lp-edge streams: OpenCV is not installed", file=sys.stderr)
        return 1
    started = time.monotonic()
    try:
        if not source.open():
            print(f"{alias}: the stream did not open (check the address, the password and the network)")
            return 1
        deadline = started + timeout
        while time.monotonic() < deadline:
            ok, frame = source.read()
            if ok and frame is not None:
                height, width = frame.shape[:2]
                print(f"{alias}: ok, {width}x{height}, first picture after {time.monotonic() - started:.1f} s")
                return 0
        print(f"{alias}: the stream opened but sent no picture within {timeout:.0f} s")
        return 1
    finally:
        source.close()


def _clock(args: argparse.Namespace, settings: Settings) -> int:
    server = args.ntp or settings.ntp_server
    if server:
        from lp_edge.clock import sntp_offset  # noqa: PLC0415

        try:
            offset, delay = sntp_offset(server)
        except (OSError, ValueError) as exc:
            print(f"lp-edge clock: {server} did not answer ({exc})", file=sys.stderr)
            return 1
        print(f"offset {offset * 1000:+.0f} ms (round trip {delay * 1000:.0f} ms) against {server}")
        return 0
    _, body = _health(settings)
    clock = (body or {}).get("clock") if isinstance(body, dict) else None
    if not clock:
        print("lp-edge clock: give --ntp SERVER, or start the agent (it measures against the ERP)", file=sys.stderr)
        return 1
    state = "OFF" if clock.get("off") else "ok"
    print(f"drift {clock.get('driftMs')} ms ({state}), measured against {clock.get('source') or 'nothing yet'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
