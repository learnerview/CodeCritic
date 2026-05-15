package com.codecritic.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.concurrent.TimeUnit;

@Controller
public class FrontendController {

    @GetMapping(value = { "/", "/index.html" }, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> index() {
        return resourceResponse("templates/index.html", MediaType.TEXT_HTML);
    }

    @GetMapping(value = { "/review", "/review.html" }, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> reviewPage() {
        return resourceResponse("templates/review.html", MediaType.TEXT_HTML);
    }

    @GetMapping(value = { "/repository", "/repository.html" }, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> repositoryPage() {
        return resourceResponse("templates/repository.html", MediaType.TEXT_HTML);
    }

    @GetMapping(value = { "/debug", "/debug.html" }, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> debugPage() {
        return resourceResponse("templates/debug.html", MediaType.TEXT_HTML);
    }

    // Explicitly serving CSS and JS from their subdirectories for robustness
    @GetMapping(value = "/css/style.css", produces = "text/css")
    public ResponseEntity<Resource> style() {
        return resourceResponse("static/css/style.css", MediaType.valueOf("text/css"));
    }

    @GetMapping(value = "/js/app.js", produces = "application/javascript")
    public ResponseEntity<Resource> script() {
        return resourceResponse("static/js/app.js", MediaType.valueOf("application/javascript"));
    }

    private ResponseEntity<Resource> resourceResponse(String classpathLocation, MediaType contentType) {
        Resource resource = new ClassPathResource(classpathLocation);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .body(resource);
    }
}
