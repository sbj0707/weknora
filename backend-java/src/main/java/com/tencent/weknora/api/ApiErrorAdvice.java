package com.tencent.weknora.api;
import com.tencent.weknora.security.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;
@RestControllerAdvice
public class ApiErrorAdvice {
    @ExceptionHandler(ApiException.class) public ResponseEntity<?> api(ApiException ex) {
        if(ex.response()!=null)return ResponseEntity.status(ex.status()).body(ex.response());
        if (ex.status()==401&&ex.appCode()==null || ex.code().equals("TENANT_REQUIRED")) return ResponseEntity.status(ex.status()).body(Map.of("error", ex.getMessage(), "code", ex.code()));
        return error(ex.status(),ex.appCode()==null?code(ex.status()):ex.appCode(),ex.getMessage(),ex.details());
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,HttpMediaTypeNotSupportedException.class}) public ResponseEntity<?> invalid(Exception ex) {return error(400,1000,"Invalid request parameters");}
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<?> database(DataAccessException ex) {return error(503,1008,"Database unavailable");}
    private ResponseEntity<?> error(int status,int code,String message) {return error(status,code,message,null);}
    private ResponseEntity<?> error(int status,int code,String message,Object details) {
        var error=new LinkedHashMap<String,Object>();error.put("code",code);error.put("message",message);error.put("details",details);
        return ResponseEntity.status(status).body(Map.of("success",false,"error",error));
    }
    private static int code(int status) {return switch(status){case 400 -> 1000;case 403 -> 1002;case 404 -> 1003;case 409 -> 1005;case 501,503 -> 1008;default -> 1007;};}
}
