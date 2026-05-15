package com.codecritic.dto;

import java.util.List;

public record BugReport(List<BugFinding> bugs) { }
