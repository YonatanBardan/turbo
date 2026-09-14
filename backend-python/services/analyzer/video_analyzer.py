import logging
import os # Operating System - navigate and manage files and directories
import time

import cv2 # Computer Vision Library - 3D NumPy Arrays
from ultralytics import YOLO

from services.analyzer.config import MODEL_PATH, TRACKER_PATH, PLAYER_CLASS_ID, REID_REFRESH_SECONDS, EDGE_MARGIN, MIN_HEIGHT, MIN_ASPECT, MAX_ASPECT
from services.identity_linker import IdentityLinker
from services.reid_extractor import extract as extract_reid_vectors
from services.reid_extractor import is_available as reid_is_available
from services.analyzer.utils import _box_track_id, _is_full_body, _rewrite_track_id
from services.analyzer.crops import _crop_score, _crop_player, _keep_top_crop, _merge_galleries

model = YOLO(MODEL_PATH, task="detect")
logger = logging.getLogger(__name__)

# Process the video and return the fps, boxes data, and players vectors
def process_video(file_path: str) -> tuple[float, list, list]:

    cap = cv2.VideoCapture(file_path)
    if not cap.isOpened():
        raise ValueError("OpenCV could not open the video")

    fps = cap.get(cv2.CAP_PROP_FPS)
    if not fps or fps <= 0:
        fps = 30.0 # Default to 30 fps

    frame_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    frame_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    linker = IdentityLinker() # New instance of IdentityLinker
    reid_ready = reid_is_available()
    refresh_gap = max(1, int(fps * REID_REFRESH_SECONDS)) # Cooldown timer for a new crop

    boxes_data = []
    crop_gallery = {}
    last_reid_frame = {} # Last crop taken for each player
    frame_index = 0
    stats = {"yolo_ms": 0.0, "reid_ms": 0.0, "reid_crops": 0} # Time (running) tracking statistics
    started_at = time.perf_counter()

    while True:
        ret, frame = cap.read()
        if not ret:
            break

        parsed, current_ids = _detect_and_parse_frame(model,frame, TRACKER_PATH, stats, PLAYER_CLASS_ID, linker) # Detect and parse the frame

        new_ids = {player_id for player_id in current_ids if player_id not in linker.known_ids}
        extracted_this_frame = set() # Set of player ids that have been extracted this frame

        if reid_ready:
            linker.observe_new_ids(new_ids, current_ids, frame_index)  # Also creates candidates list for potential matches
            current_boxes_len = len(boxes_data)
            for new_id in new_ids:
                linker.record_start_list_index(new_id, current_boxes_len) # Record the start index for faster search
            linker.drop_visible_candidates(current_ids, frame_index) # Filter out current detected players ids
            pending_extract = _extract_pending_players(parsed, frame, frame_w, frame_h, linker, stats, extracted_this_frame, last_reid_frame, frame_index) # Extract well defined crops of players that need to be extracted

            _resolve_and_merge_matches(linker, frame_index, boxes_data, parsed, crop_gallery, last_reid_frame, extracted_this_frame)

        player_xyxy = _update_linker_and_get_boxes(parsed, frame_index, linker, reid_ready, PLAYER_CLASS_ID) # Update the Linker's memory and players boxes data

        refresh_pending = _save_refresh_pending(parsed, boxes_data, frame_index, PLAYER_CLASS_ID, 
                                                frame_w, frame_h, player_xyxy, frame, crop_gallery, 
                                                reid_ready, extracted_this_frame, linker, last_reid_frame, refresh_gap)

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


# -----------------------------
# Helper functions:
# -----------------------------

# Calculating the crops vectors and registering the time of the process
def _timed_extract(crops: list, stats: dict) -> list[list[float]]:
    if not crops:
        return []
    started = time.perf_counter() # Start the timer
    vectors = extract_reid_vectors(crops) # Extract ReID Vectors
    stats["reid_ms"] += (time.perf_counter() - started) * 1000.0 # close timer
    stats["reid_crops"] += len(crops)
    return vectors

# Frame model processing and parsing into a list
def _detect_and_parse_frame(model: YOLO, frame: object, tracker_path: str, stats: dict, player_class_id: int, linker: IdentityLinker) -> tuple[list, set]:
    # Start the clock and run the model
    yolo_started = time.perf_counter()
    results = model.track(frame, persist=True, verbose=False, tracker=tracker_path)
    
    # Stop the clock and save the time to the dictionary
    stats["yolo_ms"] += (time.perf_counter() - yolo_started) * 1000.0
    
    # Extract the raw data
    result_boxes = results[0].boxes
    parsed = _result_boxes_to_parsed(result_boxes, linker)
    
    # Currently on the court
    current_ids = {
        p["trackId"]
        for p in parsed
        if p["classId"] == player_class_id and p["trackId"] >= 0
    }
    
    return parsed, current_ids

# Convert the result boxes to parsed list
def _result_boxes_to_parsed(result_boxes: list, linker: IdentityLinker) -> list:
    parsed = []
    if result_boxes is not None:
        for box in result_boxes:
            xyxy = box.xyxy[0].tolist() # Convert the bounding box tensor to a list
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
        return parsed
    return []


def _extract_pending_players(parsed: list, frame: object, frame_w: int, frame_h: int, linker: IdentityLinker, stats: dict, extracted_this_frame: set, last_reid_frame: dict, frame_index: int) -> list:
    if not parsed:
        return []

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
        vectors = _timed_extract([crop for _, crop in pending_extract], stats) # Extract ReID Vectors with a batch
        for (track_id, _), vector in zip(pending_extract, vectors): # Associate the vector with the player id
            linker.remember_vector(track_id, vector)
            extracted_this_frame.add(track_id)
            last_reid_frame[track_id] = frame_index  # Update the last frame a player's crop was taken

    return pending_extract


def _resolve_and_merge_matches(linker: IdentityLinker, frame_index: int, boxes_data: list, parsed: list, crop_gallery: dict, last_reid_frame: dict, extracted_this_frame: set) -> None:
    matches = linker.match_ready_pending(frame_index)
    for new_id, canonical_id in matches.items():
        linker.alias(new_id, canonical_id)
        
        # rewrite track id and merge galleries
        # rewrite begins from the start list index and parsed begins from 0
        start_list_index = linker.get_start_list_index(new_id)
        _rewrite_track_id(boxes_data, new_id, canonical_id, start_list_index)
        _rewrite_track_id(parsed, new_id, canonical_id, start_list_index=0)

        _merge_galleries(crop_gallery, new_id, canonical_id)
        last_reid_frame[canonical_id] = frame_index
        extracted_this_frame.add(canonical_id)

    linker.commit_ready_unmatched()

    for p in parsed:
        if p["classId"] != PLAYER_CLASS_ID or p["trackId"] < 0:
            continue
        p["trackId"] = linker.resolve(p["trackId"])


def _update_linker_and_get_boxes(parsed: list, frame_index: int, linker: IdentityLinker, reid_ready: bool, player_class_id: int) -> list:
    player_xyxy = []

    for p in parsed:
        # 1. Filter out non-players or invalid IDs
        if p["classId"] != player_class_id or p["trackId"] < 0:
            continue
            
        # 2. Update the Linker's memory
        linker.mark_seen(p["trackId"], frame_index)
        
        # 3. Fallback register if the AI engine is turned off
        if not reid_ready and p["trackId"] not in linker.known_ids:
            linker.known_ids.add(p["trackId"])

        # 4. Save the spatial coordinates for the Photography Critic later
        player_xyxy.append((p["x1"], p["y1"], p["x2"], p["y2"]))
        
    return player_xyxy


def _save_refresh_pending(
    parsed: list,
    boxes_data: list,
    frame_index: int,
    PLAYER_CLASS_ID: int,
    frame_w: int,
    frame_h: int,
    player_xyxy: list,
    frame: object,
    crop_gallery: dict,
    reid_ready: bool,
    extracted_this_frame: set,
    linker: IdentityLinker,
    last_reid_frame: dict,
    refresh_gap: int
) -> list:
    refresh_pending = []
    
    for p in parsed:
        # Append the box data to the boxes_data list
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

        # Filter out bad boxes
        if p["classId"] != PLAYER_CLASS_ID or p["trackId"] < 0:
            continue
        if not _is_full_body(p["x1"], p["y1"], p["x2"], p["y2"], frame_w, frame_h):
            continue

        # Grade the photo quality
        box_xyxy = (p["x1"], p["y1"], p["x2"], p["y2"])
        others = [xy for xy in player_xyxy if xy != box_xyxy]
        score = _crop_score(
            p["confidence"], p["width"], p["height"], frame_w, frame_h, box_xyxy, others
        )
        
        # Take the photo and update the gallery
        resized = _crop_player(frame, p, frame_w, frame_h)
        if resized is None:
            continue
        _keep_top_crop(crop_gallery, p["trackId"], score, resized, frame_index)

        # Check the Cooldown Timer
        if not reid_ready or p["trackId"] in extracted_this_frame or linker.is_pending(p["trackId"]):
            continue
            
        needs_first = not linker.has_gallery(p["trackId"])
        last = last_reid_frame.get(p["trackId"], -refresh_gap)
        needs_refresh = (frame_index - last) >= refresh_gap
        
        if needs_first or needs_refresh:
            refresh_pending.append((p["trackId"], resized))
            
    # ReturnS the list of platers qualify for refresh
    return refresh_pending
