package com.courseinsight.server.controller;

import com.courseinsight.server.config.SecurityConfig;
import com.courseinsight.server.dto.CommentAnalysisResponse;
import com.courseinsight.server.entity.AppUser;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.exception.GlobalExceptionHandler;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AppUserMapper;
import com.courseinsight.server.service.CommentAnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommentAnalysisController.class)
@ContextConfiguration(classes = {CommentAnalysisController.class, SecurityConfig.class, GlobalExceptionHandler.class})
class CommentAnalysisControllerTests {
    @Autowired private MockMvc mvc;
    @MockitoBean private CommentAnalysisService service;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private AppUserMapper users;

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(get("/api/comments/10/analysis"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(service);
    }

    @Test
    void studentDirectApiIs403BeforeReadingAnyAnalysis() throws Exception {
        authenticate(UserRole.STUDENT);
        mvc.perform(get("/api/comments/10/analysis").header("Authorization", "Bearer test-token"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"TEACHER", "ADMIN"})
    void managerIdentityIsPassedToService(UserRole role) throws Exception {
        authenticate(role);
        given(service.getByCommentId(10L, 30L, role))
                .willReturn(new CommentAnalysisResponse(null, null, null));
        mvc.perform(get("/api/comments/10/analysis").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.result").isEmpty());
    }

    @Test
    void unrelatedTeacherIs403() throws Exception {
        authenticate(UserRole.TEACHER);
        given(service.getByCommentId(10L, 30L, UserRole.TEACHER))
                .willThrow(new CourseAccessDeniedException("无权管理其他教师的课程"));
        mvc.perform(get("/api/comments/10/analysis").header("Authorization", "Bearer test-token"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void missingOrDeletedCommentIs404() throws Exception {
        authenticate(UserRole.ADMIN);
        given(service.getByCommentId(10L, 30L, UserRole.ADMIN))
                .willThrow(new ResourceNotFoundException("课程评价不存在或已删除"));
        mvc.perform(get("/api/comments/10/analysis").header("Authorization", "Bearer test-token"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
    }

    private void authenticate(UserRole role) {
        Instant now = Instant.now();
        given(jwtDecoder.decode("test-token")).willReturn(new Jwt("test-token", now,
                now.plusSeconds(3600), Map.of("alg", "HS256"), Map.of("sub", "30", "role", role.name())));
        AppUser user = new AppUser();
        user.setId(30L);
        user.setStatus(1);
        user.setRole(role.name());
        given(users.selectById(30L)).willReturn(user);
    }
}
