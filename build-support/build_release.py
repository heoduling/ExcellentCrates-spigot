"""Compile the release from source with locally supplied compile-only JARs."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
from zipfile import ZIP_DEFLATED, ZipFile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--javac', default='javac', help='JDK 26 javac executable')
    parser.add_argument('--deps', type=Path, required=True, help='Directory of compile-only JARs')
    parser.add_argument('--output', type=Path, required=True, help='Destination JAR')
    args = parser.parse_args()

    support = Path(__file__).resolve().parent
    project = support.parent
    pinned = json.loads((support / 'compile-dependencies.json').read_text(encoding='utf-8'))
    dependencies = [args.deps.resolve() / name for name in sorted(pinned)]
    for dependency in dependencies:
        if not dependency.is_file() or hashlib.sha256(dependency.read_bytes()).hexdigest() != pinned[dependency.name]:
            parser.error(f'Missing dependency or SHA-256 mismatch: {dependency}')

    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix='excellentcrates-build-') as workspace:
        classes = Path(workspace) / 'classes'
        classes.mkdir()
        sources = sorted((project / 'src/main/java').rglob('*.java'))
        arguments = [
            '-encoding', 'UTF-8', '-source', '21', '-target', '21', '-g', '-proc:none',
            '-classpath', os.pathsep.join(map(str, dependencies)), '-d', str(classes),
            *map(str, sources),
        ]
        argfile = Path(workspace) / 'javac.args'
        quoted_arguments = ['"' + arg.replace('\\', '/') + '"' for arg in arguments]
        argfile.write_text('\n'.join(quoted_arguments), encoding='utf-8')
        subprocess.run([args.javac, f'@{argfile}'], check=True)

        resources = project / 'src/main/resources'
        with ZipFile(output, 'w', ZIP_DEFLATED) as jar:
            for compiled in sorted(classes.rglob('*.class')):
                jar.write(compiled, compiled.relative_to(classes).as_posix())
            for resource in sorted(path for path in resources.rglob('*') if path.is_file()):
                jar.writestr(resource.relative_to(resources).as_posix(),
                             resource.read_bytes().replace(b'${project.version}', b'6.6.1'))
            metadata = 'META-INF/maven/su.nightexpress.excellentcrates/ExcellentCrates/'
            jar.write(project / 'pom.xml', metadata + 'pom.xml')
            jar.writestr(metadata + 'pom.properties',
                         'artifactId=ExcellentCrates\ngroupId=su.nightexpress.excellentcrates\nversion=6.6.1\n')
            jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nBuild-Jdk-Spec: 26\r\n\r\n')

    print(f'Compiled {len(sources)} source files into {output}')


if __name__ == '__main__':
    main()
