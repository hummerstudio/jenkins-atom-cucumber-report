# jenkins-atom-cucumber

Cucumber report — port of **cucumbers** (8,393) as an atom.



## Build and test

```bash
mvn verify
```

`mvn verify` packages the jar and then runs the contract tests through failsafe — the packaged jar started the way
the runner starts it (`input.json` + the `atom_*` environment).
