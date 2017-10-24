package org.jenkinsci.plugins.oneshot;

import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.ComputerLauncherFilter;
import hudson.slaves.SlaveComputer;

import java.io.IOException;

/**
 * The {@link ComputerLauncher} we expose to Jenkins API to "launch" a One-Shot agent.
 * Due to JENKINS-39232 we can't just use a distinct class, need to expose the underlying launcher to JNLPLauncher
 * delegation discovery (sic), so we use a No-Operation {@link ComputerLauncherFilter}
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
class OneShotComputerLauncher extends ComputerLauncherFilter {

    public OneShotComputerLauncher(ComputerLauncher launcher) {
        super(launcher);
    }

    /**
     * We don't actually launch slave when requested by standard lifecycle, but only when the {@link Run} has started.
     * So this filter is used to prevent launch, while still exposing the actual {@link ComputerLauncher} to core.
     * (see JENKINS-39232)
     */
    @Override
    public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
        // NoOp
    }
}
