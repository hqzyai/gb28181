# The caller must provide the tested Torch 2.12.1 / torchvision 0.27.1,
# CUDA 13.0, Python 3.12 base image by immutable digest.
ARG TORCH_BASE_IMAGE
FROM ${TORCH_BASE_IMAGE}
USER root
WORKDIR /opt/detector
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 \
    YOLO_CONFIG_DIR=/tmp/ultralytics OMP_NUM_THREADS=2 \
    OPENBLAS_NUM_THREADS=2 MPLCONFIGDIR=/tmp/matplotlib
COPY requirements*.txt ./
RUN apt-get update \
    && apt-get install --no-install-recommends -y libgl1 libglib2.0-0 python3-venv \
    && rm -rf /var/lib/apt/lists/* \
    && python -m venv --system-site-packages /opt/detector-venv
RUN ! getent passwd 1001 >/dev/null \
    && ! getent group 1001 >/dev/null \
    && groupadd --gid 1001 detector \
    && useradd --uid 1001 --gid 1001 --create-home --home-dir /home/detector --shell /usr/sbin/nologin detector
ENV PATH="/opt/detector-venv/bin:${PATH}"
# constraints prevent the Ultralytics resolver from changing the verified GPU runtime.
RUN printf 'torch==2.12.1\ntorchvision==0.27.1\n' > /tmp/torch-constraints.txt \
    && python -m pip install --no-cache-dir -c /tmp/torch-constraints.txt -r requirements.txt \
    && python -m pip check \
    && python -m pip freeze > /opt/detector/runtime-requirements.lock \
    && python -c 'import torch, torchvision; from importlib.metadata import version; assert torch.__version__.split("+")[0] == "2.12.1"; assert torchvision.__version__.split("+")[0] == "0.27.1"; assert torch.version.cuda == "13.0"; assert version("click") == "8.3.1"'
COPY detector_service ./detector_service
RUN chmod -R a=rX /opt/detector
USER 1001:1001
ENV USER=detector LOGNAME=detector HOME=/home/detector YOLO_AUTOINSTALL=false \
    TORCHINDUCTOR_CACHE_DIR=/tmp/torchinductor
EXPOSE 8080
HEALTHCHECK --interval=20s --timeout=5s --start-period=90s --retries=3 \
    CMD python -c 'import urllib.request; urllib.request.urlopen("http://127.0.0.1:8080/readyz", timeout=3)'
CMD ["python", "-m", "uvicorn", "detector_service.app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8080", "--workers", "1", "--limit-concurrency", "16", "--timeout-keep-alive", "5", "--no-access-log"]
