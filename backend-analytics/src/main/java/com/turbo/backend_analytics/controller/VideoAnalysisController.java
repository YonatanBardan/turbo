package com.turbo.backend_analytics.controller;

import com.turbo.backend_analytics.dto.VideoAnalysisResponse;
import com.turbo.backend_analytics.service.AiAnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.IOException;

@RestController
@RequestMapping("/api/video")
@CrossOrigin(origins = "*")
public class VideoAnalysisController {

    private final AiAnalysisService aiAnalysisService;

    public VideoAnalysisController (AiAnalysisService aiAnalysisService){
        this.aiAnalysisService = aiAnalysisService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<VideoAnalysisResponse> analyzeVideoFile(@RequestParam("file") MultipartFile file){

        try {

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
                    response.boxes()
            );

            // Print only the shots list to your IDE console
            System.out.println("Shots list: " + response.shots());

            // - Delete from memory the temp file
            tempFile.delete();
            return ResponseEntity.ok(response);
        }

        catch (IOException e){
            return ResponseEntity.internalServerError().build();
        }
    }
}

