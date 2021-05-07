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

import hudson.Extension;
import hudson.Launcher;
import hudson.console.ConsoleLogFilter;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.ModelObject;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.model.listeners.RunListener;
import hudson.model.queue.CauseOfBlockage;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.ComputerLauncherFilter;
import hudson.slaves.EphemeralNode;
import hudson.slaves.RetentionStrategy;
import hudson.util.StreamTaskListener;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An agent that is designed to be used only once, for a specific ${@link hudson.model.Run}, and as such has a life cycle
 * to fully match the Run's one.
 * <p>
 * Provisioning such an agent should be a lightweight process, so one can provision them at any time and concurrently
 * to match ${@link hudson.model.Queue} load. Typical usage is Docker container based Jenkins agents.
 * <p>
 * Actual launch of the agent is postponed until a ${@link Run} is created, so we can have a 1:1 match between Run and
 * Executor lifecycle:
 * <ul>
 *     <li>dump the launch log in build log.</li>
 *     <li>mark the build as ${@link Result#NOT_BUILT} on launch failure.</li>
 *     <li>shut down and remove the Executor on build completion</li>
 * </ul>
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class OneShotSlave extends Slave {

    private static final long serialVersionUID = 42L;

    /**
     *  Charset used by the computer, used to write into log.
     *  We can't detect this as a classic executor would as we write into the build log while the computer get launched.
     */
    private final String charset;

    /** The ${@link Run} assigned to this OneShotSlave */
    private transient Run executable;

    /** ID of the item from build Queue we are assigned to */
    private final long queueItemId;

    private String taskName;

    /** Flag for failure creating the associated Agent */
    private transient boolean dead;

    /**
     * @param queueItem
     *        The {@link Queue.Item} this agent is assigned to
     * @param nodeDescription
     *        Node description for UI
     * @param remoteFS
     *        agent working directory
     * @param launcher
     *        {@link ComputerLauncher} used to bootstrap this agent.
     * @param charset
     *        Computer's Charset. Need to be set by caller as we can't determine this one before actual launch.
     * @throws Descriptor.FormException
     * @throws IOException
     */
    public OneShotSlave(Queue.BuildableItem queueItem, String nodeDescription, String remoteFS, final ComputerLauncher launcher, Charset charset) throws Descriptor.FormException, IOException {
        // Create an agent with a NoOp launcher, we will run the launcher later when a Run has been created.
        super(Long.toHexString(System.nanoTime()), remoteFS, new OneShotComputerLauncher(launcher));
        this.queueItemId = queueItem.getId();
        this.taskName = queueItem.task.getDisplayName();
        setNodeDescription(nodeDescription);
        setNumExecutors(1);
        setMode(Mode.EXCLUSIVE);
        setRetentionStrategy(RetentionStrategy.NOOP);
        this.charset = charset.name();
    }

    @Override
    public String getDisplayName() {
        return "Executor for " + taskName;
    }

    @Override
    public CauseOfBlockage canTake(Queue.BuildableItem item) {

        if (this.queueItemId == item.getId()) return null;

        final Label label = item.task.getAssignedLabel();
        if (label != null) {
            if (label.matches(this)) return null;
        }

        return BecauseNodeIsDedicated;
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
        return hasExecutable()
            ? "executor for " + executable.getFullDisplayName()
            : super.getNodeDescription();
    }

    protected Charset getCharset() {
        return Charset.forName(charset);
    }

    @Override
    public OneShotComputer createComputer() {
        return new OneShotComputer(this);
    }

    @Override
    public int getNumExecutors() {
        return 1;
    }

    protected boolean hasExecutable() {
        return executable != null;
    }

    // for UI
    public Run getExecutable() {
        return executable;
    }

    // for UI
    public String getTaskName() {
        return taskName;
    }

    @Override
    public OneShotComputer getComputer() {
        if (dead) return new DeadComputer(this);
        return (OneShotComputer) super.getComputer();
    }



    // --- Actual Run execution detection

    @Extension
    public final static RunListener<Run> LISTENER = new RunListener<Run>() {

        @Override
        public void onInitialize(Run run) {
            final Node node = Computer.currentComputer().getNode();
            if (node instanceof OneShotSlave) {
                // Assign the OneShotExecutor it's run, so it can actually launch agent
                ((OneShotSlave) node).setExecutable(run);
            }
        }
    };


    /**
     * Pipeline does not use the same mechanism a other jobs to allocate nodes, especially does not notify
     * {@link RunListener}s. So we also need to consider ${@link #createLauncher(TaskListener)}
     * as an event to determine first use of the agent. see https://issues.jenkins-ci.org/browse/JENKINS-35521
     */
    @Override
    public Launcher createLauncher(TaskListener listener) {
        setExecutable(listener);

        return super.createLauncher(listener);
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

        TaskListener listener = TaskListener.NULL;
        try {
            OutputStream os = new FileOutputStream(run.getLogFile(), true);
            for (ConsoleLogFilter f : ConsoleLogFilter.all()) {
                try {
                    os = f.decorateLogger(run, os);
                } catch (IOException | InterruptedException e) {
                    LOGGER.log(Level.WARNING, "Failed to filter log with " + f, e);
                }
            }
            listener = new StreamTaskListener(os, getCharset());
        } catch (FileNotFoundException e) {
            throw new OneShotExecutorProvisioningError(e);
        }

        setExecutable(run, listener);
    }

    /**
     * Set executable based on current Executor activity.
     * Required for pipeline support.
     */
    private void setExecutable(TaskListener listener) {

        if (this.executable != null) {
            // allready provisionned
            return;
        }

        final Executor executor = Executor.currentExecutor();
        if (executor == null) throw new IllegalStateException("No executor set");

        setExecutable(executor.getCurrentExecutable().getParent(), listener);
    }

    /* package */ void setExecutable(Object executable, TaskListener listener) {

        synchronized (this) {
            if (this.executable != null) {
                return;
            }
            if (executable instanceof Run) {
                this.executable = (Run) executable;
            } else if (executable instanceof ExecutorStepExecution.PlaceholderTask) {
                this.executable = ((ExecutorStepExecution.PlaceholderTask) executable).run();
            } else {
                throw new IllegalArgumentException(executable.getClass().getName() + " is not supported.");
            }
        }

        if (executable instanceof ModelObject) {
            this.taskName = this.executable.getFullDisplayName();
        }
        doActualLaunch(listener);
    }

    private void doActualLaunch(TaskListener listener) {

        beforeLaunch(executable, listener);

        try {
            final OneShotComputer computer = this.getComputer();
            // replace computer log listener with build one, so we capture provisionning issues.
            getActualLauncher().launch(computer, listener);

            if (getComputer().isActuallyOffline()) {
                listener.getLogger().println("Failed to provision Agent");
                if (executable instanceof Run) {
                    ((Run)executable).setResult(Result.NOT_BUILT);
                }
                dead = true;
            }
        } catch (Exception e) {
            listener.getLogger().println("Failed to provision Agent");
            if (executable instanceof Run)
                ((Run)executable).setResult(Result.NOT_BUILT);
            e.printStackTrace(listener.getLogger());
            dead = true;
        }
    }

    /**
     * Offers an opportunity to customize te laucher/computer based on assigned {@link Run}.
     */
    protected void beforeLaunch(Run executable, TaskListener listener) {
        listener.getLogger().println("Launching a dedicated Agent for " + executable.getFullDisplayName());
    }

    /**
     * Retrieve the actual agent Launcher. We expose a No-Operation ComputerLauncher to Jenkins API and use this
     * one for actual just-in-time provisioning.
     * Due to JENKINS-39232 we can't just use distinct classes, see {@link OneShotComputerLauncher}
     * @return
     */
    private ComputerLauncher getActualLauncher() {
        return ((ComputerLauncherFilter) getLauncher()).getCore();
    }


    private static final Logger LOGGER = Logger.getLogger(OneShotComputer.class.getName());
}
