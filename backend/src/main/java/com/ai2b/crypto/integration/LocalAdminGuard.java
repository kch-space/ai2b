package com.ai2b.crypto.integration;

import java.net.*;
import java.util.Set;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Component
public class LocalAdminGuard implements HandlerInterceptor, WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {registry.addInterceptor(this).addPathPatterns("/api/v1/integrations/**");}
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) throws Exception {
        boolean local=false;
        try {local=InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();} catch(Exception ignored) { }
        String host=request.getServerName();
        boolean safeHost=Set.of("localhost","127.0.0.1","::1","[::1]").contains(host);
        boolean origin=true;
        String source=request.getHeader("Origin");
        if(source!=null) {
            try {URI uri=URI.create(source);origin=Set.of("http","https").contains(uri.getScheme()) && Set.of("localhost","127.0.0.1","::1","[::1]").contains(uri.getHost()) && Set.of(-1,5173,8080).contains(uri.getPort());}
            catch(Exception e){origin=false;}
        }
        if(!local || !safeHost || !origin || "cross-site".equals(request.getHeader("Sec-Fetch-Site"))
                || (!request.getMethod().equals("GET") && (!"integration-admin".equals(request.getHeader("X-AI2B-Action"))
                || request.getContentType()==null || !request.getContentType().startsWith("application/json")))) {
            response.setStatus(403); response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"集成管理仅允许本机同源操作，并需要明确操作请求头\"}");return false;
        }
        response.setHeader("Cache-Control","no-store");return true;
    }
}
