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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Executor;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Callable;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class OneShotComputer<S extends OneShotSlave> extends SlaveComputer {

    private final S slave;
    private TaskListener listener;

    public OneShotComputer(S slave) {
        super(slave);
        this.slave = slave;
    }

    @Override
    public S getNode() {
        return slave;
    }

    /**
     * Claim we are online so we get task assigned to the executor, so a ${@link Run} is created, then can actually
     * launch and report provisioning status in the build log.
     */
    @Override
    public boolean isOffline() {
        return false;
    }

    public boolean isActuallyOffline() {
        return super.isOffline();
    }

    @Override
    @SuppressFBWarnings(value="RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
    protected final void removeExecutor(Executor e) {
        setAcceptingTasks(false);
        threadPoolForRemoting.submit(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                try {
                    Jenkins.getInstance().removeNode(slave);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                terminate(getListener());
                return null;
            }
        });
        super.removeExecutor(e);
    }

    /**
     * Implement can override this method to cleanly terminate the executor and cleanup resources.
     * @param listener build log so one can report proper termination
     */
    protected void terminate(TaskListener listener) throws Exception {
    }

    @Override
    public void setChannel(Channel channel, OutputStream launchLog, Channel.Listener listener) throws IOException, InterruptedException {
        try {
            super.setChannel(channel, launchLog, listener);
        } catch (IOException e) {
            // Failed to establish channel - used to capture failure to launch JNLP slaves
            e.printStackTrace(getListener().getLogger());
            throw e;
        }
    }

    public void setListener(TaskListener listener) {
        this.listener = listener;
    }

    @Override
    public TaskListener getListener() {
        if (listener == null) return super.getListener();
        return listener;
    }
}
