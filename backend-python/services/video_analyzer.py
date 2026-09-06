import os

import cv2
from ultralytics import YOLO

from services.identity_linker import IdentityLinker
from services.reid_extractor import extract as extract_reid_vectors
from services.reid_extractor import is_available as reid_is_available

_SERVICES_DIR = os.path.dirname(os.path.abspath(__file__))
TRACKER_PATH = os.path.normpath(os.path.join(_SERVICES_DIR, "..", "custom_bytetrack.yaml"))

model = YOLO("models/best-yolo26l-version2.1-0.89-0.59_openvino_model", task="detect")

PLAYER_CLASS_ID = 2
EDGE_MARGIN = 4
MIN_ASPECT = 1.6
MAX_ASPECT = 4.5
MIN_HEIGHT = 80
CROP_WIDTH = 128
CROP_HEIGHT = 256
TOP_K_CROPS = 5
OVERLAP_PENALTY_IOU = 0.3


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


def _keep_top_crop(gallery: dict, track_id: int, score: float, crop) -> None:
    items = gallery.setdefault(track_id, [])
    items.append((score, crop))
    items.sort(key=lambda item: item[0], reverse=True)
    del items[TOP_K_CROPS:]


def _center(box: dict) -> tuple[float, float]:
    return (box["x1"] + box["x2"]) / 2.0, (box["y1"] + box["y2"]) / 2.0


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
    for score, crop in gallery.pop(from_id, []):
        _keep_top_crop(gallery, to_id, score, crop)


def process_video(file_path: str) -> tuple[float, list, list]:

    cap = cv2.VideoCapture(file_path)
    if not cap.isOpened():
        raise ValueError("OpenCV could not open the video")

    fps = cap.get(cv2.CAP_PROP_FPS)
    if not fps or fps <= 0:
        fps = 30.0

    frame_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    frame_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    linker = IdentityLinker(frame_w)
    reid_ready = reid_is_available()

    boxes_data = []
    crop_gallery = {}
    frame_index = 0

    while True:
        ret, frame = cap.read()
        if not ret:
            break

        results = model.track(frame, persist=True, verbose=False, tracker=TRACKER_PATH)
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

        if reid_ready and new_ids:
            missing_ids = linker.missing_ids(current_ids)
            candidates = []
            candidate_vectors = {}
            for p in parsed:
                if p["classId"] != PLAYER_CLASS_ID or p["trackId"] not in new_ids:
                    continue
                if not _is_full_body(p["x1"], p["y1"], p["x2"], p["y2"], frame_w, frame_h):
                    continue
                crop = _crop_player(frame, p, frame_w, frame_h)
                if crop is None:
                    continue
                vectors = extract_reid_vectors([crop])
                if not vectors:
                    continue
                candidate_vectors[p["trackId"]] = vectors[0]
                candidates.append((p["trackId"], vectors[0], _center(p)))

            matches = linker.match_new_to_missing(candidates, missing_ids)
            for new_id, canonical_id in matches.items():
                linker.alias(new_id, canonical_id)
                _rewrite_track_id(boxes_data, new_id, canonical_id)
                _rewrite_track_id(parsed, new_id, canonical_id)
                _merge_galleries(crop_gallery, new_id, canonical_id)
                vector = candidate_vectors.get(new_id)
                if vector:
                    linker.remember_vector(canonical_id, vector)

            for p in parsed:
                if p["classId"] != PLAYER_CLASS_ID or p["trackId"] < 0:
                    continue
                p["trackId"] = linker.resolve(p["trackId"])

            for new_id in new_ids - set(matches.keys()):
                vector = candidate_vectors.get(new_id)
                if vector:
                    linker.remember_vector(new_id, vector)

        for p in parsed:
            if p["classId"] != PLAYER_CLASS_ID or p["trackId"] < 0:
                continue
            linker.mark_seen(p["trackId"], _center(p), frame_index)
            if p["trackId"] not in linker.known_ids and not reid_ready:
                linker.known_ids.add(p["trackId"])

        player_xyxy = [
            (p["x1"], p["y1"], p["x2"], p["y2"])
            for p in parsed
            if p["classId"] == PLAYER_CLASS_ID and p["trackId"] >= 0
        ]

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
            _keep_top_crop(crop_gallery, p["trackId"], score, resized)

            if reid_ready and p["trackId"] not in linker.mean_vectors:
                vectors = extract_reid_vectors([resized])
                if vectors:
                    linker.remember_vector(p["trackId"], vectors[0])

        frame_index += 1

    cap.release()

    players = []
    for track_id in sorted(crop_gallery.keys()):
        crops = [crop for _, crop in crop_gallery[track_id]]
        players.append({
            "id": int(track_id),
            "appearanceVectors": extract_reid_vectors(crops),
        })

    return fps, boxes_data, players
