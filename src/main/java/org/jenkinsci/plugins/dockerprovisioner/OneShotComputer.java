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
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class OneShotComputer extends SlaveComputer {

    private final OneShotExecutor slave;

    public OneShotComputer(OneShotExecutor slave) {
        super(slave);
        this.slave = slave;
    }

    /**
     * Claim we are online so we get task assigned to the executor, so a ${@link Run} is created, then can actually
     * launch and report provisioning status in the build log.
     */
    @Override
    public boolean isOffline() {
        return false;
    }

    @Override
    public OneShotExecutor getNode() {
        return slave;
    }


    @Extension
    public final static ComputerListener COMPUTER_LISTENER = new ComputerListener() {

        @Override
        public void preLaunch(Computer c, TaskListener listener) throws IOException, InterruptedException {
            if (c instanceof OneShotComputer) {
                ((OneShotComputer) c).getNode().setComputerListener(listener);
            }
        }
    };


    /**
     * We only accept one task on this computer, so can just shut down on task completion
     * @param executor
     * @param task
     * @param durationMS
     */
    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        terminate();
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        terminate();
    }

    private void terminate() {
        try {
            Jenkins.getActiveInstance().removeNode(slave);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * ${@link Computer#getDefaultCharset()} is the first computer method used by
     * ${@link hudson.model.Run#execute(Run.RunExecution)} when a job is executed.
     * Relying on this implementation detail is fragile, but we don't really have a better
     * option yet.
     */
    @Override
    public Charset getDefaultCharset() {

        final Queue.Executable executable = Executor.currentExecutor().getCurrentExecutable();
        if (executable instanceof Run) {
            getNode().setRun((Run) executable);
        }
        return super.getDefaultCharset();
    }

    private static final Logger LOGGER = Logger.getLogger(OneShotComputer.class.getName());


}
