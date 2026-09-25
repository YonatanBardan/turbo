import logging

import numpy as np

from services.reid_extractor import cosine_similarity, update_appearance

logger = logging.getLogger(__name__)

COSINE_THRESHOLD = 0.78
MIN_MATCH_VECTORS = 15
EMA_ALPHA = 0.9
EMA_MIN_CONFIDENCE = 0.5


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
        return self.id_alias.get(track_id, track_id)

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

    def observe_new_ids(self, new_ids: set[int], current_ids: set[int], frame_index: int) -> None:
        missing = self.missing_ids(current_ids)
        for new_id in new_ids:
            if new_id < 0 or new_id in self.known_ids or new_id in self.pending:
                continue
            self._record_first_seen(new_id, frame_index)
            if missing:
                self.pending[new_id] = set(missing)
            else:
                self.known_ids.add(new_id)

    def drop_visible_candidates(self, current_ids: set[int], frame_index: int) -> None:
        """A candidate seen in the same frame as the new id cannot be the same person."""
        for new_id, candidates in list(self.pending.items()):
            if new_id not in current_ids:
                continue
            candidates.difference_update(current_ids)
            if not candidates:
                self.commit_new(new_id)

    def alias(self, new_id: int, canonical_id: int) -> None:
        self.pending.pop(new_id, None)
        self.id_alias[new_id] = canonical_id
        self.known_ids.discard(new_id)
        self.known_ids.add(canonical_id)
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
        if track_id >= 0:
            self.known_ids.add(track_id)

    def match_ready_pending(self, frame_index: int) -> dict[int, int]:
        """Greedy one-to-one: pending ids with enough vectors -> missing candidates."""
        ready_ids = [
            new_id
            for new_id in self.pending
            if new_id in self.ema
        ]
        pairs = []
        for new_id in ready_ids:
            new_ema = self.ema.get(new_id)
            for missing_id in sorted(self.pending.get(new_id, ())):
                missing_ema = self.ema.get(missing_id)
                avg = cosine_similarity(new_ema, missing_ema) if new_ema is not None and missing_ema is not None else 0.0
                passed = avg >= COSINE_THRESHOLD
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
