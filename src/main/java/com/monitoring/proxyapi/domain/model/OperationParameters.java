package com.monitoring.proxyapi.domain.model;

import com.monitoring.proxyapi.domain.exception.InvalidRequestParameterException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Typed, read-only view over the loosely typed {@code Object... params} of {@code MicroserviceProxy#execute}.
 * <p>
 * The varargs signature is imposed by the shared interface, so every service would otherwise repeat the
 * same casting and null checks. Centralising them here keeps validation messages consistent across the
 * proxy (access control) and the real services (business validation).
 */
public final class OperationParameters {

    private static final OperationParameters EMPTY = new OperationParameters(Map.of());

    private final Map<String, Object> values;

    private OperationParameters(Map<String, Object> values) {
        this.values = values;
    }

    public static OperationParameters from(Object... params) {
        if (params == null || params.length == 0) {
            return EMPTY;
        }
        if (params.length == 1) {
            Object single = params[0];
            if (single == null) {
                return EMPTY;
            }
            if (single instanceof OperationParameters parameters) {
                return parameters;
            }
            if (single instanceof Map<?, ?> map) {
                return fromMap(map);
            }
        }
        if (params.length % 2 == 0) {
            return fromKeyValuePairs(params);
        }
        throw new InvalidRequestParameterException("params",
                "must be a JSON object or key/value pairs, received " + params.length + " positional values");
    }

    private static OperationParameters fromMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return new OperationParameters(Collections.unmodifiableMap(copy));
    }

    private static OperationParameters fromKeyValuePairs(Object[] params) {
        Map<String, Object> pairs = new LinkedHashMap<>();
        for (int index = 0; index < params.length; index += 2) {
            if (!(params[index] instanceof String key) || key.isBlank()) {
                throw new InvalidRequestParameterException("params",
                        "parameter name at position " + index + " must be a non-blank string");
            }
            pairs.put(key, params[index + 1]);
        }
        return new OperationParameters(Collections.unmodifiableMap(pairs));
    }

    public boolean has(String name) {
        Object value = values.get(name);
        return value != null && !(value instanceof String text && text.isBlank());
    }

    public String requireText(String name) {
        if (!has(name)) {
            throw InvalidRequestParameterException.missing(name);
        }
        return values.get(name).toString().trim();
    }

    public String optionalText(String name, String fallback) {
        return has(name) ? values.get(name).toString().trim() : fallback;
    }

    public int requirePositiveInt(String name) {
        if (!has(name)) {
            throw InvalidRequestParameterException.missing(name);
        }
        int value = toInt(name, values.get(name));
        if (value <= 0) {
            throw new InvalidRequestParameterException(name, "must be greater than zero");
        }
        return value;
    }

    public int optionalInt(String name, int fallback) {
        return has(name) ? toInt(name, values.get(name)) : fallback;
    }

    public BigDecimal requireAmount(String name) {
        if (!has(name)) {
            throw InvalidRequestParameterException.missing(name);
        }
        return toAmount(name, values.get(name));
    }

    public Optional<BigDecimal> optionalAmount(String name) {
        return has(name) ? Optional.of(toAmount(name, values.get(name))) : Optional.empty();
    }

    public boolean flag(String name) {
        Object value = values.get(name);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString().trim());
    }

    public List<Map<String, Object>> requireObjectList(String name) {
        Object value = values.get(name);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new InvalidRequestParameterException(name, "must be a non-empty array");
        }
        List<Map<String, Object>> elements = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> map)) {
                throw new InvalidRequestParameterException(name, "must contain JSON objects only");
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), item));
            elements.add(copy);
        }
        return elements;
    }

    public Map<String, Object> asMap() {
        return values;
    }

    private static int toInt(String name, Object value) {
        if (value instanceof Number number) {
            double asDouble = number.doubleValue();
            if (asDouble != Math.rint(asDouble) || Math.abs(asDouble) > Integer.MAX_VALUE) {
                throw new InvalidRequestParameterException(name, "must be a whole number");
            }
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ex) {
            throw new InvalidRequestParameterException(name, "must be a whole number");
        }
    }

    private static BigDecimal toAmount(String name, Object value) {
        BigDecimal amount;
        try {
            amount = value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString().trim());
        } catch (NumberFormatException ex) {
            throw new InvalidRequestParameterException(name, "must be a decimal amount");
        }
        if (amount.signum() <= 0) {
            throw new InvalidRequestParameterException(name, "must be a positive amount");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
