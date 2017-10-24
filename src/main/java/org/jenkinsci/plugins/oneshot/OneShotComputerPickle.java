/*
 * The MIT License
 *
 *  Copyright (c) 2017, CloudBees, Inc.
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

import com.google.common.util.concurrent.ListenableFuture;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Node;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.pickles.Pickle;
import org.jenkinsci.plugins.workflow.support.pickles.SingleTypedPickleFactory;
import org.jenkinsci.plugins.workflow.support.pickles.TryRepeatedly;

import java.io.File;
import java.io.IOException;

/**
 * A custom pickle to rehydrate OneShotComputer and re-inject a WorkflowRun Executable. This will trigger
 * computer launch dumping the launch logs into build log, so any failure to reconnect can be reported to end-user.
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class OneShotComputerPickle extends Pickle {


    private final String name;

    public OneShotComputerPickle(OneShotComputer computer) throws IOException {
        this.name = computer.getName();
    }


    @Override
    public ListenableFuture<OneShotComputer> rehydrate(final FlowExecutionOwner owner) {
        return new TryRepeatedly<OneShotComputer>(1) {
            @Override
            protected OneShotComputer tryResolve() {
                Jenkins j = Jenkins.getInstance();
                if (j == null) {
                    return null;
                }
                try {
                    Node n =Jenkins.getInstance().getNode(name);
                    if (n == null) {
                        return null;
                    }
                    if (n instanceof OneShotSlave) {
                        OneShotSlave os = (OneShotSlave) n;
                        os.setExecutable(owner.getExecutable(), owner.getListener());
                        return os.getComputer();
                    }
                    return null;
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to rehydrate OneShotSlavePickle", e);
                }
            }
            @Override public String toString() {
                return "Looking for computer named ‘" + name + "’";
            }
        };
    }

    @Extension
    public static final class Factory extends SingleTypedPickleFactory<OneShotComputer> {
        @Override protected Pickle pickle(OneShotComputer computer) {
            try {
                return new OneShotComputerPickle(computer);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create OneShotSlavePickle", e);
            }
        }
    }
}
