# Fidelity over RMI-IIOP

Checks that what goes out over IIOP comes back unchanged, against a real
GlassFish, with ORB master and with the changes under test. Run by
`.github/workflows/iiop-fidelity.yml`.

## Why, given the other two

The load comparison in `perf/load` measures how fast the CDR encoding runs and
counts errors. It cannot tell a wrong answer from a right one: a call that
came back with the wrong string still came back, and counts as a success. Fast
and wrong is the expensive failure and nothing in this harness would have
caught it.

The ORB's unit tests cover the encoding in isolation, which is where a mistake
is easiest to find and hardest to trust. They drive the CDR classes directly:
not a bean call through a deployed application's classloader, not across
fragments, not with the indirection table that a real value graph builds.

## What the cases stand on

Each one sits on a place the encoding was changed.

| case | what it exercises |
|---|---|
| `below-the-filter` (U+D7FF), `at-the-filter-boundary` (U+E000, U+FFFF) | the branchless filter answers "maybe a surrogate" for every character at or above U+D800, so U+D7FF takes the fast path and U+E000 takes the slow one without being a surrogate at all |
| `surrogate-pairs`, `surrogate-pairs-long` | real pairs at both ends of the supplementary planes, so a mistake in either half of a pair shows |
| `mixed` | one- and two-unit characters interleaved, so an off-by-one cannot hide behind a uniform string |
| `crosses-fragments` | a payload written across fragments at every size the job runs, where the buffer holding a partially read string is now reused rather than reallocated |
| `cycle-survives`, `shared-identity` | references survive only if the indirection table maps them |
| `large-graph` | a graph big enough to grow that table past its small-array mode, which is where its entries are re-linked |
| `transient-*`, `application-exception`, `system-exception` | value semantics the encoding must not quietly change |

## Two kinds of check

Where the right answer is not in doubt - a string equal to the one sent, a
cycle that is still a cycle - the client asserts it, and a failure fails the
job.

Where the right answer is whatever this ORB has always done, such as a lone
surrogate, the client prints the outcome instead and the job requires master
and the changes to produce the same transcript. Writing down a prediction
there would only prove that the prediction and the code came from the same
person.

## Running it

From the Actions tab, *ORB fidelity over IIOP*, with `patched_ref` set to the
branch under test - the harness lives here so that the pull request carries
only ORB sources, and leaving it empty tests this branch's own copy, which is
not the same thing.
