package com.turbo.backend_analytics.util;

import com.turbo.backend_analytics.dto.DetectionBox;

import java.util.List;

public class GeometryUtil {

    // Returns: First DetectionBox that contains a box with the requested id
    public static DetectionBox findClass(List<DetectionBox> boxes, int classId){
        return boxes.stream()
                .filter(box -> box.classId() == classId)
                .findFirst().orElse(null);
    }

    // Returns: true if the 2 bounding boxes are overlapping
    public static boolean isOverLap(DetectionBox ball, DetectionBox shooter){
        boolean notOverLap =
                (ball.x() + ball.width() < shooter.x()) ||      // Ball not overlap from left
                        (ball.x() > shooter.x() + shooter.width()) ||   // Ball not overlap from right
                        (ball.y() + ball.height() < shooter.y()) ||     // Ball not overlap from top
                        (ball.y() > shooter.y() + shooter.height());    // Ball not overlap from bottom

        return !notOverLap;    // If notOverLap is true then we need to return false
    }

    public static boolean isNearShooter(DetectionBox ball, DetectionBox shooter){
        return isNearPlayer(ball, shooter);
    }

    public static boolean isNearPlayer(DetectionBox ball, DetectionBox player) {
        double marginX = player.width() * 0.25;
        double marginY = player.height() * 0.5;

        boolean nearX = ball.x() >= (player.x() - marginX) && ball.x() <= (player.x() + player.width() + marginX);
        boolean nearY = ball.y() >= (player.y() - marginY) && ball.y() <= (player.y() + player.height());

        return nearX && nearY;
    }

    public static List<DetectionBox> findAllClass(List<DetectionBox> boxes, int classId) {
        return boxes.stream()
                .filter(box -> box.classId() == classId)
                .toList();
    }

    public static DetectionBox findHighestConfidence(List<DetectionBox> boxes, int classId) {
        DetectionBox best = null;
        for (DetectionBox box : boxes) {
            if (box.classId() != classId) {
                continue;
            }
            if (best == null || box.confidence() > best.confidence()) {
                best = box;
            }
        }
        return best;
    }

    public static double centerX(DetectionBox box) {
        return box.x() + (box.width() / 2.0);
    }

    public static double centerY(DetectionBox box) {
        return box.y() + (box.height() / 2.0);
    }

    public static double centerDistance(DetectionBox a, DetectionBox b) {
        double dx = centerX(a) - centerX(b);
        double dy = centerY(a) - centerY(b);
        return Math.sqrt(dx * dx + dy * dy);
    }

    public static DetectionBox findBallPossessor(DetectionBox ball, List<DetectionBox> players) {
        if (ball == null || players == null || players.isEmpty()) {
            return null;
        }
        DetectionBox bestOverlap = null;
        double bestOverlapDist = Double.MAX_VALUE;
        DetectionBox bestNear = null;
        double bestNearDist = Double.MAX_VALUE;

        for (DetectionBox player : players) {
            if (player.trackId() < 0) {
                continue;
            }
            double dist = centerDistance(ball, player);
            if (isOverLap(ball, player) && dist < bestOverlapDist) {
                bestOverlap = player;
                bestOverlapDist = dist;
            } else if (isNearPlayer(ball, player) && dist < bestNearDist) {
                bestNear = player;
                bestNearDist = dist;
            }
        }
        return bestOverlap != null ? bestOverlap : bestNear;
    }

    public static DetectionBox findNearestPlayer(DetectionBox ball, List<DetectionBox> players) {
        if (ball == null || players == null || players.isEmpty()) {
            return null;
        }
        DetectionBox nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (DetectionBox player : players) {
            if (player.trackId() < 0) {
                continue;
            }
            double dist = centerDistance(ball, player);
            if (dist < bestDist) {
                bestDist = dist;
                nearest = player;
            }
        }
        return nearest;
    }

}
