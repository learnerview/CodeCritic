package com.codecritic.service;

import com.codecritic.dto.auth.LoginRequest;
import com.codecritic.dto.auth.LoginResponse;

public interface AuthService {

    LoginResponse authenticate(LoginRequest request);
}
