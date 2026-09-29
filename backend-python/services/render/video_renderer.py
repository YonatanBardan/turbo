from typing import Optional

from services.render.helpers.config import BANNER_HOLD_SECONDS, BANNER_MIN_HOLD_FRAMES, MARGIN_PX
from services.render.helpers.court_map import CourtMap, map_width_for_video
from services.render.helpers.overlays import draw_ball_state, draw_detections, draw_frame_counter
from services.render.helpers.scoreboard import Banner, BoxScore
from services.render.helpers.timeline import group_by_frame, labels_by_frame
from services.render.helpers.video_io import build_output_path, open_video, open_writer


# Renders the analyzed video with detection boxes, a live box score, the ball state and a
# 2D half-court shot chart in the top-left corner. Each shot is stamped on the chart (green
# circle = make, red X = miss) on the same frame its MAKE / MISS banner appears.
def create_scoreboard_video(video_path: str, shots: list, detections: list, player_ids: Optional[list] = None, ball_states: Optional[list] = None) -> str:

    cap, video_width, video_height, fps = open_video(video_path)
    output_path = build_output_path(video_path)
    out = open_writer(output_path, fps, video_width, video_height)

    shots_by_frame = group_by_frame(shots)
    detections_by_frame = group_by_frame(detections)
    ball_state_by_frame = labels_by_frame(ball_states)

    box_score = BoxScore(player_ids)
    banner = Banner(hold_frames=max(BANNER_MIN_HOLD_FRAMES, int(fps * BANNER_HOLD_SECONDS)))
    court_map = CourtMap(map_width_for_video(video_width))
    frame_counter_origin = (MARGIN_PX, MARGIN_PX + court_map.height + 24)

    ball_state_text = ""
    frame_index = 0

    while True:
        ret, frame = cap.read()
        if not ret:
            break

        # Shots that end on this frame: update the tally, the banner and the shot chart
        for shot in shots_by_frame.get(frame_index, []):
            banner.show(box_score.register_shot(shot), frame_index)
            court_map.add_shot(shot)

        # Ball state labels stay on screen until the next label starts
        ball_state_text = ball_state_by_frame.get(frame_index, ball_state_text)

        draw_detections(frame, detections_by_frame.get(frame_index, []))
        court_map.paste(frame, MARGIN_PX, MARGIN_PX)
        draw_frame_counter(frame, frame_index, frame_counter_origin)
        box_score.draw(frame, banner.text_at(frame_index))
        draw_ball_state(frame, ball_state_text)

        out.write(frame)
        frame_index += 1

    cap.release()
    out.release()

    return output_path
