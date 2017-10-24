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

import hudson.model.InvisibleAction;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAssignmentAction;
import hudson.model.labels.LabelAtom;
import hudson.model.queue.SubTask;
import hudson.util.VariableResolver;
import jenkins.model.Jenkins;

import javax.annotation.CheckForNull;

/**
 * This action track the ${@link OneShotSlave} allocated for a task.
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class OneShotAssignment extends InvisibleAction implements LabelAssignmentAction {


    private final OneShotAssignementLabel label;

    public OneShotAssignment(String assignedNodeName) {
        this.label = new OneShotAssignementLabel(assignedNodeName);
    }

    public @CheckForNull
    OneShotSlave getAssignedNode() {
        return (OneShotSlave) Jenkins.getInstance().getNode(label.getNodeName());
    }

    @Override
    public Label getAssignedLabel(SubTask task) {
        return label;
    }


    public static class OneShotAssignementLabel extends LabelAtom {

        public OneShotAssignementLabel(String nodeName) {
            super(nodeName);
        }

        protected String getNodeName() {
            return name;
        }

        public boolean matches(OneShotSlave node) {
            return getNodeName().equals(node.getNodeName());
        }
    }
}
