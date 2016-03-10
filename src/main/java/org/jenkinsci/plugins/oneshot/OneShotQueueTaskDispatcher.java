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
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
@Extension
public class OneShotQueueTaskDispatcher extends QueueTaskDispatcher {

    private static final CauseOfBlockage OVERLOADED = new CauseOfBlockage() {
        @Override
        public String getShortDescription() {
            return "Waiting for resources";
        }
    };

    @Override
    public CauseOfBlockage canRun(Queue.Item item) {
        for (OneShotProvisioner provisioner : ExtensionList.lookup(OneShotProvisioner.class)) {
            if (provisioner.usesOneShotExecutor(item)) {
                if (!provisioner.canRun(item)) {
                    return OVERLOADED;
                }
            }
        }
        return null;
    }

}
