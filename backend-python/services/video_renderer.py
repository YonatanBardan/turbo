import os

import cv2

def create_scoreboard_video(video_path: str, shots: list) -> str:

    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        raise ValueError("Could not open video for rendering.")

    video_width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    video_height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = cap.get(cv2.CAP_PROP_FPS)
    if not fps or fps <= 0:
        fps = 30.0

    # Create the output file
    base_name = os.path.basename(video_path)
    name_only, extension = os.path.splitext(base_name)
    output_path = f"{name_only}_stats{extension}"
    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    out = cv2.VideoWriter(output_path, fourcc, fps, (video_width, video_height))

    # Convert Java's shot list into a fast Python dictionary: { frameIndex: isMake }
    shot_timeline = {shot.frameIndex: shot.isMake for shot in shots}

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

        # --- Draw Scoreboard ---
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

        out.write(frame)
        frame_index += 1

    cap.release()
    out.release()

    return output_path
