---
title: Scored Logic Monad: when logic meets reinforcement learning
---

Some time ago, I wrote about a logic monad (see [Scala and logical monad programming](https://github.com/rssh/notes/blob/master/2024_01_30_logic-monad-1.md)), which is convenient for organizing logical search over the space of possible events.  In short, a logic monad represents a stream of alternatives: `mplus` combines two branches, `msplit` peels off the first result, and `mzero` is a dead end.  One limitation that bothered me in real life is the lack of prioritization.  Plain logical search is blind: all branches are equal, but usually we have preferences.  So, let's extend our logical monad for search over scored variants.

Meet `CpsScoredLogicMonad` in the new [rl-logic](https://github.com/rssh/rl-logic) package.  The idea is simple: if a logical monad is a queue of variants, then ScoredLogicMonad is a priority queue.

```Scala
trait CpsScoredLogicMonad[F[_], R: ScalingGroup : Ordering] extends CpsLogicMonad[F] {

  /** Create a pure value with a score. */
  def scoredPure[A](a: A, score: R): F[A]

  /** Create the next branch of the computation with a score. */
  def scoredMplus[A](m: F[A], scoreNext: R, next: => F[A]): F[A]

  /** Create branches of the computation according to the score. */
  def multiScore[A](m: Seq[(R, () => F[A])]): F[A] =
    m.foldLeft(empty[A]) { case (acc, (score, next)) =>
      scoredMplus(acc, score, next())
    }

  ...
}
```

Think of it as upgrading from a FIFO queue to a priority queue: in `CpsLogicMonad`, `mplus(a, b)` explores `a` then `b` in declaration order; in `CpsScoredLogicMonad`, `multiScore` assigns scores to branches and `msplit` always returns the best-scored branch first.

`R` is the score type.  `ScalingGroup[R]` defines how scores compose when you chain scored steps.  The default interpretation is multiplicative — scores are probabilities of success.  But other interpretations work too — for example, with `AdditiveScalingGroup` we can implement Dijkstra's algorithm using cumulative distance as a score[^1].

[^1]: In FP literature, the Selection monad (Escardó, M., Oliva, P.: Selection functions, bar recursion and backward induction. Mathematical Structures in Computer Science, 20(4), 2010) captures a similar idea — given a scoring function over elements, it returns the best one.  `CpsScoredLogicMonad` takes a different approach: scores are attached to branches at creation time rather than computed by an external function, and if the best-scored branch leads to a dead end, the monad backtracks to the next-best automatically.

## Dijkstra's Shortest Path

Let's express Dijkstra's algorithm as a scored logic computation.  We use negative costs as scores, so higher score = lower cost = higher priority:

```Scala
case class Edge[N](from: N, to: N, cost: Float)

trait GraphDB[N] {
  def neighbors(node: N): Seq[Edge[N]]
}

def shortestPath[F[_] : CpsScoredLogicMonad.Curry[Float], N](
    db: GraphDB[N], start: N, end: N): F[Option[IndexedSeq[N]]] = {
  import AdditiveScalingGroup.given
  val F = summon[CpsScoredLogicMonad[F, Float]]

  case class Entry(node: N, path: IndexedSeq[N], cost: Float)

  /** Expand a node to its neighbors, scoring each by negative cost. */
  def expand(e: Entry, settled: Set[N]): F[Entry] = {
    val neighbors = db.neighbors(e.node)
                      .filterNot(edge => settled.contains(edge.to))
    if (neighbors.isEmpty) F.mzero
    else F.multiScore(
      neighbors.map { edge =>
        val newCost = e.cost + edge.cost
        (-newCost, () => F.pure(Entry(edge.to, e.path :+ edge.to, newCost)))
      }
    )
  }

  /** msplit returns the best (lowest cost) entry from the frontier. */
  def dijkstra(frontier: F[Entry], settled: Set[N])
                                     : F[Option[IndexedSeq[N]]] =
    F.flatMap(F.msplit(frontier)) {
      case None => F.pure(None)
      case Some((Success(entry), rest)) =>
        if (settled.contains(entry.node))
          dijkstra(rest, settled)
        else if (entry.node == end)
          F.pure(Some(entry.path))
        else
          val expanded = expand(entry, settled + entry.node)
          F.suspended(dijkstra(F.mplus(expanded, rest), settled + entry.node))
      case Some((Failure(e), _)) => F.error(e)
    }

  val initialFrontier = F.multiScore(
    db.neighbors(start).map { edge =>
      (-edge.cost, () => F.pure(
         Entry(edge.to, IndexedSeq(start, edge.to), edge.cost)))
    }
  )
  dijkstra(initialFrontier, Set(start))
}
```

The classic Dijkstra uses a mutable priority queue.  Here, `frontier` plays that role implicitly — `msplit` always retrieves the lowest-cost entry, and `multiScore` creates scored branches explored in priority order.  The `suspended` call provides trampolining to avoid stack overflow on large graphs.

Full code is [in the repository](https://github.com/rssh/rl-logic/blob/main/shared/src/test/scala/cps/rl/examples/shortestPath/).

## Reinforcement Learning

Now, let's think about bridging ML techniques and traditional software development.  We have two worlds — in 'plain old software engineering', all is algebraic structures; in machine learning, all is numbers.  The scored logic monad lets you write declarative search where branches are guided by scores derived from a learned model.

There is a well-known framework for learning best choices based on history: Reinforcement Learning.
The main objects are the [Environment](https://github.com/rssh/rl-logic/blob/main/shared/src/main/scala/cps/rl/RLEnvironment.scala) (which has some state) and the [Agent](https://github.com/rssh/rl-logic/blob/main/shared/src/main/scala/cps/rl/RLAgentBehavior.scala), which produces actions that change state and produce feedback (reward) for the agent.

```Scala
trait RLEnvironment[S, O, A] {
  def observe(state: S): O
  def initState: S
  def isFinalState(state: S): Boolean
  def applyAction(state: S, action: A): Option[(S, Float)]
  def isActionPossible(state: S, action: A): Boolean
}
```

The bridge between the environment and the neural network is [`RLModelControl`](https://github.com/rssh/rl-logic/blob/main/shared/src/main/scala/cps/rl/RLModelControl.scala):

```Scala
trait RLModelControl[F[_], S, O, A, R, M] {
  def initialModel: M
  def rateActions(model: M, observation: O, actions: IndexedSeq[A],
                  mode: AgentRunningMode): F[A]
  def trainCase(model: M, observation: O, nextObservation: O,
                action: A, reward: Float, finish: Boolean): F[M]
}
```

Note that `rateActions` returns an action *inside the scored logic monad* `F[A]`: the neural network assigns a Q-value to each candidate action, and each becomes a branch scored by that value.  `trainCase` feeds the reward back into the model — also inside `F`, so training integrates naturally into the monadic computation.

The [agent behavior](https://github.com/rssh/rl-logic/blob/main/shared/src/main/scala/cps/rl/RLAgentBehavior.scala) ties these together. Here is the core of `performStep`, where `reify`/`reflect` let us write monadic code in direct style:

```Scala
def performStep(env: RLEnvironment[S, O, A], envState: S,
    agentBehaviorState: M, mode: AgentRunningMode) = reify[F] {
  val action = chooseAction(env, envState, agentBehaviorState, mode).reflect
  env.applyAction(envState, action) match
    case Some((newState, reward)) =>
      val observation = env.observe(envState)
      val nextObservation = env.observe(newState)
      val nextModel = modelControl.trainCase(
        agentBehaviorState, observation, nextObservation,
        action, reward, env.isFinalState(newState)).reflect
      RLAgentStepResult.Continued(newState, nextModel)
    case None =>
      RLAgentStepResult.InvalidAction(agentBehaviorState)
}
```

`chooseAction` calls `modelControl.rateActions` under the hood, so `reflect` extracts the best-scored action from the monad.  The data flow forms a closed loop: observation → neural network scores actions → best branch chosen → action applied → reward → model updated → next step.

So, if we can represent an activity as an interaction between environment and agent, we can apply reinforcement learning.  And if we already have some ad hoc heuristics, we can combine them with machine learning.

## MiniMax + Neural Network

Straightforward examples are board games, like chess or tic-tac-toe.  One way to play is to explore the other party's future moves and one's own reactions in some depth.  The other is to 'grok' the position and the next optimal move via neural network training.   With the rl-logic framework, we can implement an algorithm that leverages the best of both worlds.

For example, here is a tic-tac-toe environment:

```Scala
case class GameState(board: Board, nextPlayer: Int)
case class Move(i: Int, j: Int, player: Int)

class TikTakToeGame(boardSize: Int, n: Int)
    extends RLEnvironment[GameState, GameState, Move] {

  def observe(state: GameState): GameState = state
  def initState: GameState = GameState(Board.empty(boardSize), 1)

  override def isFinalState(state: GameState): Boolean =
    state.board.winner.isDefined || state.board.isFull

  def applyAction(state: GameState, action: Move)
                                    : Option[(GameState, Float)] =
    if !isActionPossible(state, action) then None
    else
      val newBoard = state.board.update(action.i, action.j, action.player)
      val reward = newBoard.winner match
        case Some(p) => if p == action.player then 1.0f else -1.0f
        case None    => if newBoard.isFull then -1.0f else 0.0f
      Some((GameState(newBoard, flipPlayer(action.player)), reward))
}
```

The [MiniMax agent behavior](https://github.com/rssh/rl-logic/blob/main/shared/src/main/scala/cps/rl/RLMiniMaxAgentBehavior.scala) combines tree search with neural network scoring. We use the neural network to rate possible actions, then explore the tree using the scored logic monad. Branches are weighted by gain estimation via `scoredPure`, and losing moves are pruned via `empty`:

```Scala
// Inside the minimax exploration (simplified):
val actionF = rateAndChooseAction(env, state, agentState, Explore)
rlMonad.flatMap(actionF) { action =>
  env.applyAction(state, action) match {
    case Some((newState, reward)) =>
      val gain = gainEstimation(env, newState, reward)
      if ordering.lt(gain, lossThreshold) then
        rlMonad.empty  // prune losing branches
      else if env.isFinalState(newState) then
        rlMonad.scoredPure(
          RLAgentStepResult.Finished(newState, agentState), gain)
      else
        // Self-recursive: same logic plays opponent's turn
        val opponentResult = performVirtualStep(env, newState, ...)
        rlMonad.flatMap(opponentResult) { opResult =>
          rlMonad.scoredPure(
            RLAgentStepResult.Continued(newState, opResult.agentBehaviorState),
            gain)
        }
    case None => rlMonad.empty  // invalid action
  }
}
```

The agent trains on self-play: the same neural network plays both sides.  The supplied `RLModelControl` implementation is based on the [DJL library](https://djl.ai/), but the interface is generic — one can imagine substantially different implementations: for example, in a client/server scenario, we can rely on a remote server which gives us a model for local inference and collects training data from multiple clients.

## Beyond Games

If we have any branch-and-bound style algorithm and access to data which can reveal some regularities, then we can extract these regularities via reinforcement learning.  The pattern is the same: change a function that makes a heuristic choice from returning a concrete value to returning a scored stream, wrap calls in `reify`/`reflect`, and let the monad manage exploration.

The technique applies wherever we have discrete choices guided by experience: scheduling and resource allocation, route planning and logistics, compiler optimizations, configuration tuning, network routing...  In each case, the core structure is the same: a search with choices, a reward signal, and the possibility to learn better heuristics from data.

That's all for now.  The library is still young, feedback and ideas are welcome.

---

The early version of this library is described in the ICTERI 2025 joint paper with Anatoly Doroshenko, Olena Yatsenko & Alexandr Nemish:  [Merging Logic and the Coinductive Selection Monad: Mixing Machine Learning into Logical Search](https://link.springer.com/chapter/10.1007/978-3-032-10477-9_3).

Source code: [https://github.com/rssh/rl-logic](https://github.com/rssh/rl-logic)
