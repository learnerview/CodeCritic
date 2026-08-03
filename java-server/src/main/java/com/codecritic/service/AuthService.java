package com.codecritic.service;

import com.codecritic.dto.auth.LoginRequest;
import com.codecritic.dto.auth.LoginResponse;
import com.codecritic.dto.auth.RegisterRequest;

public interface AuthService {

    LoginResponse authenticate(LoginRequest request);

    LoginResponse register(RegisterRequest request);
}
