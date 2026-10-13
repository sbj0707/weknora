package com.tencent.weknora.api;
import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final SecurityAuthService auth;
    public AuthController(SecurityAuthService auth) {this.auth=auth;}
    @GetMapping("/config") public Map<String,Object> config() {return auth.authConfig();}
    @PostMapping("/register") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Map<String,Object> register(@RequestBody Map<String,Object> body) {return auth.register(body);}
    @PostMapping("/login") public Map<String,Object> login(@RequestBody Map<String,Object> body) {return auth.login(value(body,"email"),value(body,"password"));}
    @PostMapping("/refresh") public Map<String,Object> refresh(@RequestBody Map<String,Object> body) {var issued=auth.refresh(value(body,"refreshToken"));return Map.of("success",true,"message","Token refreshed successfully","access_token",issued.get("token"),"refresh_token",issued.get("refresh_token"));}
    @GetMapping("/me") public Map<String,Object> me(HttpServletRequest request) {return auth.me(CurrentAuth.get(request));}
    @PostMapping("/logout") public Map<String,Object> logout(HttpServletRequest request) {return auth.logout(CurrentAuth.get(request));}
    @GetMapping("/validate") public Map<String,Object> validate(HttpServletRequest request) {return auth.validate(CurrentAuth.get(request),request.getHeader("Authorization"));}
    @PutMapping("/me/preferences") public Map<String,Object> preferences(HttpServletRequest request,@RequestBody Map<String,Object> body) {return auth.updatePreferences(CurrentAuth.get(request),body);}
    @PostMapping("/switch-tenant") public Map<String,Object> switchTenant(HttpServletRequest request,@RequestBody Map<String,Object> body) {return auth.switchTenant(CurrentAuth.get(request),body);}
    @PostMapping("/change-password") public Map<String,Object> changePassword(HttpServletRequest request,@RequestBody Map<String,Object> body) {return auth.changePassword(CurrentAuth.get(request),body);}
    private static String value(Map<String,Object> body,String key) {Object value=body.get(key);if(!(value instanceof String s)) throw new ApiException(400,"INVALID_REQUEST","Invalid request parameters");return s;}
}
