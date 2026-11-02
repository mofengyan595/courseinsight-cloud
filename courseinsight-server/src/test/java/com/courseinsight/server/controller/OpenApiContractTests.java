package com.courseinsight.server.controller;

import com.courseinsight.server.config.OpenApiConfig;
import com.courseinsight.server.config.SecurityConfig;
import com.courseinsight.server.exception.GlobalExceptionHandler;
import com.courseinsight.server.mapper.AppUserMapper;
import com.courseinsight.server.ratelimit.AuthenticationRateLimitService;
import com.courseinsight.server.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.configuration.SpringDocSecurityConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@ContextConfiguration(classes = {
        AdminUserController.class, AnalysisBatchController.class, AnalysisExecutionController.class,
        AnalysisTaskController.class, AnalysisTaskEnqueueController.class, AuthController.class,
        CommentAnalysisController.class, CommentController.class, CourseAnalyticsController.class,
        CourseController.class, CourseRankingController.class, HealthController.class,
        OpenApiConfig.class, SecurityConfig.class, GlobalExceptionHandler.class
})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocSecurityConfiguration.class, SpringDocWebMvcConfiguration.class,
        SwaggerConfig.class, SwaggerUiConfigProperties.class, SwaggerUiOAuthProperties.class})
class OpenApiContractTests {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private AdminUserService adminUserService;
    @MockitoBean private AnalysisBatchService batchService;
    @MockitoBean private AnalysisBatchResultService batchResultService;
    @MockitoBean private AnalysisBatchRecoveryService recoveryService;
    @MockitoBean private AnalysisExecutionService executionService;
    @MockitoBean private AnalysisTaskService taskService;
    @MockitoBean private AnalysisTaskEnqueueService enqueueService;
    @MockitoBean private UserRegistrationService registrationService;
    @MockitoBean private UserLoginService loginService;
    @MockitoBean private AuthenticationRateLimitService authenticationRateLimitService;
    @MockitoBean private CommentAnalysisService commentAnalysisService;
    @MockitoBean private CommentService commentService;
    @MockitoBean private CourseAnalyticsService analyticsService;
    @MockitoBean private CourseService courseService;
    @MockitoBean private CoursePopularityRankingService rankingService;
    @MockitoBean private AppUserMapper appUserMapper;
    @MockitoBean private JwtDecoder jwtDecoder;

    @Test
    void shouldPublishJwtSecurityAndStableUniqueOperationsWithoutCredentials() throws Exception {
        JsonNode api = api();
        assertThat(api.at("/components/securitySchemes/bearerAuth/type").asText()).isEqualTo("http");
        assertThat(api.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
        assertThat(api.at("/components/securitySchemes/bearerAuth/bearerFormat").asText()).isEqualTo("JWT");
        assertThat(api.at("/security/0/bearerAuth").isArray()).isTrue();
        assertThat(operation(api, "/api/auth/login", "post").path("security").isEmpty()).isTrue();
        assertThat(operation(api, "/api/auth/login", "post").has("security")).isTrue();
        assertThat(operation(api, "/api/health", "get").path("security").isEmpty()).isTrue();

        List<String> operationIds = new ArrayList<>();
        api.path("paths").fields().forEachRemaining(path -> {
            assertThat(path.getKey()).startsWith("/api/");
            path.getValue().elements().forEachRemaining(op -> {
                operationIds.add(op.path("operationId").asText());
                assertThat(op.path("responses").fieldNames()).toIterable()
                        .anySatisfy(code -> assertThat(code).startsWith("2"));
            });
        });
        assertThat(operationIds).containsExactlyInAnyOrder(
                "listUsers", "updateUserRole", "createAnalysisBatch", "listAnalysisBatches", "getAnalysisBatch",
                "listAnalysisBatchResults", "exportAnalysisBatchResults", "retryAnalysisBatch", "executeAnalysisTask",
                "getCommentAnalysisTask", "enqueueAnalysisTask", "registerUser", "loginUser", "getCommentAnalysis",
                "createComment", "listCourseComments", "listMyComments", "deleteComment", "getCourseAnalytics",
                "createCourse", "updateCourse", "deleteCourse", "getCourse", "listCourses", "listPopularCourses", "getHealth");
        assertThat(api.findValues("example")).isEmpty();
        assertThat(api.findValues("examples")).isEmpty();
        api.findValuesAsText("$ref").forEach(ref -> assertThat(api.at(ref.substring(1)).isMissingNode()).isFalse());
        assertThat(api.at("/components/schemas/UserLoginRequest/properties/password/writeOnly").asBoolean()).isTrue();
        assertThat(api.at("/components/schemas/UserLoginRequest/properties/password/format").asText()).isEqualTo("password");
        assertThat(api.at("/components/schemas/UserLoginRequest/properties").has("passwordWithinByteLimit")).isFalse();
        assertThat(api.at("/components/schemas/UserRegisterRequest/properties").has("passwordWithinByteLimit")).isFalse();
        assertThat(api.at("/components/schemas/ApiError/properties/data/type").asText()).isEqualTo("null");
        assertThat(api.at("/components/schemas/ApiResponseVoid/properties/data/type").asText()).isEqualTo("null");
        JsonNode login = responseSchema(api, operation(api, "/api/auth/login", "post"), "200", "application/json");
        assertThat(resolve(api, login.at("/properties/data")).path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn", "userId", "username", "displayName", "role");
    }

    @Test
    void shouldDescribeCourseLifecycleAndGenericResponseBodies() throws Exception {
        JsonNode api = api();
        JsonNode create = operation(api, "/api/courses", "post");
        JsonNode request = resolve(api, create.at("/requestBody/content/application~1json/schema"));
        assertThat(request.path("required")).contains(objectMapper.getNodeFactory().textNode("code"),
                objectMapper.getNodeFactory().textNode("name"), objectMapper.getNodeFactory().textNode("teacherName"));
        assertThat(request.at("/properties/code/maxLength").asInt()).isEqualTo(32);
        JsonNode created = responseSchema(api, create, "201", "application/json");
        assertThat(created.at("/properties/data/type").asText()).isEqualTo("integer");
        assertThat(created.at("/properties/data/format").asText()).isEqualTo("int64");

        JsonNode detail = responseSchema(api, operation(api, "/api/courses/{id}", "get"), "200", "application/json");
        assertThat(resolve(api, detail.at("/properties/data")).path("properties").has("teacherName")).isTrue();
        JsonNode list = operation(api, "/api/courses", "get");
        assertThat(list.has("requestBody")).isFalse();
        assertThat(list.path("parameters").findValuesAsText("name")).containsExactlyInAnyOrder("page", "size", "keyword");
        assertThat(list.path("parameters").findValuesAsText("in")).containsOnly("query");
        JsonNode page = resolve(api, responseSchema(api, list, "200", "application/json").at("/properties/data"));
        assertThat(page.path("properties").has("totalPages")).isTrue();
        assertThat(resolve(api, page.at("/properties/items/items")).path("properties").has("id")).isTrue();

        JsonNode delete = operation(api, "/api/courses/{id}", "delete");
        assertThat(delete.path("responses").fieldNames()).toIterable().containsExactlyInAnyOrder("204", "400", "401", "403", "404", "409");
        assertThat(delete.at("/responses/204").has("content")).isFalse();
        assertThat(responseSchema(api, delete, "409", "application/json").path("properties").fieldNames())
                .toIterable().containsExactlyInAnyOrder("code", "message", "data");
    }

    @Test
    void shouldDescribeMultipartUploadAndCsvDownload() throws Exception {
        JsonNode api = api();
        JsonNode upload = operation(api, "/api/courses/{courseId}/analysis-batches", "post");
        JsonNode multipart = resolve(api, upload.at("/requestBody/content/multipart~1form-data/schema"));
        assertThat(multipart.at("/properties/file/type").asText()).isEqualTo("string");
        assertThat(multipart.at("/properties/file/format").asText()).isEqualTo("binary");
        JsonNode csv = responseSchema(api, operation(api, "/api/analysis-batches/{batchId}/export", "get"), "200", "text/csv");
        assertThat(csv.path("type").asText()).isEqualTo("string");
        assertThat(csv.path("format").asText()).isEqualTo("binary");
    }

    @Test
    void shouldServeSwaggerUiWithoutLoginAndKeepBusinessApisProtected() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
        mvc.perform(get("/api/courses")).andExpect(status().isUnauthorized());
    }

    private JsonNode api() throws Exception {
        return objectMapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode operation(JsonNode api, String path, String method) {
        return api.path("paths").path(path).path(method);
    }

    private JsonNode responseSchema(JsonNode api, JsonNode operation, String status, String mediaType) {
        return resolve(api, operation.path("responses").path(status).path("content").path(mediaType).path("schema"));
    }

    private JsonNode resolve(JsonNode api, JsonNode schema) {
        return schema.has("$ref") ? api.at(schema.path("$ref").asText().substring(1)) : schema;
    }
}
