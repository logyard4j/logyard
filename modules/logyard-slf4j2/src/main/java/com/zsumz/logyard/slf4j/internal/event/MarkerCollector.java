package com.zsumz.logyard.slf4j.internal.event;

import com.zsumz.logyard.slf4j.internal.diagnostics.ProviderDiagnostics;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Marker;

/** Bounded, cycle-safe traversal of SLF4J marker graphs. */
public final class MarkerCollector {
    public static final int MAX_MARKERS = 64;
    public static final int MAX_DEPTH = 8;
    public static final int MAX_NAME_CHARACTERS = 256;

    public Result collect(Marker marker) {
        return marker == null ? Result.EMPTY : collect(List.of(marker));
    }

    public Result collect(List<Marker> roots) {
        if (roots == null) {
            return Result.EMPTY;
        }
        State state = new State();
        addRoots(roots, state);
        traverse(state);
        return new Result(
                List.copyOf(state.names),
                state.truncated,
                state.failures,
                state.firstFailure);
    }

    private static void addRoots(List<Marker> roots, State state) {
        Iterator<Marker> iterator;
        try {
            iterator = roots.iterator();
        } catch (Throwable failure) {
            captureFailure(state, failure);
            return;
        }
        int count = 0;
        try {
            while (count < MAX_MARKERS && iterator.hasNext()) {
                state.pending.addLast(new Node(iterator.next(), 0));
                count++;
            }
            if (iterator.hasNext()) {
                state.truncated = true;
            }
        } catch (Throwable failure) {
            captureFailure(state, failure);
        }
    }

    private static void traverse(State state) {
        while (!state.pending.isEmpty()) {
            Node node = state.pending.removeFirst();
            Marker marker = node.marker;
            if (marker == null || state.visited.put(marker, Boolean.TRUE) != null) {
                continue;
            }
            if (state.visited.size() > MAX_MARKERS) {
                state.truncated = true;
                return;
            }
            addName(marker, state);
            if (node.depth >= MAX_DEPTH) {
                if (hasChildren(marker, state)) {
                    state.truncated = true;
                }
                continue;
            }
            addChildren(marker, node.depth + 1, state);
        }
    }

    private static void addName(Marker marker, State state) {
        try {
            String name = marker.getName();
            if (name == null || name.isBlank()) {
                state.failures++;
                return;
            }
            state.names.add(name.length() <= MAX_NAME_CHARACTERS
                    ? name
                    : name.substring(0, MAX_NAME_CHARACTERS - 3) + "...");
        } catch (Throwable failure) {
            captureFailure(state, failure);
        }
    }

    private static void addChildren(Marker marker, int depth, State state) {
        Iterator<Marker> iterator;
        try {
            iterator = marker.iterator();
        } catch (Throwable failure) {
            captureFailure(state, failure);
            return;
        }
        int count = 0;
        try {
            while (count < MAX_MARKERS && iterator.hasNext()) {
                if (state.pending.size() + state.visited.size() >= MAX_MARKERS) {
                    state.truncated = true;
                    return;
                }
                state.pending.addLast(new Node(iterator.next(), depth));
                count++;
            }
            if (iterator.hasNext()) {
                state.truncated = true;
            }
        } catch (Throwable failure) {
            captureFailure(state, failure);
        }
    }

    private static boolean hasChildren(Marker marker, State state) {
        try {
            return marker.hasReferences();
        } catch (Throwable failure) {
            captureFailure(state, failure);
            return false;
        }
    }

    private static void captureFailure(State state, Throwable failure) {
        ProviderDiagnostics.rethrowIfFatal(failure);
        state.failures++;
        if (state.firstFailure == null) {
            state.firstFailure = failure;
        }
    }

    public record Result(
            List<String> names,
            boolean truncated,
            int captureFailures,
            Throwable firstFailure) {
        private static final Result EMPTY = new Result(List.of(), false, 0, null);

        public Result {
            names = Collections.unmodifiableList(new ArrayList<>(names));
        }
    }

    private record Node(Marker marker, int depth) {
    }

    private static final class State {
        private final Set<String> names = new LinkedHashSet<>();
        private final IdentityHashMap<Marker, Boolean> visited = new IdentityHashMap<>();
        private final Deque<Node> pending = new ArrayDeque<>();
        private boolean truncated;
        private int failures;
        private Throwable firstFailure;
    }
}
