
# About current debates about shrinking the Scala user base and the slow adoption of Scala 3:


The issue is that most Scala web backend projects use a Future-based stack. (Something like 53% at the end of 2022, as I remember).   And after the Lightbend license change,  they still need a clear path to migrate or how to start a new project in this space.  Moving to effect systems, like cats-effect IO or ZIO, is an overkill[1][2], cask is `underkill`; tapir + netty, in principle, can be a foundation of the next solution. Still, it has yet to be ready (the last time I looked at it, the Netty backend did not supports websockets).  And if the ecosystem has no proposal for more than 50% of the current market,  it loses.


Assembling a stack, which will align with the mainstream, is not a problem; we have all elements available now, from structured concurrency to async/await.  All that is needed is to aggregate existing stuff, mainly writing documentation, some glue code, and provide one point of support.  In an ideal world, I imagine some organization (maybe DAO) that provides subscription services and customer support for such a stack [like RedHat] and distribute part of the subscription fee to the authors of the components.  If you are planning something similar - let me know ;)


The problem is that nobody does this for economic reasons: the Lightbend story shows that the model "we support open-source stack for all for free and optionally sell support"  is not profitable enough. 


Yet another complication is that after project Loom finishes incubation and becomes a part of JDK, we will see the second round of the disruption of async frameworks. There is no point in investing in a solution that will become obsolete in a few months.

I'm sure that after those few monthes, we will see an exciting offering in this space.  How niche/mainstream it will be (along with the Scala language itself)  -  it's a question that depends on founding a suitable business model.

Update:
[1] Is overkill is some simplification. It is better to say it is overkill, and converting Future to some effect system is a lot of work because those models are different: Future responsibility of running and canceling an appropriative process is on the supplier; in IO/ZIO, the consumer runs this process. Those semantics are different – you can't automatically or semi-automatically convert applications written with  Future to IO/ZIOt:  you will need to rethink all flow and redefine it entirely with the new distribution of responsibilities between consumer and supplier,  

[2] Having a good Future stack will be in the interests of the effect systems adherents, too,  because it will be possible to convert neophytes to use effects in the next Scala project. With a user base, this will be a little easier.
