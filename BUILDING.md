# Building ExcellentCrates 6.6.1-2

The release build uses JDK 26 to compile Java 21 bytecode. Put the compile-only JARs listed in `build-support/compile-dependencies.json` in a directory of your choice. The manifest identifies the exact compiler inputs by file name and SHA-256.

Run from the checkout root with Python 3. On Windows, pass the path to `javac.exe` if it is outside `PATH`.

```sh
python build-support/build_release.py --javac /path/to/jdk-26/bin/javac --deps /path/to/compile-jars --output target/ExcellentCrates-6.6.1-2.jar
```

The build includes the plugin classes, resources and Maven metadata. Compiler inputs are supplied separately. ZIP metadata such as timestamps can differ between builds.

The release artifact was tested on Shiroha `26.2-DEV-96ca96b` with Java 25 and PlugManX 3.2.1. See the GitHub release for the tested command paths and installation instructions.
