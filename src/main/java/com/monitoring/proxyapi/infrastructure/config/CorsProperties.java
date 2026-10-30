package com.monitoring.proxyapi.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Origins allowed to call the API, bound from {@code app.cors.allowed-origin-patterns}
 * (comma separated, overridable with the {@code CORS_ALLOWED_ORIGINS} environment variable).
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(@DefaultValue({"http://localhost:5173", "https://*.vercel.app"}) List<String> allowedOriginPatterns) {
}
