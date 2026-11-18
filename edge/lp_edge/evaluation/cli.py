"""``lp-edge-eval``: measure candidate detectors on labelled footage.

    lp-edge-eval check   --dataset DIR                          validate labels, show footage coverage
    lp-edge-eval licences --candidates FILE --register FILE     licence status of each candidate
    lp-edge-eval run     --dataset DIR --candidates FILE --register FILE --out DIR [options]
                         [--cameras N --sustain-seconds S]   then the sustained multi-camera run on this machine

Exit status: 0 done, 2 invalid input. A run never fails because a candidate did badly; the report says so.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

from lp_edge.evaluation import provenance
from lp_edge.evaluation import report as reports
from lp_edge.evaluation.bench import BenchSettings
from lp_edge.evaluation.dataset import DatasetError, coverage, load_dataset
from lp_edge.evaluation.licences import RegisterError, eligibility, load_register
from lp_edge.evaluation.runner import CandidateError, VideoSource, environment, evaluate, load_candidates
from lp_edge.evaluation.thresholds import load_thresholds

DEFAULT_REGISTER = Path(__file__).resolve().parents[2] / "evaluation" / "licence-register.yaml"


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="lp-edge-eval", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    check = sub.add_parser("check", help="validate a dataset and show its coverage")
    check.add_argument("--dataset", required=True)
    lic = sub.add_parser("licences", help="licence status of each candidate")
    lic.add_argument("--candidates", required=True)
    lic.add_argument("--register", default=str(DEFAULT_REGISTER))
    run = sub.add_parser("run", help="evaluate candidates and write report.json and report.md")
    run.add_argument("--dataset", required=True)
    run.add_argument("--candidates", required=True)
    run.add_argument("--register", default=str(DEFAULT_REGISTER))
    run.add_argument("--out", required=True)
    run.add_argument("--only", action="append", help="evaluate only this candidate (repeatable)")
    run.add_argument("--fps", type=float, default=5.0, help="analysis rate, as LP_EDGE_ANALYSIS_FPS (default 5)")
    run.add_argument("--max-width", type=int, default=960, help="analysis width, as LP_EDGE_ANALYSIS_MAX_WIDTH")
    run.add_argument("--tolerance", type=float, default=2.0, help="seconds between a predicted and a labelled event")
    run.add_argument("--iou", type=float, default=0.5, help="box overlap for a correct detection")
    run.add_argument("--thresholds", help="YAML replacing the proposed acceptance thresholds")
    run.add_argument("--cameras", type=int, default=0,
                     help="cameras to run at once in the sustained hardware run (0: no hardware run)")
    run.add_argument("--sustain-seconds", type=float, default=1800.0, help="length of the hardware run (default 1800)")
    run.add_argument("--window-seconds", type=float, default=60.0, help="frame-rate window of the hardware run")
    run.add_argument("--no-video-hash", action="store_true", help="skip hashing the videos (faster; fingerprint "
                     "then does not pin the footage)")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    try:
        if args.command == "check":
            dataset = load_dataset(args.dataset)
            print(json.dumps({"name": dataset.name, "clips": len(dataset.clips), "classes": sorted(dataset.classes()),
                              "coverage": coverage(dataset)}, indent=2))
            return 0
        register = load_register(args.register)
        candidates = load_candidates(args.candidates)
        if args.command == "licences":
            print(json.dumps({c.name: eligibility(list(c.licences), register, c.sha256 or None)
                              for c in candidates}, indent=2))
            return 0
        dataset = load_dataset(args.dataset)
        if args.only:
            candidates = [c for c in candidates if c.name in set(args.only)]
            if not candidates:
                raise CandidateError("--only names no candidate of the file")
        thresholds = load_thresholds(args.thresholds)
        source = VideoSource(args.max_width)
        bench = (BenchSettings(args.cameras, args.sustain_seconds, args.fps, args.window_seconds)
                 if args.cameras > 0 else None)
        results = []
        for candidate in candidates:
            results.append(evaluate(candidate, dataset, source, fps=args.fps, tolerance=args.tolerance,
                                    thresholds=thresholds, iou_threshold=args.iou, bench=bench))
        settings = {"fps": args.fps, "maxWidth": args.max_width, "toleranceSeconds": args.tolerance, "iou": args.iou,
                    "cameras": args.cameras, "sustainSeconds": args.sustain_seconds,
                    "windowSeconds": args.window_seconds}
        prov = {"agent": provenance.agent(), "clips": provenance.footage(dataset, not args.no_video_hash),
                "command": ["lp-edge-eval", *(argv if argv is not None else sys.argv[1:])]}
        report = reports.build(dataset, coverage(dataset), thresholds, settings,
                               results,
                               {c.name: eligibility(list(c.licences), register, c.sha256 or None) for c in candidates},
                               environment(), prov)
        json_path, md_path = reports.write(report, Path(args.out))
        print(f"wrote {json_path} and {md_path}")
        for c in report["candidates"]:
            print(f"{c['candidate']}: {c['overall']['status']}")
        return 0
    except (DatasetError, RegisterError, CandidateError, FileNotFoundError) as err:
        print(f"lp-edge-eval: {err}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
