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

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.SlaveComputer;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class OneShotComputer extends SlaveComputer {

    private final OneShotSlave slave;

    public OneShotComputer(OneShotSlave slave) {
        super(slave);
        this.slave = slave;
    }

    /**
     * Claim we are online so we get job attached to the executor, then can actually launch
     * and report provisioning failure in the build log.
     */
    @Override
    public boolean isOffline() {
        return false;
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
            Run run = (Run) executable;
            final OneShotAssignment action = run.getAction(OneShotAssignment.class);
            // if (action == null) return;
            doLaunch(run, action);
        }
        return super.getDefaultCharset();
    }

    private void doLaunch(Run run, OneShotAssignment action) {
        try {
            final FileOutputStream out = new FileOutputStream(new File(run.getRootDir(), "executor.log"));
            final StreamTaskListener listener = new StreamTaskListener(out);

            OneShotSlave slave = (OneShotSlave) Jenkins.getActiveInstance().getNode(action.getAssignedNodeDisplayName());
            slave.doLaunchFor(run, listener);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failure to launch One-Shot Slave", e);
        } catch (InterruptedException e) {
            LOGGER.log(Level.SEVERE, "Failure to launch One-Shot Slave", e);
        }


    }

    /**
     * Claim we can connect immediately. Actual connection will take place when the ${@link hudson.model.Run} starts.
     */
    @Override
    protected Future<?> _connect(boolean forceReconnect) {
        return Computer.threadPoolForRemoting.submit(new java.util.concurrent.Callable<Object>() {
            public Object call() throws Exception {
                return null;
            }
        });
    }

    private static final Logger LOGGER = Logger.getLogger(OneShotComputer.class.getName());


}
