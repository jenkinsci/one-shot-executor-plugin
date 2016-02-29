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

package org.jenkinsci.plugins.dockerprovisioner;

import hudson.Extension;
import hudson.console.ConsoleLogFilter;
import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

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
public class OneShotExecutor extends Slave {

    private final transient ComputerLauncher launcher;

    /** Listener to log computer's launch and activity */
    private transient TeeSpongeTaskListener computerListener;

    /** The ${@link Run} assigned to this OneShotSlave */
    private transient Run run;

    public OneShotExecutor(Queue.BuildableItem item, String remoteFS, ComputerLauncher launcher, List<? extends NodeProperty<?>> nodeProperties) throws Descriptor.FormException, IOException {
        // Create a slave with a NoOp launcher, we will run the launcher later when a Run has been created.
        super(Long.toHexString(System.nanoTime()), null, remoteFS, 1, Mode.EXCLUSIVE, null, NOOP_LAUNCHER, RetentionStrategy.NOOP, nodeProperties);
        this.launcher = launcher;
    }

    @Override
    public String getNodeDescription() {
        return hasRun()
            ? "executor for " + run.getFullDisplayName()
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

    /**
     * Assign the ${@link ComputerLauncher} listener as the node is actually started, so we can pipe it to the
     * ${@link Run} log. We need this as we can't just use <code>getComputer().getListener()</code>
     *
     * @see OneShotComputer#COMPUTER_LISTENER
     */
    public void setComputerListener(TaskListener computerListener) {
        try {
            final File log = File.createTempFile("one-shot", "log");

            // We use a "Tee+Sponge" TaskListener here as Run's log is created after computer has been first acceded
            // If this can be changed in core, we would just need a "Tee"
            this.computerListener = new TeeSpongeTaskListener(computerListener, log);
        } catch (IOException e) {
            e.printStackTrace(); // FIXME
        }
    }

    protected boolean hasRun() {
        return run != null;
    }

    @Override
    public OneShotComputer getComputer() {
        return (OneShotComputer) super.getComputer();
    }

    /**
     * Assign a ${@link Run} to this OneShotSlave. By design, only one Run can be assigned, then slave is shut down.
     * This method has to be called just as the ${@link Run} as been created. It run the actual launch of the executor
     * and collect it's log so we can pipe it to the Run's ${@link hudson.model.BuildListener} (which is created later).
     * <p>
     * Delaying launch of the executor until the Run is actually started allows to fail the build on launch failure,
     * so we have a strong 1:1 relation between a Run and it's Executor.
     */
    public void setRun(Run run) {
        this.run = run;

        if (computerListener == null) throw new IllegalStateException("computerListener has't been set yet - can't launch");

        try {
            launcher.launch(this.getComputer(), computerListener);

            if (getComputer().isActuallyOffline()) {
                run.setResult(Result.NOT_BUILT);
                throw new OneShotExecutorProvisioningError();
            }
        } catch (Exception e) {
            run.setResult(Result.NOT_BUILT);
            throw new OneShotExecutorProvisioningError(e);
        }
    }


    /**
     * We listen to loggers creation by ${@link Run}s so we can write the executor's launch log into build log.
     */
    @Extension
    public static final ConsoleLogFilter LOG_FILTER = new ConsoleLogFilter() {

        @Override
        public OutputStream decorateLogger(AbstractBuild run, OutputStream logger) throws IOException, InterruptedException {
            OneShotAssignment assignment = run.getAction(OneShotAssignment.class);
            if (assignment == null) return logger;

            final OneShotExecutor slave = assignment.getAssignedNode();
            if (slave == null) return logger;

            slave.computerListener.setSideOutputStream(logger);
            return logger;
        }
    };

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

}
