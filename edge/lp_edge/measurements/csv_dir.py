"""``csv_dir``: CSV files with a header row, in a directory the vendor's software writes into.

``delimiter`` (default ``,``) may be set in the adapter's entry. Field names in ``fields`` are header names.
"""

from __future__ import annotations

import csv
import io
from collections.abc import Iterator
from typing import Any

from lp_edge.measurements.base import AdapterSpec, BadRecord, ConfigError
from lp_edge.measurements.directory import DirectoryAdapter
from lp_edge.store import Store


class CsvDirAdapter(DirectoryAdapter):
    default_glob = "*.csv"

    def __init__(self, spec: AdapterSpec, store: Store) -> None:
        super().__init__(spec, store)
        delimiter = spec.options.get("delimiter", ",")
        if not isinstance(delimiter, str) or len(delimiter) != 1:
            raise ConfigError("delimiter must be one character")
        self.delimiter = delimiter

    def records(self, text: str) -> Iterator[Any]:
        reader = csv.DictReader(io.StringIO(text, newline=""), delimiter=self.delimiter)
        try:
            for row in reader:
                if not any((value or "").strip() for key, value in row.items() if isinstance(value, str)):
                    continue
                if None in row:
                    yield BadRecord(f"line {reader.line_num} has more values than the header")
                    continue
                yield {key.strip(): (value.strip() if isinstance(value, str) else value) for key, value in row.items()}
        except csv.Error as exc:
            yield BadRecord(f"line {reader.line_num}: {exc}")
