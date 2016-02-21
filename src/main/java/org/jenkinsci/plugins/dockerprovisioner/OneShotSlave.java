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

import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.model.queue.CauseOfBlockage;
import hudson.remoting.Channel;
import hudson.remoting.LocalChannel;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import hudson.util.StreamTaskListener;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A slave that is designed to be used only once, for a specific ${@link hudson.model.Run}.
 * Provisioning such a slave should be a lightweight process, so one can provision them at any time and concurrently
 * to match ${@link hudson.model.Queue} load. Typical usage is Docker container based Jenkins agents.
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class OneShotSlave extends Slave {

    private final ComputerLauncher launcher;

    public OneShotSlave(String nodeDescription, String remoteFS, String labelString, ComputerLauncher launcher, List<? extends NodeProperty<?>> nodeProperties) throws Descriptor.FormException, IOException {
        this(Long.toHexString(System.nanoTime()), nodeDescription, remoteFS, labelString, launcher, nodeProperties);
    }

    public OneShotSlave(String name, String nodeDescription, String remoteFS, String labelString, ComputerLauncher launcher, List<? extends NodeProperty<?>> nodeProperties) throws Descriptor.FormException, IOException {
        super(name, nodeDescription, remoteFS, 1, Mode.EXCLUSIVE, labelString, NOOP_LAUNCHER, RetentionStrategy.NOOP, nodeProperties);
        this.launcher = launcher;
    }


    @Override
    public Computer createComputer() {
        return new OneShotComputer(this);
    }

    @Override
    public CauseOfBlockage canTake(Queue.BuildableItem item) {
        final OneShotAssignment assignment = item.getAction(OneShotAssignment.class);
        if (assignment == null) return DEDICATED;
        if (! assignment.getAssignedNodeDisplayName().equals(getDisplayName())) return DEDICATED;

        return null;
    }

    @Override
    public boolean isAcceptingTasks() {
        return true;
    }



    public static final CauseOfBlockage DEDICATED = new CauseOfBlockage() {
        @Override
        public String getShortDescription() {
            return "This slave is dedicated to another task";
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

    /**
     * Launch the actual Jenkins agent for the specified Executable.
     */
    public void doLaunchFor(Run run, TaskListener listener) throws IOException, InterruptedException {
        launcher.launch(this.getComputer(), listener);
    }
}
