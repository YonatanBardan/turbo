import cv2

from services.render.helpers.config import (
    BLACK, DIM_GRAY, FONT, LIGHT_GRAY, MAKE_COLOR, MARGIN_PX, MISS_COLOR, PANEL_PADDING_PX, WHITE,
)


class Banner:
    """Short-lived "P3  72%  MAKE" line shown above the box score after a shot ends."""

    def __init__(self, hold_frames: int):
        self._hold_frames = hold_frames
        self._text: str | None = None
        self._until = -1

    # Replaces the current banner and keeps it visible for `hold_frames` frames
    def show(self, text: str | None, frame_index: int) -> None:
        if not text:
            return
        self._text = text
        self._until = frame_index + self._hold_frames

    # The banner to draw on this frame, or None once it expired
    def text_at(self, frame_index: int) -> str | None:
        return self._text if frame_index <= self._until else None


class BoxScore:
    """Per-player FG / AST tally drawn in the bottom-left corner of the video."""

    def __init__(self, player_ids: list | None):
        self.ordered_ids = sorted({int(player_id) for player_id in player_ids or []})
        self.stats = {player_id: self._empty_row() for player_id in self.ordered_ids}

    # Counts the shot (and its assist, if any) and returns the banner text describing it
    def register_shot(self, shot) -> str | None:
        banner = None
        shooter = int(getattr(shot, "shooterTrackId", -1))
        is_make = bool(shot.isMake)

        if shooter >= 0:
            row = self.stats.setdefault(shooter, self._empty_row())
            row["fga"] += 1
            if is_make:
                row["fgm"] += 1
            percent = self._percent(getattr(shot, "makeProbability", 0.0))
            banner = f"P{shooter}  {percent}%  {'MAKE' if is_make else 'MISS'}"

        if bool(getattr(shot, "assist", False)):
            passer = int(getattr(shot, "passerTrackId", -1))
            if passer >= 0:
                self.stats.setdefault(passer, self._empty_row())["ast"] += 1
                if banner:
                    banner = f"{banner}  AST P{passer}"
        return banner

    # Draws the box score panel and, when present, the banner right above it
    def draw(self, frame, banner_text: str | None) -> None:
        video_height = frame.shape[0]
        scale = 0.45 if video_height < 720 else 0.55
        line_h = 18 if video_height < 720 else 22
        thickness = 1

        rows = ["ID   FG    AST"]
        for player_id in self.ordered_ids:
            row = self.stats.get(player_id, self._empty_row())
            rows.append(f"P{player_id:<3} {row['fgm']}-{row['fga']:<3} {row['ast']}")

        widths = [cv2.getTextSize(text, FONT, scale, thickness)[0][0] for text in rows]
        box_w = max(widths) + PANEL_PADDING_PX * 2
        box_h = PANEL_PADDING_PX * 2 + line_h * len(rows)
        x1, y2 = MARGIN_PX, video_height - MARGIN_PX
        y1 = y2 - box_h
        cv2.rectangle(frame, (x1, y1), (x1 + box_w, y2), BLACK, -1)
        cv2.rectangle(frame, (x1, y1), (x1 + box_w, y2), LIGHT_GRAY, 1)

        text_y = y1 + PANEL_PADDING_PX + line_h - 4
        cv2.putText(frame, rows[0], (x1 + PANEL_PADDING_PX, text_y), FONT, scale, DIM_GRAY, thickness, cv2.LINE_AA)
        for line in rows[1:]:
            text_y += line_h
            cv2.putText(frame, line, (x1 + PANEL_PADDING_PX, text_y), FONT, scale, WHITE, thickness, cv2.LINE_AA)

        if banner_text:
            self._draw_banner(frame, banner_text, x1, y1 - 8, scale + 0.1)

    @staticmethod
    def _draw_banner(frame, text: str, x1: int, y2: int, scale: float) -> None:
        text_w, text_h = cv2.getTextSize(text, FONT, scale, 2)[0]
        y1 = y2 - text_h - 12
        cv2.rectangle(frame, (x1, y1), (x1 + text_w + 16, y2), BLACK, -1)
        color = MAKE_COLOR if "MAKE" in text else MISS_COLOR
        cv2.putText(frame, text, (x1 + 8, y2 - 6), FONT, scale, color, 2, cv2.LINE_AA)

    @staticmethod
    def _empty_row() -> dict:
        return {"fgm": 0, "fga": 0, "ast": 0}

    @staticmethod
    def _percent(probability) -> int:
        return max(0, min(100, int(round(float(probability) * 100))))
