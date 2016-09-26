/*
 * The MIT License
 *
 *  Copyright (c) 2016, CloudBees, Inc.
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 *
 */

package org.jenkinsci.plugins.oneshot;

import hudson.Launcher;
import hudson.console.ConsoleLogFilter;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.model.queue.CauseOfBlockage;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EphemeralNode;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import hudson.util.StreamTaskListener;

import javax.annotation.Nullable;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A slave that is designed to be used only once, for a specific ${@link hudson.model.Run}, and as such has a life cycle
 * to fully match the Run's one.
 * <p>
 * Provisioning such a slave should be a lightweight process, so one can provision them at any time and concurrently
 * to match ${@link hudson.model.Queue} load. Typical usage is Docker container based Jenkins agents.
 * <p>
 * Actual launch of the Slave is postponed until a ${@link Run} is created, so we can have a 1:1 match between Run and
 * Executor lifecycle:
 * <ul>
 *     <li>dump the launch log in build log.</li>
 *     <li>mark the build as ${@link Result#NOT_BUILT} on launch failure.</li>
 *     <li>shut down and remove the Executor on build completion</li>
 * </ul>
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class OneShotSlave extends Slave implements EphemeralNode {

    private static final long serialVersionUID = 42L;

    private final transient OneShotComputerLauncher launcher;

    /**
     *  Charset used by the computer, used to write into log.
     *  We can't detect this as a classic executor would as we write into the build log while the computer get launched.
     */
    private final String charset;

    /** The ${@link Run} or ${@link Queue.Executable} assigned to this OneShotSlave */
    private transient Object executable;

    /** ID of the item from build Queue we are assigned to */
    private final long queueItemId;

    private String taskName;

    /**
     * @param queueItem
     *        The {@link Queue.Item} this slave is assigned to
     * @param nodeDescription
     *        Node description for UI
     * @param remoteFS
     *        agent working directory
     * @param launcher
     *        {@link ComputerLauncher} used to bootstrap this slave.
     * @param charset
     *        Computer's Charset. Need to be set by caller as we can't determine this one before actual launch.
     * @throws Descriptor.FormException
     * @throws IOException
     */
    public OneShotSlave(Queue.BuildableItem queueItem, String nodeDescription, String remoteFS, OneShotComputerLauncher launcher, Charset charset) throws Descriptor.FormException, IOException {
        // Create a slave with a NoOp launcher, we will run the launcher later when a Run has been created.
        super(Long.toHexString(System.nanoTime()), remoteFS, NOOP_LAUNCHER);
        this.queueItemId = queueItem.getId();
        this.taskName = queueItem.task.getDisplayName();
        setNodeDescription(nodeDescription);
        setNumExecutors(1);
        setMode(Mode.EXCLUSIVE);
        setRetentionStrategy(RetentionStrategy.NOOP);
        this.launcher = launcher;
        this.charset = charset.name();
    }

    @Override
    public CauseOfBlockage canTake(Queue.BuildableItem item) {
        return (this.queueItemId != item.getId()) ? BecauseNodeIsDedicated : null;
    }

    @Override
    public String getDisplayName() {
        return "Executor for "+taskName;
    }

    /**
     * Build is blocked because node is dedicated to another queue item
     */
    public static final CauseOfBlockage BecauseNodeIsDedicated = new CauseOfBlockage() {

        public String getShortDescription() {
            return "Node is dedicated to another task";
        }

        @Override
        public void print(TaskListener listener) {
            listener.getLogger().println("This executor is dedicated to another item");
        }
    };

    @Override
    public String getNodeDescription() {
        return hasExecutable() && executable instanceof Run
            ? "executor for " + ((Run) executable).getFullDisplayName()
            : super.getNodeDescription();
    }

    @Override
    public Computer createComputer() {
        return new OneShotComputer(this);
    }

    @Override
    public int getNumExecutors() {
        return 1;
    }

    protected boolean hasExecutable() {
        return executable != null;
    }

    @Override
    public OneShotComputer getComputer() {
        return (OneShotComputer) super.getComputer();
    }

    /**
     * Assign a ${@link Run} to this OneShotSlave. By design, only one Run can be assigned, then slave is shut down.
     * This method has to be called just as the ${@link Run} as been created, typically relying on
     * {@link hudson.model.listeners.RunListener#fireFinalized(Run)} event. It run the actual launch of the executor
     * and collect it's log into the the Run's ${@link hudson.model.BuildListener}.
     * <p>
     * Delaying launch of the executor until the Run is actually started allows to fail the build on launch failure,
     * so we have a strong 1:1 relation between a Run and it's Executor.
     */
    public void setExecutable(Run run) {

        if (this.executable != null) {
            // allready provisionned
            return;
        }
        this.executable = run;

        TaskListener listener = TaskListener.NULL;
        try {
            OutputStream os = new FileOutputStream(run.getLogFile());
            for (ConsoleLogFilter f : ConsoleLogFilter.all()) {
                try {
                    os = f.decorateLogger(run, os);
                } catch (IOException | InterruptedException e) {
                    LOGGER.log(Level.WARNING, "Failed to filter log with " + f, e);
                }
            }
            listener = new StreamTaskListener(os, Charset.forName(charset));
        } catch (FileNotFoundException e) {
            throw new OneShotExecutorProvisioningError(e);
        }
        doActualLaunch(listener);
        this.taskName = run.getDisplayName();
    }

    /**
     * Set executable based on current Executor activity.
     * Required for pipeline support.
     */
    public void setExecutable() {

        if (this.executable != null) {
            // allready provisionned
            return;
        }

        final Executor executor = Executor.currentExecutor();
        if (executor == null) throw new IllegalStateException("No executor set");

        this.executable = executor.getCurrentExecutable();

        doActualLaunch( /* TODO JENKINS-37115 */ TaskListener.NULL);
        // TODO retrieve pipeline job's name to set taskName
    }

    protected void doActualLaunch(TaskListener listener) {

        try {
            launcher.launch(this.getComputer(), listener);

            if (getComputer().isActuallyOffline()) {
                if (executable instanceof Run)
                    ((Run)executable).setResult(Result.NOT_BUILT);
                throw new OneShotExecutorProvisioningError();
            }
        } catch (Exception e) {
            if (executable instanceof Run)
                ((Run)executable).setResult(Result.NOT_BUILT);
            throw new OneShotExecutorProvisioningError(e);
        }
    }

    /**
     * Pipeline does not use the same mechanism to use nodes, so we also need to consider ${@link #createLauncher(TaskListener)}
     * as an event to determine first use of the slave. see https://issues.jenkins-ci.org/browse/JENKINS-35521
     */
    @Override
    public Launcher createLauncher(TaskListener listener) {
        setExecutable();

        return super.createLauncher(listener);
    }

    /**
     * Fake computer launche that is jug No-op as we wait for the job to get assigned to this executor before the
     * actual launch.
     */
    private static final ComputerLauncher NOOP_LAUNCHER = new ComputerLauncher() {
        @Override
        public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
            // noop;
        }
    };

    @Override
    public OneShotSlave asNode() {
        return this;
    }

    private static final Logger LOGGER = Logger.getLogger(OneShotComputer.class.getName());
}
