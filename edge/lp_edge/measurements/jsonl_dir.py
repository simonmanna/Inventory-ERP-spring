"""``jsonl_dir``: one JSON object per line, in files the vendor's software writes into a directory."""

from __future__ import annotations

import json
from collections.abc import Iterator
from typing import Any

from lp_edge.measurements.base import BadRecord
from lp_edge.measurements.directory import DirectoryAdapter


class JsonlDirAdapter(DirectoryAdapter):
    default_glob = "*.jsonl"

    def records(self, text: str) -> Iterator[Any]:
        for number, line in enumerate(text.splitlines(), start=1):
            if not line.strip():
                continue
            try:
                yield json.loads(line)
            except ValueError:
                yield BadRecord(f"line {number} is not JSON")
