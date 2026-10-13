package com.tencent.weknora.security;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController
public final class InvitationRegistrationController {
    private final InvitationRegistrationService service;
    public InvitationRegistrationController(InvitationRegistrationService service){this.service=service;}
    @PostMapping("/api/v1/auth/register-by-invite") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> register(HttpServletRequest request) throws IOException {return service.registerRequest(request.getInputStream().readAllBytes());}
}
