# Saga

[![Travis](https://img.shields.io/travis/txus/saga.svg?style=flat-square)](https://travis-ci.org/txus/saga)

Saga is an experimental programming language for interactive fiction. Think
"choose your own adventure" but with the dynamism of a Turing machine.

## How does it work?

Stories (Saga programs) are sets of passages with preconditions, consequences,
choices, and links.

Playing a story (running a Saga program) starts with an empty bag of facts, and
the story progresses from passage to passage through links, user choices and
satisfied preconditions.

As we'll see as you read on -- preconditions, choices and deterministic
consequences govern declarative, constraint-based control-flow, whereas links
and probabilistic consequences are used for probabilistic control flow (and thus
can also be used to model imperative, deterministic control flow too).

### Preconditions

A precondition is a fact that must be true for the passage to occur -- if a
passage has `n` preconditions, all of them must be true for it to occur (unless
there is a link to it).

```clojure
(-> (s/passage :taking-the-car "I took the car and drove off.")
    (s/requires (s/indeed "I have a car")))
```

Expressed in its lispy intermediate representation, the expression above means
that there is a passage with id `:taking-the-car`, that describes how I took the
car and drove off, and it requires for me to have a car.

Unless another passage has a link to it, the only way for this passage to occur
is to acquire the fact `"I have a car"` at some prior point in the story.

### Consequences

A consequence is a fact that may be true after a passage occurs depending on a
probability, and thus may be accumulated into the player's bag of facts.

When no probability is given, it defaults to certainty:

```clojure
(-> (s/passage :in-the-market "Wandering through the market, I found an apple.")
    (s/entails (s/indeed "I have an apple")))
```

This means that after the `:in-the-market` passage happens, the player will have
a new fact in their bag, namely `"I have an apple"`. This may enable future
passages that might otherwise be inaccesible (such as eating an apple).

Consequences can have independent probabilities up to 100% each:

```clojure
(-> (s/passage :in-the-market "Wandering through the market, I found an apple.")
    (s/entails (s/indeed "I have an apple"))
    (s/entails (s/indeed "Someone saw me in the market" :p 0.2)))
```

### Choices

A choice is a request that the player needs to decide on. Each of its branches
entails a set of consequences.

```clojure
(-> (s/passage :at-home "I was getting read to get out of the house, and...")
    (s/choices
      (s/when-chose "Forget the umbrella, this is Barcelona. Sunglasses time!"
        (s/not "I have an umbrella")
        (s/indeed "I have sunglasses on"))
      (s/when-chose "I took an umbrella, you never know."
        (s/indeed "I have an umbrella"))))
```

This means that, when this passage occurs, the player will be presented with a
choice between taking an umbrella or sunglasses. These facts might determine the
availability of future passages.

Consequences in choice branches can also have independent probabilities, just
like normal consequences:

```clojure
(-> (s/passage :at-the-crossroads "I came to a crossroads.")
    (s/choices
      (s/when-chose "I decided to go left."
        (s/then (s/indeed "I went left"))
        (s/then (s/indeed "A spy saw me.") :p 0.2))
      (s/when-chose "I took an umbrella, you never know."
        (s/then
          (s/indeed "I went right")))))
```

### Links

A link is a probability to jump from one passage to another when the first occurs.

```clojure
(-> (s/passage :at-the-store "That store was so full of items, that I was getting hungry.")
    (s/leading-to :at-the-mall-restaurant))
```

After the `:at-the-store` passage runs, it is guaranteed that
`:at-the-mall-restaurant` will be the next passage. That is because, being the
only link in the passage and having no probability assigned, its probability is
100%.

More generally, as long as no probabilities are assigned, the probability of
each link to occur is 1 divided between the number of links:

```clojure
(-> (s/passage :at-the-store "That store was so full of items, that I was getting hungry.")
    (s/leading-to :at-the-mall-restaurant)
    (s/leading-to :outside)))
```

In this case above, 50% of the time we'll end up at the mall restaurant, and the
other 50% of the time we'll wind up outside.

One can weight links on purpose, as long as the defined probabilities won't go over 1:

```clojure
(-> (s/passage :at-the-store "That store was so full of items, that I was getting hungry.")
    (s/leading-to :at-the-mall-restaurant :p 0.8) ;; 80% of the time we'll go here
    (s/leading-to :outside)))                     ;; ... and 20% of the time here
```

If probabilities go under 1, the remaining probability will be assigned to no
link at all:

```clojure
(-> (s/passage :at-the-store "That store was so full of items, that I was getting hungry.")
    (s/leading-to :at-the-mall-restaurant :p 0.2) ;; 20% of the time we'll go to the mall restaurant
    (s/leading-to :outside :p 0.2)))              ;; another 20% of the time we'll go outside
                                                  ;; and the remaining 60% of the time no link will occur.
```

In the most complex case, we can assign defined probabilities to some links and
implicitly divide the remaining probabilities among the rest of the links:

```clojure
(-> (s/passage :at-the-store "That store was so full of items, that I was getting hungry.")
    (s/leading-to :at-the-mall-restaurant :p 0.2)
    (s/leading-to :outside :p 0.2)
    (s/leading-to :the-basement)
    (s/leading-to :the-directors-office)))))
```

In the example above the probabilities will be:

* 20% of the time we'll go to the mall restaurant
* 20% of the time we'll go to outside

And the remaining 60% will be divided equally among the other two links:

* 30% of the time we'll end up in the basement
* 30% of the time we'll end up in the director's office

#### What's in this repo?

This repo contains two separate projects that share some common code:

* IDE: An IDE to build stories. ![Saga IDE](/screenshots/ide.png?raw=true "Saga IDE")
* Player: An app to play stories. ![Saga Player](/screenshots/player.png?raw=true "Saga Player")

The interpreter used by the player lives in the `saga.engine` namespace.

Saga programs (stories) are exported and imported as EDN files. More about this
to come.

## Building

You'll need [Boot](http://boot-clj.com) (`brew install boot-clj`).

To build the IDE:

    boot package -t ide
    open target-ide/ide.html
    
To build the player:

    boot package -t player
    open target-player/player.html
    
## Developing

This project is built entirely in ClojureScript. The IDE and the Player are both
separate Om Next apps. You'll need [Boot](http://boot-clj.com) (`brew install boot-clj`).

To work on the IDE:

    boot ide-dev
    open localhost:3000/ide.html
    
To work on the player:

    boot player-dev
    open localhost:3000/player.html

## Tests

There are currently no automated tests. To run them anyway:

    boot test
    
## Roadmap

### General 

* [x] Basic IDE working
* [x] Basic Player working
* [x] Exporting stories from IDE as EDN files
* [x] Loading stories from Player as EDN files
* [x] Saving / restoring app state automatically from local storage (IDE & Player)
* [x] HTML5 Offline capability
* [x] Material design in the IDE
* [x] Material design in the Player
* [x] Probabilistic passage links
* [x] Probabilistic consequences

### Tooling / Debugging

* [ ] Debugging a passage by running it with a canned bag of facts
* [ ] Debugging-enabled player within the IDE (manipulating facts, etc)
* [ ] Debugging-enabled player within the IDE (manipulating facts, etc)
* [ ] User-driven, generative testing of stories

### Sanity

* [ ] Test everything!!!
* [ ] Write about Saga's design decisions

### Release

* [ ] Package Player as a React Native app
* [ ] Set up Github releases with Travis CI artifacts
