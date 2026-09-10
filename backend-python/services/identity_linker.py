import logging

from services.reid_extractor import cosine_similarity

logger = logging.getLogger(__name__)

COSINE_THRESHOLD = 0.70
MAX_GALLERY_VECTORS = 15
MIN_MATCH_VECTORS = 5
TOP_K_COSINE = 3


def _top_k_cosine_report(
    gallery_a: list[list[float]], gallery_b: list[list[float]]
) -> tuple[list[float], float]:
    scores = [
        cosine_similarity(vector_a, vector_b)
        for vector_a in gallery_a
        for vector_b in gallery_b
    ]
    if len(scores) < TOP_K_COSINE:
        scores.sort(reverse=True)
        return scores, 0.0
    scores.sort(reverse=True)
    top = scores[:TOP_K_COSINE]
    return top, sum(top) / TOP_K_COSINE


def _print_reid(message: str) -> None:
    print(message, flush=True)
    logger.info("%s", message)


class IdentityLinker:
    """Map a new ByteTrack id onto a missing player's original id using ReID."""

    def __init__(self):
        self.id_alias: dict[int, int] = {}
        self.known_ids: set[int] = set()
        self.galleries: dict[int, list[list[float]]] = {}
        self.last_seen_frame: dict[int, int] = {}
        self.first_seen_frame: dict[int, int] = {}
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

    def remember_vector(self, track_id: int, vector: list[float]) -> None:
        if track_id < 0 or not vector:
            return
        gallery = self.galleries.setdefault(track_id, [])
        if len(gallery) >= MAX_GALLERY_VECTORS:
            return
        gallery.append(vector)
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
                candidate_text = ", ".join(
                    f"{player_id} (first seen frame {self._first_seen_label(player_id)})"
                    for player_id in sorted(missing)
                )
                _print_reid(
                    f"[ReID] frame={frame_index} | first detected new_id={new_id}; "
                    f"waiting for {MIN_MATCH_VECTORS} vectors; missing candidates=[{candidate_text}]"
                )
            else:
                self.known_ids.add(new_id)

    def drop_visible_candidates(self, current_ids: set[int], frame_index: int) -> None:
        """A candidate seen in the same frame as the new id cannot be the same person."""
        for new_id, candidates in list(self.pending.items()):
            if new_id not in current_ids:
                continue
            candidates.difference_update(current_ids)
            if not candidates:
                _print_reid(
                    f"[ReID] frame={frame_index} | new_id={new_id} "
                    f"(first seen frame {self._first_seen_label(new_id)}) "
                    f"no remaining missing candidates -> NEW ID {new_id}"
                )
                self.commit_new(new_id)

    def alias(self, new_id: int, canonical_id: int) -> None:
        self.pending.pop(new_id, None)
        self.id_alias[new_id] = canonical_id
        self.known_ids.discard(new_id)
        self.known_ids.add(canonical_id)
        for vector in self.galleries.pop(new_id, []):
            self.remember_vector(canonical_id, vector)

    def commit_new(self, track_id: int) -> None:
        self.pending.pop(track_id, None)
        if track_id >= 0:
            self.known_ids.add(track_id)

    def match_ready_pending(self, frame_index: int) -> dict[int, int]:
        """Greedy one-to-one: pending ids with enough vectors -> missing candidates."""
        ready_ids = [
            new_id
            for new_id in self.pending
            if self.gallery_size(new_id) >= MIN_MATCH_VECTORS
        ]
        pairs = []
        for new_id in ready_ids:
            new_gallery = self.galleries.get(new_id) or []
            for missing_id in sorted(self.pending.get(new_id, ())):
                gallery = self.galleries.get(missing_id) or []
                top, avg = _top_k_cosine_report(new_gallery, gallery)
                passed = avg >= COSINE_THRESHOLD
                top_text = ", ".join(f"{score:.3f}" for score in top) if top else "none"
                _print_reid(
                    f"[ReID] frame={frame_index} | compare new_id={new_id} "
                    f"(first seen frame {self._first_seen_label(new_id)}) "
                    f"vs missing_id={missing_id} "
                    f"(first seen frame {self._first_seen_label(missing_id)})"
                )
                _print_reid(
                    f"[ReID]   top-{TOP_K_COSINE} cosine: [{top_text}] | avg={avg:.3f} | "
                    f"threshold={COSINE_THRESHOLD:.2f} | {'PASS' if passed else 'FAIL'}"
                )
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

        for new_id in ready_ids:
            canonical_id = matches.get(new_id)
            if canonical_id is None:
                _print_reid(
                    f"[ReID] frame={frame_index} | decision: NEW ID {new_id} "
                    f"(first seen frame {self._first_seen_label(new_id)})"
                )
            else:
                _print_reid(
                    f"[ReID] frame={frame_index} | decision: MERGE new_id={new_id} "
                    f"(first seen frame {self._first_seen_label(new_id)}) "
                    f"-> player {canonical_id} "
                    f"(first seen frame {self._first_seen_label(canonical_id)})"
                )
        return matches

    def commit_ready_unmatched(self) -> None:
        for new_id in list(self.pending):
            if self.gallery_size(new_id) >= MIN_MATCH_VECTORS:
                self.commit_new(new_id)
