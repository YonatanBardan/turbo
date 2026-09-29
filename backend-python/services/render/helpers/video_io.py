import os

import cv2

from services.render.helpers.config import DEFAULT_FPS, OUTPUT_DIR, OUTPUT_SUFFIX, VIDEO_CODEC


# Opens the source video and returns the capture with its width, height and fps
def open_video(video_path: str) -> tuple[cv2.VideoCapture, int, int, float]:
    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        raise ValueError("Could not open video for rendering.")

    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = cap.get(cv2.CAP_PROP_FPS)
    if not fps or fps <= 0:
        fps = DEFAULT_FPS
    return cap, width, height, fps


# Builds "<output dir>/<video name>_stats<ext>" and makes sure the folder exists
def build_output_path(video_path: str) -> str:
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    name_only, extension = os.path.splitext(os.path.basename(video_path))
    return os.path.join(OUTPUT_DIR, f"{name_only}{OUTPUT_SUFFIX}{extension}")


# Creates the writer for the rendered video
def open_writer(output_path: str, fps: float, width: int, height: int) -> cv2.VideoWriter:
    fourcc = cv2.VideoWriter_fourcc(*VIDEO_CODEC)
    return cv2.VideoWriter(output_path, fourcc, fps, (width, height))
