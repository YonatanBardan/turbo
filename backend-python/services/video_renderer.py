import os
import cv2

from typing import Optional

def _player_ids_from_payload(shots: list, player_ids: Optional[list] = None) -> list:
    ids = set()
    if player_ids:
        for player_id in player_ids:
            ids.add(int(player_id))
    for shot in shots:
        shooter = int(getattr(shot, "shooterTrackId", -1))
        passer = int(getattr(shot, "passerTrackId", -1))
        if shooter >= 0:
            ids.add(shooter)
        if passer >= 0:
            ids.add(passer)
    return sorted(ids)


def _apply_shots(stats: dict, shots_at_frame: list) -> str | None:
    banner = None
    for shot in shots_at_frame:
        shooter = int(getattr(shot, "shooterTrackId", -1))
        is_make = bool(shot.isMake)
        probability = float(getattr(shot, "makeProbability", 0.0))
        percent = max(0, min(100, int(round(probability * 100))))
        if shooter >= 0:
            row = stats.setdefault(shooter, {"fgm": 0, "fga": 0, "ast": 0})
            row["fga"] += 1
            if is_make:
                row["fgm"] += 1
            result = "MAKE" if is_make else "MISS"
            banner = f"P{shooter}  {percent}%  {result}"
        if bool(getattr(shot, "assist", False)):
            passer = int(getattr(shot, "passerTrackId", -1))
            if passer >= 0:
                row = stats.setdefault(passer, {"fgm": 0, "fga": 0, "ast": 0})
                row["ast"] += 1
                if banner:
                    banner = f"{banner}  AST P{passer}"
    return banner


def _draw_box_score(frame, stats: dict, ordered_ids: list[int], banner: str | None, video_height: int):
    font = cv2.FONT_HERSHEY_SIMPLEX
    scale = 0.45 if video_height < 720 else 0.55
    thickness = 1
    line_h = 18 if video_height < 720 else 22
    pad = 8
    header = "ID   FG    AST"
    rows = [header]
    for player_id in ordered_ids:
        row = stats.get(player_id, {"fgm": 0, "fga": 0, "ast": 0})
        rows.append(f"P{player_id:<3} {row['fgm']}-{row['fga']:<3} {row['ast']}")

    widths = [cv2.getTextSize(text, font, scale, thickness)[0][0] for text in rows]
    box_w = max(widths) + pad * 2 if widths else 120
    box_h = pad * 2 + line_h * len(rows)
    x1, y2 = 16, frame.shape[0] - 16
    y1 = y2 - box_h
    cv2.rectangle(frame, (x1, y1), (x1 + box_w, y2), (0, 0, 0), -1)
    cv2.rectangle(frame, (x1, y1), (x1 + box_w, y2), (220, 220, 220), 1)

    text_y = y1 + pad + line_h - 4
    cv2.putText(frame, rows[0], (x1 + pad, text_y), font, scale, (180, 180, 180), thickness, cv2.LINE_AA)
    text_y += line_h
    for line in rows[1:]:
        cv2.putText(frame, line, (x1 + pad, text_y), font, scale, (255, 255, 255), thickness, cv2.LINE_AA)
        text_y += line_h

    if banner:
        b_scale = scale + 0.1
        b_size = cv2.getTextSize(banner, font, b_scale, 2)[0]
        bx1, by2 = x1, y1 - 8
        by1 = by2 - b_size[1] - 12
        cv2.rectangle(frame, (bx1, by1), (bx1 + b_size[0] + 16, by2), (0, 0, 0), -1)
        color = (0, 220, 0) if "MAKE" in banner else (0, 80, 255)
        cv2.putText(frame, banner, (bx1 + 8, by2 - 6), font, b_scale, color, 2, cv2.LINE_AA)


def _draw_ball_state(frame, text: str, video_width: int) -> None:
    if not text:
        return
    lines = [line for line in text.split("\n") if line]
    if not lines:
        return
    font = cv2.FONT_HERSHEY_SIMPLEX
    scale = 0.5 if frame.shape[0] < 720 else 0.6
    thickness = 1
    pad = 8
    gap = 6
    sizes = [cv2.getTextSize(line, font, scale, thickness)[0] for line in lines]
    line_h = max(size[1] for size in sizes) + gap
    box_w = max(size[0] for size in sizes) + pad * 2
    box_h = pad * 2 + line_h * len(lines)
    x2 = video_width - 16
    x1 = max(16, x2 - box_w)
    y1 = 16
    y2 = y1 + box_h
    cv2.rectangle(frame, (x1, y1), (x2, y2), (0, 0, 0), -1)
    cv2.rectangle(frame, (x1, y1), (x2, y2), (220, 220, 220), 1)
    text_y = y1 + pad + sizes[0][1]
    for index, line in enumerate(lines):
        color = (255, 255, 255) if index == 0 else (180, 220, 255)
        cv2.putText(frame, line, (x1 + pad, text_y), font, scale, color, thickness, cv2.LINE_AA)
        text_y += line_h


def create_scoreboard_video(video_path: str, shots: list, detections: list, player_ids: Optional[list] = None, ball_states: Optional[list] = None) -> str:

    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        raise ValueError("Could not open video for rendering.")

    video_width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    video_height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = cap.get(cv2.CAP_PROP_FPS)
    if not fps or fps <= 0:
        fps = 30.0

    output_dir = r"C:\Users\yonat\OneDrive\Desktop\Projects\Code Name - Tourbo\Process\Basketball-Logic"
    os.makedirs(output_dir, exist_ok=True)
    base_name = os.path.basename(video_path)
    name_only, extension = os.path.splitext(base_name)

    output_path = os.path.join(output_dir, f"{name_only}_stats{extension}")
    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    out = cv2.VideoWriter(output_path, fourcc, fps, (video_width, video_height))

    shots_by_frame = {}
    for shot in shots:
        shots_by_frame.setdefault(int(shot.frameIndex), []).append(shot)

    ordered_ids = _player_ids_from_payload(shots, player_ids)
    stats = {player_id: {"fgm": 0, "fga": 0, "ast": 0} for player_id in ordered_ids}

    det_timeline = {}
    for d in detections:
        idx = d.frameIndex
        det_timeline.setdefault(idx, []).append(d)

    COLOR_MAP = {
        0: (0, 140, 255),
        1: (0, 0, 255),
        2: (0, 255, 255),
    }
    CLASS_NAMES = {
        0: "Ball",
        1: "Hoop",
        2: "Player",
    }

    states_by_frame = {}
    for state in ball_states or []:
        states_by_frame[int(state.frameIndex)] = state.label

    banner = None
    banner_until = -1
    banner_hold = max(12, int(fps * 0.6))
    ball_state_text = ""
    frame_index = 0

    while True:
        ret, frame = cap.read()
        if not ret:
            break

        if frame_index in shots_by_frame:
            banner = _apply_shots(stats, shots_by_frame[frame_index])
            banner_until = frame_index + banner_hold

        if frame_index in det_timeline:
            for d in det_timeline[frame_index]:
                x = int(d.x)
                y = int(d.y)
                w = int(d.width)
                h = int(d.height)

                classId = int(d.classId)
                className = CLASS_NAMES.get(classId, "Unknown")
                color = COLOR_MAP.get(classId, (0, 255, 0))
                conf = float(d.confidence)
                track_id = getattr(d, "trackId", -1)
                if classId == 2 and track_id is not None and int(track_id) >= 0:
                    label = f"P{int(track_id)} {conf:.2f}"
                else:
                    label = f"{className} {conf:.2f}"

                cv2.rectangle(frame, (x, y), (x + w, y + h), color, 2)
                cv2.putText(frame, label, (x, max(15, y - 5)), cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)

        if frame_index in states_by_frame:
            ball_state_text = states_by_frame[frame_index]
        live_banner = banner if frame_index <= banner_until else None
        _draw_box_score(frame, stats, ordered_ids, live_banner, video_height)
        _draw_ball_state(frame, ball_state_text, video_width)
        cv2.putText(frame, f"Frame: {frame_index}", (16, 28), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 255), 2)

        out.write(frame)
        frame_index += 1

    cap.release()
    out.release()

    return output_path
