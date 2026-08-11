package com.rpa.financial_intelligence_system.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI enterpriseOpenApi() {
        String scheme = "BearerAuth";
        return new OpenAPI()
            .info(new Info().title("FinSight 企业智能管理平台 API")
                .description("企业级用户、角色、菜单、部门、知识库与大模型能力接口文档。登录接口无需认证；其余接口请点击右上角“Authorize”，填入登录返回的 JWT Token。")
                .version("1.0.0").contact(new Contact().name("FinSight 技术团队")))
            .tags(List.of(
                new Tag().name("身份认证").description("登录与当前用户信息"),
                new Tag().name("系统管理").description("用户、角色、菜单和部门管理"),
                new Tag().name("AI 智能中心").description("模型对话、向量知识库和语义检索")))
            .addSecurityItem(new SecurityRequirement().addList(scheme))
            .components(new Components().addSecuritySchemes(scheme,
                new SecurityScheme().name(scheme).type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT").description("请输入登录接口返回的 Token，无需添加 Bearer 前缀")));
    }
}
