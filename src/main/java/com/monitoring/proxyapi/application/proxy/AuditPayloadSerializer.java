package com.monitoring.proxyapi.application.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monitoring.proxyapi.domain.exception.DomainException;
import com.monitoring.proxyapi.domain.model.OperationParameters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns invocation inputs, results and failures into the JSON strings stored in the audit log.
 * <p>
 * Two production concerns live here rather than in the proxy itself:
 * <ul>
 *   <li>Sensitive fields (PAN, CVV, secrets) are masked before they are persisted, because an audit
 *       trail that stores raw card numbers would immediately fall under PCI DSS scope.</li>
 *   <li>Serialization never throws: auditing must not be the reason a business call fails.</li>
 * </ul>
 */
public class AuditPayloadSerializer {

    private static final Set<String> SENSITIVE_KEYS = Set.of("cardnumber", "cvv", "cvc", "pan", "password", "token", "pin");
    private static final int MAX_PAYLOAD_CHARS = 16_384;

    private final ObjectMapper mapper;

    public AuditPayloadSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String serializeParams(Object... params) {
        Object source;
        try {
            // Normalises both call styles (a JSON map or key/value pairs) into one JSON object.
            source = OperationParameters.from(params).asMap();
        } catch (DomainException malformed) {
            // Still audit what the caller sent; the proxy will reject the call right afterwards.
            source = Arrays.asList(params);
        }
        return write(source);
    }

    public String serializeResult(Object result) {
        return write(result);
    }

    public String serializeError(Throwable error, List<String> stackTraceSummary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", error.getClass().getSimpleName());
        if (error instanceof DomainException domainException) {
            payload.put("errorCode", domainException.getErrorCode());
            payload.put("category", domainException.getCategory().name());
        } else {
            payload.put("errorCode", "UNEXPECTED_ERROR");
            payload.put("category", "INTERNAL");
        }
        payload.put("message", error.getMessage());
        payload.put("stackTrace", stackTraceSummary);
        return write(payload);
    }

    private String write(Object source) {
        if (source == null) {
            return "null";
        }
        try {
            JsonNode tree = mapper.valueToTree(source);
            maskSensitiveFields(tree);
            String json = mapper.writeValueAsString(tree);
            return json.length() <= MAX_PAYLOAD_CHARS ? json : truncate(json);
        } catch (IllegalArgumentException | JsonProcessingException ex) {
            return "{\"serializationError\":\"" + ex.getClass().getSimpleName() + "\"}";
        }
    }

    private String truncate(String json) throws JsonProcessingException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("truncated", true);
        envelope.put("originalLength", json.length());
        envelope.put("preview", json.substring(0, MAX_PAYLOAD_CHARS / 2));
        return mapper.writeValueAsString(envelope);
    }

    private void maskSensitiveFields(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> sensitiveFields = new ArrayList<>();
            object.fields().forEachRemaining(field -> {
                if (SENSITIVE_KEYS.contains(field.getKey().toLowerCase(Locale.ROOT))) {
                    sensitiveFields.add(field.getKey());
                } else {
                    maskSensitiveFields(field.getValue());
                }
            });
            sensitiveFields.forEach(name -> object.put(name, mask(object.get(name).asText())));
        } else if (node instanceof ArrayNode array) {
            array.forEach(this::maskSensitiveFields);
        }
    }

    private static String mask(String value) {
        String compact = value.replaceAll("[\\s-]", "");
        if (compact.length() <= 4) {
            return "****";
        }
        return "*".repeat(compact.length() - 4) + compact.substring(compact.length() - 4);
    }
}
