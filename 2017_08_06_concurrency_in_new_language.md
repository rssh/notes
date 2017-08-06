

# So, I want to add concurrency to my language...

 // in addition to talk with @anemish about spectrum of design choices for [lasca language](https://github.com/nau/lasca-compiler)

## TLDR:
* All hight-level models are not universal and ‘bad’ in some sense. 
* Exists one ‘good’  low-level model, which will allow implementing all known high-level models on top of one, but it’s not free.  We can name one ‘stackless execution’ or ‘fast stack switching’  or  ‘total CPS.'   

## Long version: What bad with:

1.  Actors.  [ Erlang, Scala, Pony, …. ] 
    * Actors are not composable.  (Or - in other words, they are composable exactly as objects in OO:   if you have 2 actors:  (A, B), you have no automatically ‘Composition’ of ones.   Note, that this problem is not dependent on ‘Typed/Untyped’  problem:  even if you have actor behavior coded in some typed calculus (behavior or session types) you still have no ‘natural’ compositions.   
So, actors are an effective way of implementing low-level behavior,  but ‘actor programming’ is not scaled.

2. Channels  (CSP=Communicated Sequential Processes: Occam, Limbo, Go ….  ) 
     * CSP operations are not safe by construction.  CSP, in some sense ‘better’ than actors:  if we have two processes, we can build a pipe between than. But it is easy to construct a set of two pipes, waiting for each other. 
     * Properties of original CSP model are difficult to implement in distributed environment:  (A <c> B,  when A send a message to B then A resume execution after B receive one.) This property requires extra roundtrip if A and B are situated on different hosts. 
     * Most implementation of CSP in programming languages are too low-level and not introduce term ‘Process’ with an explicit operation, but rather force a programmer to think concerning informal,  implicit processes.  So, again - programming with channels is not scaled. 

3.  Dataflow programming (sometimes named Ozz - style concurrency): [Ozz,  Alice …  ] 

     * The main problem is an absence of control of evaluation. Ie.  in a situation where Future[X] is undistinguished from X it is hard to know exactly the sequence and timing characteristics.   


4.  Implicit parallelism.  - too high level.


For now, most programmers languages provide more limited mechanisms, such as asynchronous method calls  (JavaScript,  Python) for interpreted languages and async/await macro transformations  (Scala,  Nim) for compiling. (which are very limited without runtime support for switching stacks).


What will be optimal concurrency mechanism for new general-purpose language?

Set of runtime routines, which will allow:
* spawn a lightweight process
* suspend lightweight process, awaiting some result  (MB, from another process) and allow using this result as a value
* switch to another lightweight process  (previously stored in value)  (giving them a result, if needed)

Exists two implementation techniques:
* Total CPS  [Continuation Passing Style]  transform, which will ‘eliminate’ stack switching effect, by
* Implement fast stack switching (using stalkless code-generator and runtime support) and automatic transformation of ‘all’ calls into asynchronous form.   

----------
[index](https://github.com/rssh/notes)
