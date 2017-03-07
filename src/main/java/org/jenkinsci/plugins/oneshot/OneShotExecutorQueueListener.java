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
import hudson.ExtensionList;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.labels.LabelAtom;
import hudson.model.listeners.RunListener;
import hudson.model.queue.QueueListener;
import jenkins.model.Jenkins;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This ${@link QueueListener} is responsible for detecting jobs that are relying on a One-Shot executor.
 * When a task becomes buildable, it check all configured ${@link OneShotProvisioner} to determine if task do
 * match one of them criteria, then provision a ${@link OneShotSlave} and assign it's name to the task as a
 * label. As a result, the task won't be assigned to any executor but the one it just created.
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
@Extension
public class OneShotExecutorQueueListener extends QueueListener {

    /**
     * As an item enter the queue, provision a dedicated "one-shot" executor to host the build.
     */
    @Override
    public void onEnterBuildable(Queue.BuildableItem item) {

        final Label label = item.task.getAssignedLabel();
        if (label != null) {
            for (Node node : label.getNodes()) {
                if (node instanceof OneShotSlave) {
                    for (LabelAtom atom : label.listAtoms()) {
                        if (node.getNodeName().equals(atom.getName())) {
                            // exact slave name match
                            // So this item is labelled to run on an _existing_ one-shot-slave
                            // This happens as a pipeline re-hydrate after restart
                            // So we don't have to create a fresh new slave for it.
                            return;
                        }
                    }
                }
            }
        }

        for (OneShotProvisioner provisioner : OneShotProvisioner.all()) {
            if (provisioner.usesOneShotExecutor(item)) {
                try {
                    OneShotSlave slave = provisioner.prepareExecutorFor(item);
                    item.addAction(new OneShotAssignment(slave.getNodeName()));
                    Jenkins.getInstance().addNode(slave);
                    // slave.connect(true);
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Failure to create One-Shot Slave", e);
                    // where to report this to user ?
                    Jenkins.getInstance().getQueue().cancel(item);
                }
                return;
            }
        }

    }

    /**
     * If item is canceled, remove the executor we created for it.
     */
    @Override
    public void onLeft(Queue.LeftItem item) {
        if (item.isCancelled()) {
            OneShotAssignment action = item.getAction(OneShotAssignment.class);
            if( action == null) return;
            Node slave = action.getAssignedNode();
            if (slave == null) return;
            try {
                Jenkins.getInstance().removeNode(slave);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failure to remove One-Shot Slave", e);
            }
        }
    }

    @Extension
    public final static RunListener<Run> LISTENER = new RunListener<Run>() {

        @Override
        public void onInitialize(Run run) {
            final Node node = Computer.currentComputer().getNode();
            if (node instanceof OneShotSlave) {
                // Assign the OneShotExecutor it's run, so it can actually provision executor node
                ((OneShotSlave) node).setExecutable(run);
            }
        }
    };

    private static final Logger LOGGER = Logger.getLogger(OneShotProvisioner.class.getName());
}
