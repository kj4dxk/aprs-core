Shared APRS core for APRSISOSM and APRSIS64

Canonical source: https://github.com/kj4dxk/aprs-core
New work is licensed under AGPL-3.0, matching APRSFoundation/APRSISMO's LICENSE.
The initial four APRS source files and five tests were extracted without namespace
changes from kj4dxk/OsmAnd, commit 06fa798597, on 18 November 2026. Their existing
GPLv3-or-later terms are preserved (ORIGIN-LICENSE.txt). Combining GPLv3 and AGPLv3
parts does not relicense the original GPL code. No OsmAnd artwork is included.

Build: Java 17, ./gradlew test. The Windows parent can compile the same sources
with its newer Kotlin compiler. Android includes src/commonMain/kotlin directly
from a pinned git submodule; this preserves its existing KMP/iOS source sets and
avoids forcing a desktop toolchain upgrade on the Android build.

Protocol and application services contain no Android or desktop UI dependencies.
Current scope: APRS-IS positions, verified beaconing, text/ACK/REJ/reply-ACK,
bounded station cache, endpoint/identity parsing and durable-chat orchestration.
Mic-E, ambiguous positions and full APRSIS32 parity remain future work.
