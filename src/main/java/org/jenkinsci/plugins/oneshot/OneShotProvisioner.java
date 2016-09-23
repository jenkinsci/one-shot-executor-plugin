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

import hudson.ExtensionList;
import hudson.model.ManagementLink;
import hudson.model.Queue;

import java.util.List;

/**
 * This provisioner is responsible to create ${@link OneShotSlave}s.
 * Plugins to manage lightweight agents can use this extension point to determine jobs which require.
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public abstract class OneShotProvisioner<T extends OneShotSlave> extends ManagementLink {

    @Override
    public String getIconFileName() {
        return "/plugin/one-shot-executor/images/48x48/one-shot.png";
    }

    public static List<OneShotProvisioner> provisioners() {
        return ExtensionList.lookup(OneShotProvisioner.class);
    }

    @Override
    public String getDisplayName() {
        return "One-Shot executors";
    }

    @Override
    public String getUrlName() {
        return "one-shot-executors";
    }

    /**
     * Determine if this ${@link Queue.Item} do rely on One-Shot executors, and should be
     * handled by this specific provisioner.
     */
    protected abstract boolean usesOneShotExecutor(Queue.Item item);

    /**
     * Determine if the underlying infrastructure has enough resources to create a slave
     * for this ${@link Queue.Item}.
     * <p>
     * Implementation can rely on this to reduce concurrent executors on a static infrastructure,
     * or to schedule new resources on an elastic one.
     */
    public abstract boolean canRun(Queue.Item item);

    /**
     * Prepare a ${@link OneShotSlave} to run this ${@link Queue.BuildableItem}. The actual
     * slave isn't launched, just <em>prepared</em> which means we can use it's node name as
     * a label to for assignment.
     */
    public abstract OneShotSlave prepareExecutorFor(Queue.BuildableItem item) throws Exception;
}


