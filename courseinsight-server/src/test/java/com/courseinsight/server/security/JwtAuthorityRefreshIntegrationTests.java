package com.courseinsight.server.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.courseinsight.server.entity.AppUser;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.mapper.AppUserMapper;
import com.courseinsight.server.service.CourseAnalyticsService;
import com.courseinsight.server.service.JwtTokenService;
import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MySqlIntegrationTest
@AutoConfigureMockMvc
class JwtAuthorityRefreshIntegrationTests {

    private static final String USERNAME_PREFIX = "jwt_refresh_";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserMapper appUserMapper;

    @Autowired
    private JwtTokenService jwtTokenService;

    @MockitoBean
    private CourseAnalyticsService courseAnalyticsService;

    @BeforeEach
    void cleanUsers() {
        appUserMapper.delete(Wrappers.<AppUser>lambdaQuery()
                .likeRight(AppUser::getUsername, USERNAME_PREFIX));
    }

    @Test
    void shouldDenyTeacherEndpointWithSameJwtAfterAdministratorDemotesUser()
            throws Exception {
        AppUser administrator = createUser("admin", UserRole.ADMIN, 1);
        AppUser teacher = createUser("teacher", UserRole.TEACHER, 1);
        String administratorToken = issue(administrator);
        String teacherToken = issue(teacher);

        requestAnalytics(teacherToken).andExpect(status().isOk());

        mockMvc.perform(patch("/api/admin/users/{userId}/role", teacher.getId())
                        .header("Authorization", bearer(administratorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"STUDENT\"}"))
                .andExpect(status().isOk());

        requestAnalytics(teacherToken).andExpect(status().isForbidden());
        verify(courseAnalyticsService, times(1)).getSummary(
                14L,
                teacher.getId(),
                UserRole.TEACHER
        );
    }

    @Test
    void shouldGrantTeacherEndpointWithSameJwtAfterAdministratorPromotesUser()
            throws Exception {
        AppUser administrator = createUser("admin", UserRole.ADMIN, 1);
        AppUser student = createUser("student", UserRole.STUDENT, 1);
        String administratorToken = issue(administrator);
        String studentToken = issue(student);

        requestAnalytics(studentToken).andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/admin/users/{userId}/role", student.getId())
                        .header("Authorization", bearer(administratorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"TEACHER\"}"))
                .andExpect(status().isOk());

        requestAnalytics(studentToken).andExpect(status().isOk());
        verify(courseAnalyticsService).getSummary(
                14L,
                student.getId(),
                UserRole.TEACHER
        );
    }

    @Test
    void shouldContinueAuthorizingUnchangedActiveUser() throws Exception {
        AppUser teacher = createUser("unchanged", UserRole.TEACHER, 1);
        String token = issue(teacher);

        requestAnalytics(token).andExpect(status().isOk());
        requestAnalytics(token).andExpect(status().isOk());

        verify(courseAnalyticsService, times(2)).getSummary(
                14L,
                teacher.getId(),
                UserRole.TEACHER
        );
    }

    @Test
    void shouldRejectSameJwtAfterUserIsDisabled() throws Exception {
        AppUser teacher = createUser("disabled", UserRole.TEACHER, 1);
        String token = issue(teacher);

        requestAnalytics(token).andExpect(status().isOk());
        teacher.setStatus(0);
        appUserMapper.updateById(teacher);

        requestAnalytics(token).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectSameJwtAfterUserIsDeleted() throws Exception {
        AppUser teacher = createUser("deleted", UserRole.TEACHER, 1);
        String token = issue(teacher);

        requestAnalytics(token).andExpect(status().isOk());
        appUserMapper.deleteById(teacher.getId());

        requestAnalytics(token).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions requestAnalytics(
            String token) throws Exception {
        return mockMvc.perform(get("/api/courses/{courseId}/analytics/summary", 14L)
                .header("Authorization", bearer(token)));
    }

    private AppUser createUser(String suffix, UserRole role, int status) {
        AppUser user = new AppUser();
        user.setUsername(USERNAME_PREFIX + suffix);
        user.setPasswordHash("{noop}not-used-by-this-test");
        user.setDisplayName("JWT refresh test user");
        user.setRole(role.name());
        user.setStatus(status);
        appUserMapper.insert(user);
        return user;
    }

    private String issue(AppUser user) {
        return jwtTokenService.issue(user).value();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
