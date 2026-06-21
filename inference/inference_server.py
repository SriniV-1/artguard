"""
ArtGuard inference service — a gRPC server that runs YOLOv8 object detection
over JPEG-encoded video frames sent by the Java gateway.

Run:
    ./gen_stubs.sh                 # once, to generate the gRPC stubs
    python inference_server.py     # serves on :50051

Env:
    ARTGUARD_MODEL   YOLO weights (default: yolov8n.pt — auto-downloaded)
    ARTGUARD_PORT    listen port  (default: 50051)
    ARTGUARD_WORKERS gRPC threadpool size (default: 8)
"""
from __future__ import annotations

import os
import time
from concurrent import futures

import cv2
import grpc
import numpy as np

from generated import inference_pb2 as pb
from generated import inference_pb2_grpc as pb_grpc

MODEL_NAME = os.environ.get("ARTGUARD_MODEL", "yolov8n.pt")
PORT = int(os.environ.get("ARTGUARD_PORT", "50051"))
WORKERS = int(os.environ.get("ARTGUARD_WORKERS", "8"))


class InferenceService(pb_grpc.InferenceServiceServicer):
    def __init__(self) -> None:
        # Import here so a missing ultralytics fails loudly at startup, not import.
        import torch
        from ultralytics import YOLO

        # Prefer the Apple GPU (MPS) or CUDA if present — keeps inference well
        # under the latency budget; falls back to CPU.
        self._device = (
            "mps" if torch.backends.mps.is_available()
            else "cuda" if torch.cuda.is_available()
            else "cpu"
        )
        print(f"[inference] loading model {MODEL_NAME} on {self._device} …", flush=True)
        self._model = YOLO(MODEL_NAME)
        self._model_name = MODEL_NAME
        # Warm the model with a dummy frame so the first real request is fast.
        self._model.predict(np.zeros((640, 640, 3), dtype=np.uint8), device=self._device, verbose=False)
        print("[inference] model ready", flush=True)

    def Detect(self, request: pb.DetectRequest, context) -> pb.DetectResponse:
        t0 = time.perf_counter()
        # Decode JPEG bytes -> BGR ndarray
        buf = np.frombuffer(request.jpeg, dtype=np.uint8)
        frame = cv2.imdecode(buf, cv2.IMREAD_COLOR)

        detections = []
        if frame is not None:
            results = self._model.predict(frame, device=self._device, verbose=False)
            for r in results:
                names = r.names
                for box in r.boxes:
                    x1, y1, x2, y2 = (float(v) for v in box.xyxy[0].tolist())
                    detections.append(
                        pb.Detection(
                            label=names[int(box.cls[0])],
                            confidence=float(box.conf[0]),
                            x=x1,
                            y=y1,
                            width=x2 - x1,
                            height=y2 - y1,
                        )
                    )

        return pb.DetectResponse(
            camera_id=request.camera_id,
            frame_id=request.frame_id,
            capture_ts_ms=request.capture_ts_ms,
            detections=detections,
            inference_ms=(time.perf_counter() - t0) * 1000.0,
        )

    def Health(self, request: pb.HealthRequest, context) -> pb.HealthResponse:
        return pb.HealthResponse(ready=True, model=self._model_name)


def serve() -> None:
    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=WORKERS),
        options=[
            ("grpc.max_receive_message_length", 16 * 1024 * 1024),
            ("grpc.max_send_message_length", 16 * 1024 * 1024),
        ],
    )
    pb_grpc.add_InferenceServiceServicer_to_server(InferenceService(), server)
    server.add_insecure_port(f"[::]:{PORT}")
    server.start()
    print(f"[inference] gRPC serving on :{PORT}", flush=True)
    server.wait_for_termination()


if __name__ == "__main__":
    serve()
