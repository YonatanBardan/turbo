import logging
import os
import time

import cv2
from ultralytics import YOLO

from services.identity_linker import IdentityLinker
from services.reid_extractor import extract as extract_reid_vectors
from services.reid_extractor import is_available as reid_is_available

logger = logging.getLogger(__name__)

_SERVICES_DIR = os.path.dirname(os.path.abspath(__file__))
TRACKER_PATH = os.path.normpath(os.path.join(_SERVICES_DIR, "..", "custom_bytetrack.yaml"))

model = YOLO("models/best-detectionV2.2-0.907-0.592_openvino_model", task="detect")

PLAYER_CLASS_ID = 2
EDGE_MARGIN = 4
MIN_ASPECT = 1.6
MAX_ASPECT = 4.5
MIN_HEIGHT = 80
CROP_WIDTH = 128
CROP_HEIGHT = 256
TOP_K_CROPS = 15
MIN_CROP_GAP_FRAMES = 15
OVERLAP_PENALTY_IOU = 0.3
REID_REFRESH_SECONDS = 2.0


def _box_track_id(box) -> int:
    if box.id is None:
        return -1
    track_id = box.id
    if hasattr(track_id, "item"):
        return int(track_id.item())
    return int(track_id)


def _iou(a, b) -> float:
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    iw, ih = max(0.0, ix2 - ix1), max(0.0, iy2 - iy1)
    inter = iw * ih
    if inter <= 0:
        return 0.0
    area_a = max(0.0, ax2 - ax1) * max(0.0, ay2 - ay1)
    area_b = max(0.0, bx2 - bx1) * max(0.0, by2 - by1)
    union = area_a + area_b - inter
    return inter / union if union > 0 else 0.0


def _is_full_body(x1, y1, x2, y2, frame_w, frame_h) -> bool:
    if x1 < EDGE_MARGIN or y1 < EDGE_MARGIN:
        return False
    if x2 > frame_w - EDGE_MARGIN or y2 > frame_h - EDGE_MARGIN:
        return False
    width = x2 - x1
    height = y2 - y1
    if width <= 0 or height < MIN_HEIGHT:
        return False
    aspect = height / width
    return MIN_ASPECT <= aspect <= MAX_ASPECT


def _crop_score(confidence, width, height, frame_w, frame_h, box_xyxy, other_player_xyxy) -> float:
    frame_area = max(1.0, float(frame_w * frame_h))
    area_norm = (width * height) / frame_area
    score = (0.5 * confidence) + (0.3 * area_norm) + 0.2
    for other in other_player_xyxy:
        iou = _iou(box_xyxy, other)
        if iou > OVERLAP_PENALTY_IOU:
            score *= (1.0 - iou)
    return score


def _keep_top_crop(gallery: dict, track_id: int, score: float, crop, frame_index: int) -> None:
    items = gallery.setdefault(track_id, [])
    close_idx = None
    for i, (_, existing_frame, _) in enumerate(items):
        if abs(frame_index - existing_frame) < MIN_CROP_GAP_FRAMES:
            close_idx = i
            break
    if close_idx is not None:
        if score > items[close_idx][0]:
            items[close_idx] = (score, frame_index, crop)
            items.sort(key=lambda item: item[0], reverse=True)
        return
    items.append((score, frame_index, crop))
    items.sort(key=lambda item: item[0], reverse=True)
    del items[TOP_K_CROPS:]


def _crop_player(frame, box: dict, frame_w: int, frame_h: int):
    x1i, y1i = max(0, int(box["x1"])), max(0, int(box["y1"]))
    x2i, y2i = min(frame_w, int(box["x2"])), min(frame_h, int(box["y2"]))
    if x2i <= x1i or y2i <= y1i:
        return None
    crop = frame[y1i:y2i, x1i:x2i]
    if crop.size == 0:
        return None
    return cv2.resize(crop, (CROP_WIDTH, CROP_HEIGHT))


def _rewrite_track_id(boxes_data: list, from_id: int, to_id: int) -> None:
    for box in boxes_data:
        if box["trackId"] == from_id:
            box["trackId"] = to_id


def _merge_galleries(gallery: dict, from_id: int, to_id: int) -> None:
    for score, frame_index, crop in gallery.pop(from_id, []):
        _keep_top_crop(gallery, to_id, score, crop, frame_index)


def _timed_extract(crops: list, stats: dict) -> list[list[float]]:
    if not crops:
        return []
    started = time.perf_counter()
    vectors = extract_reid_vectors(crops)
    stats["reid_ms"] += (time.perf_counter() - started) * 1000.0
    stats["reid_crops"] += len(crops)
    return vectors


def process_video(file_path: str) -> tuple[float, list, list]:

    cap = cv2.VideoCapture(file_path)
    if not cap.isOpened():
        raise ValueError("OpenCV could not open the video")

    fps = cap.get(cv2.CAP_PROP_FPS)
    if not fps or fps <= 0:
        fps = 30.0

    frame_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    frame_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    linker = IdentityLinker()
    reid_ready = reid_is_available()
    refresh_gap = max(1, int(fps * REID_REFRESH_SECONDS))

    boxes_data = []
    crop_gallery = {}
    last_reid_frame = {}
    frame_index = 0
    stats = {"yolo_ms": 0.0, "reid_ms": 0.0, "reid_crops": 0}
    started_at = time.perf_counter()

    while True:
        ret, frame = cap.read()
        if not ret:
            break

        yolo_started = time.perf_counter()
        results = model.track(frame, persist=True, verbose=False, tracker=TRACKER_PATH)
        stats["yolo_ms"] += (time.perf_counter() - yolo_started) * 1000.0
        result_boxes = results[0].boxes
        parsed = []

        if result_boxes is not None:
            for box in result_boxes:
                xyxy = box.xyxy[0].tolist()
                x1, y1, x2, y2 = xyxy[0], xyxy[1], xyxy[2], xyxy[3]
                track_id = linker.resolve(_box_track_id(box))
                parsed.append({
                    "x1": x1,
                    "y1": y1,
                    "x2": x2,
                    "y2": y2,
                    "width": x2 - x1,
                    "height": y2 - y1,
                    "classId": int(box.cls[0]),
                    "confidence": float(box.conf[0]),
                    "trackId": track_id,
                })

        current_ids = {
            p["trackId"]
            for p in parsed
            if p["classId"] == PLAYER_CLASS_ID and p["trackId"] >= 0
        }
        new_ids = {player_id for player_id in current_ids if player_id not in linker.known_ids}
        extracted_this_frame = set()

        if reid_ready:
            linker.observe_new_ids(new_ids, current_ids)
            linker.drop_visible_candidates(current_ids)

            pending_extract = []
            for p in parsed:
                if p["classId"] != PLAYER_CLASS_ID or not linker.needs_pending_vector(p["trackId"]):
                    continue
                if not _is_full_body(p["x1"], p["y1"], p["x2"], p["y2"], frame_w, frame_h):
                    continue
                crop = _crop_player(frame, p, frame_w, frame_h)
                if crop is None:
                    continue
                pending_extract.append((p["trackId"], crop))

            if pending_extract:
                vectors = _timed_extract([crop for _, crop in pending_extract], stats)
                for (track_id, _), vector in zip(pending_extract, vectors):
                    linker.remember_vector(track_id, vector)
                    extracted_this_frame.add(track_id)
                    last_reid_frame[track_id] = frame_index

            matches = linker.match_ready_pending()
            for new_id, canonical_id in matches.items():
                linker.alias(new_id, canonical_id)
                _rewrite_track_id(boxes_data, new_id, canonical_id)
                _rewrite_track_id(parsed, new_id, canonical_id)
                _merge_galleries(crop_gallery, new_id, canonical_id)
                last_reid_frame[canonical_id] = frame_index
                extracted_this_frame.add(canonical_id)

            linker.commit_ready_unmatched()

            for p in parsed:
                if p["classId"] != PLAYER_CLASS_ID or p["trackId"] < 0:
                    continue
                p["trackId"] = linker.resolve(p["trackId"])

        for p in parsed:
            if p["classId"] != PLAYER_CLASS_ID or p["trackId"] < 0:
                continue
            linker.mark_seen(p["trackId"], frame_index)
            if p["trackId"] not in linker.known_ids and not reid_ready:
                linker.known_ids.add(p["trackId"])

        player_xyxy = [
            (p["x1"], p["y1"], p["x2"], p["y2"])
            for p in parsed
            if p["classId"] == PLAYER_CLASS_ID and p["trackId"] >= 0
        ]

        refresh_pending = []
        for p in parsed:
            boxes_data.append({
                "frameIndex": int(frame_index),
                "classId": p["classId"],
                "confidence": p["confidence"],
                "x": float(p["x1"]),
                "y": float(p["y1"]),
                "width": float(p["width"]),
                "height": float(p["height"]),
                "trackId": p["trackId"],
            })

            if p["classId"] != PLAYER_CLASS_ID or p["trackId"] < 0:
                continue
            if not _is_full_body(p["x1"], p["y1"], p["x2"], p["y2"], frame_w, frame_h):
                continue

            box_xyxy = (p["x1"], p["y1"], p["x2"], p["y2"])
            others = [xy for xy in player_xyxy if xy != box_xyxy]
            score = _crop_score(
                p["confidence"], p["width"], p["height"], frame_w, frame_h, box_xyxy, others
            )
            resized = _crop_player(frame, p, frame_w, frame_h)
            if resized is None:
                continue
            _keep_top_crop(crop_gallery, p["trackId"], score, resized, frame_index)

            if not reid_ready or p["trackId"] in extracted_this_frame or linker.is_pending(p["trackId"]):
                continue
            needs_first = not linker.has_gallery(p["trackId"])
            last = last_reid_frame.get(p["trackId"], -refresh_gap)
            needs_refresh = (frame_index - last) >= refresh_gap
            if needs_first or needs_refresh:
                refresh_pending.append((p["trackId"], resized))

        if refresh_pending:
            vectors = _timed_extract([crop for _, crop in refresh_pending], stats)
            for (track_id, _), vector in zip(refresh_pending, vectors):
                linker.remember_vector(track_id, vector)
                last_reid_frame[track_id] = frame_index

        frame_index += 1

    cap.release()

    players = []
    all_crops = []
    slices = []
    for track_id in sorted(crop_gallery.keys()):
        crops = [crop for _, _, crop in crop_gallery[track_id]]
        start = len(all_crops)
        all_crops.extend(crops)
        slices.append((int(track_id), start, len(all_crops)))

    all_vectors = _timed_extract(all_crops, stats) if all_crops else []
    for track_id, start, end in slices:
        players.append({
            "id": track_id,
            "appearanceVectors": all_vectors[start:end],
        })

    elapsed = max(time.perf_counter() - started_at, 1e-6)
    yolo_per_frame = stats["yolo_ms"] / max(frame_index, 1)
    logger.info(
        "process_video: frames=%s yolo_ms/frame=%.1f reid_crops=%s reid_ms=%.1f process_fps=%.2f",
        frame_index,
        yolo_per_frame,
        stats["reid_crops"],
        stats["reid_ms"],
        frame_index / elapsed,
    )

    return fps, boxes_data, players
