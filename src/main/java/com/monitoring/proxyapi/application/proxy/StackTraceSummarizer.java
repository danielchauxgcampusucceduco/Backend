package com.monitoring.proxyapi.application.proxy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Condenses a stack trace into its header plus the first N frames that matter.
 * <p>
 * A raw Spring stack trace easily exceeds 120 frames, almost all of them Tomcat, CGLIB and reflection
 * plumbing. Persisting that for every failed call would bloat the in-memory audit log and bury the
 * one line a developer needs. The heuristic is: walk to the root cause, keep frames from our own
 * code base (excluding the proxy itself, which appears in every trace), and fall back to the top
 * frames when the failure originates entirely in third-party code.
 */
public class StackTraceSummarizer {

    private static final String PROXY_CLASS = LoggingProxy.class.getName();

    private final String applicationPackage;
    private final int maxFrames;

    public StackTraceSummarizer(String applicationPackage, int maxFrames) {
        this.applicationPackage = applicationPackage;
        this.maxFrames = maxFrames;
    }

    public List<String> summarize(Throwable error) {
        Throwable root = rootCause(error);
        List<String> lines = new ArrayList<>(maxFrames + 2);
        lines.add(root.getClass().getName() + ": " + root.getMessage());

        StackTraceElement[] frames = root.getStackTrace();
        Arrays.stream(frames)
                .filter(this::isRelevant)
                .limit(maxFrames)
                .map(frame -> "at " + frame)
                .forEach(lines::add);

        if (lines.size() == 1) {
            Arrays.stream(frames).limit(maxFrames).map(frame -> "at " + frame).forEach(lines::add);
        }
        if (root != error) {
            lines.add("wrapped by " + error.getClass().getName());
        }
        return List.copyOf(lines);
    }

    private boolean isRelevant(StackTraceElement frame) {
        String className = frame.getClassName();
        return className.startsWith(applicationPackage)
                && !className.startsWith(PROXY_CLASS)
                && !className.contains("$$");
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
