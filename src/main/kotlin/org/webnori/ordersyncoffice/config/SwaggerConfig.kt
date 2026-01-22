package org.webnori.ordersyncoffice.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.Contact
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SwaggerConfig {

    @Bean
    fun openAPI(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("OrderSyncOffice API")
                    .description("Imweb Order Sync Office API Documentation")
                    .version("v1.0.0")
                    .contact(
                        Contact()
                            .name("OrderSyncOffice")
                            .email("support@ordersyncoffice.com")
                    )
            )
    }
}
