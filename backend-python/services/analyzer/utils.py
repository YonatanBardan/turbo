from config import EDGE_MARGIN, MIN_HEIGHT, MIN_ASPECT, MAX_ASPECT

# get track id from box / out of tensor
def _box_track_id(box) -> int:
    if box.id is None:
        return -1
    track_id = box.id
    # check if track_id is a tensor
    if hasattr(track_id, "item"):
        return int(track_id.item())
    return int(track_id)

# intersection over union ratio calculation
def _iou(a, b) -> float:
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    #intersection width and height
    iw, ih = max(0.0, ix2 - ix1), max(0.0, iy2 - iy1)
    inter = iw * ih
    if inter <= 0:
        return 0.0
    area_a = max(0.0, ax2 - ax1) * max(0.0, ay2 - ay1)
    area_b = max(0.0, bx2 - bx1) * max(0.0, by2 - by1)
    union = area_a + area_b - inter
    return inter / union if union > 0 else 0.0


def _is_full_body(x1, y1, x2, y2, frame_w, frame_h) -> bool:
    # rejects boxes too close to the edges
    if x1 < EDGE_MARGIN or y1 < EDGE_MARGIN:
        return False
    if x2 > frame_w - EDGE_MARGIN or y2 > frame_h - EDGE_MARGIN:
        return False

    # rejects boxes too small or too tall
    width = x2 - x1
    height = y2 - y1
    if width <= 0 or height < MIN_HEIGHT:
        return False
    aspect = height / width
    return MIN_ASPECT <= aspect <= MAX_ASPECT

# rewrite track id from from_id to to_id starting from start_list_index#
def _rewrite_track_id(boxes_data: list, from_id: int, to_id: int, start_list_index: int) -> None:
    for box in boxes_data[start_list_index]:
        if box["trackId"] == from_id:
            box["trackId"] = to_id
