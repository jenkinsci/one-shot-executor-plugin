# one-shot-executor-plugin

This is a prototype for an Executor infrastructure to have dedicated per-job executor on Jenkins, strictly tied to a job. 
So executor provisioning details are part of the build log, and failure to provision will fail the build.

* create a new `Slave` as job enter the queue. Claim slave is connected
* As Job is assigned to executor and start a Run, actually launch the executor and log into build log
