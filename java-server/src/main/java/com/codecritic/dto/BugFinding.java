package com.codecritic.dto;

public record BugFinding(String type, int line, String message, String suggestion) { }
