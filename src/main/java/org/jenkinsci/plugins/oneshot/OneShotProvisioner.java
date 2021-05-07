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

import com.google.common.base.Predicate;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.TaskListener;
import hudson.model.queue.CauseOfBlockage;
import jenkins.model.Jenkins;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * This provisioner is responsible to create ${@link OneShotSlave}s.
 * Plugins to manage lightweight agents can use this extension point to determine jobs which require.
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public abstract class OneShotProvisioner<T extends OneShotSlave> implements ExtensionPoint {

    /**
     * Determine if this ${@link Queue.Item} do rely on One-Shot executors, and should be
     * handled by this specific provisioner.
     */
    public abstract boolean usesOneShotExecutor(Queue.Item item);

    /**
     * Determine if the underlying infrastructure has enough resources to create an agent
     * for this ${@link Queue.Item}.
     * <p>
     * Implementation can rely on this to reduce concurrent executors on a static infrastructure,
     * or to schedule new resources on an elastic one.
     */
    public abstract boolean canRun(Queue.Item item);

    /**
     * Prepare a ${@link OneShotSlave} to run this ${@link Queue.BuildableItem}. The actual
     * agent isn't launched, just <em>prepared</em> which means we can use it's node name as
     * a label to for assignment. Implementation should create adequate {@link OneShotSlave}
     * derived class <em>but</em> not run any actual provisioning, which will get postponed
     * until the run has started and {@link OneShotSlave#doActualLaunch(TaskListener)} is ran.
     */
    public abstract @Nonnull T prepareExecutorFor(Queue.BuildableItem item) throws Exception;

    public static List<OneShotProvisioner> all() {
        return ExtensionList.lookup(OneShotProvisioner.class);
    }

    /**
     * Utility method to count active one-shot-executors.
     * Implementors can rely on this to implement ${@link #canRun(Queue.Item)} using a simple <em>max number of instances</em>
     * approach, comparable to ${@link hudson.slaves.AbstractCloudImpl#setInstanceCap(int)}
     */
    protected int countExecutors(Class<T> type, Predicate<T> ... filters) {
        int slaveCount = 0;
        NODES:
        for (Node node : Jenkins.getInstance().getNodes()) {
            if (type.isAssignableFrom(type)) {
                T s = (T) node;
                for (Predicate<T> filter : filters) {
                    if (filter.apply(s)) break NODES;
                }
                slaveCount++;
            }
        }
        return slaveCount;
    }

    public static final CauseOfBlockage WAIT_FOR_RESOURCES = new CauseOfBlockage() {
        @Override
        public String getShortDescription() {
            return "Waiting for available resources";
        }
    };

}


