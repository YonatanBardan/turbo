package com.turbo.backend_analytics.controller;

import com.turbo.backend_analytics.dto.Player.Player;
import com.turbo.backend_analytics.dto.Player.PlayerStatView;
import com.turbo.backend_analytics.dto.Response.VideoAnalysisResponse;
import com.turbo.backend_analytics.service.AiAnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/video")
@CrossOrigin(origins = "*")
public class VideoAnalysisController {

    private final AiAnalysisService aiAnalysisService;

    public VideoAnalysisController (AiAnalysisService aiAnalysisService){
        this.aiAnalysisService = aiAnalysisService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<List<PlayerStatView>> analyzeVideoFile(@RequestParam("file") MultipartFile file){

        try {
            long startedNanos = System.nanoTime();

            // - Allocating new space in memory for the uploaded video;
            File tempFile = File.createTempFile("upload_", "_" + file.getOriginalFilename());
            file.transferTo(tempFile);
            String AbsolutePath = tempFile.getAbsolutePath();

            // - Activating the analyzed service
            VideoAnalysisResponse response = aiAnalysisService.analyzeVideo(AbsolutePath);

            // - Activating the render service on the file and the shots stats
            String finalVideoPath = aiAnalysisService.renderScoreboardVideo(
                    AbsolutePath,
                    response.shots(),
                    response.boxes(),
                    response.players(),
                    response.ballStates()
            );

            System.out.println("Shots list: " + response.shots());
            System.out.println("Rendered video: " + finalVideoPath);
            printPipelineSpeed(startedNanos, response.totalFrames());

            // - Delete from memory the temp file
            tempFile.delete();
            return ResponseEntity.ok(toPlayerStats(response.players()));
        }

        catch (IOException e){
            return ResponseEntity.internalServerError().build();
        }
    }

    
    // Prints the pipeline speed
    private static void printPipelineSpeed(long startedNanos, int frameCount) {
        double seconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
        double framesPerSecond = frameCount / Math.max(seconds, 1e-6);
        System.out.printf(
                "Pipeline finished in %.2f s for %d frames (%.2f frames/s)%n",
                seconds,
                frameCount,
                framesPerSecond
        );
    }

    // Converts the players to player stats
    private static List<PlayerStatView> toPlayerStats(List<Player> players) {
        List<PlayerStatView> stats = new ArrayList<>();
        if (players == null) {
            return stats;
        }
        for (Player player : players) {
            stats.add(new PlayerStatView(player.id(), player.stats()));
        }
        return stats;
    }
}

