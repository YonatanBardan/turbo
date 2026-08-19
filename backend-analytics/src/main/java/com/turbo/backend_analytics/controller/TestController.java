package com.turbo.backend_analytics.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {

    @GetMapping("/ping")
    public String healthCheck(){
        return "The Turbo is up and running";
    }
}
