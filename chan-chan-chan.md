Why channels of channels of channels can be useful?  Why we often see statements like `make(chan chan chan int)` in  Go code ?

Let's look at example [here](https://rogpeppe.wordpress.com/2009/12/01/concurrent-idioms-1-broadcasting-values-in-go-with-linked-channels/)
or [scala version](https://github.com/rssh/scala-gopher/blob/master/src/test/scala/example/BroadcasterSuite.scala)

While listener is a type of `Channel[Channel[Channel[Message]]]` ?

The answer is -  exchanging information between goroutines in dynamic ways, like an emulation of 
[asynchronous method calls](https://en.wikipedia.org/wiki/Asynchronous_method_invocation)  calls between objects.

Let we want to request information which must return to us some 'A'.
Instead calling `o.f(x)` and invocation of a method which will return `A` on the stack, we create a channel 
(of type `Channel[A]`) and pass one to target goroutine via endpoint channel (which will have type `Channel[Channel[A]]`).  
After this goroutine will retrieve `a` and send one back to the canal which was received from the endpoint.  

So, if we see in signature `Channel[Channel[A]]`  this usually means  "entry for information request about 'A'".
 
-----------------------
[index](https://github.com/rssh/notes)
