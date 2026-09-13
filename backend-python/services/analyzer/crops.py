import cv2
from .config import IDEAL_NORM, CURVE, OVERLAP_PENALTY_IOU, CROP_WIDTH, CROP_HEIGHT, MIN_CROP_GAP_FRAMES, TOP_K_CROPS
from services.analyzer.utils import _iou
# --- crop player from frame for ReID calculations ---

# calculates the score of the bounding box
def _crop_score(confidence, width, height, frame_w, frame_h, box_xyxy, other_player_xyxy) -> float:
    frame_area = max(1.0, float(frame_w * frame_h))
    box_norm = (width * height) / frame_area             # calculates the relation between bounding box and total frame area
    
    # calculates the score of the bounding box based on ideal norm of 15%
    box_score = 1.0 - (CURVE * ((IDEAL_NORM - box_norm)**2))
    box_score = max(0.0, box_score)
    score = (0.5 * confidence) + (0.5 * box_score)
    
    for other in other_player_xyxy:
        iou = _iou(box_xyxy, other)
        if iou > OVERLAP_PENALTY_IOU:
            score *= (1.0 - iou)
    return score

# keeps the top crops for the gallery
def _keep_top_crop(gallery: dict, track_id: int, score: float, crop, frame_index: int) -> None:
    items = gallery.setdefault(track_id, [])           # pull the current crops for the id or default to empty list
    
    # checks if the crop frmaeindex is bellow the limit ans save the index if it is
    close_idx = None
    for i, (_, existing_frame, _) in enumerate(items):
        if abs(frame_index - existing_frame) < MIN_CROP_GAP_FRAMES:
            close_idx = i
            break
    # if bellow the limit, overwrite the object in the index if the score is higher
    if close_idx is not None:
        if score > items[close_idx][0]:
            items[close_idx] = (score, frame_index, crop)
            items.sort(key=lambda item: item[0], reverse=True)   # sort the items by score using lambda function - higher score first
        return
    # if above the limit, add the crop to the list and sort the list by score - higher score first
    items.append((score, frame_index, crop))
    items.sort(key=lambda item: item[0], reverse=True)
    del items[TOP_K_CROPS:]


def _crop_player(frame, box: dict, frame_w: int, frame_h: int):
    x1i, y1i = max(0, int(box["x1"])), max(0, int(box["y1"]))
    x2i, y2i = min(frame_w, int(box["x2"])), min(frame_h, int(box["y2"]))
    if x2i <= x1i or y2i <= y1i:
        return None
    crop = frame[y1i:y2i, x1i:x2i]   # crop the frame to the bounding box using
    if crop.size == 0:
        return None
    return cv2.resize(crop, (CROP_WIDTH, CROP_HEIGHT)) # resize the crop to the desired width and height - by split and merge pixels

# merges the galleries of the two players
def _merge_galleries(gallery: dict, from_id: int, to_id: int) -> None:
    for score, frame_index, crop in gallery.pop(from_id, []):
        _keep_top_crop(gallery, to_id, score, crop, frame_index)


