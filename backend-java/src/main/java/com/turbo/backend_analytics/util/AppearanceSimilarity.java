package com.turbo.backend_analytics.util;

import java.util.List;

public final class AppearanceSimilarity {

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

    public static double maxCosine(List<float[]> galleryA, List<float[]> galleryB) {
        if (galleryA == null || galleryB == null || galleryA.isEmpty() || galleryB.isEmpty()) {
            return 0.0;
        }
        double best = 0.0;
        for (float[] a : galleryA) {
            for (float[] b : galleryB) {
                best = Math.max(best, cosine(a, b));
            }
        }
        return best;
    }
}
