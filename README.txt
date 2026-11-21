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
Mic-E encoding/decoding, centered ambiguous positions, long-chat splitting,
frequency decoding and optional per-message routing/ACK metadata are implemented.
The Windows 0.6.0 release runs 55 shared-core tests including independent Mic-E
specification vectors. Complete historical APRSIS32 parity is not claimed.

Unbuilt beaconing additions (2026-11-20): BeaconEngine implements APRS-IS
position/object/item/status formatting and adaptive scheduling; NmeaPosition
decodes checksummed RMC/GGA/GLL fixes. AprsIsClient.sendBroadcast uses the
verified session. New tests are prepared but NOT RUN. No APRSIS32 source copied.
Platform GPS I/O, preferences, persistence and lifecycle remain in the host app.

Unbuilt radio additions (2026-11-20): RadioPacket/Ax25Ui, streaming KISS and AGW
codecs, TNC monitor parsing, RadioRouter digipeater and IGate policy. ChatSession
now accepts AprsMessageTransport for shared RF/IS chat handling. AprsIsClient
remains compatible for Android callers. New regression source is NOT RUN.

Source-only spatial additions (2026-11-20; NOT compiled or tested): AprsSpatial
provides PHG/DF decoding, predictions, horizon, multiline and area geometry.
QruObjects provides requester-centered selection and item-in-message formatting.
AprsProtocol retains compressed motion/altitude and accepts addressed items,
including owner-scoped kills in AprsStationStore. Regression fixtures are added
but not executed. Rendering and radio-history persistence remain in the host.
