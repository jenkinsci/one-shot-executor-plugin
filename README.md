# one-shot-executor-plugin

This is a prototype for an Executor infrastructure to have dedicated per-job executor on Jenkins, strictly tied to a job. 
So executor provisioning details are part of the build log, and failure to provision will fail the build.

* create a new `Slave` as job enter the queue. Claim slave is connected
* As Job is assigned to executor and start a Run, actually launch the executor and log into build log


## Implementation Details

One-Shot Executors are provisioned by dedicated Jenkins extensions. They don't rely on the `Cloud` API and it's
`NodeProvisioner` provisioning strategy, but immediately when requested.

The `OneShotExecutor` Slave name is set as a lable on the queue's task, and the Slave claim
to be online to enforce it get assigned by the Queue logic to this specific executor. As a Result, a 'Run' object is
created.

There's no early initialization event we can listen to to catch this specific timing when the `Run` is created but
didn't yet accessed the assigned `Computer`. As a _temporary workaround_ we rely on some Run's lifecycle implementation 
details. See PR#TODO

As the `Run` is started the `OneShotExecutor` slave is actually launched, it's launch log is piped to the build log.
TODO: maybe we should only do this when something goes wrong ?

If launch fails, the Run is also marked as failed. When build completes, the executor is terminated.
 
There's no event we can listen to to catch the Executor termination. If we use existing listener we run into a race
condition as we try to terminate a Slave/Computer still being used by an active Executor. As a _temporary workaround_ 
we rely on some Executor's lifecycle implementation details. See PR#TODO 