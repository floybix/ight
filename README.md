# Hatto ハット"

Early stage - experiments only.

Hatto is a programming game. That means you don't play the game
directly. Instead you make a program to play it. Your program is the
player, and you tell it how to play.

The game takes place in a 2D world (with physics and gravity). The aim
is to push your opponent off the arena, or at least avoid falling off
until your opponent falls first. It's like a wrestling match.


See:

[Creatures](https://github.com/floybix/hatto/wiki/Creatures)

[How to play](https://github.com/floybix/hatto/wiki/How-to-play)


## Running bouts

Get [Leiningen](http://leiningen.org/) first.

You can run basic in-process demos:

```
cd hatto/runner
lein run -m org.nfrac.hatto.tests.legsoid
lein run -m org.nfrac.hatto.tests.humanoid
lein run -m org.nfrac.hatto.tests.wormoid
```

Or try running player servers communicating over TCP:

```
cd hatto/players/clojure
lein run -m org.nfrac.hatto.examples.legsoid1 5555
lein run -m org.nfrac.hatto.examples.legsoid1 5556
cd hatto/runner
lein run -m org.nfrac.hatto.visual-runner sumo tcp://localhost:5555 tcp://localhost:5556
```

Or use the Clojure REPL: see
[REPL session](https://github.com/floybix/hatto/wiki/REPL-session)


## License

Copyright © 2015 Felix Andrews

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
