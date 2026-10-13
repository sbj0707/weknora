package com.tencent.weknora.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/** Share-link registration preserves Go's independent account, membership and token commit stages. */
@Service
public final class InvitationRegistrationService {
    private final AuthRepository repository;
    private final TenantInvitationService invitations;
    private final SecurityAuthService auth;
    private final SecuritySettings settings;
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder();
    public InvitationRegistrationService(AuthRepository repository,TenantInvitationService invitations,SecurityAuthService auth,SecuritySettings settings,JdbcTemplate jdbc){this.repository=repository;this.invitations=invitations;this.auth=auth;this.settings=settings;this.jdbc=jdbc;}
    public Map<String,Object> registerRequest(byte[] raw){
        if(!Set.of("self_serve","invite_register").contains(settings.registrationMode()))throw new ApiException(403,"FORBIDDEN","Registration is disabled").withAppCode(1002);
        return register(InvitationRequestDecoder.decode(raw,InvitationRequestDecoder.Schema.REGISTER));
    }
    public Map<String,Object> register(Map<String,Object> body){
        if(!Set.of("self_serve","invite_register").contains(settings.registrationMode()))throw new ApiException(403,"FORBIDDEN","Registration is disabled").withAppCode(1002);
        String token=required(body,"token"),email=required(body,"email"),username=required(body,"username"),password=required(body,"password");
        token=AuthPreferences.trimGoSpace(token);email=AuthPreferences.trimGoSpace(lowerGo(email));username=AuthPreferences.trimGoSpace(username);
        if(token.isEmpty()||email.isEmpty()||username.isEmpty()||password.isEmpty())throw invalid("token, email, username and password are required");
        Map<String,Object> invitation;try{invitation=invitations.lookup(token);}catch(DataAccessException ex){throw gone("invitation link is invalid or has been revoked");}
        try{if(repository.anyUserByEmail(email)!=null)throw new ApiException(409,"CONFLICT","this email already has an account; please log in to join the workspace").withAppCode(1005);}catch(DataAccessException ignored){}
        try{SecurityAuthService.validatePasswordPolicy(password,settings.complexPasswordEnabled());}catch(ApiException ex){int size=password.codePointCount(0,password.length());boolean complex=settings.complexPasswordEnabled()&&size>=8&&size<=32;throw invalid(complex?"password must be 8-32 characters and must contain uppercase and lowercase letters, numbers, and special characters":"password must be 8-32 characters and contain at least one letter and one number");}
        try{if(repository.anyUserByEmail(email)!=null)throw bad("user with this email already exists");if(repository.anyUserByUsername(username)!=null)throw bad("user with this username already exists");}catch(DataAccessException ignored){}
        if(password.getBytes(StandardCharsets.UTF_8).length>72)throw bad("failed to process password");
        Map<String,Object> user;try{user=repository.createRegisteredUser(username,email,passwords.encode(password),0);}catch(DataAccessException ex){throw bad("failed to create user");}
        String id=AuthRepository.string(user,"id");long tenant=AuthRepository.number(invitation,"tenant_id");
        try{repository.saveHomeTenant(id,tenant);}catch(DataAccessException ex){deletePartial(id);throw internal("failed to finalise invited account");}
        // The public registration request has no authenticated actor yet. The new user
        // id is the membership target; it must not replace the empty audit actor.
        AuthContext anonymous=new AuthContext("",0,"viewer",false,null,false,Set.of(),Set.of());
        try{invitations.acceptToken(anonymous,token,id);}catch(RuntimeException ex){try{repository.saveHomeTenant(id,0);}catch(RuntimeException repair){deletePartial(id);}throw gone("invitation link is no longer valid; please log in to your new account");}
        Map<String,Object> issued;try{issued=auth.issueTokens(repository.user(id));}catch(RuntimeException ex){throw internal("token generation failed");}
        Map<String,Object> response=new LinkedHashMap<>(issued);response.put("message","Registration successful");return response;
    }
    private void deletePartial(String user){try{jdbc.update("UPDATE users SET deleted_at=? WHERE id=? AND deleted_at IS NULL",repository.timestampParameter(Instant.now()),user);}catch(RuntimeException ignored){}}
    private static String required(Map<String,Object> body,String key){if(body!=null&&body.get(key) instanceof String text&&!text.isEmpty())return text;throw invalid("Invalid registration parameters");}
    private static String lowerGo(String value){StringBuilder out=new StringBuilder();value.codePoints().forEach(cp->out.appendCodePoint(Character.toLowerCase(cp)));return out.toString();}
    private static ApiException invalid(String message){return new ApiException(400,"INVALID_REQUEST",message).withAppCode(1010);}
    private static ApiException bad(String message){return new ApiException(400,"INVALID_REQUEST",message).withAppCode(1000);}
    private static ApiException gone(String message){return new ApiException(410,"NOT_FOUND",message).withAppCode(1003);}
    private static ApiException internal(String message){return new ApiException(500,"INTERNAL_ERROR",message).withAppCode(1007).withDetails("Invited account operation failed");}
}
