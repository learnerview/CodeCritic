package com.codecritic.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FrontendController {

    // The dashboard is a single-page app; every page route serves it and the
    // client shows the matching tab (Review / Repository / Debug).
    @GetMapping(value = { "/", "/index.html", "/review", "/review.html",
            "/repository", "/repository.html", "/debug", "/debug.html" }, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> dashboard() {
        return resourceResponse("templates/index.html", MediaType.TEXT_HTML);
    }

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
        // HTML/JS/CSS change with every deploy: never serve stale copies.
        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.noCache())
                .body(resource);
    }
}
