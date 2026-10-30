package com.monitoring.proxyapi.infrastructure.config;

import com.monitoring.proxyapi.domain.model.ServiceId;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Global CORS policy and request parameter conversion.
 * <p>
 * Origin <i>patterns</i> are used instead of plain origins because every Vercel preview deployment
 * gets its own sub-domain ({@code https://observability-proxy-dashboard-git-feature-x.vercel.app});
 * listing them one by one is not viable.
 */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    private final CorsProperties corsProperties;

    public WebConfiguration(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(corsProperties.allowedOriginPatterns().stream().map(String::trim).toArray(String[]::new))
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Request-Id")
                .maxAge(3600);
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new ServiceIdConverter());
    }

    /**
     * Lets {@code ?service=payments} and {@code ?service=PAYMENTS} both work; blank means "all services".
     */
    static final class ServiceIdConverter implements Converter<String, ServiceId> {

        @Override
        public ServiceId convert(String source) {
            if (source.isBlank()) {
                return null;
            }
            return ServiceId.parse(source)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown service '" + source + "'"));
        }
    }
}
