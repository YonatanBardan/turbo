import logging
import math

import numpy as np

from services.analyzer.config import EDGE_MARGIN, MAX_ASPECT, MIN_HEIGHT
from services.analyzer.utils import _iou
from services.identifications.reid_extractor import cosine_similarity, update_appearance

logger = logging.getLogger(__name__)

COSINE_THRESHOLD = 0.78
OCCLUSION_COSINE_THRESHOLD = 0.65
NEARBY_BODY_HEIGHTS = 2.0
MIN_MATCH_VECTORS = 15
EMA_ALPHA = 0.9
EMA_MIN_CONFIDENCE = 0.85
MIN_CROP_ASPECT = 1.5
OVERLAP_IOU_LIMIT = 0.10
MIN_CLEAN_FRAMES = 3


def _print_reid(message: str) -> None:
    print(message, flush=True)
    logger.info("%s", message)


class IdentityLinker:
    """Map a new ByteTrack id onto a missing player's original id using ReID."""

    def __init__(self):
        self.id_alias: dict[int, int] = {}
        self.known_ids: set[int] = set()
        self.ema: dict[int, np.ndarray] = {}
        self.ema_updates: dict[int, int] = {}
        self.last_seen_frame: dict[int, int] = {}
        self.first_seen_frame: dict[int, int] = {}
        self.start_list_index: dict[int, int] = {}
        self.pending: dict[int, set[int]] = {}
        self.clean_frame_count: dict[int, int] = {}
        self.last_clean_frame: dict[int, int] = {}
        self.last_box: dict[int, tuple] = {}
        self.occlusion_ids: set[int] = set() 

    def get_first_seen_frame(self, track_id: int) -> int:
        return self.first_seen_frame.get(track_id, -1)

    def record_start_list_index(self, track_id: int, index: int) -> None:
        if track_id not in self.start_list_index:
            self.start_list_index[track_id] = index

    def get_start_list_index(self, track_id: int) -> int:
        return self.start_list_index.get(track_id, 0)

    def resolve(self, track_id: int) -> int:
        if track_id < 0:
            return track_id
        current = track_id
        seen = set()
        while current in self.id_alias:
            if current in seen:
                break
            seen.add(current)
            current = self.id_alias[current]
        return current

    def missing_ids(self, current_ids: set[int]) -> set[int]:
        return {player_id for player_id in self.known_ids if player_id not in current_ids}

    def is_pending(self, track_id: int) -> bool:
        return track_id in self.pending

    def needs_pending_vector(self, track_id: int) -> bool:
        return track_id in self.pending and self.gallery_size(track_id) < MIN_MATCH_VECTORS

    def gallery_size(self, track_id: int) -> int:
        return self.ema_updates.get(track_id, 0)

    def has_gallery(self, track_id: int) -> bool:
        return track_id in self.ema

    def _record_first_seen(self, track_id: int, frame_index: int) -> None:
        if track_id < 0 or track_id in self.first_seen_frame:
            return
        self.first_seen_frame[track_id] = frame_index

    def _first_seen_label(self, track_id: int) -> str:
        frame = self.first_seen_frame.get(track_id)
        return str(frame) if frame is not None else "unknown"

    def mark_seen(self, track_id: int, frame_index: int) -> None:
        if track_id < 0:
            return
        self._record_first_seen(track_id, frame_index)
        self.last_seen_frame[track_id] = frame_index

    def observe_crop_frame(self, players: list, frame_index: int, frame_w: int, frame_h: int) -> None:
        verdict = {}
        for index, player in enumerate(players):
            track_id = player.get("trackId", -1)
            if track_id < 0:
                continue
            others = players[:index] + players[index + 1:]
            clean = self._crop_is_acceptable(player, others, frame_w, frame_h)
            if track_id in verdict:
                verdict[track_id] = verdict[track_id] and clean
            else:
                verdict[track_id] = clean
        for track_id, clean in verdict.items():
            self._mark_clean(track_id, frame_index, clean)

    def accepted_this_frame(self, track_id: int, frame_index: int) -> bool:
        return self.last_clean_frame.get(track_id) == frame_index

    def _crop_is_acceptable(self, player: dict, others: list, frame_w: int, frame_h: int) -> bool:
        confidence = float(player.get("confidence") or 0.0)
        width = float(player.get("width") or 0.0)
        height = float(player.get("height") or 0.0)
        if width <= 0 or height < MIN_HEIGHT or confidence < EMA_MIN_CONFIDENCE:
            return False
        aspect = height / width
        if aspect < MIN_CROP_ASPECT or aspect > MAX_ASPECT:
            return False
        x1 = float(player["x1"])
        y1 = float(player["y1"])
        x2 = float(player["x2"])
        y2 = float(player["y2"])
        if x1 < EDGE_MARGIN or y1 < EDGE_MARGIN or x2 > frame_w - EDGE_MARGIN or y2 > frame_h - EDGE_MARGIN:
            return False
        box = (x1, y1, x2, y2)
        for other in others:
            if other.get("trackId", -1) < 0:
                continue
            other_box = (float(other["x1"]), float(other["y1"]), float(other["x2"]), float(other["y2"]))
            if _iou(box, other_box) > OVERLAP_IOU_LIMIT:
                return False
        return True

    def _mark_clean(self, track_id: int, frame_index: int, clean: bool) -> None:
        if not clean:
            self.clean_frame_count[track_id] = 0
            self.last_clean_frame.pop(track_id, None)
            return
        if self.last_clean_frame.get(track_id) == frame_index:
            return
        previous = self.last_clean_frame.get(track_id)
        if previous is not None and frame_index == previous + 1:
            self.clean_frame_count[track_id] = self.clean_frame_count.get(track_id, 0) + 1
        else:
            self.clean_frame_count[track_id] = 1
        self.last_clean_frame[track_id] = frame_index

    def remember_vector(self, track_id: int, vector: list[float], confidence: float | None = None) -> None:
        if track_id < 0 or not vector:
            return
        if confidence is not None and confidence < EMA_MIN_CONFIDENCE:
            return
        new = np.asarray(vector, dtype=np.float32).reshape(-1)
        norm = float(np.linalg.norm(new))
        if norm <= 0.0:
            return
        new = new / norm
        existing = self.ema.get(track_id)
        if existing is None:
            self.ema[track_id] = new
        else:
            self.ema[track_id] = update_appearance(existing, new, EMA_ALPHA)
        self.ema_updates[track_id] = self.ema_updates.get(track_id, 0) + 1
        if track_id not in self.pending:
            self.known_ids.add(track_id)

    def remember_positions(self, players: list) -> None:
        for player in players:
            track_id = player.get("trackId", -1)
            if track_id < 0:
                continue
            self.last_box[track_id] = (
                float(player["x1"]),
                float(player["y1"]),
                float(player["x2"]),
                float(player["y2"]),
            )

    def observe_new_ids(self, new_ids: set[int], current_ids: set[int], frame_index: int) -> None:
        missing = self.missing_ids(current_ids)
        for new_id in new_ids:
            if new_id < 0 or new_id in self.known_ids or new_id in self.pending:
                continue
            self._record_first_seen(new_id, frame_index)
            nearby = {missing_id for missing_id in missing if self._is_nearby(new_id, missing_id)}
            if nearby:
                self.pending[new_id] = nearby
                self.occlusion_ids.add(new_id)
            else:
                self.known_ids.add(new_id)

    def _is_nearby(self, new_id: int, missing_id: int) -> bool:
        new_box = self.last_box.get(new_id)
        old_box = self.last_box.get(missing_id)
        if new_box is None or old_box is None:
            return False
        new_center_x = (new_box[0] + new_box[2]) / 2.0
        new_center_y = (new_box[1] + new_box[3]) / 2.0
        old_center_x = (old_box[0] + old_box[2]) / 2.0
        old_center_y = (old_box[1] + old_box[3]) / 2.0
        distance = math.hypot(new_center_x - old_center_x, new_center_y - old_center_y)
        height = max(new_box[3] - new_box[1], old_box[3] - old_box[1], 1.0)
        return distance <= NEARBY_BODY_HEIGHTS * height

    def drop_visible_candidates(self, current_ids: set[int], frame_index: int) -> None:
        """A candidate seen in the same frame as the new id cannot be the same person."""
        for new_id, candidates in list(self.pending.items()):
            if new_id not in current_ids:
                continue
            candidates.difference_update(current_ids)
            if not candidates:
                self.commit_new(new_id)

    def alias(self, new_id: int, canonical_id: int) -> None:
        canonical_id = self.resolve(canonical_id)
        self.pending.pop(new_id, None)
        if new_id < 0 or new_id == canonical_id:
            if new_id >= 0:
                self.known_ids.add(new_id)
            return
        self.id_alias[new_id] = canonical_id
        for ghost_id, target_id in list(self.id_alias.items()):
            if target_id == new_id and ghost_id != canonical_id:
                self.id_alias[ghost_id] = canonical_id
        self.known_ids.discard(new_id)
        self.known_ids.add(canonical_id)
        self.occlusion_ids.discard(new_id)
        new_ema = self.ema.pop(new_id, None)
        new_count = self.ema_updates.pop(new_id, 0)
        if new_ema is not None:
            if canonical_id not in self.ema:
                self.ema[canonical_id] = new_ema
            else:
                self.ema[canonical_id] = update_appearance(self.ema[canonical_id], new_ema, EMA_ALPHA)
            self.ema_updates[canonical_id] = self.ema_updates.get(canonical_id, 0) + new_count

    def commit_new(self, track_id: int) -> None:
        self.pending.pop(track_id, None)
        self.occlusion_ids.discard(track_id)
        if track_id >= 0:
            self.known_ids.add(track_id)

    def match_ready_pending(self, frame_index: int) -> dict[int, int]:
        """Greedy one-to-one: pending ids with enough vectors -> missing candidates."""
        ready_ids = [
            new_id
            for new_id in self.pending
            if new_id in self.ema and self.clean_frame_count.get(new_id, 0) >= MIN_CLEAN_FRAMES
        ]
        pairs = []
        for new_id in ready_ids:
            new_ema = self.ema.get(new_id)
            for missing_id in sorted(self.pending.get(new_id, ())):
                missing_ema = self.ema.get(missing_id)
                avg = cosine_similarity(new_ema, missing_ema) if new_ema is not None and missing_ema is not None else 0.0
                threshold = OCCLUSION_COSINE_THRESHOLD if new_id in self.occlusion_ids else COSINE_THRESHOLD
                passed = avg >= threshold
                if passed:
                    pairs.append((avg, new_id, missing_id))

        pairs.sort(key=lambda item: item[0], reverse=True)
        used_new = set()
        used_missing = set()
        matches = {}
        for score, new_id, missing_id in pairs:
            if new_id in used_new or missing_id in used_missing:
                continue
            used_new.add(new_id)
            used_missing.add(missing_id)
            matches[new_id] = missing_id

        for new_id, canonical_id in matches.items():
            _print_reid(
                f"[ReID] frame={frame_index} | MERGE {new_id} -> {canonical_id}"
            )
        return matches

    def commit_ready_unmatched(self) -> None:
        for new_id in list(self.pending):
            if self.gallery_size(new_id) >= MIN_MATCH_VECTORS:
                self.commit_new(new_id) 
