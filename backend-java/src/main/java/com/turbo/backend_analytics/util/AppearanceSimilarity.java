package com.turbo.backend_analytics.util;

import com.turbo.backend_analytics.dto.Player.ScoredAppearanceVector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AppearanceSimilarity {

    private static final int TOP_K_COSINE = 3;

    private AppearanceSimilarity() {}

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na <= 0.0 || nb <= 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public static double maxCosine(List<ScoredAppearanceVector> galleryA, List<ScoredAppearanceVector> galleryB) {
        if (galleryA == null || galleryB == null || galleryA.isEmpty() || galleryB.isEmpty()) {
            return 0.0;
        }
        List<Double> scores = new ArrayList<>();
        for (ScoredAppearanceVector a : galleryA) {
            for (ScoredAppearanceVector b : galleryB) {
                scores.add(cosine(a.vector(), b.vector()));
            }
        }
        scores.sort(Comparator.reverseOrder());
        int used = Math.min(TOP_K_COSINE, scores.size());
        double avg = 0.0;
        for (int i = 0; i < used; i++) {
            avg += scores.get(i);
        }
        return avg / used;
    }
}
