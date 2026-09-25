# Modified version of zpa-cli

This repository (`paddi35/zpa-cli`) is a modified fork of
[felipebz/zpa-cli](https://github.com/felipebz/zpa-cli) by Felipe Zorzo. It is distributed under
the same license, the [GNU Lesser General Public License v3.0](LICENSE).

Modifications by Patrick Völkel, since 2026-09-25 (the full list is the git history on top of
the upstream `main` branch):

- Issues on lines with a NOSONAR comment are suppressed.
- CI: Docker Hub publishing is skipped outside `felipebz/zpa-cli`.

Local builds use the ZPA version given by `-PzpaVersion` (for example a `-local-SNAPSHOT` build of
the `paddi35/zpa` fork) and are not official zpa-cli releases.
