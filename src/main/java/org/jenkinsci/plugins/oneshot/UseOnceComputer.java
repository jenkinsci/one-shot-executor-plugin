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
import hudson.model.ExecutorListener;
import hudson.model.Queue;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.model.queue.QueueListener;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.durabletask.executors.ContinuableExecutable;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.logging.Level;

/**
 * A computer that will be used only once, for a build, then destroyed.
 * Better than relying on {@link hudson.slaves.RetentionStrategy} we capture the {@link Executor}
 * end-of-life event.
 */
public class UseOnceComputer extends SlaveComputer implements ExecutorListener {

    public UseOnceComputer(Slave slave) {
        super(slave);
    }


    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        super.taskCompleted(executor, task, durationMS);
        done(executor);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        super.taskCompletedWithProblems(executor, task, durationMS, problems);
        done(executor);
    }

    @SuppressFBWarnings(value="RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
    protected final void done(Executor executor) {

        Queue.Executable exec = executor.getCurrentExecutable();
        if (exec instanceof ContinuableExecutable && ((ContinuableExecutable) exec).willContinue()) {
            // sounds like jenkins is restarting and need to shut down executor, but task will continue after restart
        } else {
            setAcceptingTasks(false);
            recordTermination();
            threadPoolForRemoting.submit(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    final Slave node = getNode();
                    if (node != null) {
                        Jenkins.getInstance().removeNode(node);
                    }
                    terminate();
                    return null;
                }
            });
        }
        super.removeExecutor(executor);
    }

    /**
     * Implement can override this method to cleanly terminate the executor and cleanup resources.
     */
    protected void terminate() throws Exception {
    }


}
