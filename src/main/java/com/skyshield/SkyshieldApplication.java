
package com.skyshield;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class SkyshieldApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkyshieldApplication.class, args);
    }

    @RestController
    public static class StatusController {

        @GetMapping("/api/status")
        public String status() {
            return "SKYSHIELD AI is online. Demo mode is active.";
        }
    }
}
