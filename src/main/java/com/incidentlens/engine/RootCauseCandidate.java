package com.incidentlens.engine;

import java.util.List;

/**
 * @param suspectedDependency uninstrumented component (database, external API) most of this service's
 *                            errors involve, if any
 */
public record RootCauseCandidate(String service, double score, String suspectedDependency, List<Evidence> evidence) {
}
