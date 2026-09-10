import logging
import os
import time

import cv2
from ultralytics import YOLO

from services.identity_linker import IdentityLinker
from services.reid_extractor import extract as extract_reid_vectors
from services.reid_extractor import is_available as reid_is_available

logger = logging.getLogger(__name__)

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
            linker.observe_new_ids(new_ids, current_ids, frame_index)
            
            # record start list index for new ids
            current_boxes_len = len(boxes_data)
            for new_id in new_ids:
                linker.record_list_index(new_id, current_boxes_len)

            linker.drop_visible_candidates(current_ids, frame_index)

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

            matches = linker.match_ready_pending(frame_index)
            for new_id, canonical_id in matches.items():
                linker.alias(new_id, canonical_id)
                
                # rewrite track id and merge galleries
                # rewrite begins from the start list index and parsed begins from 0
                start_list_index = linker.get_start_list_index(new_id)
                _rewrite_track_id(boxes_data, new_id, canonical_id, start_list_index)
                _rewrite_track_id(parsed, new_id, canonical_id, start_frame=0)

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
