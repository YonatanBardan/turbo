import logging

from services.reid_extractor import cosine_similarity

logger = logging.getLogger(__name__)

COSINE_THRESHOLD = 0.70
POSITION_GATE_FRACTION = 0.4
MAX_GALLERY_VECTORS = 15


class IdentityLinker:
    """Map a new ByteTrack id onto a missing player's original id using ReID."""

    def __init__(self, frame_w: int):
        self.frame_w = max(1, int(frame_w))
        self.id_alias: dict[int, int] = {}
        self.known_ids: set[int] = set()
        self.galleries: dict[int, list[list[float]]] = {}
        self.last_seen_frame: dict[int, int] = {}
        self.last_center: dict[int, tuple[float, float]] = {}

    def resolve(self, track_id: int) -> int:
        if track_id < 0:
            return track_id
        return self.id_alias.get(track_id, track_id)

    def missing_ids(self, current_ids: set[int]) -> set[int]:
        return {player_id for player_id in self.known_ids if player_id not in current_ids}

    def has_gallery(self, track_id: int) -> bool:
        return bool(self.galleries.get(track_id))

    def mark_seen(self, track_id: int, center: tuple[float, float], frame_index: int) -> None:
        if track_id < 0:
            return
        self.last_seen_frame[track_id] = frame_index
        self.last_center[track_id] = center

    def remember_vector(self, track_id: int, vector: list[float]) -> None:
        if track_id < 0 or not vector:
            return
        self.known_ids.add(track_id)
        gallery = self.galleries.setdefault(track_id, [])
        if len(gallery) >= MAX_GALLERY_VECTORS:
            return
        gallery.append(vector)

    def alias(self, new_id: int, canonical_id: int) -> None:
        self.id_alias[new_id] = canonical_id
        self.known_ids.discard(new_id)
        self.known_ids.add(canonical_id)
        for vector in self.galleries.pop(new_id, []):
            self.remember_vector(canonical_id, vector)
        logger.info("ReID linked ByteTrack id %s -> original id %s", new_id, canonical_id)

    def match_new_to_missing(
        self,
        new_candidates: list[tuple[int, list[float], tuple[float, float]]],
        missing_ids: set[int],
    ) -> dict[int, int]:
        """Greedy one-to-one: new_id -> canonical_id for pairs above threshold."""
        pairs = []
        max_dist = POSITION_GATE_FRACTION * self.frame_w

        for new_id, vector, center in new_candidates:
            for missing_id in missing_ids:
                gallery = self.galleries.get(missing_id)
                if not gallery:
                    continue
                last_center = self.last_center.get(missing_id)
                if last_center is not None:
                    dx = center[0] - last_center[0]
                    dy = center[1] - last_center[1]
                    if (dx * dx + dy * dy) ** 0.5 > max_dist:
                        continue
                score = max(cosine_similarity(vector, stored) for stored in gallery)
                if score >= COSINE_THRESHOLD:
                    pairs.append((score, new_id, missing_id))

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
            logger.info(
                "ReID match score %.3f: new id %s -> missing id %s",
                score,
                new_id,
                missing_id,
            )
        return matches
