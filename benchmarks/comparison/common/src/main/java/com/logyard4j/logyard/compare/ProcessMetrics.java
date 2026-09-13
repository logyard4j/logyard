package com.logyard4j.logyard.compare;

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Samples living platform threads before shutdown; unavailable virtual-thread counters stay explicit. */
public final class ProcessMetrics {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final OperatingSystemMXBean PROCESS = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final Map<Long, Sample> samples;
    private final long processCpu;

    private ProcessMetrics(Map<Long, Sample> samples, long processCpu) {
        this.samples = samples;
        this.processCpu = processCpu;
    }

    public static ProcessMetrics capture(Set<Long> producers, String workerToken) {
        if (!THREADS.isThreadAllocatedMemoryEnabled()) THREADS.setThreadAllocatedMemoryEnabled(true);
        if (!THREADS.isThreadCpuTimeEnabled()) THREADS.setThreadCpuTimeEnabled(true);
        Map<Long, Sample> samples = new HashMap<>();
        for (long id : THREADS.getAllThreadIds()) {
            ThreadInfo info = THREADS.getThreadInfo(id);
            if (info == null) continue;
            String role = producers.contains(id) ? "caller" : info.getThreadName().contains(workerToken) ? "worker" : "other";
            samples.put(id, new Sample(role, THREADS.getThreadAllocatedBytes(id), THREADS.getThreadCpuTime(id)));
        }
        return new ProcessMetrics(samples, PROCESS.getProcessCpuTime());
    }

    public Map<String, Long> since(ProcessMetrics before, boolean virtualThreads) {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("process_cpu_ns", processCpu - before.processCpu);
        for (String role : new String[] {"caller", "worker", "other"}) {
            long allocation = 0;
            long cpu = 0;
            long count = 0;
            for (Map.Entry<Long, Sample> entry : samples.entrySet()) {
                Sample current = entry.getValue();
                if (!current.role.equals(role)) continue;
                Sample previous = before.samples.get(entry.getKey());
                allocation += current.allocation - (previous == null ? 0 : previous.allocation);
                cpu += current.cpu - (previous == null ? 0 : previous.cpu);
                count++;
            }
            values.put(role + "_threads", count);
            values.put(role + "_allocated_bytes", virtualThreads && role.equals("caller") ? -1 : allocation);
            values.put(role + "_cpu_ns", virtualThreads && role.equals("caller") ? -1 : cpu);
        }
        values.put("threads_missing_at_end", before.samples.keySet().stream().filter(id -> !samples.containsKey(id)).count());
        return values;
    }

    private record Sample(String role, long allocation, long cpu) {
    }
}
