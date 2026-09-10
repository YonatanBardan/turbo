# crop player from frame for ReID calculations

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

    
def _merge_galleries(gallery: dict, from_id: int, to_id: int) -> None:
    for score, frame_index, crop in gallery.pop(from_id, []):
        _keep_top_crop(gallery, to_id, score, crop, frame_index)


