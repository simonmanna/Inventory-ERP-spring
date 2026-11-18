# syntax=docker/dockerfile:1
#
# lp-edge: the loss-prevention edge agent. It only makes outbound HTTPS calls; the one port it opens is its health
# endpoint on 127.0.0.1. Video stays on the box (an encrypted ring buffer under /var/lib/lp-edge) unless an
# investigator asks for a clip.
#
# OpenCV comes from the opencv-python-headless wheel, which carries its own LGPL FFmpeg build (stream intake and clip
# encoding). No system ffmpeg is installed.
#
#   docker build -t lp-edge edge

ARG PYTHON_IMAGE=python:3.12-slim

FROM ${PYTHON_IMAGE} AS build
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1
WORKDIR /src
COPY pyproject.toml README.md ./
COPY lp_edge ./lp_edge
RUN python -m venv /opt/lp-edge \
 && /opt/lp-edge/bin/pip install --upgrade pip \
 && /opt/lp-edge/bin/pip install .

FROM ${PYTHON_IMAGE}
ARG VERSION=0.1.0
LABEL org.opencontainers.image.title="lp-edge" \
      org.opencontainers.image.description="Inventory-ERP loss-prevention edge agent" \
      org.opencontainers.image.version="${VERSION}"

ENV PATH=/opt/lp-edge/bin:$PATH \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    LP_EDGE_DATA_DIR=/var/lib/lp-edge \
    LP_EDGE_TMP_DIR=/run/lp-edge \
    LP_EDGE_HEALTH_PORT=8765 \
    OPENCV_LOG_LEVEL=ERROR \
    OPENCV_FFMPEG_LOGLEVEL=-8

RUN groupadd --system --gid 10001 lpedge \
 && useradd --system --uid 10001 --gid lpedge --home-dir /var/lib/lp-edge --no-create-home \
            --shell /usr/sbin/nologin lpedge \
 && install -d -m 0700 -o lpedge -g lpedge /var/lib/lp-edge /run/lp-edge

COPY --from=build /opt/lp-edge /opt/lp-edge

# Fail the build, not the site, when a native library the wheels need is missing from the base image.
RUN python -c "import cv2, numpy, onnxruntime, lp_edge; print('lp-edge', lp_edge.__version__, 'opencv', cv2.__version__)"

USER lpedge
WORKDIR /var/lib/lp-edge
# Credentials, stream addresses, the outbox and the encrypted video buffer. Mount a disk with room for the buffer.
VOLUME ["/var/lib/lp-edge"]

# Healthy when the agent runs, its cameras deliver pictures and the server accepts its credentials. Until the box
# is enrolled the agent waits and reports nothing, so the container shows as unhealthy.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 CMD ["python", "-m", "lp_edge.healthcheck"]

ENTRYPOINT ["lp-edge"]
CMD ["run"]
