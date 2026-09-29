import math

import cv2
import numpy as np

from services.render.helpers.config import (
    BACKBOARD_HALF_WIDTH_CM, BACKBOARD_Y_CM, CENTER_CIRCLE_RADIUS_CM, COURT_CENTER_X_CM,
    COURT_HALF_LENGTH_CM, COURT_WIDTH_CM, DASH_LENGTH_DEG, FREE_THROW_RADIUS_CM, HOOP_RADIUS_CM,
    HOOP_Y_CM, LANE_MARK_LENGTH_CM, LANE_MARKS_Y_CM, MAKE_COLOR, MAP_ALPHA, MAP_BACKGROUND,
    MAP_LINE_COLOR, MAP_MAX_WIDTH_PX, MAP_MIN_WIDTH_PX, MAP_WIDTH_RATIO,
    MARKER_OUTLINE, MARKER_RADIUS_RATIO, MISS_COLOR, PAINT_DEPTH_CM, PAINT_LEFT_CM,
    PAINT_RIGHT_CM, RESTRICTED_RADIUS_CM, THREE_POINT_RADIUS_CM, THREE_POINT_SIDE_CM,
)


# Picks the chart width for a given video width, clamped to a readable range
def map_width_for_video(video_width: int) -> int:
    return max(MAP_MIN_WIDTH_PX, min(MAP_MAX_WIDTH_PX, int(video_width * MAP_WIDTH_RATIO)))


class CourtMap:
    """2D half-court shot chart pasted onto the video.

    The court is drawn once into `canvas`; shots are stamped onto it permanently the moment
    their ending frame is reached, so every following frame keeps showing them.
    """

    def __init__(self, width_px: int):
        self.width = width_px
        self.height = int(round(width_px * COURT_HALF_LENGTH_CM / COURT_WIDTH_CM))
        self._scale = width_px / COURT_WIDTH_CM
        self._line = max(1, int(round(width_px / 180)))
        self._marker_radius = max(4, int(round(width_px * MARKER_RADIUS_RATIO)))
        self.canvas = self._draw_court()

    # Stamps a made shot as a green circle or a missed shot as a red X at its 2D court location
    def add_shot(self, shot) -> None:
        location = self._shot_location(shot)
        if location is None:
            return
        if bool(shot.isMake):
            self._draw_make(location)
        else:
            self._draw_miss(location)

    # Blends the chart onto the frame with its top-left corner at (x, y)
    def paste(self, frame, x: int, y: int) -> None:
        h = min(self.height, frame.shape[0] - y)
        w = min(self.width, frame.shape[1] - x)
        if h <= 0 or w <= 0:
            return
        roi = frame[y:y + h, x:x + w]
        frame[y:y + h, x:x + w] = cv2.addWeighted(self.canvas[:h, :w], MAP_ALPHA, roi, 1 - MAP_ALPHA, 0)

    # ------------------------------------------------------------
    # Shot markers
    # ------------------------------------------------------------

    # Converts the shot's mapped cm coordinates to chart pixels, or None when there is no usable location
    def _shot_location(self, shot) -> tuple[int, int] | None:
        x_cm = float(getattr(shot, "mapped_x", 0.0))
        y_cm = float(getattr(shot, "mapped_y", 0.0))
        if not (math.isfinite(x_cm) and math.isfinite(y_cm)):
            return None
        if x_cm == 0.0 and y_cm == 0.0:
            return None  # Java sends (0, 0) when the court could not be mapped for this shot
        # The homography is solved from the 4 paint corners only, so far-away shooters can land
        # outside the lines. Every counted shot must still show up: snap it to the nearest court
        # edge, keeping the whole marker visible inside the chart.
        x_px, y_px = self._px(x_cm, y_cm)
        r = self._marker_radius
        return min(max(x_px, r), self.width - 1 - r), min(max(y_px, r), self.height - 1 - r)

    def _draw_make(self, center: tuple[int, int]) -> None:
        cv2.circle(self.canvas, center, self._marker_radius, MAKE_COLOR, -1, cv2.LINE_AA)
        cv2.circle(self.canvas, center, self._marker_radius, MARKER_OUTLINE, 1, cv2.LINE_AA)

    def _draw_miss(self, center: tuple[int, int]) -> None:
        x, y = center
        r = self._marker_radius
        thickness = max(2, self._line + 1)
        cv2.line(self.canvas, (x - r, y - r), (x + r, y + r), MISS_COLOR, thickness, cv2.LINE_AA)
        cv2.line(self.canvas, (x - r, y + r), (x + r, y - r), MISS_COLOR, thickness, cv2.LINE_AA)

    # ------------------------------------------------------------
    # Court template
    # ------------------------------------------------------------

    def _draw_court(self) -> np.ndarray:
        canvas = np.full((self.height, self.width, 3), MAP_BACKGROUND, dtype=np.uint8)
        self._draw_boundary(canvas)
        self._draw_paint(canvas)
        self._draw_basket(canvas)
        self._draw_three_point_line(canvas)
        self._draw_center_circle(canvas)
        return canvas

    def _draw_boundary(self, img: np.ndarray) -> None:
        cv2.rectangle(img, (0, 0), (self.width - 1, self.height - 1), MAP_LINE_COLOR, self._line)

    def _draw_paint(self, img: np.ndarray) -> None:
        cv2.rectangle(img, self._px(PAINT_LEFT_CM, 0), self._px(PAINT_RIGHT_CM, PAINT_DEPTH_CM), MAP_LINE_COLOR, self._line)

        # Free-throw circle: solid half outside the paint, dashed half inside it
        center = self._px(COURT_CENTER_X_CM, PAINT_DEPTH_CM)
        radius = self._len(FREE_THROW_RADIUS_CM)
        self._arc(img, center, radius, 0, 180)
        self._dashed_arc(img, center, radius, 180, 360)

        # Rebound lane marks on the outside of both paint edges
        for y_cm in LANE_MARKS_Y_CM:
            for edge_x_cm, direction in ((PAINT_LEFT_CM, -1), (PAINT_RIGHT_CM, 1)):
                start = self._px(edge_x_cm, y_cm)
                end = self._px(edge_x_cm + direction * LANE_MARK_LENGTH_CM, y_cm)
                cv2.line(img, start, end, MAP_LINE_COLOR, self._line)

    def _draw_basket(self, img: np.ndarray) -> None:
        # Backboard
        left = self._px(COURT_CENTER_X_CM - BACKBOARD_HALF_WIDTH_CM, BACKBOARD_Y_CM)
        right = self._px(COURT_CENTER_X_CM + BACKBOARD_HALF_WIDTH_CM, BACKBOARD_Y_CM)
        cv2.line(img, left, right, MAP_LINE_COLOR, self._line * 2)

        # Rim
        hoop = self._px(COURT_CENTER_X_CM, HOOP_Y_CM)
        cv2.circle(img, hoop, self._len(HOOP_RADIUS_CM), MAP_LINE_COLOR, self._line, cv2.LINE_AA)

        # No-charge semicircle with straight legs back to the backboard
        self._arc(img, hoop, self._len(RESTRICTED_RADIUS_CM), 0, 180)
        for side in (-1, 1):
            leg_x_cm = COURT_CENTER_X_CM + side * RESTRICTED_RADIUS_CM
            cv2.line(img, self._px(leg_x_cm, HOOP_Y_CM), self._px(leg_x_cm, BACKBOARD_Y_CM), MAP_LINE_COLOR, self._line)

    def _draw_three_point_line(self, img: np.ndarray) -> None:
        # The arc is centered on the hoop and meets the straight corner segments where
        # the radius reaches THREE_POINT_SIDE_CM from the sideline
        dx = COURT_CENTER_X_CM - THREE_POINT_SIDE_CM
        dy = math.sqrt(THREE_POINT_RADIUS_CM ** 2 - dx ** 2)
        corner_depth_cm = HOOP_Y_CM + dy
        junction_deg = math.degrees(math.atan2(dy, dx))

        hoop = self._px(COURT_CENTER_X_CM, HOOP_Y_CM)
        self._arc(img, hoop, self._len(THREE_POINT_RADIUS_CM), junction_deg, 180 - junction_deg)
        for x_cm in (THREE_POINT_SIDE_CM, COURT_WIDTH_CM - THREE_POINT_SIDE_CM):
            cv2.line(img, self._px(x_cm, 0), self._px(x_cm, corner_depth_cm), MAP_LINE_COLOR, self._line)

    def _draw_center_circle(self, img: np.ndarray) -> None:
        center = self._px(COURT_CENTER_X_CM, COURT_HALF_LENGTH_CM)
        self._arc(img, center, self._len(CENTER_CIRCLE_RADIUS_CM), 180, 360)

    # ------------------------------------------------------------
    # Drawing primitives (OpenCV angles: 0 = +x, growing clockwise on screen)
    # ------------------------------------------------------------

    def _arc(self, img: np.ndarray, center: tuple[int, int], radius: int, start_deg: float, end_deg: float) -> None:
        cv2.ellipse(img, center, (radius, radius), 0, start_deg, end_deg, MAP_LINE_COLOR, self._line, cv2.LINE_AA)

    def _dashed_arc(self, img: np.ndarray, center: tuple[int, int], radius: int, start_deg: float, end_deg: float) -> None:
        angle = start_deg
        while angle < end_deg:
            self._arc(img, center, radius, angle, min(angle + DASH_LENGTH_DEG, end_deg))
            angle += DASH_LENGTH_DEG * 2

    # Court centimeters -> chart pixels
    def _px(self, x_cm: float, y_cm: float) -> tuple[int, int]:
        return int(round(x_cm * self._scale)), int(round(y_cm * self._scale))

    def _len(self, cm: float) -> int:
        return max(1, int(round(cm * self._scale)))
