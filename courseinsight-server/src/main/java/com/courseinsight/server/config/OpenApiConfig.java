package com.courseinsight.server.config;

import com.courseinsight.server.controller.AuthController;
import com.courseinsight.server.controller.HealthController;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI courseInsightOpenApi() {
        return new OpenAPI()
                .info(new Info().title("CourseInsight API").version("v1")
                        .description("课程评价与分析接口。受保护接口使用 JWT Bearer；具体角色限制见各操作说明。"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
                        .addSchemas("ApiError", new ObjectSchema()
                                .addProperty("code", new IntegerSchema().description("与 HTTP 状态码相同"))
                                .addProperty("message", new StringSchema().description("错误说明"))
                                .addProperty("data", new Schema<>().types(Set.of("null")).description("错误时固定为 null"))
                                .required(List.of("code", "message", "data"))))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    @Bean
    public OpenApiCustomizer voidResponseSchema() {
        return api -> {
            Schema<?> response = api.getComponents().getSchemas().get("ApiResponseVoid");
            if (response != null) {
                response.addProperty("data", new Schema<>().types(Set.of("null")));
            }
        };
    }

    @Bean
    public OperationCustomizer commonApiResponses() {
        return (operation, handlerMethod) -> {
            Class<?> controller = handlerMethod.getBeanType();
            boolean publicEndpoint = controller == AuthController.class || controller == HealthController.class;
            if (publicEndpoint) {
                operation.setSecurity(List.of());
            } else {
                operation.getResponses().addApiResponse("401", errorResponse("未登录或令牌无效"));
                operation.getResponses().addApiResponse("403", errorResponse("角色或对象访问权限不足"));
            }
            if (controller != HealthController.class) {
                operation.getResponses().addApiResponse("400", errorResponse("请求参数校验失败或格式错误"));
            }
            return operation;
        };
    }

    private ApiResponse errorResponse(String description) {
        return new ApiResponse().description(description).content(new Content()
                .addMediaType("application/json", new io.swagger.v3.oas.models.media.MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/ApiError"))));
    }
}
