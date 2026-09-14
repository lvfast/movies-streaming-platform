package com.lvfast.transcoder.jobs;

/** Executes one claimed job within its bounded slot while holding an active lease. */
public interface JobExecutor {

    void execute(ClaimResult claim, LeaseGuard lease);
}
