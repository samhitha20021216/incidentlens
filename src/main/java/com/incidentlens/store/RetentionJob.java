package com.incidentlens.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.incidentlens.config.IncidentLensProperties;

@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final LogStore logs;
    private final IncidentLensProperties props;

    public RetentionJob(LogStore logs, IncidentLensProperties props) {
        this.logs = logs;
        this.props = props;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void purge() {
        int deleted = logs.deleteOlderThan(props.logRetention());
        if (deleted > 0) {
            log.info("Deleted {} log events older than {}", deleted, props.logRetention());
        }
    }
}
