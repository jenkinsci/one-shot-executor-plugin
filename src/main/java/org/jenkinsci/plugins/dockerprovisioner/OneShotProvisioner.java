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
import hudson.model.AbstractBuild;
import hudson.model.Descriptor;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.model.queue.QueueListener;
import hudson.slaves.CommandLauncher;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;

import javax.annotation.CheckForNull;
import java.io.IOException;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This provisioner is responsible to create ${@link OneShotSlave}s.
 * Plugins to manage lightweight agents can use this extension point to manage agent lifecycle.
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
@Extension
public class OneShotProvisioner {




    /**
     * As a ${@link hudson.model.Queue.BuildableItem} enter the Queue, and it has been flagged with
     * a ${@link OneShotExecutor} action, this method will be executed to provision a ${@link OneShotSlave}
     * to host the build. Can return null if the OneShotExecutor does not match this specific implemenation.
     * @param item
     * @return
     */
    protected @CheckForNull OneShotSlave provision(Queue.BuildableItem item, OneShotExecutor action)  {
        try {
            return new OneShotSlave("executor for " + item.getDisplayName(),
                    "/Users/nicolas/jenkins", null,
                    new CommandLauncher("java -jar /Users/nicolas/Downloads/slave.jar"), Collections.EMPTY_LIST);
        } catch (Descriptor.FormException e) {
            LOGGER.log(Level.WARNING, "Failure to provision One-Shot Slave", e);
            return null;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failure to provision One-Shot Slave", e);
            return null;
        }
    }

    @Extension
    public final static QueueListener QUEUE_LISTENER = new QueueListener() {

        /**
         * As a ${@link OneShotExecutor} item enter the queue, provision an executor to host the build so
         * it get assigned without delay.
         */
        @Override
        public void onEnterBuildable(Queue.BuildableItem item) {

            final OneShotExecutor action = item.getAction(OneShotExecutor.class);
            // if (action == null) return;

            for (OneShotProvisioner provisioner : Jenkins.getActiveInstance().getExtensionList(OneShotProvisioner.class)) {
                OneShotSlave slave = provisioner.provision(item, action);
                if (slave != null) {
                    item.addAction(new OneShotAssignment(slave.getNodeName()));
                    try {
                        Jenkins.getActiveInstance().addNode(slave);
                    } catch (IOException e) {
                        LOGGER.log(Level.SEVERE, "Failure to register One-Shot Slave", e);
                        // where to report this to user ?
                        Jenkins.getActiveInstance().getQueue().cancel(item);
                    }
                    break;
                }
            }
        }

    };

    @Extension
    public final static RunListener RUN_LISTENER = new RunListener<Run>() {

        /**
         * Build is done, executor can be shutdown as it won't be re-used
         * @param run
         */
        @Override
        public void onFinalized(Run run) {
            OneShotAssignment assignment = run.getAction(OneShotAssignment.class);
            if (assignment == null) return;

            final OneShotSlave slave = (OneShotSlave) Jenkins.getActiveInstance().getNode(assignment.getAssignedNodeDisplayName());
            if (slave == null) return; // already deleted

            try {
                Jenkins.getActiveInstance().removeNode(slave);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    };

    private static final Logger LOGGER = Logger.getLogger(OneShotProvisioner.class.getName());

}
