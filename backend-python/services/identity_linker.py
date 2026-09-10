import logging

from services.reid_extractor import cosine_similarity

logger = logging.getLogger(__name__)

COSINE_THRESHOLD = 0.70
MAX_GALLERY_VECTORS = 15
MIN_MATCH_VECTORS = 5


def _max_gallery_cosine(gallery_a: list[list[float]], gallery_b: list[list[float]]) -> float:
    best = 0.0
    for vector_a in gallery_a:
        for vector_b in gallery_b:
            best = max(best, cosine_similarity(vector_a, vector_b))
    return best


class IdentityLinker:
    """Map a new ByteTrack id onto a missing player's original id using ReID."""

    def __init__(self):
        self.id_alias: dict[int, int] = {}
        self.known_ids: set[int] = set()
        self.galleries: dict[int, list[list[float]]] = {}
        self.last_seen_frame: dict[int, int] = {}
        self.pending: dict[int, set[int]] = {}

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
        return len(self.galleries.get(track_id, []))

    def has_gallery(self, track_id: int) -> bool:
        return bool(self.galleries.get(track_id))

    def mark_seen(self, track_id: int, frame_index: int) -> None:
        if track_id < 0:
            return
        self.last_seen_frame[track_id] = frame_index

    def remember_vector(self, track_id: int, vector: list[float]) -> None:
        if track_id < 0 or not vector:
            return
        gallery = self.galleries.setdefault(track_id, [])
        if len(gallery) >= MAX_GALLERY_VECTORS:
            return
        gallery.append(vector)
        if track_id not in self.pending:
            self.known_ids.add(track_id)

    def observe_new_ids(self, new_ids: set[int], current_ids: set[int]) -> None:
        missing = self.missing_ids(current_ids)
        for new_id in new_ids:
            if new_id < 0 or new_id in self.known_ids or new_id in self.pending:
                continue
            if missing:
                self.pending[new_id] = set(missing)
                logger.info(
                    "ReID pending id %s until %s vectors; candidates=%s",
                    new_id,
                    MIN_MATCH_VECTORS,
                    sorted(missing),
                )
            else:
                self.known_ids.add(new_id)

    def drop_visible_candidates(self, current_ids: set[int]) -> None:
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
        for vector in self.galleries.pop(new_id, []):
            self.remember_vector(canonical_id, vector)
        logger.info("ReID linked ByteTrack id %s -> original id %s", new_id, canonical_id)

    def commit_new(self, track_id: int) -> None:
        self.pending.pop(track_id, None)
        if track_id >= 0:
            self.known_ids.add(track_id)

    def match_ready_pending(self) -> dict[int, int]:
        """Greedy one-to-one: pending ids with enough vectors -> missing candidates."""
        pairs = []
        for new_id, candidates in self.pending.items():
            new_gallery = self.galleries.get(new_id)
            if not new_gallery or len(new_gallery) < MIN_MATCH_VECTORS:
                continue
            for missing_id in candidates:
                gallery = self.galleries.get(missing_id)
                if not gallery:
                    continue
                score = _max_gallery_cosine(new_gallery, gallery)
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

    def commit_ready_unmatched(self) -> None:
        for new_id in list(self.pending):
            if self.gallery_size(new_id) >= MIN_MATCH_VECTORS:
                self.commit_new(new_id)
