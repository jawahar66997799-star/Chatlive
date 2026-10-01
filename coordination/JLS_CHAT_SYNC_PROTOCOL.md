# JLS CHAT SYNC PROTOCOL

This repository mirror backs the Project Library coordination bus.

Every active MAIN/WS1..WS6 turn must:
1. read /Sync/JLS_CHAT_SYNC_PROTOCOL.md
2. read /Sync/JLS_SHARED_SNAPSHOT.md
3. read newest /Sync/JLS_SHARED_EVENT_LOG.md
4. read /Sync/JLS_MAIN_COORDINATION.md
5. read its own WS coordination heartbeat
6. inspect current youtube-live-sync HEAD and relevant CI/runtime
7. publish its result back to its heartbeat + shared event log

Chats cannot inspect other live chat-window messages or wake themselves while idle. Project Library + GitHub are the durable shared communication channel.

Main integration chat resolves conflicts. No workstream may silently drift the wire protocol or relabel simulated evidence as measured physical evidence.
