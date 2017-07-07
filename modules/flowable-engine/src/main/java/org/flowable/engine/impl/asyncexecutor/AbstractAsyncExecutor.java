/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flowable.engine.impl.asyncexecutor;

import java.util.LinkedList;
import java.util.UUID;

import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.cmd.UnacquireOwnedJobsCmd;
import org.flowable.engine.impl.persistence.entity.JobInfoEntity;
import org.flowable.engine.impl.persistence.entity.JobInfoEntityManager;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.engine.runtime.JobInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Joram Barrez
 * @author Tijs Rademakers
 * @author Marcus Klimstra
 */
public abstract class AbstractAsyncExecutor implements AsyncExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractAsyncExecutor.class);

    protected boolean timerRunnableNeeded = true; // default true for backwards compatibility (History Async executor came later)
    protected AcquireTimerJobsRunnable timerJobRunnable;
    protected String acquireRunnableThreadName;
    protected JobInfoEntityManager<? extends JobInfoEntity> jobEntityManager;
    protected AcquireAsyncJobsDueRunnable asyncJobsDueRunnable;
    protected String resetExpiredRunnableName;
    protected ResetExpiredJobsRunnable resetExpiredJobsRunnable;

    protected ExecuteAsyncRunnableFactory executeAsyncRunnableFactory;
    
    protected AsyncRunnableExecutionExceptionHandler asyncRunnableExecutionExceptionHandler;

    protected boolean isAutoActivate;
    protected boolean isActive;
    protected boolean isMessageQueueMode;

    protected int maxTimerJobsPerAcquisition = 1;
    protected int maxAsyncJobsDuePerAcquisition = 1;
    protected int defaultTimerJobAcquireWaitTimeInMillis = 10 * 1000;
    protected int defaultAsyncJobAcquireWaitTimeInMillis = 10 * 1000;
    protected int defaultQueueSizeFullWaitTime;

    protected String lockOwner = UUID.randomUUID().toString();
    protected int timerLockTimeInMillis = 5 * 60 * 1000;
    protected int asyncJobLockTimeInMillis = 5 * 60 * 1000;
    protected int retryWaitTimeInMillis = 500;

    protected int resetExpiredJobsInterval = 60 * 1000;
    protected int resetExpiredJobsPageSize = 3;

    // Job queue used when async executor is not yet started and jobs are
    // already added.
    // This is mainly used for testing purpose.
    protected LinkedList<JobInfo> temporaryJobQueue = new LinkedList<>();

    protected ProcessEngineConfigurationImpl processEngineConfiguration;

    public boolean executeAsyncJob(final JobInfo job) {
        if (isMessageQueueMode) {
            // When running with a message queue based job executor,
            // the job is not executed here.
            return true;
        }

        Runnable runnable = null;
        if (isActive) {
            runnable = createRunnableForJob(job);
            return executeAsyncJob(job, runnable);
        } else {
            temporaryJobQueue.add(job);
        }

        return true;
    }

    protected abstract boolean executeAsyncJob(final JobInfo job, Runnable runnable);

    protected void unlockOwnedJobs() {
        processEngineConfiguration.getCommandExecutor().execute(new UnacquireOwnedJobsCmd(lockOwner, null));
    }

    protected Runnable createRunnableForJob(final JobInfo job) {
        if (executeAsyncRunnableFactory == null) {
            return new ExecuteAsyncRunnable(job, processEngineConfiguration, jobEntityManager, asyncRunnableExecutionExceptionHandler);
        } else {
            return executeAsyncRunnableFactory.createExecuteAsyncRunnable(job, processEngineConfiguration);
        }
    }

    /** Starts the async executor */
    public void start() {
        if (isActive) {
            return;
        }

        isActive = true;

        LOGGER.info("Starting up the async job executor [{}].", getClass().getName());

        initializeJobEntityManager();
        initializeRunnables();
        startAdditionalComponents();
        executeTemporaryJobs();
    }
    
    protected void initializeJobEntityManager() {
        if (jobEntityManager == null) {
            jobEntityManager = processEngineConfiguration.getJobEntityManager();
        }
    }

    protected void initializeRunnables() {
        if (timerRunnableNeeded && timerJobRunnable == null) {
            timerJobRunnable = new AcquireTimerJobsRunnable(this, processEngineConfiguration.getJobManager());
        }

        JobInfoEntityManager<? extends JobInfoEntity> jobEntityManagerToUse = jobEntityManager != null
                ? jobEntityManager : CommandContextUtil.getProcessEngineConfiguration().getJobEntityManager();

        if (resetExpiredJobsRunnable == null) {
            String resetRunnableName = resetExpiredRunnableName != null ? resetExpiredRunnableName : "flowable-reset-expired-jobs";
            resetExpiredJobsRunnable = new ResetExpiredJobsRunnable(resetRunnableName, this, jobEntityManagerToUse);
        }

        if (!isMessageQueueMode && asyncJobsDueRunnable == null) {
            String acquireJobsRunnableName = acquireRunnableThreadName != null ? acquireRunnableThreadName : "flowable-acquire-async-jobs";
            asyncJobsDueRunnable = new AcquireAsyncJobsDueRunnable(acquireJobsRunnableName, this, jobEntityManagerToUse);
        }
    }

    protected abstract void startAdditionalComponents();

    protected void executeTemporaryJobs() {
        while (!temporaryJobQueue.isEmpty()) {
            JobInfo job = temporaryJobQueue.pop();
            executeAsyncJob(job);
        }
    }

    /** Shuts down the whole job executor */
    public synchronized void shutdown() {
        if (!isActive) {
            return;
        }
        LOGGER.info("Shutting down the async job executor [{}].", getClass().getName());

        stopRunnables();
        shutdownAdditionalComponents();

        isActive = false;
    }

    protected void stopRunnables() {
        if (timerJobRunnable != null) {
            timerJobRunnable.stop();
        }
        if (asyncJobsDueRunnable != null) {
            asyncJobsDueRunnable.stop();
        }
        if (resetExpiredJobsRunnable != null) {
            resetExpiredJobsRunnable.stop();
        }

        timerJobRunnable = null;
        asyncJobsDueRunnable = null;
        resetExpiredJobsRunnable = null;
    }

    protected abstract void shutdownAdditionalComponents();

    /* getters and setters */

    public ProcessEngineConfigurationImpl getProcessEngineConfiguration() {
        return processEngineConfiguration;
    }

    public void setProcessEngineConfiguration(ProcessEngineConfigurationImpl processEngineConfiguration) {
        this.processEngineConfiguration = processEngineConfiguration;
    }

    public boolean isAutoActivate() {
        return isAutoActivate;
    }

    public void setAutoActivate(boolean isAutoActivate) {
        this.isAutoActivate = isAutoActivate;
    }

    public boolean isActive() {
        return isActive;
    }

    public boolean isMessageQueueMode() {
        return isMessageQueueMode;
    }

    public void setMessageQueueMode(boolean isMessageQueueMode) {
        this.isMessageQueueMode = isMessageQueueMode;
    }

    public String getLockOwner() {
        return lockOwner;
    }

    public void setLockOwner(String lockOwner) {
        this.lockOwner = lockOwner;
    }

    public int getTimerLockTimeInMillis() {
        return timerLockTimeInMillis;
    }

    public void setTimerLockTimeInMillis(int timerLockTimeInMillis) {
        this.timerLockTimeInMillis = timerLockTimeInMillis;
    }

    public int getAsyncJobLockTimeInMillis() {
        return asyncJobLockTimeInMillis;
    }

    public void setAsyncJobLockTimeInMillis(int asyncJobLockTimeInMillis) {
        this.asyncJobLockTimeInMillis = asyncJobLockTimeInMillis;
    }

    public int getMaxTimerJobsPerAcquisition() {
        return maxTimerJobsPerAcquisition;
    }

    public void setMaxTimerJobsPerAcquisition(int maxTimerJobsPerAcquisition) {
        this.maxTimerJobsPerAcquisition = maxTimerJobsPerAcquisition;
    }

    public int getMaxAsyncJobsDuePerAcquisition() {
        return maxAsyncJobsDuePerAcquisition;
    }

    public void setMaxAsyncJobsDuePerAcquisition(int maxAsyncJobsDuePerAcquisition) {
        this.maxAsyncJobsDuePerAcquisition = maxAsyncJobsDuePerAcquisition;
    }

    public int getDefaultTimerJobAcquireWaitTimeInMillis() {
        return defaultTimerJobAcquireWaitTimeInMillis;
    }

    public void setDefaultTimerJobAcquireWaitTimeInMillis(int defaultTimerJobAcquireWaitTimeInMillis) {
        this.defaultTimerJobAcquireWaitTimeInMillis = defaultTimerJobAcquireWaitTimeInMillis;
    }

    public int getDefaultAsyncJobAcquireWaitTimeInMillis() {
        return defaultAsyncJobAcquireWaitTimeInMillis;
    }

    public void setDefaultAsyncJobAcquireWaitTimeInMillis(int defaultAsyncJobAcquireWaitTimeInMillis) {
        this.defaultAsyncJobAcquireWaitTimeInMillis = defaultAsyncJobAcquireWaitTimeInMillis;
    }

    public void setTimerJobRunnable(AcquireTimerJobsRunnable timerJobRunnable) {
        this.timerJobRunnable = timerJobRunnable;
    }

    public int getDefaultQueueSizeFullWaitTimeInMillis() {
        return defaultQueueSizeFullWaitTime;
    }

    public void setDefaultQueueSizeFullWaitTimeInMillis(int defaultQueueSizeFullWaitTime) {
        this.defaultQueueSizeFullWaitTime = defaultQueueSizeFullWaitTime;
    }

    public void setAsyncJobsDueRunnable(AcquireAsyncJobsDueRunnable asyncJobsDueRunnable) {
        this.asyncJobsDueRunnable = asyncJobsDueRunnable;
    }

    public void setTimerRunnableNeeded(boolean timerRunnableNeeded) {
        this.timerRunnableNeeded = timerRunnableNeeded;
    }

    public void setAcquireRunnableThreadName(String acquireRunnableThreadName) {
        this.acquireRunnableThreadName = acquireRunnableThreadName;
    }

    public void setJobEntityManager(JobInfoEntityManager<? extends JobInfoEntity> jobEntityManager) {
        this.jobEntityManager = jobEntityManager;
    }
    
    public void setResetExpiredRunnableName(String resetExpiredRunnableName) {
        this.resetExpiredRunnableName = resetExpiredRunnableName;
    }

    public void setResetExpiredJobsRunnable(ResetExpiredJobsRunnable resetExpiredJobsRunnable) {
        this.resetExpiredJobsRunnable = resetExpiredJobsRunnable;
    }

    public int getRetryWaitTimeInMillis() {
        return retryWaitTimeInMillis;
    }

    public void setRetryWaitTimeInMillis(int retryWaitTimeInMillis) {
        this.retryWaitTimeInMillis = retryWaitTimeInMillis;
    }

    public int getResetExpiredJobsInterval() {
        return resetExpiredJobsInterval;
    }

    public void setResetExpiredJobsInterval(int resetExpiredJobsInterval) {
        this.resetExpiredJobsInterval = resetExpiredJobsInterval;
    }

    public int getResetExpiredJobsPageSize() {
        return resetExpiredJobsPageSize;
    }

    public void setResetExpiredJobsPageSize(int resetExpiredJobsPageSize) {
        this.resetExpiredJobsPageSize = resetExpiredJobsPageSize;
    }

    public ExecuteAsyncRunnableFactory getExecuteAsyncRunnableFactory() {
        return executeAsyncRunnableFactory;
    }

    public void setExecuteAsyncRunnableFactory(ExecuteAsyncRunnableFactory executeAsyncRunnableFactory) {
        this.executeAsyncRunnableFactory = executeAsyncRunnableFactory;
    }

    public AsyncRunnableExecutionExceptionHandler getAsyncRunnableExecutionExceptionHandler() {
        return asyncRunnableExecutionExceptionHandler;
    }

    public void setAsyncRunnableExecutionExceptionHandler(AsyncRunnableExecutionExceptionHandler asyncRunnableExecutionExceptionHandler) {
        this.asyncRunnableExecutionExceptionHandler = asyncRunnableExecutionExceptionHandler;
    }
    
}
