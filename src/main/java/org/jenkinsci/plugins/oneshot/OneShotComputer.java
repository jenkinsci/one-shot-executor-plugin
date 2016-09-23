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

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
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
                terminate(getListener());
                try {
                    Jenkins.getInstance().removeNode(slave);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return null;
            }
        });
        super.removeExecutor(e);
    }

    /**
     * Implement can override this method to cleanly terminate the executor and cleanup resources.
     * @param listener build log so one can report proper termination
     */
    protected void terminate(TaskListener listener) {
    }
}
