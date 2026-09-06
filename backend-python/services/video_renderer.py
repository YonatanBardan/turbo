import os
import cv2

def create_scoreboard_video(video_path: str, shots: list, detections: list) -> str:

    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        raise ValueError("Could not open video for rendering.")

    video_width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    video_height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = cap.get(cv2.CAP_PROP_FPS)
    if not fps or fps <= 0:
        fps = 30.0

    # Create the output file
    output_dir = r"C:\Users\yonat\OneDrive\Desktop\Stuff\semester 2\Projects\Code Name - Tourbo\Process\Basketball-Logic"
    os.makedirs(output_dir, exist_ok=True)
    base_name = os.path.basename(video_path)
    name_only, extension = os.path.splitext(base_name)

    output_path = os.path.join(output_dir,f"{name_only}_stats{extension}")
    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    out = cv2.VideoWriter(output_path, fourcc, fps, (video_width, video_height))

    # Convert Java's shot list into a fast Python dictionary: { frameIndex: isMake }
    shot_timeline = {shot.frameIndex: shot.isMake for shot in shots}

    # Groups detections by frameIndex for fast lookups during the video loop
    # Memory Cost of O(N) in the worst case (advantage for quicker search O(1))
    det_timeline = {}
    for d in detections:
        idx = d.frameIndex
        if idx not in det_timeline:
            det_timeline[idx] = []
        det_timeline[idx].append(d)

    # Defines detection box classes colors (3-class: ball, hoop, player)
    COLOR_MAP = {
        0: (0, 140, 255),    # Ball: Orange
        1: (0, 0, 255),      # Hoop: Red
        2: (0, 255, 255),    # Player: Yellow
    }
    CLASS_NAMES = {
        0: "Ball",
        1: "Hoop",
        2: "Player",
    }

    makes = 0
    attempts = 0
    frame_index = 0

    while True:
        ret, frame = cap.read()
        if not ret:
            break

        # Update attempts if there is a shot listed to the frame and "make" in a same manner
        if frame_index in shot_timeline:
            attempts += 1
            if shot_timeline[frame_index]:  # If isMake is True
                makes += 1

        # Draw Detection Boxes
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

                # 1. Draw the box - (frame to print on / top left point / bottom right point / color / thickness)
                cv2.rectangle(frame, (x, y), (x + w, y + h), color, 2)

                # 2. Draw the text label - (frame / label / bottom left / font / font scale / color / thickness)
                cv2.putText(frame, label, (x, max(15, y - 5)), cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)

        # Draw Scoreboard
        score_text = f"SHOTS: {makes} / {attempts}"
        font = cv2.FONT_HERSHEY_SIMPLEX
        font_scale = 1.5
        thickness = 4

        text_size = cv2.getTextSize(score_text, font, font_scale, thickness)[0]
        text_x = video_width - text_size[0] - 30
        text_y = video_height - 30

        # Black background box
        box_coords_1 = (text_x - 15, text_y - text_size[1] - 15)
        box_coords_2 = (text_x + text_size[0] + 15, text_y + 15)
        cv2.rectangle(frame, box_coords_1, box_coords_2, (0, 0, 0), -1)

        # White text
        cv2.putText(frame, score_text, (text_x, text_y), font, font_scale, (255, 255, 255), thickness)
        cv2.putText(frame, f"Frame: {frame_index}", (20, 50), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 0, 255), 2)

        out.write(frame)
        frame_index += 1

    cap.release()
    out.release()

    return output_path
