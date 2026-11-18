"""YOLOX object detector on ONNX Runtime (YOLOX is Apache-2.0; Ultralytics YOLO, AGPL-3.0, is not used).

Two output layouts are supported:

- ``yolox``: the raw head of a model exported with YOLOX's ``tools/export_onnx.py`` (default), shape
  ``[1, N, 5 + classes]`` with grid-relative boxes, decoded here with strides 8, 16 and 32;
- ``yolox-decoded``: a model exported with ``--decode_in_inference``, boxes already in input pixels.

Pre-processing follows YOLOX's own demo: BGR, letterboxed to the input size with value 114, no normalisation.
"""

from __future__ import annotations

import threading
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from lp_edge.detectors.base import Detection

STRIDES = (8, 16, 32)


def preprocess(image: Any, input_size: tuple[int, int]) -> tuple[Any, float]:
    import cv2  # noqa: PLC0415
    import numpy as np  # noqa: PLC0415

    height, width = input_size
    padded = np.full((height, width, 3), 114, dtype=np.uint8)
    ratio = min(height / image.shape[0], width / image.shape[1])
    new_h, new_w = int(image.shape[0] * ratio), int(image.shape[1] * ratio)
    resized = cv2.resize(image, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
    padded[:new_h, :new_w] = resized
    blob = padded.transpose(2, 0, 1)[None].astype(np.float32)
    return np.ascontiguousarray(blob), ratio


def decode(outputs: Any, input_size: tuple[int, int], strides: Sequence[int] = STRIDES) -> Any:
    """Turn grid-relative predictions ``[N, 5 + C]`` into boxes (cx, cy, w, h) in input pixels."""
    import numpy as np  # noqa: PLC0415

    grids = []
    expanded = []
    for stride in strides:
        rows, cols = input_size[0] // stride, input_size[1] // stride
        xv, yv = np.meshgrid(np.arange(cols), np.arange(rows))
        grid = np.stack((xv, yv), 2).reshape(-1, 2)
        grids.append(grid)
        expanded.append(np.full((grid.shape[0], 1), stride))
    grid_all = np.concatenate(grids, 0).astype(np.float32)
    stride_all = np.concatenate(expanded, 0).astype(np.float32)
    if outputs.shape[0] != grid_all.shape[0]:
        raise ValueError(
            f"the model gives {outputs.shape[0]} predictions; {grid_all.shape[0]} expected for input {input_size}"
        )
    decoded = outputs.astype(np.float32, copy=True)
    decoded[:, :2] = (decoded[:, :2] + grid_all) * stride_all
    decoded[:, 2:4] = np.exp(decoded[:, 2:4]) * stride_all
    return decoded


def nms(boxes: Any, scores: Any, threshold: float) -> list[int]:
    """Indices kept by non-maximum suppression (boxes x1, y1, x2, y2)."""
    import numpy as np  # noqa: PLC0415

    x1, y1, x2, y2 = boxes[:, 0], boxes[:, 1], boxes[:, 2], boxes[:, 3]
    areas = np.maximum(0.0, x2 - x1) * np.maximum(0.0, y2 - y1)
    order = scores.argsort()[::-1]
    keep: list[int] = []
    while order.size > 0:
        i = int(order[0])
        keep.append(i)
        rest = order[1:]
        w = np.maximum(0.0, np.minimum(x2[i], x2[rest]) - np.maximum(x1[i], x1[rest]))
        h = np.maximum(0.0, np.minimum(y2[i], y2[rest]) - np.maximum(y1[i], y1[rest]))
        inter = w * h
        overlap = inter / (areas[i] + areas[rest] - inter + 1e-9)
        order = rest[overlap <= threshold]
    return keep


def postprocess(
    predictions: Any,
    ratio: float,
    frame_shape: Sequence[int],
    classes: Sequence[str],
    score_threshold: float,
    nms_threshold: float,
) -> list[Detection]:
    """Scored, class-wise suppressed detections in normalised coordinates of the original frame."""
    import numpy as np  # noqa: PLC0415

    if predictions.size == 0:
        return []
    boxes = predictions[:, :4]
    objectness = predictions[:, 4]
    class_scores = predictions[:, 5:]
    if class_scores.shape[1] == 0:
        class_ids = np.zeros(len(predictions), dtype=np.int64)
        scores = objectness
    else:
        class_ids = class_scores.argmax(1)
        scores = objectness * class_scores[np.arange(len(predictions)), class_ids]
    keep = scores >= score_threshold
    if not keep.any():
        return []
    boxes, scores, class_ids = boxes[keep], scores[keep], class_ids[keep]
    xyxy = np.empty_like(boxes)
    xyxy[:, 0] = boxes[:, 0] - boxes[:, 2] / 2.0
    xyxy[:, 1] = boxes[:, 1] - boxes[:, 3] / 2.0
    xyxy[:, 2] = boxes[:, 0] + boxes[:, 2] / 2.0
    xyxy[:, 3] = boxes[:, 1] + boxes[:, 3] / 2.0
    xyxy /= ratio
    height, width = float(frame_shape[0]), float(frame_shape[1])
    xyxy[:, [0, 2]] = np.clip(xyxy[:, [0, 2]], 0.0, width)
    xyxy[:, [1, 3]] = np.clip(xyxy[:, [1, 3]], 0.0, height)
    detections = []
    for class_id in np.unique(class_ids):
        index = np.where(class_ids == class_id)[0]
        for k in nms(xyxy[index], scores[index], nms_threshold):
            i = index[k]
            x1, y1, x2, y2 = (float(v) for v in xyxy[i])
            if x2 <= x1 or y2 <= y1:
                continue
            cid = int(class_id)
            name = classes[cid] if cid < len(classes) else f"class_{cid}"
            detections.append(Detection(name, float(scores[i]), (x1 / width, y1 / height, x2 / width, y2 / height)))
    return detections


class YoloxDetector:
    """A YOLOX ONNX model. Inference is serialised: one picture at a time uses every intra-op thread."""

    def __init__(
        self,
        model_path: Path | str,
        *,
        version: str,
        classes: Sequence[str],
        input_size: tuple[int, int] = (640, 640),
        decoded: bool = False,
        score_threshold: float = 0.1,
        nms_threshold: float = 0.45,
        providers: Sequence[str] | None = None,
        session: Any = None,
    ) -> None:
        if session is None:
            import onnxruntime as ort  # noqa: PLC0415

            available = ort.get_available_providers()
            chosen = [p for p in (providers or available) if p in available] or ["CPUExecutionProvider"]
            options = ort.SessionOptions()
            options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
            session = ort.InferenceSession(str(model_path), sess_options=options, providers=chosen)
        self._session = session
        self._input_name = session.get_inputs()[0].name
        self.version = version
        self.classes = tuple(classes)
        self.input_size = input_size
        self.decoded = decoded
        self.score_threshold = score_threshold
        self.nms_threshold = nms_threshold
        self._lock = threading.Lock()

    def detect(self, frame: Any) -> list[Detection]:
        blob, ratio = preprocess(frame, self.input_size)
        with self._lock:
            output = self._session.run(None, {self._input_name: blob})[0]
        predictions = output[0] if output.ndim == 3 else output
        if not self.decoded:
            predictions = decode(predictions, self.input_size)
        return postprocess(predictions, ratio, frame.shape, self.classes, self.score_threshold, self.nms_threshold)
