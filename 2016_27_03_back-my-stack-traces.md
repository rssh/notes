
# Give my stacktraces back

One of the annoying things when debugging concurrent Scala programs - is luck of stack traces from asynchronous calls 
with ```Future```s.   Ie. when we span task and receive an exception from one, then it is impossible to find in the exception trace, 
from where this task was spawned. 

  I just wrote a debug agent, which fix this issue: https://github.com/rssh/trackedfuture

----------
[index](https://github.com/rssh/notes)
