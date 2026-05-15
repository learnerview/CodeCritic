package com.codecritic.dto;

public record TestGenerationRequest(String className, String methodName, String parameters, String code) { }
