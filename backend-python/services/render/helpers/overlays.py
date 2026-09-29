import cv2

from services.analyzer.config import PLAYER_CLASS_ID
from services.render.helpers.config import (
    BLACK, CLASS_COLORS, CLASS_NAMES, DETECTION_BOX_THICKNESS, FONT, LIGHT_GRAY, MARGIN_PX,
    MISS_COLOR, PANEL_PADDING_PX, SOFT_BLUE, UNKNOWN_CLASS_COLOR, WHITE,
)


# Draws every detection box of the frame with its class / player label
def draw_detections(frame, detections: list) -> None:
    for detection in detections:
        x, y = int(detection.x), int(detection.y)
        w, h = int(detection.width), int(detection.height)
        class_id = int(detection.classId)
        color = CLASS_COLORS.get(class_id, UNKNOWN_CLASS_COLOR)

        cv2.rectangle(frame, (x, y), (x + w, y + h), color, DETECTION_BOX_THICKNESS)
        cv2.putText(frame, _detection_label(detection, class_id), (x, max(15, y - 5)), FONT, 0.6, color, 2)


# "P7 0.91" for tracked players, "Ball 0.88" style for everything else
def _detection_label(detection, class_id: int) -> str:
    confidence = float(detection.confidence)
    track_id = getattr(detection, "trackId", -1)
    if class_id == PLAYER_CLASS_ID and track_id is not None and int(track_id) >= 0:
        return f"P{int(track_id)} {confidence:.2f}"
    return f"{CLASS_NAMES.get(class_id, 'Unknown')} {confidence:.2f}"


# Draws the multi-line ball state panel in the top-right corner
def draw_ball_state(frame, text: str) -> None:
    lines = [line for line in (text or "").split("\n") if line]
    if not lines:
        return

    video_height, video_width = frame.shape[:2]
    scale = 0.5 if video_height < 720 else 0.6
    thickness = 1
    gap = 6

    sizes = [cv2.getTextSize(line, FONT, scale, thickness)[0] for line in lines]
    line_h = max(size[1] for size in sizes) + gap
    box_w = max(size[0] for size in sizes) + PANEL_PADDING_PX * 2
    box_h = PANEL_PADDING_PX * 2 + line_h * len(lines)
    x2 = video_width - MARGIN_PX
    x1 = max(MARGIN_PX, x2 - box_w)
    y1 = MARGIN_PX
    y2 = y1 + box_h
    cv2.rectangle(frame, (x1, y1), (x2, y2), BLACK, -1)
    cv2.rectangle(frame, (x1, y1), (x2, y2), LIGHT_GRAY, 1)

    text_y = y1 + PANEL_PADDING_PX + sizes[0][1]
    for index, line in enumerate(lines):
        color = WHITE if index == 0 else SOFT_BLUE
        cv2.putText(frame, line, (x1 + PANEL_PADDING_PX, text_y), FONT, scale, color, thickness, cv2.LINE_AA)
        text_y += line_h


# Draws "Frame: N" at the given text origin
def draw_frame_counter(frame, frame_index: int, origin: tuple[int, int]) -> None:
    cv2.putText(frame, f"Frame: {frame_index}", origin, FONT, 0.6, MISS_COLOR, 2)
