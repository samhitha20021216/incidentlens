package com.incidentlens.simulator;

import java.util.List;

/** Something that goes wrong in the simulated system while a scenario is active. */
public sealed interface Fault {

    String service();

    /** A component that {@code service} calls (database, external API) gets slow and/or starts failing. */
    record DependencyFault(String service, String dependency, long extraLatencyMs, double failureProbability,
                           String exceptionType, String message, List<String> frames) implements Fault {
    }

    /** The service's own code fails, e.g. a bad deploy or a misconfiguration. */
    record ServiceFault(String service, double failureProbability, int status, String exceptionType,
                        String message, List<String> frames) implements Fault {
    }
}
